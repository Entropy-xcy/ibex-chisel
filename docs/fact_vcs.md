# VCS setup for this repo

Working install found at:

- `/fact_data/softwares/synopsys/vcs/W-2024.09-SP2`

The key detail is that the wrapper defaults to 32-bit `linux` on this host unless told otherwise. That fails on the first shared library check because the 32-bit binary wants 32-bit `libelf`.

## Working environment

```bash
export VCS_HOME=/fact_data/softwares/synopsys/vcs/W-2024.09-SP2
export VCS_TARGET_ARCH=linux64
export SNPS_CONTAINER=1
export LM_LICENSE_FILE=27041@eels3.ece.ust.hk
export SNPSLMD_LICENSE_FILE=27041@eels3.ece.ust.hk
export PATH="$VCS_HOME/bin:$PATH"
export LD_LIBRARY_PATH="$VCS_HOME/linux64/lib:/lib64:/usr/lib64:${LD_LIBRARY_PATH:-}"
```

Sanity check:

```bash
vcs -ID
```

Expected output includes:

- `machine type = linux64`
- `Compiler version = VCS W-2024.09-SP2_Full64`

## Notes

- Do not rely on `command -v vcs` alone; it only proves the wrapper is on `PATH`.
- Without `VCS_TARGET_ARCH=linux64`, this machine reports `machine type = linux` and the underlying `vcs1` binary tries to use the 32-bit runtime.
- If `junit_xml` is needed for post-processing, use `generated/ibex-python-venv/bin/python` or recreate it with `scripts/setup_ibex_python_venv.sh`.
- `scripts/run_chisel_uvm_regression.sh` must provide `generated/rtl` as a symlink to the current Chisel RTL output. The upstream `core_ibex` `wrapper.mk` evaluates `find ../../../rtl` from `generated/<run>/<config>/core_ibex_overlay`, which resolves to `generated/rtl`.
- A real VCS compile also needs the Synopsys license environment from `docs/fact_eda_wiki.md`: `LM_LICENSE_FILE=27041@eels3.ece.ust.hk` and `SNPSLMD_LICENSE_FILE=27041@eels3.ece.ust.hk`. Without it, VCS reaches the W-2024.09-SP2_Full64 banner and then exits with `Cannot find license file`.
- The cluster wiki lists VCS `V-2023.12-SP2-4` as the default Synopsys setup. This repo has also verified `W-2024.09-SP2`; keep `VCS_HOME`, `PATH`, and `LD_LIBRARY_PATH` from the same install tree.

## Running Chisel UVM regressions

Run one upstream riscv-dv category as a smoke test:

```bash
IBEX_CHISEL_UVM_OUT=generated/chisel-uvm-vcs-smoke \
IBEX_CHISEL_UVM_CONFIG=opentitan \
IBEX_CHISEL_UVM_GOAL=all \
IBEX_CHISEL_UVM_TEST=riscv_arithmetic_basic_test \
IBEX_CHISEL_UVM_ITERATIONS=1 \
IBEX_CHISEL_UVM_SEED=1 \
SIMULATOR=vcs \
ISS=spike \
scripts/run_chisel_uvm_regression.sh
```

Run one seed of every upstream riscv-dv test category:

```bash
MAKEFLAGS=-j64 \
IBEX_CHISEL_UVM_OUT=generated/chisel-uvm-vcs-opentitan-riscvdv-all1 \
IBEX_CHISEL_UVM_CONFIG=opentitan \
IBEX_CHISEL_UVM_GOAL=all \
IBEX_CHISEL_UVM_TEST=all_riscvdv \
IBEX_CHISEL_UVM_ITERATIONS=1 \
IBEX_CHISEL_UVM_SEED=1 \
SIMULATOR=vcs \
ISS=spike \
scripts/run_chisel_uvm_regression.sh
```

The upstream `riscv_dv_extension/testlist.yaml` currently has 57 riscv-dv test categories and 1540 default iterations for `opentitan`; `ITERATIONS=1` is only a convergence scan, not the full default overnight regression.

To run the full default riscv-dv regression, do not use
`scripts/run_chisel_uvm_regression.sh` as-is: that wrapper always passes
`ITERATIONS=1`. Generate the Chisel overlay, activate the repo Python venv, and
run the upstream `core_ibex` Makefile without an `ITERATIONS=` override so the
testlist defaults are preserved. The verified Slurm helper pattern is:

```bash
source generated/ibex-python-venv/bin/activate
export VCS_HOME=/fact_data/softwares/synopsys/vcs/W-2024.09-SP2
export VCS_TARGET_ARCH=linux64
export SNPS_CONTAINER=1
export LM_LICENSE_FILE=27041@eels3.ece.ust.hk
export SNPSLMD_LICENSE_FILE=27041@eels3.ece.ust.hk
export PATH="$VCS_HOME/bin:$PATH"
export LD_LIBRARY_PATH="$VCS_HOME/linux64/lib:/lib64:/usr/lib64:${LD_LIBRARY_PATH:-}"

# After EmitIbex and prepare_chisel_uvm_overlay.py:
cd generated/<run>/opentitan/core_ibex_overlay
MAKEFLAGS=-j8 make --keep-going \
  OUT="$PWD/../uvm_out" \
  IBEX_CONFIG=opentitan \
  SIMULATOR=vcs \
  ISS=spike \
  TEST=all_riscvdv \
  WAVES=0 \
  COV=0 \
  GOAL=all
```

The repo Python venv is not optional for this path: the upstream config render
step imports `mako`, which is available in `generated/ibex-python-venv`.

## Directed regression notes

The directed simple-system flow uses precompiled binaries in
`generated/uvm-compile-directed-all/run/tests` and writes per-test vmem files
under `generated/uvm-directed-simple/vmem`. Older vmem files may only contain
`// entry=...` metadata and no `// boot=...` line. If those stale files are
reused, `scripts/run_chisel_uvm_directed_simple_system.sh` fails while parsing
the boot address. The script now treats a missing `// boot=0x...` line as a
reason to regenerate the vmem.

Recent full directed baseline:

- original-SV proxy: `942 total, 750 PASS, 192 FAIL`
- Chisel simple-system after stale-vmem fix: `942 total, 751 PASS, 191 FAIL`
- remaining directed failures are all in `epmp_generated`; Chisel passes the
  `zicntr_counter_alias` case that failed in the original-SV proxy run.
