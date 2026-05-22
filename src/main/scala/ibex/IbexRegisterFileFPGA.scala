// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexRegisterFileFPGA(
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

  // The source SystemVerilog uses an initial block rather than rst_ni to initialize inferred FPGA
  // RAM. Chisel has no direct semantic equivalent for this register vector, so the reset domain
  // carries the same deterministic initialization for now.
  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val mem = RegInit(VecInit(Seq.fill(numWords)(wordZeroVal.U(dataWidth.W))))
    val raddr_a_int = raddr_a_i(addrWidth - 1, 0)
    val raddr_b_int = raddr_b_i(addrWidth - 1, 0)
    val waddr_a_int = waddr_a_i(addrWidth - 1, 0)

    val we = (waddr_a_i =/= 0.U) && we_a_i
    when(we) {
      mem(waddr_a_int) := wdata_a_i
    }

    rdata_a_o := Mux(raddr_a_i === 0.U, wordZeroVal.U(dataWidth.W), mem(raddr_a_int))
    rdata_b_o := Mux(raddr_b_i === 0.U, wordZeroVal.U(dataWidth.W), mem(raddr_b_int))
  }
}
