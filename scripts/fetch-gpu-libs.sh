#!/usr/bin/env bash
# Fetch Google's prebuilt android_arm64 GPU libraries into the app's jniLibs
# so LiteRT-LM's GPU path has every library its factory can dlopen.
#
# HISTORY, because this file's premise was overturned by a device result: it
# long claimed "GrapheneOS ships no OpenCL, so GPU generation fails". That was
# never a tested fact — apps targeting API 31+ cannot dlopen vendor libraries
# they do not declare, so a missing driver and namespace isolation were
# indistinguishable. Once AndroidManifest.xml declared
# <uses-native-library libOpenCL.so / libOpenCL-pixel.so>, a Pixel 9 loaded
# Gemma 4 E4B on the GPU backend first try (GPU verdict: USED; decode
# ~12-14 est-tok/s vs ~1.6 on CPU, whole turns 0.9-2.2s vs 8.2s). The WebGPU
# chain stays bundled as the zero-vendor-dependency fallback for devices whose
# vendor image genuinely lacks the driver.
#
# Google publishes these prebuilts in the LiteRT-LM repo via Git LFS — we pin
# the tag matching the AAR and verify the LFS oid (= the file's SHA-256).
#
# WHICH of the seven upstream prebuilts we take, and why (checked against
# liblitertlm_jni.so's own strings — those are the dlopen candidates):
#   libLiteRtWebGpuAccelerator.so   referenced; WebGPU-only accelerator     TAKE
#   libLiteRtGpuAccelerator.so      referenced; combined CL+WebGPU variant,
#                                   exports the same LiteRtAcceleratorImpl   TAKE
#   libLiteRtTopKWebGpuSampler.so   referenced, but UNLOADABLE here — see the
#                                   sampler note below.                     SKIP
#   libLiteRtTopKOpenClSampler.so   same.                                   SKIP
#   libwebgpu_dawn.so               not referenced by name, but DT_NEEDED of
#                                   both accelerators above                  TAKE
#   libLiteRtOpenClAccelerator.so   referenced, but the combined accelerator
#                                   above already carries the CL path that
#                                   a Pixel 9 measurably uses. 15MB saved.  SKIP
#   libGemmaModelConstraintProvider.so  NOT referenced by the Android runtime
#                                   at all (19MB, CLI/other builds).        SKIP
# If a STOCK-Android device ever needs the OpenCL path, add those two here —
# their oids come from the same tag.
#
# NEITHER SAMPLER CAN EVER LOAD IN THIS APP, and shipping them was my mistake.
# The device said "libLiteRtTopKOpenClSampler.so not found", so 2026-08-27 I
# added it; the next log said instead:
#
#   dlopen failed: cannot locate symbol "kLiteRtRuntimeBuiltin"
#     referenced by ".../libLiteRtTopKOpenClSampler.so"
#
# Both sampler prebuilts have that symbol UNDEFINED (`llvm-nm -D --undefined-only`
# on either shows it), because they are built to sit beside a SHARED LiteRT
# runtime that exports it. The AAR ships the opposite shape: one statically
# linked liblitertlm_jni.so exporting exactly 30 symbols, all of them
# Java_com_google_ai_edge_litertlm_* JNI entry points and none of them LiteRT
# C API. There is nothing in the process for the samplers to bind against, so
# the runtime falls back to its statically linked sampler either way and the
# only thing 23MB of APK bought was a different error message. Adding the
# shared runtime .so to satisfy the symbol would put a SECOND LiteRT (and a
# second OpenCL context) in the process — the exact configuration that already
# crashes Mali on Tensor G4. The accelerators below stay: neither has an
# undefined kLiteRt* symbol, so they are at least loadable.
#
# All are 16KB-page-aligned arm64 builds.
# Apache-2.0 (google-ai-edge/LiteRT-LM). ~26MB added to the APK, arm64 only —
# other ABIs keep today's CPU fallback.
#
# Usage: scripts/fetch-gpu-libs.sh   (run from anywhere; CI runs it before
# assembling, and a local build without it still works — CPU fallback).
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/fetch.sh"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
# LiteRT-LM v0.16.1 — keep in lockstep with libs.versions.toml's litertlm.
COMMIT="924e79c91542761242244e4f1651851f822e4cbb"
BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/$COMMIT/prebuilt/android_arm64"

# name|sha256 (= Git LFS oid pinned from the v0.16.1 tag's pointer files)
LIBS=(
  "libLiteRtGpuAccelerator.so|1287e5ae01666a605f2bc5d72453f32cf4a294ef38acabb86cd61140207e41c3"
  "libLiteRtWebGpuAccelerator.so|acf02905cd1d7b7d1f0f70a6d885ddbd392c0c69a99b92834da7e26d6859abcf"
  "libwebgpu_dawn.so|3b2a53de934efabce2efb5e9f703a7bd6b63a5814b2f8f0c7ed610cabf53b147"
)

mkdir -p "$DEST"
trap 'clean_partials "$DEST"' EXIT

for entry in "${LIBS[@]}"; do
  name="${entry%%|*}"
  want="${entry##*|}"
  # --retry-all-errors inside fetch_verified also covers curl's HTTP/2
  # PROTOCOL_ERROR (exit 92), which GitHub's LFS media CDN throws
  # intermittently on larger files (killed a CI run once).
  fetch_verified "$BASE/$name" "$DEST/$name" "$want" "$name"
done

# PRUNE what LIBS no longer lists. This directory is gitignored and owned
# entirely by this script, so anything here that is not pinned above is a
# leftover from an older revision — and leftovers here are not inert, they get
# PACKAGED INTO THE APK. Removing the two TopK sampler prebuilts (23 MB that
# provably cannot load: they need a `kLiteRtRuntimeBuiltin` symbol the
# statically linked liblitertlm_jni.so does not export) would otherwise have
# kept shipping them for everyone whose checkout predates the change.
for f in "$DEST"/*.so; do
  [ -e "$f" ] || continue
  base="$(basename "$f")"
  keep=0
  for entry in "${LIBS[@]}"; do [ "${entry%%|*}" = "$base" ] && keep=1 && break; done
  if [ "$keep" = 0 ]; then
    echo "   pruned   $base (no longer pinned; would otherwise ship in the APK)"
    rm -f "$f"
  fi
done
echo "GPU libs ready in $DEST"
