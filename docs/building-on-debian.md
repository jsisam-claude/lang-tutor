# Building on Debian (as a normal user)

Verified end to end on 2026-08-20: fresh `git clone`, unprivileged user (uid 1001),
SDK inside `$HOME`, cold Gradle cache, **JDK 25** — both build lanes green and a
working APK out the other end.

Sizes are approximate, not a checksum: debug lands near 245 MB and release near
237 MB, but the exact byte count drifts a few hundred KB between environments
(dex merging and R8 are not bit-reproducible across machines), so don't treat a
small difference from CI's published APK as a problem.

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
# -> app/build/outputs/apk/debug/app-debug.apk  (~245 MB)
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

## Stage 4 — a release APK signed with YOUR certificate

Debug builds sign themselves with a throwaway debug key. A release build needs
your own certificate — the keystore you already have. Two equivalent ways;
both were tested end-to-end on this exact setup (JDK 25, build-tools 36.0.0).

**Never commit the keystore or its passwords.** `.gitignore` already blocks
`keystore.properties`, `*.jks` and `*.keystore`; keep the keystore outside the
repo anyway.

### Option A — Gradle signs during the build (recommended)

Create `keystore.properties` in the **project root** (template:
`keystore.properties.example`):

```properties
# Comments must sit on their OWN line: java.util.Properties treats an
# inline '#' as part of the value, silently corrupting the path/alias.
# Your keystore, absolute path:
storeFile=/home/you/keys/my-release.jks
storePassword=...
# keytool -list -keystore <file> shows the aliases:
keyAlias=...
# Often the same as storePassword:
keyPassword=...
```

```bash
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk   (signed + aligned)
```

The build script only creates the signing config when `keystore.properties`
exists, so its absence never breaks anyone else's build — they just get
`app-release-unsigned.apk` instead.

### Option B — sign an unsigned APK by hand

Without `keystore.properties`, `assembleRelease` produces
`app-release-unsigned.apk`; align it, then sign it (this order — apksigner's
hash covers the final byte layout, so zipalign must run first):

```bash
BT=$ANDROID_HOME/build-tools/36.0.0
cd app/build/outputs/apk/release
$BT/zipalign -P 16 -f 4 app-release-unsigned.apk app-release-aligned.apk
$BT/apksigner sign --ks /home/you/keys/my-release.jks --ks-key-alias YOUR_ALIAS \
    --out app-release.apk app-release-aligned.apk
# prompts for the passwords; --ks-pass pass:... only if scripting
```

(`-P 16` = 16 KB page alignment for uncompressed .so files — required for
Android 15+ / 16 KB-page devices, and what Gradle itself does in Option A.)

### Check the signature (either option)

```bash
$BT/apksigner verify --print-certs app-release.apk
# Signer #1 certificate DN / SHA-256 should be YOUR certificate
```

### Installing over the debug build fails — expected

The debug APK from CI and your release APK carry different signatures, and
Android refuses signature changes on update:
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first
(`adb uninstall org.sisam.langtutor` — this deletes the installed models;
they'll re-download/re-push), then `adb install app-release.apk`. Same story
in reverse when going back to a CI debug APK.

### If your signing cert was issued by a CA — read this first

A certificate issued by a CA (rather than a self-signed `keytool` one) **works**:
Gradle takes the PKCS#12 straight from `storeFile`, no conversion. But Android
does not use it the way a CA cert is normally used, and one property of CA certs
is a trap here.

**The CA is irrelevant to Android.** APK signatures are not validated against
any trust store — not the system CAs, not user-installed CAs. Android *pins the
certificate*: on update it compares the new APK's certificate against the
installed one and allows the update only if they match. Verified by signing this
app with a CA-issued cert and inspecting the result — apksigner reports a single
signer (the leaf), and the issuing CA's bytes are not present in the APK at all.
So having the issuing CA installed on the phone grants nothing: no easier
install, no extra permission, no bypass of "install unknown apps".

**The trap is renewal.** Android compares the *certificate*, not the key. Re-issuing
from the same CA with the same private key yields an identical public key but a
different certificate — and therefore, to Android, a different signer. Google's
guidance is that an app signing key be valid **at least 25 years**, because once
the validity period lapses "users will no longer be able to seamlessly upgrade".
A typical CA cert is 1–3 years. When it renews, every install must be uninstalled
and reinstalled — on this app that means re-downloading multi-GB models.

Nothing warns you either: apksigner signs happily with an already-expired
certificate (`minSdk 31` turns off v1/JAR signing, so validity dates are never
checked at sign or install time). It breaks silently, later, at update time.

**Recommendation:** sign the APK with a long-lived self-signed key and keep the
CA-issued certificate for what CAs are for (TLS).

```bash
keytool -genkeypair -v -keystore ~/keys/tuki-release.jks \
  -alias tuki -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Your Name, O=Sisam, C=IL"
# 10000 days ~ 27 years, clearing the 25-year bar.
```

Back that keystore up. Losing it has exactly the same consequence as a renewal.

### If your certificate lives in another format

- PKCS#12 (`.p12`/`.pfx`, e.g. exported from a previous tool): works as-is —
  both Gradle and apksigner auto-detect it; just point `storeFile` at it.
- Raw key + cert PEM pair: skip keystores entirely with
  `apksigner sign --key key.pk8 --cert cert.pem ...`, or import into a
  keystore once with `keytool -importkeystore`.

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
