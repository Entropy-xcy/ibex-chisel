# Ibex Chisel Regression Status

Last updated: 2026-05-24

## Passing

- Chisel strict software/simple-system regression: 8/8 simple-system tests pass, 192/192 ISA tests terminate without fail markers.
- Original SV strict software/simple-system regression: same supported matrix passes, 192/192 ISA tests terminate without fail markers.
- Chisel vs original SV directed proxy comparison: no Chisel-only failures across the full 942 directed binary set. Current artifacts: `generated/uvm-directed-current-full2/logs/run/opentitan/summary.txt`, `generated/original-sv-uvm-directed-all/logs/run/opentitan/summary.txt`. Comparison result: 750 both-pass, 191 both-fail in the known `epmp_generated` bucket, and original SV alone fails `zicntr.1`.
- Original Ibex UVM `rtl_tb_compile` with VCS passes.
- Chisel UVM overlay `rtl_tb_compile` with VCS passes for `opentitan`.
- Chisel UVM `small` smoke (`riscv_arithmetic_basic_test`, seed 1, 1 iteration) passes RTL simulation and Spike cosim after exposing real RVFI extension signals. Artifact: `generated/chisel-uvm-vcs-small-smoke-rvfi-ext/small/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`.
- Original SV UVM `opentitan` smoke (`riscv_arithmetic_basic_test`, seed 1, 1 iteration) passes RTL simulation and Spike cosim. Artifact: `generated/original-vcs-opentitan-smoke/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`.

## Failing

- Full `opentitan` VCS/Spike `all_riscvdv` default-iteration regression, seed `20794`:
  - Original SV baseline completed under the upstream `core_ibex` Makefile with `TEST=all_riscvdv`, no `ITERATIONS` override: `1430/1530 PASS`, `100 FAIL`. Artifact: `generated/original-sv-vcs-opentitan-riscvdv-full-seed20794/uvm_out/run/regr.log`.
  - Chisel overlay completed with the same test/seed expansion: `1201/1530 PASS`, `329 FAIL`. Artifact: `generated/chisel-uvm-vcs-opentitan-riscvdv-full5/opentitan/uvm_out/run/regr.log`.
  - Set comparison: 91 common failing test-seeds, 9 original-SV-only failing test-seeds, and 238 Chisel-only failing test-seeds. The Chisel-only failures are concentrated in debug/interrupt/memory-error/memory-integrity tests.
  - Both runs also fail the two bitmanip buckets at test compile time for the current `opentitan` filtered configuration.
- `opentitan` Chisel UVM smoke:
  - Command shape: `IBEX_CHISEL_UVM_CONFIG=opentitan IBEX_CHISEL_UVM_GOAL=all IBEX_CHISEL_UVM_TEST=riscv_arithmetic_basic_test IBEX_CHISEL_UVM_ITERATIONS=1 IBEX_CHISEL_UVM_SEED=1`
  - Previous failure fixed: `NoAlertsTriggered` at time 206400 caused by lockstep comparison enable coming up one cycle earlier than original SV.
  - Current failure: RTL test timeout. Temporary debug on the generated wrapper showed `rst=1`, `fetch_enable=IbexMuBiOn`, `core_sleep=0`, no alerts, no scramble request, but top-level `instr_req_o` remains 0 and PC stalls at `0x80000084`.
  - Current artifacts: `generated/chisel-uvm-vcs-opentitan-smoke-lockstep-fix/opentitan/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`, `generated/chisel-uvm-vcs-opentitan-smoke-clocken-fix/opentitan/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim_dbg.log`.

## Not Yet Completed

- Full default `all_riscvdv` was run for original SV and Chisel RTL, but it is not passing yet.
- Full Chisel UVM regression across the upstream directed UVM testlist was not separately collected in this latest run; the completed 942 directed binary comparison above covers the existing directed proxy/simple-system flow.
- UVM functional coverage equivalence: not proven. The Chisel overlay currently skips original SV-only `core_ibex_fcov_bind.sv` because it relies on original `ibex_core` internal hierarchy.
- `opentitan` Chisel frontend/ICache startup root cause: not fixed yet. The next suspected area is the Chisel `IbexIfStage`/`IbexIcache` path where the PC advances to `0x80000084` without asserting external `instr_req_o`.
- Post-result JUnit collection: the environment currently lacks Python package `junit_xml`; this only affects final collection after individual RTL results have been produced.
