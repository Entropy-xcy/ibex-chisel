// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util.{Cat, Fill, MuxCase, PopCount}

class IbexBranchPredict extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val fetch_rdata_i = IO(Input(UInt(32.W)))
  val fetch_pc_i = IO(Input(UInt(32.W)))
  val fetch_valid_i = IO(Input(Bool()))

  val predict_branch_taken_o = IO(Output(Bool()))
  val predict_branch_pc_o = IO(Output(UInt(32.W)))

  private val instr = fetch_rdata_i

  private val imm_j_type = Cat(Fill(12, instr(31)), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))
  private val imm_b_type = Cat(Fill(19, instr(31)), instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
  private val imm_cj_type = Cat(
    Fill(20, instr(12)), instr(12), instr(8), instr(10, 9), instr(6), instr(7),
    instr(2), instr(11), instr(5, 3), 0.U(1.W)
  )
  private val imm_cb_type = Cat(
    Fill(23, instr(12)), instr(12), instr(6, 5), instr(2), instr(11, 10), instr(4, 3), 0.U(1.W)
  )

  private val instr_b = instr(6, 0) === IbexPkg.Opcode.BRANCH
  private val instr_j = instr(6, 0) === IbexPkg.Opcode.JAL
  private val instr_cb = instr(1, 0) === "b01".U && (instr(15, 13) === "b110".U || instr(15, 13) === "b111".U)
  private val instr_cj = instr(1, 0) === "b01".U && (instr(15, 13) === "b101".U || instr(15, 13) === "b001".U)

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(!fetch_valid_i || PopCount(Seq(instr_j, instr_b, instr_cj, instr_cb)) <= 1.U,
      "BranchInsTypeOneHot")
  }

  private val branch_imm = MuxCase(imm_b_type, Seq(
    instr_j -> imm_j_type,
    instr_b -> imm_b_type,
    instr_cj -> imm_cj_type,
    instr_cb -> imm_cb_type
  ))

  private val instr_b_taken = (instr_b && imm_b_type(31).asBool) || (instr_cb && imm_cb_type(31).asBool)

  predict_branch_taken_o := fetch_valid_i && (instr_j || instr_cj || instr_b_taken)
  predict_branch_pc_o := fetch_pc_i + branch_imm
}
