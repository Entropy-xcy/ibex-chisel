// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexRegisterFileFF(
    rv32e: Boolean = false,
    dataWidth: Int = 32,
    dummyInstructions: Boolean = false,
    wordZeroVal: BigInt = 0)
    extends RawModule {
  require(dataWidth > 0, s"DataWidth must be positive, got $dataWidth")
  require(wordZeroVal >= 0 && wordZeroVal < (BigInt(1) << dataWidth), s"WordZeroVal must fit in DataWidth=$dataWidth")

  private val addrWidth = if (rv32e) 4 else 5
  private val numWords = 1 << addrWidth

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val test_en_i = IO(Input(Bool()))
  val dummy_instr_id_i = IO(Input(Bool()))
  val dummy_instr_wb_i = IO(Input(Bool()))

  val raddr_a_i = IO(Input(UInt(5.W)))
  val rdata_a_o = IO(Output(UInt(dataWidth.W)))

  val raddr_b_i = IO(Input(UInt(5.W)))
  val rdata_b_o = IO(Output(UInt(dataWidth.W)))

  val waddr_a_i = IO(Input(UInt(5.W)))
  val wdata_a_i = IO(Input(UInt(dataWidth.W)))
  val we_a_i = IO(Input(Bool()))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val rf_reg = Wire(Vec(numWords, UInt(dataWidth.W)))
    val rf_reg_q = RegInit(VecInit(Seq.fill(numWords - 1)(wordZeroVal.U(dataWidth.W))))

    for (i <- 1 until numWords) {
      val we_a_dec = (waddr_a_i === i.U(5.W)) && we_a_i
      when(we_a_dec) {
        rf_reg_q(i - 1) := wdata_a_i
      }
      rf_reg(i) := rf_reg_q(i - 1)
    }

    if (dummyInstructions) {
      val rf_r0_q = RegInit(wordZeroVal.U(dataWidth.W))
      val we_r0_dummy = we_a_i && dummy_instr_wb_i

      when(we_r0_dummy) {
        rf_r0_q := wdata_a_i
      }

      rf_reg(0) := Mux(dummy_instr_id_i, rf_r0_q, wordZeroVal.U(dataWidth.W))
    } else {
      rf_reg(0) := wordZeroVal.U(dataWidth.W)
    }

    val raddr_a = raddr_a_i(addrWidth - 1, 0)
    val raddr_b = raddr_b_i(addrWidth - 1, 0)

    rdata_a_o := rf_reg(raddr_a)
    rdata_b_o := rf_reg(raddr_b)
  }
}
