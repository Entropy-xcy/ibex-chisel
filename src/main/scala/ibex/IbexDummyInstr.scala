// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexDummyInstr(
    rndCnstLfsrSeed: BigInt = 0xac533bf4L,
    rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16))
    extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val dummy_instr_en_i = IO(Input(Bool()))
  val dummy_instr_mask_i = IO(Input(UInt(3.W)))
  val dummy_instr_seed_en_i = IO(Input(Bool()))
  val dummy_instr_seed_i = IO(Input(UInt(32.W)))

  val fetch_valid_i = IO(Input(Bool()))
  val id_in_ready_i = IO(Input(Bool()))
  val insert_dummy_instr_o = IO(Output(Bool()))
  val dummy_instr_data_o = IO(Output(UInt(32.W)))

  private val timeoutCntW = 5
  private val opW = 5
  private val lfsrOutW = 2 + opW + opW + timeoutCntW
  private val statePerm = (0 until 32).map(i => ((rndCnstLfsrPerm >> (i * 5)) & 0x1f).toInt)

  val lfsr_i = Module(new PrimLfsr(
    lfsrDw = 32,
    entropyDw = 8,
    stateOutDw = lfsrOutW,
    defaultSeed = rndCnstLfsrSeed,
    statePermEn = true,
    statePerm = statePerm
  ))

  lfsr_i.clk_i := clk_i
  lfsr_i.rst_ni := rst_ni
  lfsr_i.seed_en_i := dummy_instr_seed_en_i
  lfsr_i.entropy_i := 0.U

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val dummy_instr_seed_q = RegInit(0.U(32.W))
    val dummy_instr_seed_d = dummy_instr_seed_q ^ dummy_instr_seed_i

    when(dummy_instr_seed_en_i) {
      dummy_instr_seed_q := dummy_instr_seed_d
    }
    lfsr_i.seed_i := dummy_instr_seed_d

    val lfsr_state = lfsr_i.state_o
    val cnt = lfsr_state(4, 0)
    val op_a = lfsr_state(9, 5)
    val op_b = lfsr_state(14, 10)
    val instr_type = lfsr_state(16, 15)

    val mask = Cat(dummy_instr_mask_i, Fill(timeoutCntW - 3, 1.U(1.W)))
    val dummy_cnt_threshold = cnt & mask
    val dummy_cnt_q = RegInit(0.U(timeoutCntW.W))
    val insert_dummy_instr = dummy_instr_en_i && (dummy_cnt_q === dummy_cnt_threshold)
    val dummy_cnt_incr = dummy_cnt_q + 1.U
    val dummy_cnt_d = Mux(insert_dummy_instr, 0.U, dummy_cnt_incr)
    val dummy_cnt_en = dummy_instr_en_i && id_in_ready_i && (fetch_valid_i || insert_dummy_instr)

    when(dummy_cnt_en) {
      dummy_cnt_q := dummy_cnt_d
    }

    val lfsr_en = insert_dummy_instr && id_in_ready_i
    lfsr_i.lfsr_en_i := lfsr_en

    val dummy_set = Wire(UInt(7.W))
    val dummy_opcode = Wire(UInt(3.W))
    dummy_set := 0.U
    dummy_opcode := 0.U
    switch(instr_type) {
      is("b00".U) {
        dummy_set := "b0000000".U
        dummy_opcode := "b000".U
      }
      is("b01".U) {
        dummy_set := "b0000001".U
        dummy_opcode := "b000".U
      }
      is("b10".U) {
        dummy_set := "b0000001".U
        dummy_opcode := "b100".U
      }
      is("b11".U) {
        dummy_set := "b0000000".U
        dummy_opcode := "b111".U
      }
    }

    insert_dummy_instr_o := insert_dummy_instr
    dummy_instr_data_o := Cat(dummy_set, op_b, op_a, dummy_opcode, 0.U(5.W), "h33".U(7.W))
  }
}
