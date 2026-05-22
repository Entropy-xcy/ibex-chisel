// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexPrefetchBuffer(resetAll: Boolean = false) extends RawModule {
  private val numReqs = 2

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val req_i = IO(Input(Bool()))
  val branch_i = IO(Input(Bool()))
  val addr_i = IO(Input(UInt(32.W)))

  val ready_i = IO(Input(Bool()))
  val valid_o = IO(Output(Bool()))
  val rdata_o = IO(Output(UInt(32.W)))
  val addr_o = IO(Output(UInt(32.W)))
  val err_o = IO(Output(Bool()))
  val err_plus2_o = IO(Output(Bool()))

  val instr_req_o = IO(Output(Bool()))
  val instr_gnt_i = IO(Input(Bool()))
  val instr_addr_o = IO(Output(UInt(32.W)))
  val instr_rdata_i = IO(Input(UInt(32.W)))
  val instr_err_i = IO(Input(Bool()))
  val instr_rvalid_i = IO(Input(Bool()))

  val busy_o = IO(Output(Bool()))

  val valid_req_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val discard_req_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rdata_outstanding_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U(numReqs.W))
  }
  val branch_discard_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U(numReqs.W))
  }

  val stored_addr_q = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  } else {
    withClock(clk_i) { Reg(UInt(32.W)) }
  }
  val fetch_addr_q = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  } else {
    withClock(clk_i) { Reg(UInt(32.W)) }
  }

  val fifo_i = Module(new IbexFetchFifo(numReqs = numReqs, resetAll = resetAll))
  fifo_i.clk_i := clk_i
  fifo_i.rst_ni := rst_ni
  fifo_i.clear_i := branch_i
  fifo_i.in_rdata_i := instr_rdata_i
  fifo_i.in_err_i := instr_err_i
  fifo_i.out_ready_i := ready_i
  valid_o := fifo_i.out_valid_o
  rdata_o := fifo_i.out_rdata_o
  addr_o := fifo_i.out_addr_o
  err_o := fifo_i.out_err_o
  err_plus2_o := fifo_i.out_err_plus2_o

  val rdata_outstanding_rev = Cat((0 until numReqs).reverse.map(i => rdata_outstanding_q(numReqs - 1 - i)))
  val fifo_ready = !((fifo_i.busy_o | rdata_outstanding_rev).andR)
  val valid_new_req = req_i && (fifo_ready || branch_i) && !rdata_outstanding_q(numReqs - 1)
  val valid_req = valid_req_q || valid_new_req
  val valid_req_d = valid_req && !instr_gnt_i
  val discard_req_d = valid_req_q && (branch_i || discard_req_q)

  val stored_addr_en = valid_new_req && !valid_req_q && !instr_gnt_i
  val instr_addr = Mux(valid_req_q, stored_addr_q, Mux(branch_i, addr_i, fetch_addr_q))
  val instr_addr_w_aligned = Cat(instr_addr(31, 2), 0.U(2.W))

  val fetch_addr_en = branch_i || (valid_new_req && !valid_req_q)
  val fetch_addr_d = Mux(branch_i, addr_i, Cat(fetch_addr_q(31, 2), 0.U(2.W))) +
    Cat(0.U(29.W), valid_new_req && !valid_req_q, 0.U(2.W))

  withClock(clk_i) {
    when(stored_addr_en) {
      stored_addr_q := instr_addr
    }
    when(fetch_addr_en) {
      fetch_addr_q := fetch_addr_d
    }
  }

  val rdata_outstanding_n = Wire(Vec(numReqs, Bool()))
  val branch_discard_n = Wire(Vec(numReqs, Bool()))
  for (i <- 0 until numReqs) {
    if (i == 0) {
      rdata_outstanding_n(i) := (valid_req && instr_gnt_i) || rdata_outstanding_q(i)
      branch_discard_n(i) := (valid_req && instr_gnt_i && discard_req_d) ||
        (branch_i && rdata_outstanding_q(i)) || branch_discard_q(i)
    } else {
      rdata_outstanding_n(i) := (valid_req && instr_gnt_i && rdata_outstanding_q(i - 1)) ||
        rdata_outstanding_q(i)
      branch_discard_n(i) := (valid_req && instr_gnt_i && discard_req_d && rdata_outstanding_q(i - 1)) ||
        (branch_i && rdata_outstanding_q(i)) || branch_discard_q(i)
    }
  }

  val rdata_outstanding_n_uint = rdata_outstanding_n.asUInt
  val branch_discard_n_uint = branch_discard_n.asUInt
  val rdata_outstanding_s = Mux(instr_rvalid_i, Cat(0.U(1.W), rdata_outstanding_n_uint(numReqs - 1, 1)), rdata_outstanding_n_uint)
  val branch_discard_s = Mux(instr_rvalid_i, Cat(0.U(1.W), branch_discard_n_uint(numReqs - 1, 1)), branch_discard_n_uint)

  fifo_i.in_valid_i := instr_rvalid_i && !branch_discard_q(0)
  fifo_i.in_addr_i := addr_i

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    valid_req_q := valid_req_d
    discard_req_q := discard_req_d
    rdata_outstanding_q := rdata_outstanding_s
    branch_discard_q := branch_discard_s
  }

  instr_req_o := valid_req
  instr_addr_o := instr_addr_w_aligned
  busy_o := rdata_outstanding_q.orR || instr_req_o
}
