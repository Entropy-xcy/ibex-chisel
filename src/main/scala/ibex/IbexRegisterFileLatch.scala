// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexRegisterFileLatch(
    rv32e: Boolean = false,
    dataWidth: Int = 32,
    dummyInstructions: Boolean = false,
    wordZeroVal: BigInt = 0)
    extends RawModule {
  require(dataWidth > 0, s"DataWidth must be positive, got $dataWidth")
  require(wordZeroVal >= 0 && wordZeroVal < (BigInt(1) << dataWidth), s"WordZeroVal must fit in DataWidth=$dataWidth")

  private val ADDR_WIDTH = if (rv32e) 4 else 5
  private val NUM_WORDS = 1 << ADDR_WIDTH

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

  // The source RTL is latch-based and relies on prim_clock_gating cells. This Chisel model keeps
  // the same architectural register-file behavior and port protocol; replacing these registers
  // with latch/cell blackboxes is still needed for physical ASIC structural equivalence.
  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val mem = RegInit(VecInit(Seq.fill(NUM_WORDS)(wordZeroVal.U(dataWidth.W))))
    val wdata_a_q = RegInit(wordZeroVal.U(dataWidth.W))
    val mem_r0 = if (dummyInstructions) Some(RegInit(wordZeroVal.U(dataWidth.W))) else None

    val raddr_a_int = raddr_a_i(ADDR_WIDTH - 1, 0)
    val raddr_b_int = raddr_b_i(ADDR_WIDTH - 1, 0)
    val waddr_a_int = waddr_a_i(ADDR_WIDTH - 1, 0)

    when(we_a_i) {
      wdata_a_q := wdata_a_i
    }

    for (i <- 1 until NUM_WORDS) {
      val waddr_onehot_a = we_a_i && (waddr_a_int === i.U(ADDR_WIDTH.W))
      when(waddr_onehot_a) {
        mem(i) := wdata_a_i
      }
    }

    val r0_data = if (dummyInstructions) {
      val we_r0_dummy = we_a_i && dummy_instr_wb_i
      when(we_r0_dummy) {
        mem_r0.get := wdata_a_i
      }
      Mux(dummy_instr_id_i, mem_r0.get, wordZeroVal.U(dataWidth.W))
    } else {
      wordZeroVal.U(dataWidth.W)
    }

    mem(0) := wordZeroVal.U(dataWidth.W)

    rdata_a_o := Mux(raddr_a_int === 0.U, r0_data, mem(raddr_a_int))
    rdata_b_o := Mux(raddr_b_int === 0.U, r0_data, mem(raddr_b_int))

    dontTouch(wdata_a_q)
  }

  dontTouch(test_en_i)
}
