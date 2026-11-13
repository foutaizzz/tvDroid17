#!/usr/bin/env bash
# Shared Python bootstrap for the shell scripts (build-and-install.sh, run-tests.sh,
# tools/disable.sh, tools/restore.sh).
#
#   source tools/pyenv.sh     # defines PY
#
# The scripts run Python with -I (isolated mode) so that only the standard library and the
# interpreter's own site-packages are visible. Isolated mode hides the per-user directory
# where "pip install --user" puts the modules, which is why a workspace virtualenv is the
# reliable place for them: its site-packages stay visible under -I.
#
# Order of preference: $TV_PYTHON, then the workspace venv, then any python3/python.

if [ -z "${PY:-}" ]; then
  for candidate in "${TV_PYTHON:-}" tools/venv/bin/python tools/venv/Scripts/python.exe python3 python; do
    [ -n "$candidate" ] || continue
    command -v "$candidate" >/dev/null 2>&1 || continue
    # Check the import the same way the scripts will use it, otherwise a user-site-only
    # installation would look fine here and fail later with a bare ModuleNotFoundError.
    if "$candidate" -I -c "import adb_shell" >/dev/null 2>&1; then
      PY="$candidate"
      break
    fi
  done
fi

if [ -z "${PY:-}" ]; then
  printf '%s\n' \
    "The ADB client modules are missing (adb_shell)." \
    "" \
    "Install them into a virtual environment, then run the script again:" \
    "" \
    "    python3 -m venv tools/venv" \
    "    tools/venv/bin/pip install -r tools/requirements.txt" \
    "" \
    "Windows PowerShell:" \
    "" \
    "    python -m venv tools\venv" \
    "    tools\venv\Scripts\pip.exe install -r tools\requirements.txt" \
    "" \
    "To use another interpreter, set TV_PYTHON=/path/to/python." >&2
  exit 1
fi
