#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

jobs="${IBEX_ORIG_SV_SW_JOBS:-64}"
configs=(${IBEX_ORIG_SV_SW_CONFIGS:-small opentitan maxperf maxperf-pmp-bmbalanced})
out_root="${IBEX_ORIG_SV_SW_OUT:-generated/original-sv-software-regression}"
build_root="${IBEX_ORIG_SV_SW_BUILD_ROOT:-build/original-sv-software-regression}"
term_after_cycles="${IBEX_ORIG_SV_SW_TERM_AFTER_CYCLES:-5000000}"
isa_term_after_cycles="${IBEX_ORIG_SV_ISA_TERM_AFTER_CYCLES:-1000000}"
isa_build_root="${IBEX_ORIG_SV_ISA_BUILD_ROOT:-${out_root}/riscv-tests-build}"

software_tests=(${IBEX_ORIG_SV_SW_TESTS:-})
rv32ui_tests=(
  simple add addi and andi auipc beq bge bgeu blt bltu bne fence_i jal jalr
  lb lbu lh lhu lw lui or ori sb sh sw sll slli slt slti sltiu sltu sra srai
  srl srli sub xor xori
)
rv32um_tests=(div divu mul mulh mulhsu mulhu rem remu)
rv32uc_tests=(rvc)

mkdir -p "${out_root}/logs/build" "${out_root}/logs/simple_system" "${out_root}/logs/riscv_isa" "${isa_build_root}"

run_parallel() {
  local description="$1"
  local commands_file="$2"

  echo "[original-sv-sw] ${description}"
  xargs -r -d '\n' -P "${jobs}" -I{} bash -euo pipefail -c '{}' < "${commands_file}"
}

build_cmds="$(mktemp)"
sw_cmds="$(mktemp)"
isa_compile_cmds="$(mktemp)"
isa_run_cmds="$(mktemp)"
trap 'rm -f "${build_cmds}" "${sw_cmds}" "${isa_compile_cmds}" "${isa_run_cmds}"' EXIT

for cfg in "${configs[@]}"; do
  cfg_opts_text="$(externals/ibex/util/ibex_config.py \
    --config_filename externals/ibex/ibex_configs.yaml \
    "${cfg}" fusesoc_opts)"
  log="${out_root}/logs/build/${cfg}_ibex_simple_system.log"
  target_build_root="${build_root}/${cfg}/ibex_simple_system"
  printf '%q ' fusesoc --cores-root externals/ibex \
    run --target=sim --build-root "${target_build_root}" --build \
    "lowrisc:ibex:ibex_simple_system" >> "${build_cmds}"
  printf '%s >%q 2>&1\n' "${cfg_opts_text}" "${log}" >> "${build_cmds}"
done

run_parallel "building upstream SV simple_system targets" "${build_cmds}"

failed_builds=0
for log in "${out_root}"/logs/build/*.log; do
  if rg -q "ERROR|Error|FAILED|Failed|%Error" "${log}"; then
    echo "[original-sv-sw] build log contains an error marker: ${log}" >&2
    failed_builds=1
  fi
done
if [[ "${failed_builds}" != 0 ]]; then
  exit 1
fi

add_sw_cmd() {
  local cfg="$1"
  local test="$2"
  local expect_pass_log="${3:-0}"
  local sim_bin="${repo_root}/${build_root}/${cfg}/ibex_simple_system/lowrisc_ibex_ibex_simple_system_0/sim-verilator/Vibex_simple_system"
  local vmem="${repo_root}/externals/ibex/examples/sw/simple_system/${test}/${test}.vmem"
  local run_dir="${repo_root}/${out_root}/logs/simple_system/${cfg}/${test}"
  printf 'rm -rf %q && mkdir -p %q && (cd %q && timeout 120s %q --meminit=ram,%q,vmem --term-after-cycles=%q >stdout 2>&1) && rg -q %q %q' \
    "${run_dir}" "${run_dir}" "${run_dir}" "${sim_bin}" "${vmem}" "${term_after_cycles}" \
    "Terminating simulation by software request" "${run_dir}/stdout" >> "${sw_cmds}"
  if [[ "${expect_pass_log}" == 1 ]]; then
    printf ' && rg -q %q %q' "PASS: All test sequences behaved as expected" "${run_dir}/ibex_simple_system.log" >> "${sw_cmds}"
  fi
  printf ' && ! rg -q %q %q\n' "FAILURE" "${run_dir}/ibex_simple_system.log" >> "${sw_cmds}"
}

for cfg in "${configs[@]}"; do
  if ((${#software_tests[@]})); then
    for test in "${software_tests[@]}"; do
      add_sw_cmd "${cfg}" "${test}"
    done
  else
    add_sw_cmd "${cfg}" hello_test
    case "${cfg}" in
      opentitan)
        add_sw_cmd "${cfg}" dummy_instr_test 1
        add_sw_cmd "${cfg}" dit_test 1
        add_sw_cmd "${cfg}" pmp_smoke_test
        ;;
      maxperf-pmp-bmbalanced)
        add_sw_cmd "${cfg}" pmp_smoke_test
        ;;
    esac
  fi
done

run_parallel "running upstream SV simple-system software tests" "${sw_cmds}"

compile_one() {
  local suite="$1"
  local test="$2"
  local src="externals/ibex/vendor/riscv-tests/isa/${suite}/${test}.S"
  local stem="${suite}-p-${test}"
  local raw_elf="${isa_build_root}/${stem}.raw.elf"
  local bin="${isa_build_root}/${stem}.bin"
  local vmem="${isa_build_root}/${stem}.vmem"

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

run_isa_one() {
  local cfg="$1"
  local suite="$2"
  local test="$3"
  local stem="${suite}-p-${test}"
  local sim_bin="${repo_root}/${build_root}/${cfg}/ibex_simple_system/lowrisc_ibex_ibex_simple_system_0/sim-verilator/Vibex_simple_system"
  local vmem="${repo_root}/${isa_build_root}/${stem}.vmem"
  local run_dir="${repo_root}/${out_root}/logs/riscv_isa/${cfg}/${stem}"
  local stdout="${run_dir}/stdout"

  mkdir -p "${run_dir}"
  (
    cd "${run_dir}"
    timeout 120s "${sim_bin}" \
      --meminit=ram,"${vmem}",vmem \
      --term-after-cycles="${isa_term_after_cycles}" \
      > stdout 2>&1
  )
  rg -q "Terminating simulation by software request" "${stdout}"
  if [[ -f "${run_dir}/ibex_simple_system.log" ]] && rg -q "FAIL" "${run_dir}/ibex_simple_system.log"; then
    echo "FAIL marker found in ${run_dir}/ibex_simple_system.log" >&2
    return 1
  fi
}

export -f compile_one run_isa_one
export repo_root out_root build_root isa_build_root isa_term_after_cycles

for test in "${rv32ui_tests[@]}"; do
  printf 'compile_one %q %q\n' rv32ui "${test}" >> "${isa_compile_cmds}"
done
for test in "${rv32um_tests[@]}"; do
  printf 'compile_one %q %q\n' rv32um "${test}" >> "${isa_compile_cmds}"
done
for test in "${rv32uc_tests[@]}"; do
  printf 'compile_one %q %q\n' rv32uc "${test}" >> "${isa_compile_cmds}"
done

run_parallel "compiling upstream riscv-tests ISA images" "${isa_compile_cmds}"

for cfg in "${configs[@]}"; do
  for test in "${rv32ui_tests[@]}"; do
    printf 'run_isa_one %q %q %q\n' "${cfg}" rv32ui "${test}" >> "${isa_run_cmds}"
  done
  for test in "${rv32um_tests[@]}"; do
    printf 'run_isa_one %q %q %q\n' "${cfg}" rv32um "${test}" >> "${isa_run_cmds}"
  done
  for test in "${rv32uc_tests[@]}"; do
    printf 'run_isa_one %q %q %q\n' "${cfg}" rv32uc "${test}" >> "${isa_run_cmds}"
  done
done

run_parallel "running upstream SV riscv-tests ISA images" "${isa_run_cmds}"

echo "[original-sv-sw] completed"
