#!/usr/bin/env bash
# Disable every package listed in a debloat file on the TV (one package per line, # comments allowed).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same file>.
#
# Usage: TV_HOST=TV_IP tools/disable.sh debloat/xiaomi-mi-tv-4a/debloat.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: disable.sh <debloat list>}"
. tools/pyenv.sh
export MSYS_NO_PATHCONV=1
{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | awk '{print "pm disable-user --user 0 " $1}'
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
