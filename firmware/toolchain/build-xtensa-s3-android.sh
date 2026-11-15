#!/usr/bin/env bash
# Phase B of the Canadian cross for ESP32-S3: builds an xtensa-esp32s3-elf
# toolchain whose binaries execute on aarch64-linux-android (Android arm64 /
# Bionic).
#
# Sibling of build-xtensa-android.sh (ESP8266/lx106). Kept separate rather
# than parametrized because the pinned Espressif refs are different and the
# gcc/newlib combo for S3 is on a much newer branch (gcc 13.2 vs lx106's
# gcc 10.3). A single script branching on TARGET would obscure which versions
# ship with which artifact.
#
# Expects the same env contract as build-xtensa-android.sh:
#   NDK_ROOT       — path to extracted Android NDK
#   ANDROID_API    — min Android API (24+)
#   TARGET         — xtensa-esp32s3-elf  (required; validated below)
#   HOST_TRIPLE    — aarch64-linux-android
#   PREFIX         — final install prefix (e.g. /opt/xtensa-esp32s3-elf-android)
#   PHASE_A_PREFIX — prefix where the native x86_64-hosted cross lives
#
# Pins are the arduino-esp32 3.x era so the resulting toolchain is
# ABI-compatible with the arduino-esp32 core we'll prebuild in phase 2.

set -euo pipefail

: "${NDK_ROOT:?NDK_ROOT must be set}"
: "${ANDROID_API:?ANDROID_API must be set}"
: "${TARGET:?TARGET must be set}"
: "${HOST_TRIPLE:?HOST_TRIPLE must be set}"
: "${PREFIX:?PREFIX must be set}"
: "${PHASE_A_PREFIX:?PHASE_A_PREFIX must be set}"

if [[ "$TARGET" != "xtensa-esp32s3-elf" ]]; then
  echo "ERROR: this script only builds xtensa-esp32s3-elf (got '$TARGET')" >&2
  echo "       use build-xtensa-android.sh for xtensa-lx106-elf (ESP8266)" >&2
  exit 2
fi

# --- Pinned versions (arduino-esp32 3.x era, gcc 13.2) -----------------------
# These tags track the toolchain that ships with ESP-IDF v5.1.x, which
# arduino-esp32 3.x is built against. Bump when we move to IDF v5.2+.
BINUTILS_REF="esp-2.41.0_20240530"
GCC_REF="esp-13.2.0_20240530"
NEWLIB_REF="esp-4.3.0_20240530"
GMP_VER="6.3.0"
MPFR_VER="4.2.1"
MPC_VER="1.3.1"
ISL_VER="0.26"

WORK="$HOME/xtensa-s3-canadian"
SRC="$WORK/src"
BLD="$WORK/build"
mkdir -p "$SRC" "$BLD" "$PREFIX"
sudo chown -R "$USER" "$PREFIX" || true

# --- Android NDK toolchain as host CC ----------------------------------------
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$NDK_BIN/${HOST_TRIPLE}${ANDROID_API}-clang"
export CXX="$NDK_BIN/${HOST_TRIPLE}${ANDROID_API}-clang++"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export LD="$NDK_BIN/ld.lld"

export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"
export LDFLAGS=""

# Phase-A target compiler on PATH so Canadian stage can produce target libs.
export PATH="$PHASE_A_PREFIX/bin:$PATH"

# --- Fetch sources -----------------------------------------------------------
fetch_git() {
  local url=$1 ref=$2 dir=$3
  if [[ ! -d "$SRC/$dir" ]]; then
    git clone --depth 1 --branch "$ref" "$url" "$SRC/$dir"
  fi
}
fetch_tar() {
  local url=$1 dir=$2
  if [[ ! -d "$SRC/$dir" ]]; then
    wget -q "$url" -O "$SRC/$(basename "$url")"
    tar -xf "$SRC/$(basename "$url")" -C "$SRC"
  fi
}

fetch_git https://github.com/espressif/binutils-gdb.git     "$BINUTILS_REF" binutils
fetch_git https://github.com/espressif/gcc.git              "$GCC_REF"      gcc
fetch_git https://github.com/espressif/newlib-esp32.git     "$NEWLIB_REF"   newlib
fetch_tar "https://ftp.gnu.org/gnu/gmp/gmp-${GMP_VER}.tar.xz"       "gmp-${GMP_VER}"
fetch_tar "https://ftp.gnu.org/gnu/mpfr/mpfr-${MPFR_VER}.tar.xz"    "mpfr-${MPFR_VER}"
fetch_tar "https://ftp.gnu.org/gnu/mpc/mpc-${MPC_VER}.tar.gz"       "mpc-${MPC_VER}"
fetch_tar "https://libisl.sourceforge.io/isl-${ISL_VER}.tar.xz"     "isl-${ISL_VER}"

ln -sfn "$SRC/gmp-${GMP_VER}"   "$SRC/gcc/gmp"
ln -sfn "$SRC/mpfr-${MPFR_VER}" "$SRC/gcc/mpfr"
ln -sfn "$SRC/mpc-${MPC_VER}"   "$SRC/gcc/mpc"
ln -sfn "$SRC/isl-${ISL_VER}"   "$SRC/gcc/isl"

JOBS=$(nproc)

# --- 1. binutils -------------------------------------------------------------
echo "===== binutils (Canadian, $TARGET) ====="
mkdir -p "$BLD/binutils" && cd "$BLD/binutils"
"$SRC/binutils/configure" \
  --build=x86_64-linux-gnu \
  --host="$HOST_TRIPLE" \
  --target="$TARGET" \
  --prefix="$PREFIX" \
  --with-sysroot="$PREFIX/$TARGET" \
  --disable-nls \
  --disable-werror \
  --disable-shared \
  --enable-static \
  --disable-gdb \
  --disable-sim \
  --disable-readline \
  --disable-libdecnumber \
  --with-system-zlib=no
make -j"$JOBS"
make install

# --- 2. gcc stage 2 (reuses phase-A target libs) -----------------------------
echo "===== staging phase-A target libs into Canadian prefix ====="
mkdir -p "$PREFIX/$TARGET"
cp -a "$PHASE_A_PREFIX/$TARGET/." "$PREFIX/$TARGET/"
mkdir -p "$PREFIX/lib/gcc/$TARGET"
cp -a "$PHASE_A_PREFIX/lib/gcc/$TARGET/." "$PREFIX/lib/gcc/$TARGET/"

echo "===== gcc (Canadian, $TARGET) ====="
mkdir -p "$BLD/gcc" && cd "$BLD/gcc"
"$SRC/gcc/configure" \
  --build=x86_64-linux-gnu \
  --host="$HOST_TRIPLE" \
  --target="$TARGET" \
  --prefix="$PREFIX" \
  --with-sysroot="$PREFIX/$TARGET" \
  --with-newlib \
  --with-headers="$PREFIX/$TARGET/include" \
  --enable-languages=c,c++ \
  --enable-static \
  --disable-shared \
  --disable-nls \
  --disable-werror \
  --disable-libssp \
  --disable-libstdcxx-pch \
  --disable-multilib \
  --disable-threads \
  --disable-libmudflap \
  --disable-libgomp \
  --disable-libatomic \
  --disable-libquadmath \
  --disable-decimal-float \
  --with-gnu-ld \
  --with-gnu-as \
  --with-local-prefix="$PREFIX/$TARGET"
make -j"$JOBS" all-gcc
make install-gcc

# --- 3. Strip & package ------------------------------------------------------
echo "===== strip ====="
file "$PREFIX/bin/$TARGET-gcc" | grep -q "ELF 64-bit LSB.*ARM aarch64" || {
  echo "ERROR: $PREFIX/bin/$TARGET-gcc is not an aarch64 ELF binary"
  file "$PREFIX/bin/$TARGET-gcc"
  exit 1
}
find "$PREFIX" -type f \( -name "*.so*" -o -perm -u+x \) \
  -exec "$STRIP" --strip-unneeded {} + 2>/dev/null || true

du -sh "$PREFIX"
echo "===== done ====="
ls "$PREFIX/bin"
