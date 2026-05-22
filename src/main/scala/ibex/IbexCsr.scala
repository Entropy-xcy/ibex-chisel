// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexCsr(width: Int = 32, shadowCopy: Boolean = false, resetValue: BigInt = 0) extends RawModule {
  require(width > 0, s"Width must be positive, got $width")
  require(resetValue >= 0 && resetValue < (BigInt(1) << width), s"ResetValue must fit in Width=$width")

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val wr_data_i = IO(Input(UInt(width.W)))
  val wr_en_i = IO(Input(Bool()))
  val rd_data_o = IO(Output(UInt(width.W)))

  val rd_error_o = IO(Output(Bool()))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val rdata_q = RegInit(resetValue.U(width.W))

    when(wr_en_i) {
      rdata_q := wr_data_i
    }

    rd_data_o := rdata_q

    if (shadowCopy) {
      val shadow_q = RegInit((~resetValue & ((BigInt(1) << width) - 1)).U(width.W))

      when(wr_en_i) {
        shadow_q := ~wr_data_i
      }

      rd_error_o := rdata_q =/= ~shadow_q
    } else {
      rd_error_o := false.B
    }
  }
}
