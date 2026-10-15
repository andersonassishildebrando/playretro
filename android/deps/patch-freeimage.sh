#!/usr/bin/env bash
#  SPDX-License-Identifier: MIT
#
#  PlayRetro
#  patch-freeimage.sh
#
#  Patches the FreeImage 3.18.0 sources so they build with the NDK clang toolchain.
#  Usage: patch-freeimage.sh <FreeImage source dir>
#

set -euo pipefail
cd "$1"

[ -f .playretro-patched ] && exit 0

JXR_GLUE=Source/LibJXR/jxrgluelib/JXRGlueJxr.c
SEGDEC=Source/LibJXR/image/decode/segdec.c
sed -i 's/\r$//' "$JXR_GLUE" "$SEGDEC"

# wcslen() and friends are used without including <wchar.h>.
sed -i 's/^#include <JXRGlue.h>$/#include <JXRGlue.h>\n#include <wchar.h>/' "$JXR_GLUE"

# _byteswap_ulong() is an MSVC intrinsic; provide a portable replacement.
perl -0pi -e 's|(// Memory access functions\n//=+\n)|$1#if !defined(_WIN32)\nstatic U32 _byteswap_ulong2(U32 bits)\n{\n    return ((bits & 0xffu) << 24) \| ((bits << 8) & 0xff0000u) \| ((bits >> 8) & 0xff00u) \|\n           ((bits >> 24) & 0xffu);\n}\n#define _byteswap_ulong _byteswap_ulong2\n#endif\n\n|' "$SEGDEC"

touch .playretro-patched
