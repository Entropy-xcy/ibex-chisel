#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/.." && pwd)"

sim_bin="${IBEX_CHISEL_SIMPLE_SYSTEM_BIN:-${repo_root}/build/local_ibex_chisel_ibex_simple_system_0.1/sim-verilator/Vibex_simple_system}"
out_dir="${IBEX_CHISEL_SMOKE_OUT:-${repo_root}/generated/simple_system_smoke}"

tests=(
  "hello_test:externals/ibex/examples/sw/simple_system/hello_test/hello_test.vmem:200000"
  "dummy_instr_test:externals/ibex/examples/sw/simple_system/dummy_instr_test/dummy_instr_test.vmem:300000"
  "dit_test:externals/ibex/examples/sw/simple_system/dit_test/dit_test.vmem:1200000"
  "pmp_smoke_test:externals/ibex/examples/sw/simple_system/pmp_smoke_test/pmp_smoke_test.vmem:300000"
)

if [[ ! -x "${sim_bin}" ]]; then
  echo "error: simulator binary is not executable: ${sim_bin}" >&2
  echo "hint: build it with the local:ibex_chisel:ibex_simple_system:0.1 FuseSoC target first" >&2
  exit 1
fi

mkdir -p "${out_dir}"

echo "simulator: ${sim_bin}"
echo "logs:      ${out_dir}"

for entry in "${tests[@]}"; do
  IFS=: read -r name vmem_rel term_cycles <<< "${entry}"
  vmem="${repo_root}/${vmem_rel}"
  log="${out_dir}/${name}.log"

  if [[ ! -f "${vmem}" ]]; then
    echo "error: missing ${name} memory image: ${vmem}" >&2
    exit 1
  fi

  "${sim_bin}" \
    +ibex_tracer_enable=0 \
    --meminit=ram,"${vmem}",vmem \
    --term-after-cycles="${term_cycles}" \
    >"${log}" 2>&1

  if ! grep -q "Terminating simulation by software request" "${log}"; then
    echo "FAIL ${name}: software finish marker not found; see ${log}" >&2
    exit 1
  fi

  if grep -q "Simulation timeout" "${log}"; then
    echo "FAIL ${name}: simulation timeout; see ${log}" >&2
    exit 1
  fi

  cycles="$(sed -n 's/^Executed cycles:[[:space:]]*//p' "${log}" | tail -n 1)"
  instrs="$(sed -n 's/.*Instructions Retired:[[:space:]]*//p' "${log}" | tail -n 1)"

  if [[ -n "${instrs}" ]]; then
    echo "PASS ${name}: cycles=${cycles:-unknown} instructions=${instrs}"
  else
    echo "PASS ${name}: cycles=${cycles:-unknown}"
  fi
done
