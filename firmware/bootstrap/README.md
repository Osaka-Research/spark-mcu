# Bootstrap toolchain (dev-only, never ships)

Scaffold track for Spark's on-phone compile pipeline. Uses binaries extracted
from an ArduinoDroid APK to validate phases 3-6 of the Kotlin build pipeline
while the real Canadian-cross toolchain matures in CI.

**This directory's output is not allowed to ship with Spark.**
See `memory/project_spark_toolchain_policy.md` for the why.

## Usage

```
cd firmware/bootstrap
./extract-arduinodroid.sh /path/to/ArduinoDroid.apk --dry-run   # inventory
./extract-arduinodroid.sh /path/to/ArduinoDroid.apk              # extract
# → out/xtensa-lx106-elf-android-arm64.tar.xz
```

Upload the tarball to a GH release (tag it something like `toolchain-bootstrap-YYYYMMDD`)
and point `spark.toolchain.releaseTag` in `gradle.properties` at it.

## Why ArduinoDroid?

- Their APK already contains Bionic-hosted xtensa binaries under
  `lib/arm64-v8a/`, renamed `lib<name>.so` for Android's exec rules.
- GCC/binutils/newlib are GPL — redistribution is permitted provided we
  make sources available (upstream Espressif repos satisfy this).
- Saves ~1 week of Canadian-cross iteration before we have anything to run.

## When this dies

Once `.github/workflows/xtensa-toolchain.yml` produces a working tarball,
flip `spark.toolchain.releaseTag` to the clean build tag and delete the
bootstrap release. The Gradle fetcher doesn't care which tag it pulls from
— shape is identical.
