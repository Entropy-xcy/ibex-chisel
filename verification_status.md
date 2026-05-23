# Ibex Chisel Regression Status

Last updated: 2026-05-23

## Passing

- Chisel strict software/simple-system regression: 8/8 simple-system tests pass, 192/192 ISA tests terminate without fail markers.
- Original SV strict software/simple-system regression: same supported matrix passes, 192/192 ISA tests terminate without fail markers.
- Chisel vs original SV directed proxy comparison: no Chisel-only failures. Common failures are the known ePMP generated-test bucket; original SV has one extra `zicntr.1` failure due counter alias handling.
- Original Ibex UVM `rtl_tb_compile` with VCS passes.
- Chisel UVM overlay `rtl_tb_compile` with VCS passes for `opentitan`.
- Chisel UVM `small` smoke (`riscv_arithmetic_basic_test`, seed 1, 1 iteration) passes RTL simulation and Spike cosim after exposing real RVFI extension signals. Artifact: `generated/chisel-uvm-vcs-small-smoke-rvfi-ext/small/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`.
- Original SV UVM `opentitan` smoke (`riscv_arithmetic_basic_test`, seed 1, 1 iteration) passes RTL simulation and Spike cosim. Artifact: `generated/original-vcs-opentitan-smoke/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`.

## Failing

- `opentitan` Chisel UVM smoke:
  - Command shape: `IBEX_CHISEL_UVM_CONFIG=opentitan IBEX_CHISEL_UVM_GOAL=all IBEX_CHISEL_UVM_TEST=riscv_arithmetic_basic_test IBEX_CHISEL_UVM_ITERATIONS=1 IBEX_CHISEL_UVM_SEED=1`
  - Previous failure fixed: `NoAlertsTriggered` at time 206400 caused by lockstep comparison enable coming up one cycle earlier than original SV.
  - Current failure: RTL test timeout. Temporary debug on the generated wrapper showed `rst=1`, `fetch_enable=IbexMuBiOn`, `core_sleep=0`, no alerts, no scramble request, but top-level `instr_req_o` remains 0 and PC stalls at `0x80000084`.
  - Current artifacts: `generated/chisel-uvm-vcs-opentitan-smoke-lockstep-fix/opentitan/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`, `generated/chisel-uvm-vcs-opentitan-smoke-clocken-fix/opentitan/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim_dbg.log`.

## Not Yet Completed

- Full original Ibex UVM hardware regression comparison: not run to completion for Chisel RTL.
- Full Chisel UVM regression matrix across original riscv-dv and directed testlists: not run.
- UVM functional coverage equivalence: not proven. The Chisel overlay currently skips original SV-only `core_ibex_fcov_bind.sv` because it relies on original `ibex_core` internal hierarchy.
- `opentitan` Chisel frontend/ICache startup root cause: not fixed yet. The next suspected area is the Chisel `IbexIfStage`/`IbexIcache` path where the PC advances to `0x80000084` without asserting external `instr_req_o`.
- Post-result JUnit collection: the environment currently lacks Python package `junit_xml`; this only affects final collection after individual RTL results have been produced.
