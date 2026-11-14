# xtensa-lx106-elf toolchain for Android arm64

Phase 1 of the on-phone compile pipeline. Produces a cross-compiler tarball
that runs **on Android arm64 (Bionic)** and compiles **for ESP8266**.

## Why this exists

Espressif publishes prebuilt ESP8266 toolchains for Linux/macOS/Windows.
None of them are Bionic-hosted, so none can run inside an Android app.
This repo cross-builds the same toolchain against the Android NDK so the
Spark APK can bundle the binaries and invoke them via `ProcessBuilder`.

## What it builds

A Canadian cross:

| axis   | value                     |
|--------|---------------------------|
| build  | `x86_64-linux-gnu` (CI)   |
| host   | `aarch64-linux-android` (API 24+) |
| target | `xtensa-lx106-elf`        |

Contents of the output tarball:

- `bin/xtensa-lx106-elf-{gcc,g++,ld,ar,as,objcopy,strip,...}` — aarch64 ELFs
- `xtensa-lx106-elf/include/` — newlib + libstdc++ headers
- `xtensa-lx106-elf/lib/` — libc.a, libm.a, libstdc++.a, libgcc.a

Everything static. No shared libs — Bionic doesn't have the same dynamic
linker glibc expects, and we don't want to ship glibc.

## How to run it

Trigger from the GitHub Actions UI:

1. Repo → **Actions** → *xtensa-lx106-elf toolchain (Android host)*
2. **Run workflow** — tick `upload_release` to publish the tarball as a
   release asset.

The run takes ~90 min on a free-tier ubuntu runner (phase A ~45 min,
phase B ~30 min, rest is bookkeeping).

## Known fragility

- **Phase A can fail on crosstool-NG config drift.** Espressif's fork gets
  rebased; if `ct-ng xtensa-lx106-elf` stops producing a usable config,
  pin the submodule.
- **Canadian cross reuses phase-A target libs.** This is valid because
  target libs are host-independent, but gcc's install-gcc step will
  occasionally re-link something it shouldn't. If you see "target libs
  missing" errors, the fallback is to run the full `make all && make install`
  on phase B with `--with-build-sysroot` pointed at phase-A's sysroot.
- **NDK version matters.** API 24 is the floor. Going higher (26, 28) is
  fine; going lower means missing symbols like `getauxval` that newer gcc
  emits calls to.

## Consuming the tarball

Phase 2 (prebuild Arduino core) pulls it via the workflow's artifact API.
Phase 3 (APK packaging) pulls it from the release tag and renames each
binary to `lib<name>.so` under `jniLibs/arm64-v8a/` so Android's package
manager puts it somewhere exec-permitted.
