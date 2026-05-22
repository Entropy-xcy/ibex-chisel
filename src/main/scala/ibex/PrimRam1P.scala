// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class PrimRam1P(width: Int, depth: Int, dataBitsPerMask: Int) extends RawModule {
  require(width > 0, s"Width must be positive, got $width")
  require(depth > 0 && isPow2(depth), s"Depth must be a positive power of two, got $depth")
  require(dataBitsPerMask > 0, s"DataBitsPerMask must be positive, got $dataBitsPerMask")

  private val aw = log2Ceil(depth)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val req_i = IO(Input(Bool()))
  val write_i = IO(Input(Bool()))
  val addr_i = IO(Input(UInt(aw.W)))
  val wdata_i = IO(Input(UInt(width.W)))
  val wmask_i = IO(Input(UInt(width.W)))
  val rdata_o = IO(Output(UInt(width.W)))
  val cfg_i = IO(Input(new PrimRam1PPkg.Ram1PCfg))
  val cfg_rsp_o = IO(Output(new PrimRam1PPkg.Ram1PCfgRsp))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val mem = RegInit(VecInit(Seq.fill(depth)(0.U(width.W))))
    val rdata_q = RegInit(0.U(width.W))

    when(req_i && write_i) {
      val oldData = mem(addr_i)
      mem(addr_i) := (wdata_i & wmask_i) | (oldData & ~wmask_i)
    }

    when(req_i && !write_i) {
      rdata_q := mem(addr_i)
    }

    rdata_o := rdata_q
  }

  cfg_rsp_o.done := cfg_i.ram_cfg.cfg_en || cfg_i.rf_cfg.cfg_en
}
