#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

out_root="${IBEX_CHISEL_UVM_OUT:-generated/chisel-uvm-regression}"
config="${IBEX_CHISEL_UVM_CONFIG:-opentitan}"
goal="${IBEX_CHISEL_UVM_GOAL:-rtl_tb_compile}"
test_name="${IBEX_CHISEL_UVM_TEST:-riscv_arithmetic_basic_test}"
seed="${IBEX_CHISEL_UVM_SEED:-1}"
iterations="${IBEX_CHISEL_UVM_ITERATIONS:-1}"
simulator="${SIMULATOR:-xlm}"
iss="${ISS:-spike}"
local_spike_pkgconfig="${repo_root}/tools/spike-ibex-cosim/lib/pkgconfig"
llvm_riscv_toolchain="${repo_root}/scripts/llvm_riscv_toolchain"
python_venv="${repo_root}/generated/ibex-python-venv"

chisel_dir="${out_root}/${config}/rtl"
overlay_dir="${out_root}/${config}/core_ibex_overlay"
uvm_out="${out_root}/${config}/uvm_out"
log_dir="${out_root}/${config}/logs"
mkdir -p "${log_dir}"

if [[ -d "${local_spike_pkgconfig}" ]]; then
  export PKG_CONFIG_PATH="${local_spike_pkgconfig}:${PKG_CONFIG_PATH:-}"
  export LD_LIBRARY_PATH="${repo_root}/tools/spike-ibex-cosim/lib:${LD_LIBRARY_PATH:-}"
fi

if [[ -x "${llvm_riscv_toolchain}/bin/riscv32-unknown-elf-gcc" ]]; then
  export RISCV_TOOLCHAIN="${RISCV_TOOLCHAIN:-${llvm_riscv_toolchain}}"
  export RISCV_GCC="${RISCV_GCC:-${llvm_riscv_toolchain}/bin/riscv32-unknown-elf-gcc}"
  export RISCV_OBJCOPY="${RISCV_OBJCOPY:-${llvm_riscv_toolchain}/bin/riscv32-unknown-elf-objcopy}"
fi

if [[ -x "${python_venv}/bin/python" ]]; then
  export PATH="${python_venv}/bin:${PATH}"
fi

missing=()
case "${simulator}" in
  xlm) command -v xrun >/dev/null 2>&1 || missing+=("xrun") ;;
  vcs) command -v vcs >/dev/null 2>&1 || missing+=("vcs") ;;
  questa)
    command -v vlog >/dev/null 2>&1 || missing+=("vlog")
    command -v vsim >/dev/null 2>&1 || missing+=("vsim")
    ;;
  dsim) command -v "${DSIM:-dsim}" >/dev/null 2>&1 || missing+=("${DSIM:-dsim}") ;;
  qrun) command -v qrun >/dev/null 2>&1 || missing+=("qrun") ;;
  *) missing+=("unsupported simulator ${simulator}") ;;
esac
command -v pkg-config >/dev/null 2>&1 || missing+=("pkg-config")
if command -v pkg-config >/dev/null 2>&1 &&
   ! pkg-config --exists riscv-riscv riscv-disasm riscv-fdt riscv-fesvr; then
  missing+=("lowRISC Spike pkg-config packages")
fi
command -v "${RISCV_GCC:-riscv32-unknown-elf-gcc}" >/dev/null 2>&1 || missing+=("${RISCV_GCC:-riscv32-unknown-elf-gcc}")
command -v "${RISCV_OBJCOPY:-riscv32-unknown-elf-objcopy}" >/dev/null 2>&1 || missing+=("${RISCV_OBJCOPY:-riscv32-unknown-elf-objcopy}")

if ((${#missing[@]})); then
  printf 'Missing Chisel UVM prerequisites: %s\n' "${missing[*]}" | tee "${log_dir}/uvm_prereq.log"
  exit 2
fi

echo "[chisel-uvm] generating ${config} Chisel top-tracing RTL"
./mill -i ibex_chisel.runMain ibex.EmitIbex \
  --config "${config}" \
  --top top-tracing \
  --target-dir "${chisel_dir}" \
  > "${log_dir}/emit_chisel.log" 2>&1

echo "[chisel-uvm] creating core_ibex overlay"
scripts/prepare_chisel_uvm_overlay.py \
  --chisel-dir "${chisel_dir}" \
  --overlay-dir "${overlay_dir}" \
  > "${log_dir}/prepare_overlay.log" 2>&1

export IBEX_UVM_CORE_IBEX_DIR="${repo_root}/${overlay_dir}"
export IBEX_UVM_PRJ_DIR="${repo_root}/externals/ibex"
export PRJ_DIR="${repo_root}/externals/ibex"
export LOWRISC_IP_DIR="${repo_root}/externals/ibex/vendor/lowrisc_ip"

echo "[chisel-uvm] running UVM ${goal} with Chisel RTL overlay"
(
  cd "${overlay_dir}"
  make --keep-going \
    OUT="${repo_root}/${uvm_out}" \
    IBEX_CONFIG="${config}" \
    SIMULATOR="${simulator}" \
    ISS="${iss}" \
    ITERATIONS="${iterations}" \
    SEED="${seed}" \
    TEST="${test_name}" \
    RISCV_TOOLCHAIN="${RISCV_TOOLCHAIN:-}" \
    RISCV_GCC="${RISCV_GCC:-}" \
    RISCV_OBJCOPY="${RISCV_OBJCOPY:-}" \
    WAVES=0 \
    COV=0 \
    GOAL="${goal}"
) > "${log_dir}/uvm_${goal}.log" 2>&1

echo "[chisel-uvm] completed: ${log_dir}/uvm_${goal}.log"
