#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

jobs="${IBEX_UVM_DIRECTED_JOBS:-64}"
configs=(${IBEX_UVM_DIRECTED_CONFIGS:-opentitan})
compile_out="${IBEX_UVM_DIRECTED_COMPILE_OUT:-generated/uvm-compile-directed-all}"
out_root="${IBEX_UVM_DIRECTED_OUT:-generated/uvm-directed-simple}"
build_root="${IBEX_UVM_DIRECTED_BUILD_ROOT:-build/uvm-directed-simple}"
term_after_cycles="${IBEX_UVM_DIRECTED_TERM_AFTER_CYCLES:-20000000}"
timeout_s="${IBEX_UVM_DIRECTED_TIMEOUT_S:-300}"
ram_size_bytes="${IBEX_UVM_DIRECTED_RAM_SIZE_BYTES:-4194304}"
ram_depth_words=$((ram_size_bytes / 4))
test_filter="${IBEX_UVM_DIRECTED_TEST_FILTER:-}"
test_exclude="${IBEX_UVM_DIRECTED_TEST_EXCLUDE:-}"
pmp_load_mode="${IBEX_UVM_DIRECTED_PMP_LOAD_MODE:-original-bin}"
pmp_bin_base_addr="${IBEX_UVM_DIRECTED_PMP_BIN_BASE_ADDR:-0x80000000}"

tests_dir="${compile_out}/run/tests"
if [[ ! -d "${tests_dir}" ]]; then
  echo "error: missing compiled UVM directed tests: ${tests_dir}" >&2
  echo "Run the core_ibex compile_directed_tests goal first." >&2
  exit 1
fi

mkdir -p "${out_root}/logs/build" "${out_root}/logs/run" "${out_root}/vmem"

for cfg in "${configs[@]}"; do
  echo "[uvm-directed] generating ${cfg} Chisel simple-system with UVM status endpoint"
  ./mill -i ibex_chisel.runMain ibex.EmitIbex \
    --config "${cfg}" \
    --top top-tracing \
    --target-dir "${out_root}/${cfg}" \
    --ram-depth "${ram_depth_words}" \
    --ram-base-addr 0x00000000 \
    --ram-addr-mask 0x00000000 \
    --uvm-test-status-ctrl

  echo "[uvm-directed] building ${cfg} Verilator simple-system"
  fusesoc --cores-root externals/ibex --cores-root "${out_root}/${cfg}" \
    run --target=sim \
    --build-root "${build_root}/${cfg}/ibex_simple_system" \
    --build \
    "local:ibex_chisel:ibex_simple_system:0.1" \
    > "${out_root}/logs/build/${cfg}_ibex_simple_system.log" 2>&1
done

run_one() {
  local cfg="$1"
  local test_dir="$2"
  local test_name
  test_name="$(basename "${test_dir}")"

  local sim_bin="${repo_root}/${build_root}/${cfg}/ibex_simple_system/local_ibex_chisel_ibex_simple_system_0.1/sim-verilator/Vibex_simple_system"
  local bin="${test_dir}/test.bin"
  local elf="${test_dir}/test.o"
  local vmem="${repo_root}/${out_root}/vmem/${test_name}.vmem"
  local run_dir="${repo_root}/${out_root}/logs/run/${cfg}/${test_name}"
  local stdout="${run_dir}/stdout"
  local status_log="${run_dir}/ibex_uvm_test_status.log"
  local tohost_status_log="${run_dir}/ibex_tohost_test_status.log"
  local result_file="${run_dir}/result"

  if [[ ! -x "${sim_bin}" ]]; then
    echo "error: simulator is not executable: ${sim_bin}" >&2
    return 1
  fi
  if [[ ! -f "${elf}" ]]; then
    echo "error: missing test ELF: ${elf}" >&2
    return 1
  fi

  if [[ "${test_name}" == test_pmp_* ]]; then
    if [[ ! -f "${vmem}" || "${bin}" -nt "${vmem}" ]]; then
      case "${pmp_load_mode}" in
        original-bin)
          "${repo_root}/scripts/elf_to_chisel_vmem.py" \
            --bin "${bin}" \
            --bin-base-addr "${pmp_bin_base_addr}" \
            --out "${vmem}" \
            --ram-size-bytes "${ram_size_bytes}"
          ;;
        mseccfg-layout)
          "${repo_root}/scripts/elf_to_chisel_vmem.py" \
            --elf "${elf}" \
            --mseccfg-layout \
            --out "${vmem}" \
            --ram-size-bytes "${ram_size_bytes}"
          ;;
        elf-offset)
          "${repo_root}/scripts/elf_to_chisel_vmem.py" \
            --elf "${elf}" \
            --load-addr-offset 0x80000000 \
            --out "${vmem}" \
            --ram-size-bytes "${ram_size_bytes}"
          ;;
        *)
          echo "error: unknown IBEX_UVM_DIRECTED_PMP_LOAD_MODE=${pmp_load_mode}" >&2
          return 1
          ;;
      esac
    fi
  elif [[ ! -f "${vmem}" || "${elf}" -nt "${vmem}" ]]; then
    "${repo_root}/scripts/elf_to_chisel_vmem.py" \
      --elf "${elf}" \
      --out "${vmem}" \
      --ram-size-bytes "${ram_size_bytes}"
  fi
  local boot boot_addr
  boot="$(sed -n 's,^// boot=0x,,p' "${vmem}" | head -1)"
  boot_addr="$(printf '0x%08x' "$(( (16#${boot}) & 0xffffff00 ))")"

  rm -rf "${run_dir}"
  mkdir -p "${run_dir}"
  local sim_status=0
  (
    cd "${run_dir}"
    timeout "${timeout_s}s" "${sim_bin}" \
      "+boot_addr=${boot_addr}" \
      --meminit=ram,"${vmem}",vmem \
      --term-after-cycles="${term_after_cycles}" \
      > "${stdout}" 2>&1
  ) || sim_status=$?

  local pass_status_seen=0
  if [[ -f "${status_log}" ]] && rg -q "0x00000001" "${status_log}"; then
    pass_status_seen=1
  fi
  if [[ -f "${tohost_status_log}" ]] && rg -q "0x00000001" "${tohost_status_log}"; then
    pass_status_seen=1
  fi
  if rg -q "UVM directed test PASS signature observed" "${stdout}" && [[ "${pass_status_seen}" == 1 ]]; then
    echo "PASS" > "${result_file}"
    return 0
  fi

  if rg -q "UVM directed test FAIL|UVM directed test failed" "${stdout}"; then
    echo "FAIL" > "${result_file}"
  elif [[ "${sim_status}" == 124 ]] || rg -q "timeout|Terminating simulation due to timeout" "${stdout}"; then
    echo "TIMEOUT" > "${result_file}"
  elif rg -q "%Error|Fatal|Aborting" "${stdout}"; then
    echo "ERROR" > "${result_file}"
  else
    echo "NO_PASS" > "${result_file}"
  fi

  if [[ "${IBEX_UVM_DIRECTED_KEEP_GOING:-1}" != 1 ]]; then
    echo "error: $(cat "${result_file}") in ${stdout}" >&2
    return 1
  fi
  return 0
}

export -f run_one
export repo_root build_root out_root term_after_cycles timeout_s ram_size_bytes pmp_load_mode pmp_bin_base_addr

cmds="$(mktemp)"
trap 'rm -f "${cmds}"' EXIT

while IFS= read -r -d '' test_dir; do
  test_name="$(basename "${test_dir}")"
  if [[ -n "${test_filter}" ]] && ! [[ "${test_name}" =~ ${test_filter} ]]; then
    continue
  fi
  if [[ -n "${test_exclude}" ]] && [[ "${test_name}" =~ ${test_exclude} ]]; then
    continue
  fi
  for cfg in "${configs[@]}"; do
    printf 'run_one %q %q\n' "${cfg}" "${test_dir}" >> "${cmds}"
  done
done < <(find "${tests_dir}" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

total="$(wc -l < "${cmds}")"
echo "[uvm-directed] running ${total} directed binary simulations with ${jobs} jobs"
xargs -r -d '\n' -P "${jobs}" -I{} bash -euo pipefail -c '{}' < "${cmds}"

for cfg in "${configs[@]}"; do
  mkdir -p "${out_root}/logs/run/${cfg}"
  summary="${out_root}/logs/run/${cfg}/summary.txt"
  {
    echo "total ${total}"
    for result in PASS FAIL TIMEOUT ERROR NO_PASS; do
      count="$(find "${out_root}/logs/run/${cfg}" -mindepth 2 -maxdepth 2 -name result -exec grep -lx "${result}" {} + 2>/dev/null | wc -l || true)"
      echo "${result} ${count}"
    done
  } | tee "${summary}"
  "${repo_root}/scripts/summarize_uvm_directed_results.py" \
    "${out_root}/logs/run/${cfg}" \
    | tee "${out_root}/logs/run/${cfg}/summary_by_group.txt"
done

echo "[uvm-directed] completed"
