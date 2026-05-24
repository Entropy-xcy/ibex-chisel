#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
venv="${IBEX_PYTHON_VENV:-${repo_root}/generated/ibex-python-venv}"

python3 -m venv "${venv}"
"${venv}/bin/python" -m pip install --upgrade pip setuptools wheel
"${venv}/bin/python" -m pip install \
  mako \
  pathlib3x \
  pyyaml \
  bitstring==3.1.9 \
  typeguard \
  typing-utils \
  portalocker \
  pydantic \
  junit-xml \
  tabulate \
  pandas \
  pyvsc

"${venv}/bin/python" - <<'PY'
import mako
import yaml
import junit_xml
from typing_utils import get_args
print("ibex-python-venv-ok")
PY
