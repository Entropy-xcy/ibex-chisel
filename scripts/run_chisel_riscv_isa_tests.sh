#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

jobs="${IBEX_RISCV_TEST_JOBS:-16}"
configs=(${IBEX_RISCV_TEST_CONFIGS:-small opentitan maxperf maxperf-pmp-bmbalanced})
out_root="${IBEX_RISCV_TEST_OUT:-generated/riscv-tests}"
build_root="${IBEX_REGRESSION_BUILD_ROOT:-build/chisel-regression-script-check}"
term_after_cycles="${IBEX_RISCV_TEST_TERM_AFTER_CYCLES:-1000000}"

rv32ui_tests=(
  simple add addi and andi auipc beq bge bgeu blt bltu bne fence_i jal jalr
  lb lbu lh lhu lw lui or ori sb sh sw sll slli slt slti sltiu sltu sra srai
  srl srli sub xor xori
)
rv32um_tests=(div divu mul mulh mulhsu mulhu rem remu)
rv32uc_tests=(rvc)

mkdir -p "${out_root}/build" "${out_root}/logs"

compile_one() {
  local suite="$1"
  local test="$2"
  local src="externals/ibex/vendor/riscv-tests/isa/${suite}/${test}.S"
  local stem="${suite}-p-${test}"
  local raw_elf="${out_root}/build/${stem}.raw.elf"
  local bin="${out_root}/build/${stem}.bin"
  local vmem="${out_root}/build/${stem}.vmem"

  clang --target=riscv32-unknown-elf \
    -fuse-ld=mold \
    -march=rv32imc_zicsr_zifencei \
    -mabi=ilp32 \
    -nostdlib \
    -nostartfiles \
    -fno-asynchronous-unwind-tables \
    -fno-exceptions \
    -Wl,-Ttext=0x100000 \
    -Wl,--defsym=_stack_start=0x1f1000 \
    -I scripts/riscv_tests_env \
    -I externals/ibex/vendor/riscv-tests/isa/macros/scalar \
    "${src}" \
    -o "${raw_elf}"

  llvm-objcopy -O binary --only-section=.text --only-section=.data "${raw_elf}" "${bin}"
  {
    echo "@00000000"
    xxd -e -g4 -c4 "${bin}" | awk '{print $2}'
  } > "${vmem}"
}

run_one() {
  local cfg="$1"
  local suite="$2"
  local test="$3"
  local stem="${suite}-p-${test}"
  local sim_bin="${build_root}/${cfg}/ibex_simple_system/local_ibex_chisel_ibex_simple_system_0.1/sim-verilator/Vibex_simple_system"
  local vmem="${out_root}/build/${stem}.vmem"
  local run_dir="${out_root}/logs/${cfg}/${stem}"
  local stdout="${run_dir}/stdout"

  mkdir -p "${run_dir}"
  (
    cd "${run_dir}"
    timeout 120s "${repo_root}/${sim_bin}" \
      --meminit=ram,"${repo_root}/${vmem}",vmem \
      --term-after-cycles="${term_after_cycles}" \
      > stdout 2>&1
  )
  rg -q "Terminating simulation by software request" "${stdout}"
  if [[ -f "${run_dir}/ibex_simple_system.log" ]] && rg -q "FAIL" "${run_dir}/ibex_simple_system.log"; then
    echo "FAIL marker found in ${run_dir}/ibex_simple_system.log" >&2
    return 1
  fi
}

export -f compile_one run_one
export repo_root out_root build_root term_after_cycles

compile_cmds="$(mktemp)"
run_cmds="$(mktemp)"
trap 'rm -f "${compile_cmds}" "${run_cmds}"' EXIT

for test in "${rv32ui_tests[@]}"; do
  printf 'compile_one %q %q\n' rv32ui "${test}" >> "${compile_cmds}"
done
for test in "${rv32um_tests[@]}"; do
  printf 'compile_one %q %q\n' rv32um "${test}" >> "${compile_cmds}"
done
for test in "${rv32uc_tests[@]}"; do
  printf 'compile_one %q %q\n' rv32uc "${test}" >> "${compile_cmds}"
done

echo "[riscv-tests] compiling ISA tests"
xargs -r -d '\n' -P "${jobs}" -I{} bash -euo pipefail -c '{}' < "${compile_cmds}"

for cfg in "${configs[@]}"; do
  for test in "${rv32ui_tests[@]}"; do
    printf 'run_one %q %q %q\n' "${cfg}" rv32ui "${test}" >> "${run_cmds}"
  done
  for test in "${rv32um_tests[@]}"; do
    printf 'run_one %q %q %q\n' "${cfg}" rv32um "${test}" >> "${run_cmds}"
  done
  for test in "${rv32uc_tests[@]}"; do
    printf 'run_one %q %q %q\n' "${cfg}" rv32uc "${test}" >> "${run_cmds}"
  done
done

echo "[riscv-tests] running ISA tests on Chisel simple_system"
xargs -r -d '\n' -P "${jobs}" -I{} bash -euo pipefail -c '{}' < "${run_cmds}"

echo "[riscv-tests] completed"
