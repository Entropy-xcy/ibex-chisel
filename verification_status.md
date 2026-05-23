# Ibex Chisel Regression Status

Last updated: 2026-05-23

## Passing

- Chisel strict software/simple-system regression: 8/8 simple-system tests pass, 192/192 ISA tests terminate without fail markers.
- Original SV strict software/simple-system regression: same supported matrix passes, 192/192 ISA tests terminate without fail markers.
- Chisel vs original SV directed proxy comparison: no Chisel-only failures. Common failures are the known ePMP generated-test bucket; original SV has one extra `zicntr.1` failure due counter alias handling.
- Original Ibex UVM `rtl_tb_compile` with VCS passes.
- Chisel UVM overlay `rtl_tb_compile` with VCS passes for `opentitan`.

## Failing

- `small` Chisel UVM smoke:
  - Command shape: `IBEX_CHISEL_UVM_CONFIG=small IBEX_CHISEL_UVM_GOAL=all IBEX_CHISEL_UVM_TEST=riscv_arithmetic_basic_test IBEX_CHISEL_UVM_ITERATIONS=1 IBEX_CHISEL_UVM_SEED=1`
  - Current failure: Spike cosim mismatch at `csrr x10,mcycle`; DUT reports non-zero `mcycle`, overlay currently feeds `rvfi_ext_mcycle=0` to cosim.
  - Current artifact: `generated/chisel-uvm-vcs-small-smoke/small/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`.
- `opentitan` Chisel UVM smoke:
  - Command shape: `IBEX_CHISEL_UVM_CONFIG=opentitan IBEX_CHISEL_UVM_GOAL=all IBEX_CHISEL_UVM_TEST=riscv_arithmetic_basic_test IBEX_CHISEL_UVM_ITERATIONS=1 IBEX_CHISEL_UVM_SEED=1`
  - Current failure: `NoAlertsTriggered` assertion after RTL simulation starts; `alert_major_internal` is asserted.
  - Current artifact: `generated/chisel-uvm-vcs-smoke3/opentitan/uvm_out/run/tests/riscv_arithmetic_basic_test.1/rtl_sim.log`.

## Not Yet Completed

- Full original Ibex UVM hardware regression comparison: not run to completion for Chisel RTL.
- Full Chisel UVM regression matrix across original riscv-dv and directed testlists: not run.
- UVM functional coverage equivalence: not proven. The Chisel overlay currently skips original SV-only `core_ibex_fcov_bind.sv` because it relies on original `ibex_core` internal hierarchy.
- `opentitan` alert/secure/lockstep root cause: not fixed yet.
- Post-result JUnit collection: the environment currently lacks Python package `junit_xml`; this only affects final collection after individual RTL results have been produced.
