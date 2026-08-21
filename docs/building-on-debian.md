# Building on Debian (as a normal user)

Verified end to end on 2026-08-20: fresh `git clone`, unprivileged user (uid 1001),
SDK inside `$HOME`, cold Gradle cache, **JDK 25** — both build lanes green and the
resulting APK byte-identical in size to the one CI publishes (242,694,197 B).

Only ONE step needs `sudo`: installing the JDK. Everything else lives in `$HOME`.

## What you need

| Tier | Needs | Cost |
|---|---|---|
| **1. JVM lane** — 7 core modules + all unit tests | JDK, `curl`, `unzip`, `git` | ~1.4 GB Gradle cache |
| **2. APK** | + Android SDK (cmdline-tools) | +620 MB, ~2m40s cold |
| **3. Device** | udev rules + `plugdev` group | `adb` ships *inside* the SDK |

No NDK: the three `arm64-v8a` `.so` files are prebuilt downloads, not compiled here.
No extra system libraries: `aapt2`, `aapt`, `zipalign` and `adb` were checked with
`ldd` and resolve against `libc/libm/libdl/libgcc_s/librt` only — all base install.
No i386 multiarch.

## Stage 0 — the only sudo

```bash
sudo apt update && sudo apt install -y openjdk-25-jdk curl unzip git
```

Debian trixie ships `openjdk-25-jdk` (e.g. `25.0.4+7-1~deb13u1`). JDK 21 also works
if you prefer the older default. See "Why Gradle 9.5.1" below — the JDK version and
the Gradle version are coupled.

## Stage 1 — JVM lane (no Android SDK, no Google servers)

```bash
git clone https://github.com/jsisam-claude/lang-tutor && cd lang-tutor
./gradlew -Plangtutor.jvmOnly=true build
```

The Gradle wrapper is committed (`gradlew` + `gradle/wrapper/gradle-wrapper.jar`),
so it bootstraps its own Gradle. This lane excludes `:app` and the asset pack and
never puts AGP on the classpath, so it needs nothing from Google.

## Stage 2 — Android SDK, entirely in $HOME

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# ^ add both lines to ~/.bashrc; Gradle locates the SDK via ANDROID_HOME

mkdir -p "$ANDROID_HOME/cmdline-tools" && cd /tmp

# Do NOT hardcode the build number: Google rotates it (13114758, 15859902 and
# 16111833 are all live right now) and any literal you paste is stale within
# weeks. Ask the SDK repository manifest for the current one instead:
CT=$(curl -sS https://dl.google.com/android/repository/repository2-3.xml \
     | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | sort -t- -k3 -n | tail -1)
curl -fLO "https://dl.google.com/android/repository/$CT"

unzip -q commandlinetools-linux-*.zip
mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"   # the /latest layout is mandatory
yes | sdkmanager --licenses > /dev/null
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## Stage 3 — build the APK

```bash
cd ~/lang-tutor
scripts/fetch-gpu-libs.sh && scripts/fetch-voice-assets.sh && scripts/fetch-vad-asset.sh
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk  (~243 MB)
```

Skipping the fetch scripts still builds; you lose GPU decode, Tuki's voice and
hands-free listening, and the APK comes out ~24 MB smaller.

### A note on cmdline-tools versions

The build number in the filename rotates, and several are live at once. Each of
these was installed and used for a full `:app:assembleDebug` on JDK 25 on
2026-08-20 — all three produced a working APK with the commands above unchanged:

| cmdline-tools | zip size | `sdkmanager --version` | build |
|---|---|---|---|
| `13114758` | 164,760,899 B | `19.0` | ✅ |
| `15859902` | 181,833,628 B | `22.0` | ✅ |
| `16111833` (rev 23.0.0, current) | 181,052,239 B | `1.0.15985488 (Android CLI)` | ✅ |

Worth noting that rev 23.0.0 ships a visibly different sdkmanager — a changed CLI
could have broken the documented flags, so `--licenses` and `--install` were
re-tested against it rather than assumed. They behave identically.

Older build numbers still resolve on dl.google.com, so an out-of-date link is
stale rather than broken — but the `CT=` lookup above avoids the question.

## Why Gradle 9.5.1 (do not "upgrade" past it yet)

The wrapper pins **9.5.1**, and both neighbours are broken for us — measured, not assumed:

- **Gradle 8.14.3 cannot run on JDK 25.** Not a Gradle limit: the Kotlin that the
  Gradle *distribution* embeds to compile our `.kts` files is 2.0.21, whose bundled
  IntelliJ `JavaVersion.parse` throws `IllegalArgumentException` on any `25.x`
  string — confirmed directly against `25`, `25.0.2`, `25.0.3`, `25.0.4` and
  `25.0.4+7-1~deb13u1`. The failure surfaces as a build error whose entire message
  is the version number. Bumping the *project's* Kotlin does not help; the embedded
  one is what compiles the build scripts.
- **Gradle 9.6.0+ cannot run AGP 8.13.** AGP relies on
  `org.gradle.api.problems.internal.InternalProblems`, removed in 9.6.0. Gradle's own
  error names 9.5 as the fallback.

So 9.5.1 is the only version satisfying both. Escaping the ceiling means moving to
AGP 9.x, which also needs the root `buildscript {}` AGP classpath reconciled with the
`plugins {}` block (they currently conflict: "plugin is already on the classpath with
an unknown version").

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Build fails, message is just `25.0.4` | Gradle < 9 on JDK 25 | use the committed wrapper (9.5.1), not a system `gradle` |
| `relies on ... InternalProblems, removed in Gradle 9.6.0` | Gradle too new for AGP 8.13 | stay on 9.5.1 |
| `fatal: detected dubious ownership` | cloned/owned by another user | `git config --global --add safe.directory <path>` |
| `sdkmanager: command not found` | wrong cmdline-tools layout | it must sit at `$ANDROID_HOME/cmdline-tools/latest/bin` |
| `Gradle build daemon has been stopped` | another process ran `--stop` on a shared `GRADLE_USER_HOME` | re-run; avoid parallel builds sharing `~/.gradle` |
| `adb: no permissions` | udev rules / `plugdev` | `sudo apt install android-sdk-platform-tools-common`, add yourself to `plugdev`, re-login |

## Not verified here

Executed on Ubuntu 24.04, not literal Debian — nothing run was Ubuntu-specific, but
that is a real gap. `packages.debian.org` and every Debian mirror are unreachable from
the environment this was written in, so the Stage 0 apt line is the one command taken
from documentation rather than execution. Stage 3's udev/USB half is untested (no
phone attached). ARM64 Debian is untested — the SDK binaries are x86_64.
