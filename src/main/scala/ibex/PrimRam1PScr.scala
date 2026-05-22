// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class PrimRam1PScr(
    width: Int,
    depth: Int,
    dataBitsPerMask: Int,
    numPrinceRoundsHalf: Int = 2,
    numDiffRounds: Int = 0,
    diffWidth: Int = 0,
    numAddrScrRounds: Int = 2,
    replicateKeyStream: Boolean = false)
    extends RawModule {
  require(width > 0, s"Width must be positive, got $width")
  require(depth > 0 && isPow2(depth), s"Depth must be a positive power of two, got $depth")
  require(dataBitsPerMask > 0, s"DataBitsPerMask must be positive, got $dataBitsPerMask")
  require(numPrinceRoundsHalf > 0 && numPrinceRoundsHalf < 6, "NumPrinceRoundsHalf must be in 1..5")
  require(numDiffRounds >= 0, "NumDiffRounds must be non-negative")
  require(numAddrScrRounds >= 0, "NumAddrScrRounds must be non-negative")
  private val DiffWidth = if (diffWidth == 0) dataBitsPerMask else diffWidth
  require(DiffWidth >= 4, "DiffWidth must be at least 4")

  private val aw = log2Ceil(depth)
  private val numParScr = if (replicateKeyStream) 1 else (width + 63) / 64
  private val dataNonceWidth = 64 - aw
  private val nonceWidth = 64 * numParScr

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val key_valid_i = IO(Input(Bool()))
  val key_i = IO(Input(UInt(IbexPkg.SCRAMBLE_KEY_W.W)))
  val nonce_i = IO(Input(UInt(nonceWidth.W)))

  val req_i = IO(Input(Bool()))
  val gnt_o = IO(Output(Bool()))
  val write_i = IO(Input(Bool()))
  val addr_i = IO(Input(UInt(aw.W)))
  val wdata_i = IO(Input(UInt(width.W)))
  val wmask_i = IO(Input(UInt(width.W)))
  val intg_error_i = IO(Input(Bool()))

  val rdata_o = IO(Output(UInt(width.W)))
  val rvalid_o = IO(Output(Bool()))
  val rerror_o = IO(Output(UInt(2.W)))
  val raddr_o = IO(Output(UInt(aw.W)))
  val cfg_i = IO(Input(new PrimRam1PPkg.Ram1PCfg))
  val cfg_rsp_o = IO(Output(new PrimRam1PPkg.Ram1PCfgRsp))
  val wr_collision_o = IO(Output(Bool()))
  val write_pending_o = IO(Output(Bool()))
  val alert_o = IO(Output(Bool()))

  private def repeated(value: UInt, outWidth: Int): UInt = {
    val chunks = (outWidth + value.getWidth - 1) / value.getWidth
    Cat(Seq.fill(chunks)(value)).asUInt(outWidth - 1, 0)
  }

  private def diffuse(data: UInt, decrypt: Boolean): UInt = {
    val blocks = (width + DiffWidth - 1) / DiffWidth
    val blockData = (0 until blocks).map { block =>
      val localWidth = math.min(DiffWidth, width - block * DiffWidth)
      val lo = block * DiffWidth
      val blockInput = data(lo + localWidth - 1, lo)
      PrimSubstPerm.transform(blockInput, 0.U(localWidth.W), localWidth, numDiffRounds, decrypt)
    }
    Cat(blockData.reverse)
  }

  private def scrambleWriteData(data: UInt, stream: UInt): UInt =
    diffuse(data ^ stream, decrypt = false)

  private def descrambleReadData(data: UInt, stream: UInt): UInt =
    diffuse(data, decrypt = true) ^ stream

  private val keystreamBlocks = (0 until numParScr).map { block =>
    val nonceLo = block * dataNonceWidth
    val dataScrNonce = nonce_i(nonceLo + dataNonceWidth - 1, nonceLo)
    val princeIv = Cat(dataScrNonce, addr_i)
    PrimPrince.transform(
      data = princeIv,
      key = key_i,
      dataWidth = 64,
      keyWidth = 128,
      numRoundsHalf = numPrinceRoundsHalf,
      useOldKeySched = false,
      dec = false.B)
  }
  private val keystreamRaw = Cat(keystreamBlocks.reverse)
  private val keystream = repeated(keystreamRaw, width)
  private val addr_scramble = if (numAddrScrRounds > 0) {
    PrimSubstPerm.transform(addr_i, nonce_i(nonceWidth - 1, nonceWidth - aw), aw, numAddrScrRounds, decrypt = false)
  } else {
    addr_i
  }
  private val gnt = req_i && key_valid_i
  private val read_en = gnt && !write_i
  private val write_en = gnt && write_i

  gnt_o := gnt
  alert_o := false.B
  rerror_o := 0.U
  cfg_rsp_o.done := cfg_i.ram_cfg.cfg_en || cfg_i.rf_cfg.cfg_en

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val mem = RegInit(VecInit(Seq.fill(depth)(0.U(width.W))))
    val rvalid_q = RegInit(false.B)
    val raddr_q = RegInit(0.U(aw.W))
    val rdata_q = RegInit(0.U(width.W))
    val wr_collision_q = RegInit(false.B)
    val pending_valid_q = RegInit(false.B)
    val pending_addr_q = RegInit(0.U(aw.W))
    val pending_wdata_q = RegInit(0.U(width.W))
    val pending_wmask_q = RegInit(0.U(width.W))
    val pending_scr_data_q = RegInit(0.U(width.W))
    val intg_error_r_q = RegInit(false.B)
    val intg_error_w_q = RegInit(false.B)

    val addr_collision = pending_valid_q && read_en && addr_scramble === pending_addr_q
    val commit_pending = pending_valid_q && !read_en
    val mem_read_data = descrambleReadData(mem(addr_scramble), keystream)
    val forwarded_data = (pending_wdata_q & pending_wmask_q) | (mem_read_data & ~pending_wmask_q)
    val new_pending = write_en && !intg_error_i

    when(commit_pending) {
      val oldData = mem(pending_addr_q)
      mem(pending_addr_q) := (pending_scr_data_q & pending_wmask_q) | (oldData & ~pending_wmask_q)
    }

    when(commit_pending) {
      pending_valid_q := false.B
    }

    when(new_pending) {
      pending_valid_q := true.B
      pending_addr_q := addr_scramble
      pending_wdata_q := wdata_i
      pending_wmask_q := wmask_i
      pending_scr_data_q := scrambleWriteData(wdata_i, keystream)
    }

    intg_error_r_q := intg_error_i
    intg_error_w_q := intg_error_i && write_en

    rvalid_q := read_en && !intg_error_i
    wr_collision_q := addr_collision
    when(read_en) {
      raddr_q := addr_i
      rdata_q := Mux(addr_collision, forwarded_data, mem_read_data)
    }

    rdata_o := rdata_q
    rvalid_o := rvalid_q && !intg_error_r_q
    raddr_o := raddr_q
    wr_collision_o := wr_collision_q
    write_pending_o := pending_valid_q || new_pending
  }
}
