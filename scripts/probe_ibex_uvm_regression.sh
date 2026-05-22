#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

out="${IBEX_UVM_PROBE_OUT:-generated/regression/uvm_probe_out}"
log="${IBEX_UVM_PROBE_LOG:-generated/regression/logs/uvm_probe.log}"
simulator="${SIMULATOR:-xlm}"
iss="${ISS:-spike}"
ibex_config="${IBEX_CONFIG:-opentitan}"
test_name="${TEST:-riscv_arithmetic_basic_test}"
seed="${SEED:-1}"
iterations="${ITERATIONS:-1}"
local_spike_pkgconfig="${repo_root}/tools/spike-ibex-cosim/lib/pkgconfig"

mkdir -p "$(dirname "${log}")"

if [[ -d "${local_spike_pkgconfig}" ]]; then
  export PKG_CONFIG_PATH="${local_spike_pkgconfig}:${PKG_CONFIG_PATH:-}"
  export LD_LIBRARY_PATH="${repo_root}/tools/spike-ibex-cosim/lib:${LD_LIBRARY_PATH:-}"
fi

missing=()
case "${simulator}" in
  xlm)
    command -v xrun >/dev/null 2>&1 || missing+=("xrun")
    ;;
  vcs)
    command -v vcs >/dev/null 2>&1 || missing+=("vcs")
    ;;
  questa)
    command -v vlog >/dev/null 2>&1 || missing+=("vlog")
    command -v vsim >/dev/null 2>&1 || missing+=("vsim")
    ;;
  dsim)
    command -v "${DSIM:-dsim}" >/dev/null 2>&1 || missing+=("${DSIM:-dsim}")
    ;;
  qrun)
    command -v qrun >/dev/null 2>&1 || missing+=("qrun")
    ;;
esac

command -v pkg-config >/dev/null 2>&1 || missing+=("pkg-config")
if command -v pkg-config >/dev/null 2>&1 &&
   ! pkg-config --exists riscv-riscv riscv-disasm riscv-fdt riscv-fesvr; then
  missing+=("lowRISC Spike pkg-config packages")
fi

if ((${#missing[@]})); then
  printf 'Missing UVM prerequisites: %s\n' "${missing[*]}" | tee "${log}"
  exit 2
fi

(
  cd externals/ibex/dv/uvm/core_ibex
  make --keep-going \
    OUT="${repo_root}/${out}" \
    IBEX_CONFIG="${ibex_config}" \
    SIMULATOR="${simulator}" \
    ISS="${iss}" \
    ITERATIONS="${iterations}" \
    SEED="${seed}" \
    TEST="${test_name}" \
    WAVES=0 \
    COV=0 \
    GOAL=rtl_tb_compile
) > "${log}" 2>&1

echo "UVM probe completed: ${log}"
