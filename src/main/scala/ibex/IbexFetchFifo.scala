// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexFetchFifo(numReqs: Int = 2, resetAll: Boolean = false) extends RawModule {
  require(numReqs > 0, s"NUM_REQS must be positive, got $numReqs")

  private val depth = numReqs + 1

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val clear_i = IO(Input(Bool()))
  val busy_o = IO(Output(UInt(numReqs.W)))

  val in_valid_i = IO(Input(Bool()))
  val in_addr_i = IO(Input(UInt(32.W)))
  val in_rdata_i = IO(Input(UInt(32.W)))
  val in_err_i = IO(Input(Bool()))

  val out_valid_o = IO(Output(Bool()))
  val out_ready_i = IO(Input(Bool()))
  val out_addr_o = IO(Output(UInt(32.W)))
  val out_rdata_o = IO(Output(UInt(32.W)))
  val out_err_o = IO(Output(Bool()))
  val out_err_plus2_o = IO(Output(Bool()))

  val valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(depth)(false.B)))
  }
  val rdata_q = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(VecInit(Seq.fill(depth)(0.U(32.W))))
    }
  } else {
    withClock(clk_i) { Reg(Vec(depth, UInt(32.W))) }
  }
  val err_q = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(VecInit(Seq.fill(depth)(false.B)))
    }
  } else {
    withClock(clk_i) { Reg(Vec(depth, Bool())) }
  }
  val instr_addr_q = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(31.W)) }
  } else {
    withClock(clk_i) { Reg(UInt(31.W)) }
  }

  val rdata = Mux(valid_q(0), rdata_q(0), in_rdata_i)
  val err = Mux(valid_q(0), err_q(0), in_err_i)
  val valid = valid_q(0) || in_valid_i

  val rdata_unaligned = Mux(valid_q(1), Cat(rdata_q(1)(15, 0), rdata(31, 16)), Cat(in_rdata_i(15, 0), rdata(31, 16)))
  val unaligned_is_compressed = (rdata(17, 16) =/= "b11".U) && !err
  val aligned_is_compressed = (rdata(1, 0) =/= "b11".U) && !err
  val err_unaligned = Mux(
    valid_q(1),
    (err_q(1) && !unaligned_is_compressed) || err_q(0),
    (valid_q(0) && err_q(0)) || (in_err_i && (!valid_q(0) || !unaligned_is_compressed))
  )
  val err_plus2 = Mux(valid_q(1), err_q(1) && !err_q(0), in_err_i && valid_q(0) && !err_q(0))
  val valid_unaligned = Mux(valid_q(1), true.B, valid_q(0) && in_valid_i)

  out_addr_o := Cat(instr_addr_q, 0.U(1.W))
  when(out_addr_o(1)) {
    out_rdata_o := rdata_unaligned
    out_err_o := err_unaligned
    out_err_plus2_o := err_plus2
    out_valid_o := Mux(unaligned_is_compressed, valid, valid_unaligned)
  }.otherwise {
    out_rdata_o := rdata
    out_err_o := err
    out_err_plus2_o := false.B
    out_valid_o := valid
  }

  val instr_addr_en = clear_i || (out_ready_i && out_valid_o)
  val addr_incr_two = Mux(instr_addr_q(0), unaligned_is_compressed, aligned_is_compressed)
  val instr_addr_next = instr_addr_q + Cat(0.U(29.W), !addr_incr_two, addr_incr_two)
  val instr_addr_d = Mux(clear_i, in_addr_i(31, 1), instr_addr_next)

  withClock(clk_i) {
    when(instr_addr_en) {
      instr_addr_q := instr_addr_d
    }
  }

  val valid_d = Wire(Vec(depth, Bool()))
  val rdata_d = Wire(Vec(depth, UInt(32.W)))
  val err_d = Wire(Vec(depth, Bool()))
  val entry_en = Wire(Vec(depth, Bool()))
  val lowest_free_entry = Wire(Vec(depth, Bool()))
  val valid_pushed = Wire(Vec(depth, Bool()))
  val valid_popped = Wire(Vec(depth, Bool()))

  val pop_fifo = out_ready_i && out_valid_o && (!aligned_is_compressed || out_addr_o(1))

  for (i <- 0 until depth - 1) {
    lowest_free_entry(i) := !valid_q(i) && (if (i == 0) true.B else valid_q(i - 1))
    valid_pushed(i) := (in_valid_i && lowest_free_entry(i)) || valid_q(i)
    valid_popped(i) := Mux(pop_fifo, valid_pushed(i + 1), valid_pushed(i))
    valid_d(i) := valid_popped(i) && !clear_i
    entry_en(i) := (valid_pushed(i + 1) && pop_fifo) ||
      (in_valid_i && lowest_free_entry(i) && !pop_fifo)
    rdata_d(i) := Mux(valid_q(i + 1), rdata_q(i + 1), in_rdata_i)
    err_d(i) := Mux(valid_q(i + 1), err_q(i + 1), in_err_i)
  }
  lowest_free_entry(depth - 1) := !valid_q(depth - 1) && valid_q(depth - 2)
  valid_pushed(depth - 1) := valid_q(depth - 1) || (in_valid_i && lowest_free_entry(depth - 1))
  valid_popped(depth - 1) := Mux(pop_fifo, false.B, valid_pushed(depth - 1))
  valid_d(depth - 1) := valid_popped(depth - 1) && !clear_i
  entry_en(depth - 1) := in_valid_i && lowest_free_entry(depth - 1)
  rdata_d(depth - 1) := in_rdata_i
  err_d(depth - 1) := in_err_i

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    valid_q := valid_d
  }
  withClock(clk_i) {
    for (i <- 0 until depth) {
      when(entry_en(i)) {
        rdata_q(i) := rdata_d(i)
        err_q(i) := err_d(i)
      }
    }
  }

  busy_o := Cat((0 until numReqs).reverse.map(i => valid_q(depth - numReqs + i)))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(!(in_valid_i && pop_fifo) || !valid_q(depth - 1) || clear_i,
      "IbexFetchFifoPushPopFull")
    assert(!in_valid_i || !valid_q(depth - 1) || clear_i,
      "IbexFetchFifoPushFull")
  }
}
