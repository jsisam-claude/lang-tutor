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
#   libLiteRtTopKWebGpuSampler.so   referenced; the sampler the AAR omits    TAKE
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
# All are 16KB-page-aligned arm64 builds.
# Apache-2.0 (google-ai-edge/LiteRT-LM). ~37MB added to the APK, arm64 only —
# other ABIs keep today's CPU fallback.
#
# Usage: scripts/fetch-gpu-libs.sh   (run from anywhere; CI runs it before
# assembling, and a local build without it still works — CPU fallback).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
# LiteRT-LM v0.16.1 — keep in lockstep with libs.versions.toml's litertlm.
COMMIT="924e79c91542761242244e4f1651851f822e4cbb"
BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/$COMMIT/prebuilt/android_arm64"

# name|sha256 (= Git LFS oid pinned from the v0.16.1 tag's pointer files)
LIBS=(
  "libLiteRtGpuAccelerator.so|1287e5ae01666a605f2bc5d72453f32cf4a294ef38acabb86cd61140207e41c3"
  "libLiteRtTopKWebGpuSampler.so|c52a1cf69a92a2d2c4d3c08f5c087d1eb405f709af61c3312b215221135e18db"
  # The OpenCL sampler, added 2026-08-27 after the device said it was missing:
  #   sampler_factory.cc: "OpenCL sampler not available, falling back to
  #   statically linked C API ... libLiteRtTopKOpenClSampler.so not found"
  # printed on every engine load. The comment below used to argue the combined
  # accelerator "carries the CL path" — true of the ACCELERATOR, wrong about
  # the SAMPLER, which the runtime dlopens separately by name. Sampling runs
  # once per token, so the fallback was doing every token's top-K off the GPU.
  "libLiteRtTopKOpenClSampler.so|4404dc68786460602685cab62ddfa29035e9cfc38bb4550dec15abaaa1302a82"
  "libLiteRtWebGpuAccelerator.so|acf02905cd1d7b7d1f0f70a6d885ddbd392c0c69a99b92834da7e26d6859abcf"
  "libwebgpu_dawn.so|3b2a53de934efabce2efb5e9f703a7bd6b63a5814b2f8f0c7ed610cabf53b147"
)

mkdir -p "$DEST"
for entry in "${LIBS[@]}"; do
  name="${entry%%|*}"
  want="${entry##*|}"
  out="$DEST/$name"
  if [[ -f "$out" ]] && echo "$want  $out" | sha256sum -c --status 2>/dev/null; then
    echo "OK (cached)   $name"
    continue
  fi
  echo "fetching      $name"
  # --retry-all-errors: plain --retry does NOT cover curl's HTTP/2
  # PROTOCOL_ERROR (exit 92), which GitHub's LFS media CDN throws
  # intermittently on larger files (killed a CI run once).
  curl -fsSL --retry 4 --retry-all-errors -o "$out.tmp" "$BASE/$name"
  got="$(sha256sum "$out.tmp" | awk '{print $1}')"
  if [[ "$got" != "$want" ]]; then
    rm -f "$out.tmp"
    echo "SHA-256 MISMATCH for $name: got $got, want $want" >&2
    exit 1
  fi
  mv "$out.tmp" "$out"
  echo "OK (verified) $name"
done
echo "GPU libs ready in $DEST"
