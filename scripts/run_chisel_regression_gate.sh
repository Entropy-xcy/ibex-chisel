#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

jobs="${IBEX_REGRESSION_JOBS:-16}"
term_after_cycles="${IBEX_REGRESSION_TERM_AFTER_CYCLES:-5000000}"
out_root="${IBEX_REGRESSION_OUT:-generated/regression}"
build_root="${IBEX_REGRESSION_BUILD_ROOT:-build/chisel-regression}"
local_spike_pkgconfig="${repo_root}/tools/spike-ibex-cosim/lib/pkgconfig"
llvm_riscv_toolchain="${repo_root}/scripts/llvm_riscv_toolchain"

if [[ -d "${local_spike_pkgconfig}" ]]; then
  export PKG_CONFIG_PATH="${local_spike_pkgconfig}:${PKG_CONFIG_PATH:-}"
  export LD_LIBRARY_PATH="${repo_root}/tools/spike-ibex-cosim/lib:${LD_LIBRARY_PATH:-}"
fi

if [[ -x "${llvm_riscv_toolchain}/bin/riscv32-unknown-elf-gcc" ]]; then
  export RISCV_TOOLCHAIN="${RISCV_TOOLCHAIN:-${llvm_riscv_toolchain}}"
  export RISCV_GCC="${RISCV_GCC:-${llvm_riscv_toolchain}/bin/riscv32-unknown-elf-gcc}"
  export RISCV_OBJCOPY="${RISCV_OBJCOPY:-${llvm_riscv_toolchain}/bin/riscv32-unknown-elf-objcopy}"
fi

configs=(${IBEX_REGRESSION_CONFIGS:-small opentitan maxperf maxperf-pmp-bmbalanced})
software_tests=(${IBEX_REGRESSION_SW_TESTS:-hello_test dummy_instr_test dit_test pmp_smoke_test})
fusesoc_targets=(ibex_simple_system ibex_riscv_compliance tb_cs_registers)

mkdir -p "${out_root}/logs/build" "${out_root}/logs/simple_system" "${out_root}/logs/cs_registers"

run_parallel() {
  local description="$1"
  local commands_file="$2"

  echo "[gate] ${description}"
  if ! xargs -r -d '\n' -P "${jobs}" -I{} bash -c '{}' < "${commands_file}"; then
    echo "[gate] ${description} failed" >&2
    return 1
  fi
}

echo "[gate] generating Chisel SV for supported Ibex configurations"
for cfg in "${configs[@]}"; do
  ./mill -i ibex_chisel.runMain ibex.EmitIbex \
    --config "${cfg}" \
    --top top-tracing \
    --target-dir "${out_root}/${cfg}"
done

build_cmds="$(mktemp)"
trap 'rm -f "${build_cmds:-}" "${sw_cmds:-}" "${csr_cmds:-}"' EXIT

for cfg in "${configs[@]}"; do
  for target in "${fusesoc_targets[@]}"; do
    log="${out_root}/logs/build/${cfg}_${target}.log"
    target_build_root="${build_root}/${cfg}/${target}"
    printf '%q ' fusesoc --cores-root externals/ibex --cores-root "${out_root}/${cfg}" \
      run --target=sim --build-root "${target_build_root}" --build \
      "local:ibex_chisel:${target}:0.1" >> "${build_cmds}"
    printf '>%q 2>&1\n' "${log}" >> "${build_cmds}"
  done
done

run_parallel "building FuseSoC/Verilator targets" "${build_cmds}"

failed_builds=0
for log in "${out_root}"/logs/build/*.log; do
  if rg -q "ERROR|Error|FAILED|Failed|%Error" "${log}"; then
    echo "[gate] build log contains an error marker: ${log}" >&2
    failed_builds=1
  fi
done
if [[ "${failed_builds}" != 0 ]]; then
  exit 1
fi

sw_cmds="$(mktemp)"
for cfg in "${configs[@]}"; do
  sim_bin="${build_root}/${cfg}/ibex_simple_system/local_ibex_chisel_ibex_simple_system_0.1/sim-verilator/Vibex_simple_system"
  for test in "${software_tests[@]}"; do
    vmem="externals/ibex/examples/sw/simple_system/${test}/${test}.vmem"
    log="${out_root}/logs/simple_system/${cfg}_${test}.stdout"
    printf '%q --meminit=ram,%q,vmem --term-after-cycles=%q >%q 2>&1 && rg -q %q %q\n' \
      "${sim_bin}" "${vmem}" "${term_after_cycles}" "${log}" \
      "Terminating simulation by software request" "${log}" >> "${sw_cmds}"
  done
done

run_parallel "running simple-system software tests" "${sw_cmds}"

csr_cmds="$(mktemp)"
for cfg in "${configs[@]}"; do
  sim_bin="${build_root}/${cfg}/tb_cs_registers/local_ibex_chisel_tb_cs_registers_0.1/sim-verilator/Vtb_cs_registers"
  log="${out_root}/logs/cs_registers/${cfg}.stdout"
  printf '%q >%q 2>&1 && rg -q %q %q\n' \
    "${sim_bin}" "${log}" "TEST PASSED" "${log}" >> "${csr_cmds}"
done

run_parallel "running CS-registers DV wrapper" "${csr_cmds}"

uvm_missing=()
for tool in xrun pkg-config "${RISCV_GCC:-riscv32-unknown-elf-gcc}" "${RISCV_OBJCOPY:-riscv32-unknown-elf-objcopy}"; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    uvm_missing+=("${tool}")
  fi
done
if command -v pkg-config >/dev/null 2>&1; then
  if ! pkg-config --exists riscv-riscv riscv-disasm riscv-fdt riscv-fesvr; then
    uvm_missing+=("lowRISC Spike pkg-config packages")
  fi
fi

if ((${#uvm_missing[@]})); then
  echo "[gate] UVM core regression not run; missing: ${uvm_missing[*]}"
else
  echo "[gate] UVM prerequisites are present; run externals/ibex/dv/uvm/core_ibex/Makefile for full core regression."
fi

echo "[gate] completed Chisel Verilator regression gate"
