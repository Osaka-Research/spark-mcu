#!/usr/bin/env bash
# Phase B of the Canadian cross: builds an xtensa-lx106-elf toolchain
# whose binaries execute on aarch64-linux-android (Android arm64 / Bionic).
#
# Expects the following env from the workflow:
#   NDK_ROOT       — path to extracted Android NDK
#   ANDROID_API    — min Android API (e.g. 24)
#   TARGET         — xtensa-lx106-elf
#   HOST_TRIPLE    — aarch64-linux-android
#   PREFIX         — final install prefix (e.g. /opt/xtensa-lx106-elf-android)
#   PHASE_A_PREFIX — prefix where the native x86_64-hosted cross lives (phase A output)
#
# Pins Espressif's gcc/binutils/newlib forks. These versions match what
# Arduino-ESP8266 3.x ships with, so the resulting toolchain is ABI-compatible
# with the ESP8266 Arduino core we prebuild in phase 2.

set -euo pipefail

: "${NDK_ROOT:?NDK_ROOT must be set}"
: "${ANDROID_API:?ANDROID_API must be set}"
: "${TARGET:?TARGET must be set}"
: "${HOST_TRIPLE:?HOST_TRIPLE must be set}"
: "${PREFIX:?PREFIX must be set}"
: "${PHASE_A_PREFIX:?PHASE_A_PREFIX must be set}"

# --- Pinned versions (Arduino-ESP8266 3.1.2 era) -----------------------------
BINUTILS_REF="esp-2021r2-patch5"
GCC_REF="esp-2021r2-patch5"
NEWLIB_REF="esp-2021r2-patch5"
GMP_VER="6.3.0"
MPFR_VER="4.2.1"
MPC_VER="1.3.1"
ISL_VER="0.26"

WORK="$HOME/xtensa-canadian"
SRC="$WORK/src"
BLD="$WORK/build"
mkdir -p "$SRC" "$BLD" "$PREFIX"
sudo chown -R "$USER" "$PREFIX" || true

# --- Android NDK toolchain as host CC ----------------------------------------
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
NDK_SYSROOT="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
export CC="$NDK_BIN/${HOST_TRIPLE}${ANDROID_API}-clang"
export CXX="$NDK_BIN/${HOST_TRIPLE}${ANDROID_API}-clang++"
export AR="$NDK_BIN/llvm-ar"
export RANLIB="$NDK_BIN/llvm-ranlib"
export STRIP="$NDK_BIN/llvm-strip"
export LD="$NDK_BIN/ld.lld"

# Bionic doesn't ship iconv, libintl — keep the build lean.
export CFLAGS="-O2 -fPIC"
export CXXFLAGS="-O2 -fPIC"
# Bionic's pthread is baked into libc; no -lpthread needed, but harmless.
export LDFLAGS=""

# Also need the phase-A target compiler on PATH to generate target libs.
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

fetch_git https://github.com/espressif/binutils-gdb.git "$BINUTILS_REF" binutils
fetch_git https://github.com/espressif/gcc.git "$GCC_REF" gcc
fetch_git https://github.com/espressif/newlib-esp32.git "$NEWLIB_REF" newlib
fetch_tar "https://ftp.gnu.org/gnu/gmp/gmp-${GMP_VER}.tar.xz" "gmp-${GMP_VER}"
fetch_tar "https://ftp.gnu.org/gnu/mpfr/mpfr-${MPFR_VER}.tar.xz" "mpfr-${MPFR_VER}"
fetch_tar "https://ftp.gnu.org/gnu/mpc/mpc-${MPC_VER}.tar.gz" "mpc-${MPC_VER}"
fetch_tar "https://libisl.sourceforge.io/isl-${ISL_VER}.tar.xz" "isl-${ISL_VER}"

# Symlink gmp/mpfr/mpc/isl inside gcc src so gcc's in-tree build picks them up.
ln -sfn "$SRC/gmp-${GMP_VER}"  "$SRC/gcc/gmp"
ln -sfn "$SRC/mpfr-${MPFR_VER}" "$SRC/gcc/mpfr"
ln -sfn "$SRC/mpc-${MPC_VER}"  "$SRC/gcc/mpc"
ln -sfn "$SRC/isl-${ISL_VER}"  "$SRC/gcc/isl"

JOBS=$(nproc)

# --- 1. binutils (Canadian: build on x86_64, run on android-arm64) -----------
echo "===== binutils (Canadian) ====="
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

# --- 2. gcc stage 2 (Canadian, reuses phase-A target libs) -------------------
# For a Canadian cross with bare-metal target, target libs (libgcc, newlib, libstdc++)
# can be copied from phase-A's install — they're identical bits. This saves ~1hr.
echo "===== staging phase-A target libs into Canadian prefix ====="
mkdir -p "$PREFIX/$TARGET"
cp -a "$PHASE_A_PREFIX/$TARGET/." "$PREFIX/$TARGET/"
mkdir -p "$PREFIX/lib/gcc/$TARGET"
cp -a "$PHASE_A_PREFIX/lib/gcc/$TARGET/." "$PREFIX/lib/gcc/$TARGET/"

echo "===== gcc (Canadian) ====="
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
# Canadian build: only the gcc *driver* and cc1/cc1plus are rebuilt for host.
# Target libs (libgcc/libstdc++) are the staged ones from phase-A.
make -j"$JOBS" all-gcc all-target-libgcc || true
make -j"$JOBS" all-gcc
make install-gcc
make install-target-libgcc || true

# --- 3. Strip & package ------------------------------------------------------
echo "===== strip ====="
find "$PREFIX/bin" -type f -exec file {} \; | grep -q "ELF 64-bit LSB" || {
  echo "ERROR: $PREFIX/bin does not contain aarch64 ELF binaries"
  file "$PREFIX/bin/"* | head
  exit 1
}
find "$PREFIX" -type f \( -name "*.so*" -o -perm -u+x \) \
  -exec "$STRIP" --strip-unneeded {} + 2>/dev/null || true

du -sh "$PREFIX"
echo "===== done ====="
ls "$PREFIX/bin"
