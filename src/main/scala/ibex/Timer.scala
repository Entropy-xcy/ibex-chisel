// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class Timer(dataWidth: Int = 32, addressWidth: Int = 32) extends RawModule {
  require(dataWidth == 32, s"DataWidth must be 32, got $dataWidth")
  require(addressWidth >= 10, s"AddressWidth must be at least 10, got $addressWidth")

  private val tw = 64
  private val addrOffset = 10
  private val mtimeLow = 0
  private val mtimeHigh = 4
  private val mtimecmpLow = 8
  private val mtimecmpHigh = 12

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val timer_req_i = IO(Input(Bool()))
  val timer_addr_i = IO(Input(UInt(addressWidth.W)))
  val timer_we_i = IO(Input(Bool()))
  val timer_be_i = IO(Input(UInt((dataWidth / 8).W)))
  val timer_wdata_i = IO(Input(UInt(dataWidth.W)))
  val timer_rvalid_o = IO(Output(Bool()))
  val timer_rdata_o = IO(Output(UInt(dataWidth.W)))
  val timer_err_o = IO(Output(Bool()))
  val timer_intr_o = IO(Output(Bool()))

  private def mergeBytes(oldWord: UInt): UInt = {
    Cat((0 until dataWidth / 8).reverse.map { b =>
      Mux(timer_be_i(b), timer_wdata_i(b * 8 + 7, b * 8), oldWord(b * 8 + 7, b * 8))
    })
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val mtime_q = RegInit(0.U(tw.W))
    val mtimecmp_q = RegInit(0.U(tw.W))
    val interrupt_q = RegInit(false.B)
    val error_q = Reg(Bool())
    val rdata_q = Reg(UInt(dataWidth.W))
    val rvalid_q = RegInit(false.B)

    val timer_we = timer_req_i && timer_we_i
    val addr_lo = timer_addr_i(addrOffset - 1, 0)
    val mtime_we = timer_we && addr_lo === mtimeLow.U
    val mtimeh_we = timer_we && addr_lo === mtimeHigh.U
    val mtimecmp_we = timer_we && addr_lo === mtimecmpLow.U
    val mtimecmph_we = timer_we && addr_lo === mtimecmpHigh.U

    val mtime_inc = mtime_q + 1.U
    val mtime_wdata = mergeBytes(mtime_q(31, 0))
    val mtimeh_wdata = mergeBytes(mtime_q(63, 32))
    val mtimecmp_wdata = mergeBytes(mtimecmp_q(31, 0))
    val mtimecmph_wdata = mergeBytes(mtimecmp_q(63, 32))

    val mtime_d = Cat(
      Mux(mtimeh_we, mtimeh_wdata, mtime_inc(63, 32)),
      Mux(mtime_we, mtime_wdata, mtime_inc(31, 0))
    )
    val mtimecmp_d = Cat(
      Mux(mtimecmph_we, mtimecmph_wdata, mtimecmp_q(63, 32)),
      Mux(mtimecmp_we, mtimecmp_wdata, mtimecmp_q(31, 0))
    )

    mtime_q := mtime_d
    when(mtimecmp_we || mtimecmph_we) {
      mtimecmp_q := mtimecmp_d
    }

    val interrupt_d = ((mtime_q >= mtimecmp_q) || interrupt_q) && !(mtimecmp_we || mtimecmph_we)
    interrupt_q := interrupt_d

    val rdata_d = WireDefault(0.U(dataWidth.W))
    val error_d = WireDefault(false.B)
    switch(addr_lo) {
      is(mtimeLow.U) {
        rdata_d := mtime_q(31, 0)
      }
      is(mtimeHigh.U) {
        rdata_d := mtime_q(63, 32)
      }
      is(mtimecmpLow.U) {
        rdata_d := mtimecmp_q(31, 0)
      }
      is(mtimecmpHigh.U) {
        rdata_d := mtimecmp_q(63, 32)
      }
    }
    when(addr_lo =/= mtimeLow.U && addr_lo =/= mtimeHigh.U && addr_lo =/= mtimecmpLow.U && addr_lo =/= mtimecmpHigh.U) {
      error_d := true.B
    }

    when(timer_req_i) {
      rdata_q := rdata_d
      error_q := error_d
    }
    rvalid_q := timer_req_i

    timer_intr_o := interrupt_q
    timer_rdata_o := rdata_q
    timer_rvalid_o := rvalid_q
    timer_err_o := error_q
  }
}
