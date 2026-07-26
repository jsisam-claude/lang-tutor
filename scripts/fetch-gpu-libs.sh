#!/usr/bin/env bash
# Fetch Google's prebuilt android_arm64 WebGPU libraries into the app's
# jniLibs so the GPU sampler works on devices without OpenCL (GrapheneOS).
#
# WHY: the litertlm-android 0.14.0 AAR statically fuses the WebGPU executor
# and Dawn into liblitertlm_jni.so but omits the top-K sampler, whose factory
# dlopen()s libLiteRtTopKWebGpuSampler.so at first generation. On stock Pixels
# the runtime silently falls back to an OpenCL sampler; GrapheneOS ships no
# OpenCL, so GPU generation fails and the engine drops to CPU (see
# LiteRtLmEngine's load-time smoke test). Google publishes exactly these
# prebuilts in the LiteRT-LM repo via Git LFS — we pin the v0.14.0 tag
# (commit 80f301ff), the same version as the AAR, and verify the LFS oid
# (which IS the file's SHA-256) end to end.
#
# The three libs are the upstream dynamic layout: accelerator and sampler both
# link the SHARED libwebgpu_dawn.so. All are 16KB-page-aligned arm64 builds.
# Apache-2.0 (google-ai-edge/LiteRT-LM). ~23MB added to the APK, arm64 only —
# other ABIs keep today's CPU fallback.
#
# Usage: scripts/fetch-gpu-libs.sh   (run from anywhere; CI runs it before
# assembling, and a local build without it still works — CPU fallback).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
# LiteRT-LM v0.14.0 — keep in lockstep with libs.versions.toml's litertlm.
COMMIT="80f301ff9a3b02c2c1e7be2dd1a567752f7b51b6"
BASE="https://media.githubusercontent.com/media/google-ai-edge/LiteRT-LM/$COMMIT/prebuilt/android_arm64"

# name|sha256 (= Git LFS oid pinned from the v0.14.0 tag's pointer files)
LIBS=(
  "libLiteRtTopKWebGpuSampler.so|36986455ae1140b601d43c17657bc2e83c18819533465951534cbc36df6c107a"
  "libLiteRtWebGpuAccelerator.so|1e958236315a1e9e270669b733da4bfcd1fa3a272fedcb201a215d7077170fd7"
  "libwebgpu_dawn.so|7282aacdb076ce89f0c9d93107a145b991b99eb1dfbd5b5746dd0d99466ab3c3"
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
