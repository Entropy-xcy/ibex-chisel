// Minimal Ibex simple-system target environment for the upstream riscv-tests ISA tests.
// The test bodies are the original riscv-tests assembly sources; this target
// environment maps pass/fail to Ibex simple_system's simulator_ctrl block.

#ifndef IBEX_CHISEL_RISCV_TEST_H
#define IBEX_CHISEL_RISCV_TEST_H

#define SIM_CTRL_BASE 0x20000
#define SIM_CTRL_OUT  0x0
#define SIM_CTRL_CTRL 0x8

#define TESTNUM gp

#define RVTEST_RV64U .macro init; .endm
#define RVTEST_RV64M .macro init; .endm
#define RVTEST_RV32U .macro init; .endm
#define RVTEST_RV32M .macro init; .endm

#define INIT_XREG       \
  li x1, 0;             \
  li x2, 0;             \
  li x3, 0;             \
  li x4, 0;             \
  li x5, 0;             \
  li x6, 0;             \
  li x7, 0;             \
  li x8, 0;             \
  li x9, 0;             \
  li x10, 0;            \
  li x11, 0;            \
  li x12, 0;            \
  li x13, 0;            \
  li x14, 0;            \
  li x15, 0;            \
  li x16, 0;            \
  li x17, 0;            \
  li x18, 0;            \
  li x19, 0;            \
  li x20, 0;            \
  li x21, 0;            \
  li x22, 0;            \
  li x23, 0;            \
  li x24, 0;            \
  li x25, 0;            \
  li x26, 0;            \
  li x27, 0;            \
  li x28, 0;            \
  li x29, 0;            \
  li x30, 0;            \
  li x31, 0

#define RVTEST_CODE_BEGIN                         \
  .section .text.init, "ax";                     \
  .org 0x80;                                      \
  .globl _start;                                  \
  .globl reset_vector;                            \
_start:                                           \
reset_vector:                                     \
  INIT_XREG;                                      \
  la sp, _stack_start;                            \
  li TESTNUM, 0;                                  \
  init

#define RVTEST_CODE_END                           \
1:                                                \
  wfi;                                            \
  j 1b

#define RVTEST_PASS                               \
  fence;                                          \
  li t0, SIM_CTRL_BASE + SIM_CTRL_CTRL;           \
  li t1, 1;                                       \
  sw t1, 0(t0);                                   \
1:                                                \
  wfi;                                            \
  j 1b

#define RVTEST_FAIL                               \
  fence;                                          \
  li t0, SIM_CTRL_BASE + SIM_CTRL_OUT;            \
  li t1, 70; sw t1, 0(t0);                        \
  li t1, 65; sw t1, 0(t0);                        \
  li t1, 73; sw t1, 0(t0);                        \
  li t1, 76; sw t1, 0(t0);                        \
  li t1, 10; sw t1, 0(t0);                        \
  li t0, SIM_CTRL_BASE + SIM_CTRL_CTRL;           \
  li t1, 1;                                       \
  sw t1, 0(t0);                                   \
1:                                                \
  wfi;                                            \
  j 1b

#define RVTEST_DATA_BEGIN .align 4
#define RVTEST_DATA_END

#endif
