#!/usr/bin/env bash
#  SPDX-License-Identifier: MIT
#
#  PlayRetro
#  build-deps.sh
#
#  Cross-compiles the native dependencies for Android using the NDK (run on Linux/WSL).
#
#  Usage: android/deps/build-deps.sh [-a ABI] [step ...]
#         Without steps, all steps are run in order. Steps already completed are skipped
#         unless FORCE=1 is set.
#
#  Environment:
#    ANDROID_NDK_ROOT  NDK location (default /opt/android/android-ndk-r28c)
#    WORK_DIR          Source/build directory (default ~/playretro-deps)
#    API               Android API level (default 29)
#
#  Output:
#    android/prefix/<abi>/{include,lib}  headers and libraries
#    android/libs/<abi>/                 libraries copied with unversioned sonames, as
#                                        expected by CMakeLists.txt and the APK packaging
#

set -euo pipefail

ABI=arm64-v8a
while getopts "a:" opt; do
    case $opt in
        a) ABI=$OPTARG ;;
        *) exit 1 ;;
    esac
done
shift $((OPTIND - 1))

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
NDK=${ANDROID_NDK_ROOT:-/opt/android/android-ndk-r28c}
API=${API:-29}
WORK=${WORK_DIR:-$HOME/playretro-deps}
JOBS=$(nproc)

SRC=$WORK/src
BUILD=$WORK/build/$ABI
PREFIX=$WORK/prefix/$ABI
STAMPS=$WORK/stamps/$ABI
OUT_PREFIX=$REPO_DIR/android/prefix/$ABI
OUT_LIBS=$REPO_DIR/android/libs/$ABI

case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android; CPU=aarch64; OPENSSL_TARGET=android-arm64 ;;
    x86_64) TRIPLE=x86_64-linux-android; CPU=x86_64; OPENSSL_TARGET=android-x86_64 ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi; CPU=arm; OPENSSL_TARGET=android-arm ;;
    *) echo "Unsupported ABI $ABI"; exit 1 ;;
esac

TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export PATH=$TOOLCHAIN/bin:$PATH
export CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip
export NM=$TOOLCHAIN/bin/llvm-nm
export LD=$TOOLCHAIN/bin/ld.lld
# 16 KB page size support is required for new apps targeting Android 15+.
export LDFLAGS="-Wl,-z,max-page-size=16384 -L$PREFIX/lib"
export CFLAGS="-O2 -fPIC -DNDEBUG"
export CXXFLAGS="-O2 -fPIC -DNDEBUG"
export CPPFLAGS="-I$PREFIX/include"
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig
export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig

CMAKE_ANDROID=(
    -G Ninja
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="android-$API"
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    -DANDROID_STL=c++_shared
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX="$PREFIX"
    -DCMAKE_PREFIX_PATH="$PREFIX"
    -DCMAKE_FIND_ROOT_PATH="$PREFIX"
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)

# Versions.
SDL2_VERSION=2.32.10
OPENSSL_VERSION=3.5.9
CURL_VERSION=8.22.0
JPEG_VERSION=3.1.4.1
FREETYPE_VERSION=2.15.0
HARFBUZZ_VERSION=14.6.0
ICU_VERSION=78.3
LIBGIT2_VERSION=1.9.7
PUGIXML_VERSION=1.16
FREEIMAGE_VERSION=3180
POPPLER_VERSION=26.06.0
DAV1D_VERSION=1.5.4
FFMPEG_VERSION=8.1.3

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

fetch() { # url, dest-dir-name
    local url=$1 name=$2 file
    file=$SRC/$(basename "${url%%\?*}")
    [ -d "$SRC/$name" ] && return
    mkdir -p "$SRC"
    [ -f "$file" ] || curl -fL --retry 3 -o "$file" "$url"
    local tmp=$SRC/.extract-$name
    rm -rf "$tmp" && mkdir -p "$tmp"
    case $file in
        *.zip) unzip -q "$file" -d "$tmp" ;;
        *) tar -xf "$file" -C "$tmp" ;;
    esac
    # Archives contain a single top-level directory.
    mv "$tmp"/* "$SRC/$name"
    rm -rf "$tmp"
}

meson_cross_file() {
    local f=$BUILD/meson-cross.ini
    mkdir -p "$BUILD"
    cat > "$f" << EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-O2', '-fPIC', '-I$PREFIX/include']
cpp_args = ['-O2', '-fPIC', '-I$PREFIX/include']
c_link_args = ['-Wl,-z,max-page-size=16384', '-L$PREFIX/lib']
cpp_link_args = ['-Wl,-z,max-page-size=16384', '-L$PREFIX/lib']

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = '$CPU'
cpu = '$CPU'
endian = 'little'
EOF
    echo "$f"
}

#---------------------------------------------------------------------------------------------------

step_openssl() {
    fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" openssl
    rm -rf "$BUILD/openssl" && cp -r "$SRC/openssl" "$BUILD/openssl" && cd "$BUILD/openssl"
    export ANDROID_NDK_ROOT=$NDK
    ./Configure "$OPENSSL_TARGET" -D__ANDROID_API__="$API" shared no-tests no-docs no-apps \
        no-engine no-module --prefix="$PREFIX" --libdir=lib
    make -j"$JOBS" build_libs
    make install_dev
}

step_curl() {
    fetch "https://curl.se/download/curl-$CURL_VERSION.tar.xz" curl
    cmake -S "$SRC/curl" -B "$BUILD/curl" "${CMAKE_ANDROID[@]}" \
        -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF \
        -DBUILD_LIBCURL_DOCS=OFF -DBUILD_MISC_DOCS=OFF -DENABLE_CURL_MANUAL=OFF \
        -DCURL_USE_OPENSSL=ON -DOPENSSL_ROOT_DIR="$PREFIX" -DCURL_USE_LIBPSL=OFF \
        -DCURL_USE_LIBSSH2=OFF -DUSE_LIBIDN2=OFF -DUSE_NGHTTP2=OFF -DCURL_ZSTD=OFF \
        -DCURL_BROTLI=OFF -DCURL_DISABLE_LDAP=ON -DCURL_CA_FALLBACK=OFF
    cmake --build "$BUILD/curl" -j"$JOBS" && cmake --install "$BUILD/curl"
}

step_jpeg() {
    fetch "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$JPEG_VERSION/libjpeg-turbo-$JPEG_VERSION.tar.gz" libjpeg-turbo
    cmake -S "$SRC/libjpeg-turbo" -B "$BUILD/jpeg" "${CMAKE_ANDROID[@]}" \
        -DENABLE_SHARED=ON -DENABLE_STATIC=OFF -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF \
        -DWITH_TESTS=OFF -DCMAKE_INSTALL_LIBDIR=lib
    cmake --build "$BUILD/jpeg" -j"$JOBS" && cmake --install "$BUILD/jpeg"
}

step_freetype() {
    fetch "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VERSION.tar.xz" freetype
    cmake -S "$SRC/freetype" -B "$BUILD/freetype" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON \
        -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_BROTLI=ON \
        -DFT_REQUIRE_ZLIB=ON -DCMAKE_INSTALL_LIBDIR=lib
    cmake --build "$BUILD/freetype" -j"$JOBS" && cmake --install "$BUILD/freetype"
}

step_harfbuzz() {
    fetch "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ_VERSION/harfbuzz-$HARFBUZZ_VERSION.tar.xz" harfbuzz
    local cross
    cross=$(meson_cross_file)
    rm -rf "$BUILD/harfbuzz"
    meson setup "$BUILD/harfbuzz" "$SRC/harfbuzz" --cross-file "$cross" --prefix "$PREFIX" \
        --libdir lib --buildtype release --default-library shared -Dfreetype=enabled \
        -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled -Dchafa=disabled \
        -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dbenchmark=disabled \
        -Dintrospection=disabled
    meson compile -C "$BUILD/harfbuzz" && meson install -C "$BUILD/harfbuzz"
}

step_icu() {
    fetch "https://github.com/unicode-org/icu/releases/download/release-$ICU_VERSION/icu4c-$ICU_VERSION-sources.tgz" icu
    cp "$REPO_DIR/es-app/assets/icu_filters.json" "$SRC/icu/source/"
    # Cross-compiling ICU requires a build for the host first.
    local host=$WORK/build/icu-host
    if [ ! -f "$host/lib/libicuuc.a" ]; then
        rm -rf "$host" && mkdir -p "$host" && cd "$host"
        env -u CC -u CXX -u AR -u RANLIB -u LD -u CFLAGS -u CXXFLAGS -u CPPFLAGS -u LDFLAGS \
            ICU_DATA_FILTER_FILE="$SRC/icu/source/icu_filters.json" \
            "$SRC/icu/source/configure" --disable-shared --enable-static --disable-tests \
            --disable-samples --disable-extras
        env -u CC -u CXX -u AR -u RANLIB -u LD make -j"$JOBS"
    fi
    rm -rf "$BUILD/icu" && mkdir -p "$BUILD/icu" && cd "$BUILD/icu"
    ICU_DATA_FILTER_FILE="$SRC/icu/source/icu_filters.json" \
        CXXFLAGS="$CXXFLAGS -DUCONFIG_NO_COLLATION -DUCONFIG_NO_TRANSLITERATION" \
        "$SRC/icu/source/configure" --host="$TRIPLE" --with-cross-build="$host" \
        --prefix="$PREFIX" --disable-shared --enable-static --disable-tests --disable-samples \
        --disable-extras --disable-icuio --disable-tools --with-data-packaging=static
    make -j"$JOBS" && make install
}

# Minimal libintl (intl/intl.cpp) instead of GNU gettext's, whose gettext() never returned
# on Android 15 (busy loop on the first lookup).
step_intl() {
    rm -f "$PREFIX"/lib/libintl.* "$PREFIX/include/libintl.h"
    "$CXX" $CXXFLAGS -std=c++17 -Wall -Wextra -shared -Wl,-soname,libintl.so $LDFLAGS \
        -I"$SCRIPT_DIR/intl" "$SCRIPT_DIR/intl/intl.cpp" -o "$PREFIX/lib/libintl.so"
    cp "$SCRIPT_DIR/intl/libintl.h" "$PREFIX/include/"
}

step_libgit2() {
    fetch "https://github.com/libgit2/libgit2/archive/refs/tags/v$LIBGIT2_VERSION.tar.gz" libgit2
    cmake -S "$SRC/libgit2" -B "$BUILD/libgit2" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON \
        -DBUILD_TESTS=OFF -DBUILD_CLI=OFF -DBUILD_EXAMPLES=OFF -DUSE_SSH=OFF \
        -DUSE_HTTPS=OpenSSL -DOPENSSL_ROOT_DIR="$PREFIX" -DREGEX_BACKEND=builtin \
        -DUSE_HTTP_PARSER=builtin -DUSE_BUNDLED_ZLIB=OFF -DCMAKE_INSTALL_LIBDIR=lib
    cmake --build "$BUILD/libgit2" -j"$JOBS" && cmake --install "$BUILD/libgit2"
}

step_pugixml() {
    fetch "https://github.com/zeux/pugixml/releases/download/v$PUGIXML_VERSION/pugixml-$PUGIXML_VERSION.tar.gz" pugixml
    cmake -S "$SRC/pugixml" -B "$BUILD/pugixml" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=OFF \
        -DPUGIXML_BUILD_TESTS=OFF -DCMAKE_INSTALL_LIBDIR=lib
    cmake --build "$BUILD/pugixml" -j"$JOBS" && cmake --install "$BUILD/pugixml"
}

step_freeimage() {
    fetch "https://downloads.sourceforge.net/project/freeimage/Source%20Distribution/3.18.0/FreeImage$FREEIMAGE_VERSION.zip" FreeImage
    rm -rf "$BUILD/freeimage" && cp -r "$SRC/FreeImage" "$BUILD/freeimage" && cd "$BUILD/freeimage"
    bash "$SCRIPT_DIR/patch-freeimage.sh" .
    # The makefile appends to CFLAGS/CXXFLAGS, so they must come from the environment rather than
    # the make command line. It links with $(CC), hence the explicit C++ runtime.
    CFLAGS="$CFLAGS -fexceptions -fvisibility=hidden -DHAVE_UNISTD_H -DPNG_ARM_NEON_OPT=0 -Wno-implicit-function-declaration -Wno-int-conversion -Wno-incompatible-pointer-types" \
    CXXFLAGS="$CXXFLAGS -fexceptions -fvisibility=hidden -DHAVE_UNISTD_H -DPNG_ARM_NEON_OPT=0 -std=c++14 -Wno-ctor-dtor-privacy -Wno-register -Wno-c++11-narrowing" \
        make -f Makefile.gnu -j"$JOBS" CC="$CC" CXX="$CXX" AR="$AR" \
        LIBRARIES="$LDFLAGS -lc++_shared -lm" libfreeimage-3.18.0.so
    install -Dm644 Source/FreeImage.h "$PREFIX/include/FreeImage.h"
    install -Dm755 libfreeimage-3.18.0.so "$PREFIX/lib/libfreeimage.so"
}

step_poppler() {
    fetch "https://poppler.freedesktop.org/poppler-$POPPLER_VERSION.tar.xz" poppler
    cmake -S "$SRC/poppler" -B "$BUILD/poppler" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON \
        -DENABLE_UTILS=OFF -DENABLE_CPP=ON -DENABLE_GLIB=OFF -DENABLE_QT5=OFF -DENABLE_QT6=OFF \
        -DENABLE_BOOST=OFF -DENABLE_NSS3=OFF -DENABLE_GPGME=OFF -DENABLE_LCMS=OFF \
        -DENABLE_LIBCURL=OFF -DENABLE_LIBTIFF=OFF -DENABLE_LIBOPENJPEG=none -DWITH_PNG=OFF \
        -DENABLE_DCTDECODER=libjpeg -DFONT_CONFIGURATION=android -DRUN_GPERF_IF_PRESENT=OFF \
        -DBUILD_GTK_TESTS=OFF -DBUILD_QT5_TESTS=OFF -DBUILD_QT6_TESTS=OFF -DBUILD_CPP_TESTS=OFF \
        -DBUILD_MANUAL_TESTS=OFF -DENABLE_ZLIB_UNCOMPRESS=OFF -DCMAKE_INSTALL_LIBDIR=lib
    cmake --build "$BUILD/poppler" -j"$JOBS" && cmake --install "$BUILD/poppler"
}

step_dav1d() {
    fetch "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.gz" dav1d
    local cross
    cross=$(meson_cross_file)
    rm -rf "$BUILD/dav1d"
    meson setup "$BUILD/dav1d" "$SRC/dav1d" --cross-file "$cross" --prefix "$PREFIX" \
        --libdir lib --buildtype release --default-library static -Denable_tools=false \
        -Denable_tests=false -Denable_examples=false
    meson compile -C "$BUILD/dav1d" && meson install -C "$BUILD/dav1d"
}

step_ffmpeg() {
    fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" ffmpeg
    rm -rf "$BUILD/ffmpeg" && mkdir -p "$BUILD/ffmpeg" && cd "$BUILD/ffmpeg"
    local arch=$CPU
    [ "$ABI" = arm64-v8a ] && arch=aarch64
    "$SRC/ffmpeg/configure" --prefix="$PREFIX" --target-os=android --arch="$arch" \
        --enable-cross-compile --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" \
        --nm="$NM" --strip="$STRIP" --pkg-config=pkg-config --sysroot="$TOOLCHAIN/sysroot" \
        --extra-cflags="$CFLAGS -I$PREFIX/include" --extra-ldflags="$LDFLAGS" \
        --enable-shared --disable-static --disable-programs --disable-doc --disable-avdevice \
        --disable-debug --enable-pic --enable-jni --enable-mediacodec --enable-libdav1d \
        --disable-lzma --disable-vulkan
    make -j"$JOBS" && make install
}

step_sdl2() {
    fetch "https://github.com/libsdl-org/SDL/releases/download/release-$SDL2_VERSION/SDL2-$SDL2_VERSION.tar.gz" SDL2
    cmake -S "$SRC/SDL2" -B "$BUILD/sdl2" "${CMAKE_ANDROID[@]}" -DSDL_SHARED=ON -DSDL_STATIC=OFF \
        -DSDL_TEST=OFF -DCMAKE_INSTALL_LIBDIR=lib
    cmake --build "$BUILD/sdl2" -j"$JOBS" && cmake --install "$BUILD/sdl2"
    # SDL's Java glue is needed by the app module; keep it alongside the sources.
    rm -rf "$WORK/sdl2-java" && mkdir -p "$WORK/sdl2-java"
    cp -r "$SRC/SDL2/android-project/app/src/main/java/org" "$WORK/sdl2-java/"
}

# Compile the gettext message catalogs into resources/locale, which is bundled in the APK.
# COMPILE_LOCALIZATIONS is off for Android builds as the Gradle build may run on Windows.
step_locales() {
    local po name
    for po in "$REPO_DIR"/locale/po/*.po; do
        name=$(basename "$po" .po)
        mkdir -p "$REPO_DIR/resources/locale/$name/LC_MESSAGES"
        msgfmt -c -o "$REPO_DIR/resources/locale/$name/LC_MESSAGES/$name.mo" "$po"
    done
}

# Copy headers and libraries into the repository, renaming versioned shared objects
# (libfoo.so.3) to libfoo.so and patching sonames and dependencies accordingly.
step_install() {
    rm -rf "$OUT_PREFIX" "$OUT_LIBS"
    mkdir -p "$OUT_PREFIX" "$OUT_LIBS"
    cp -r "$PREFIX/include" "$OUT_PREFIX/"
    mkdir -p "$OUT_PREFIX/lib"
    cp -a "$PREFIX"/lib/*.a "$OUT_PREFIX/lib/" 2>/dev/null || true
    cp -a "$PREFIX"/lib/*.a "$OUT_LIBS/" 2>/dev/null || true

    local so real base
    for so in "$PREFIX"/lib/*.so "$PREFIX"/lib/*.so.*; do
        [ -e "$so" ] || continue
        real=$(readlink -f "$so")
        base=$(basename "$so" | sed -E 's/\.so(\..*)?$/.so/')
        [ -f "$OUT_LIBS/$base" ] && continue
        cp "$real" "$OUT_LIBS/$base"
        chmod 755 "$OUT_LIBS/$base"
        patchelf --set-soname "$base" "$OUT_LIBS/$base"
    done
    local sysroot_triple=$TRIPLE
    [ "$ABI" = armeabi-v7a ] && sysroot_triple=arm-linux-androideabi
    cp "$TOOLCHAIN/sysroot/usr/lib/$sysroot_triple/libc++_shared.so" "$OUT_LIBS/"
    for so in "$OUT_LIBS"/*.so; do
        local dep
        for dep in $(patchelf --print-needed "$so"); do
            if [[ $dep =~ ^(lib.+\.so)\.[0-9.]+$ ]]; then
                patchelf --replace-needed "$dep" "${BASH_REMATCH[1]}" "$so"
            fi
        done
        "$STRIP" --strip-unneeded "$so"
    done
    cp -a "$OUT_LIBS"/*.so "$OUT_PREFIX/lib/"

    if [ -d "$WORK/sdl2-java" ]; then
        rm -rf "$REPO_DIR/android/sdl2-java"
        cp -r "$WORK/sdl2-java" "$REPO_DIR/android/sdl2-java"
    fi
    log "Installed to $OUT_LIBS"
    ls -la "$OUT_LIBS"
}

ALL_STEPS=(openssl curl jpeg freetype harfbuzz icu intl libgit2 pugixml freeimage poppler dav1d
           ffmpeg sdl2 locales install)

mkdir -p "$SRC" "$BUILD" "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$STAMPS"

# The NDK provides zlib but no pkg-config file for it, which pkg-config consumers require.
cat > "$PREFIX/lib/pkgconfig/zlib.pc" << EOF
Name: zlib
Description: zlib compression library (Android NDK)
Version: 1.3
Libs: -lz
Cflags:
EOF

[ $# -eq 0 ] && set -- "${ALL_STEPS[@]}"

for step in "$@"; do
    if [ -f "$STAMPS/$step" ] && [ "${FORCE:-0}" != 1 ] && [ "$step" != install ]; then
        log "Skipping $step (already built)"
        continue
    fi
    log "Building $step for $ABI"
    # Run in a subshell with errexit re-enabled: errexit is ignored inside functions invoked as
    # part of a && list, which would otherwise hide build failures.
    set +e
    (set -e; cd "$WORK"; "step_$step") 2>&1 | tee "$WORK/build-$ABI-$step.log"
    status=${PIPESTATUS[0]}
    set -e
    if [ "$status" -ne 0 ]; then
        log "Step $step failed, see $WORK/build-$ABI-$step.log"
        exit "$status"
    fi
    touch "$STAMPS/$step"
done
