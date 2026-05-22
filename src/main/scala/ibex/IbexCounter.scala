// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util.Cat

class IbexCounter(counterWidth: Int = 32, provideValUpd: Boolean = false) extends RawModule {
  require(counterWidth > 0 && counterWidth <= 64, s"CounterWidth must be in 1..64, got $counterWidth")

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val counter_inc_i = IO(Input(Bool()))
  val counterh_we_i = IO(Input(Bool()))
  val counter_we_i = IO(Input(Bool()))
  val counter_val_i = IO(Input(UInt(32.W)))
  val counter_val_o = IO(Output(UInt(64.W)))
  val counter_val_upd_o = IO(Output(UInt(64.W)))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val counter_q = RegInit(0.U(counterWidth.W))
    val counter = counter_q.pad(64)
    val counter_upd = counter_q + 1.U

    val counter_load_low = Wire(UInt(64.W))
    counter_load_low := Cat(counter(63, 32), counter_val_i)

    val counter_load_high = Wire(UInt(64.W))
    counter_load_high := Cat(counter_val_i, counter(31, 0))

    val we = counter_we_i || counterh_we_i
    val counter_load = Mux(counterh_we_i, counter_load_high, counter_load_low)
    val counter_d = Mux(we, counter_load(counterWidth - 1, 0), Mux(counter_inc_i, counter_upd, counter_q))

    counter_q := counter_d
    counter_val_o := counter
    counter_val_upd_o := Mux(provideValUpd.B, counter_upd.pad(64), 0.U(64.W))
  }
}
