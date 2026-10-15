#!/usr/bin/bash
#  SPDX-License-Identifier: MIT
#
#  PlayRetro
#  generate_man_page.sh
#
#  Generates the Unix manual page.
#  The script takes no arguments and replaces the man page file es-app/assets/emulationstation.6.gz
#  It has to be run from within the tools directory.
#
#  The command help2man must be installed, or the script will fail.
#
#  This script is only intended to be used on Linux systems.
#

if [ ! -f ../es-app/CMakeLists.txt ]; then
  echo "You need to run this script from within the tools directory."
  exit
fi

ESBINARY=../playretro

TEMPFILE_INPUT=tempfile_input_$(date +%H%M%S)
TEMPFILE_OUTPUT=tempfile_output_$(date +%H%M%S)
TARGET_FILENAME=playretro.6

MAN_INCLUDE="
[NAME]
playretro - Play Retro

[DESCRIPTION]
PlayRetro is a frontend for browsing and launching games from your multi-platform collection.

[AUTHOR]
Leon Styhre <https://github.com/andersonassishildebrando/playretro>

RetroPie community (RetroPie EmulationStation)

Alec Lofquist (original EmulationStation)

[SEE ALSO]
Full documentation available at: <https://github.com/andersonassishildebrando/playretro>
"

echo "${MAN_INCLUDE}" > $TEMPFILE_INPUT

help2man --section 6 --no-info --include $TEMPFILE_INPUT $ESBINARY > $TEMPFILE_OUTPUT

# Manual string replacements, these may need to be modified if changes are made to the
# command line --help output.
cat $TEMPFILE_OUTPUT | sed s/"ES\\\-DE Frontend"/""/g | \
sed s/"Set to at least"/".br\nSet to at least"/ > $TARGET_FILENAME

gzip -9 $TARGET_FILENAME
mv ${TARGET_FILENAME}.gz ../es-app/assets/

echo "The man page was generated and saved to ../es-app/assets/playretro.6.gz"

rm $TEMPFILE_INPUT
rm $TEMPFILE_OUTPUT
