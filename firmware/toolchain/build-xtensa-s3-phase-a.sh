#!/usr/bin/env bash
# Phase A of the Canadian cross for ESP32-S3: builds a native x86_64 Linux
# -> xtensa-esp32s3-elf cross. Its output feeds PHASE_A_PREFIX consumed by
# build-xtensa-s3-android.sh (phase B), which produces the aarch64-Android
# toolchain we actually ship.
#
# Why this is a standalone script rather than `ct-ng xtensa-esp32s3-elf`
# (what the lx106 phase A does in the workflow): Espressif moved the S3 /
# gcc-13.2 toolchain off crosstool-NG. The pins below are the same tags
# phase B uses, so both phases stay version-locked.
#
# Env:
#   PHASE_A_PREFIX — install prefix (consumed by phase B as PHASE_A_PREFIX)

set -euo pipefail

: "${PHASE_A_PREFIX:?PHASE_A_PREFIX must be set}"

TARGET="xtensa-esp32s3-elf"

# Must match build-xtensa-s3-android.sh — phase B copies target libs from
# PHASE_A_PREFIX, so a version skew would silently ship mismatched libgcc.
BINUTILS_REF="esp-2.41.0_20240530"
GCC_REF="esp-13.2.0_20240530"
NEWLIB_REF="esp-4.3.0_20240530"
GMP_VER="6.3.0"
MPFR_VER="4.2.1"
MPC_VER="1.3.1"
ISL_VER="0.26"

WORK="$HOME/xtensa-s3-phase-a"
SRC="$WORK/src"
BLD="$WORK/build"
mkdir -p "$SRC" "$BLD" "$PHASE_A_PREFIX"
sudo chown -R "$USER" "$PHASE_A_PREFIX" || true

# Native host CC — runner's system gcc. Clear any env phase B may have set.
unset CC CXX AR RANLIB STRIP LD CFLAGS CXXFLAGS LDFLAGS

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

fetch_git https://github.com/espressif/binutils-gdb.git    "$BINUTILS_REF" binutils
fetch_git https://github.com/espressif/gcc.git             "$GCC_REF"      gcc
fetch_git https://github.com/espressif/newlib-esp32.git    "$NEWLIB_REF"   newlib
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
echo "===== binutils (phase A, $TARGET) ====="
mkdir -p "$BLD/binutils" && cd "$BLD/binutils"
"$SRC/binutils/configure" \
  --target="$TARGET" \
  --prefix="$PHASE_A_PREFIX" \
  --with-sysroot="$PHASE_A_PREFIX/$TARGET" \
  --disable-nls \
  --disable-werror \
  --disable-shared \
  --enable-static \
  --disable-gdb \
  --disable-sim \
  --disable-readline \
  --disable-libdecnumber
make -j"$JOBS"
make install

export PATH="$PHASE_A_PREFIX/bin:$PATH"

# --- 2. gcc stage 1 (C only, no libc headers) --------------------------------
# Bootstrap gcc without newlib so we can build newlib with it in step 3.
echo "===== gcc stage 1 (phase A, $TARGET) ====="
mkdir -p "$BLD/gcc1" && cd "$BLD/gcc1"
"$SRC/gcc/configure" \
  --target="$TARGET" \
  --prefix="$PHASE_A_PREFIX" \
  --with-sysroot="$PHASE_A_PREFIX/$TARGET" \
  --without-headers \
  --with-newlib \
  --enable-languages=c \
  --disable-shared \
  --enable-static \
  --disable-nls \
  --disable-werror \
  --disable-libssp \
  --disable-multilib \
  --disable-threads \
  --disable-libgomp \
  --disable-libatomic \
  --disable-libquadmath \
  --disable-decimal-float \
  --with-gnu-ld --with-gnu-as
make -j"$JOBS" all-gcc
make install-gcc
make -j"$JOBS" all-target-libgcc
make install-target-libgcc

# --- 3. newlib ---------------------------------------------------------------
echo "===== newlib (phase A, $TARGET) ====="
mkdir -p "$BLD/newlib" && cd "$BLD/newlib"
"$SRC/newlib/configure" \
  --target="$TARGET" \
  --prefix="$PHASE_A_PREFIX" \
  --disable-newlib-supplied-syscalls \
  --disable-nls
make -j"$JOBS"
make install

# --- 4. gcc stage 2 (C + C++ against installed newlib) -----------------------
echo "===== gcc stage 2 (phase A, $TARGET) ====="
mkdir -p "$BLD/gcc2" && cd "$BLD/gcc2"
"$SRC/gcc/configure" \
  --target="$TARGET" \
  --prefix="$PHASE_A_PREFIX" \
  --with-sysroot="$PHASE_A_PREFIX/$TARGET" \
  --with-newlib \
  --with-headers="$PHASE_A_PREFIX/$TARGET/include" \
  --enable-languages=c,c++ \
  --disable-shared \
  --enable-static \
  --disable-nls \
  --disable-werror \
  --disable-libssp \
  --disable-libstdcxx-pch \
  --disable-multilib \
  --disable-threads \
  --disable-libgomp \
  --disable-libatomic \
  --disable-libquadmath \
  --disable-decimal-float \
  --with-gnu-ld --with-gnu-as
make -j"$JOBS"
make install

du -sh "$PHASE_A_PREFIX"
echo "===== phase A done ====="
ls "$PHASE_A_PREFIX/bin"
