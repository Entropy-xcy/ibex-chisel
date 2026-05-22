// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class PrimLfsr(
    lfsrType: String = "GAL_XOR",
    lfsrDw: Int = 32,
    entropyDw: Int = 8,
    stateOutDw: Int = 8,
    defaultSeed: BigInt = 1,
    customCoeffs: BigInt = 0,
    statePermEn: Boolean = false,
    statePerm: Seq[Int] = Seq.empty,
    nonLinearOut: Boolean = false)
    extends RawModule {
  private val lutOff = 3
  private val lfsrCoeffs = Seq(
    BigInt("6", 16),
    BigInt("c", 16),
    BigInt("14", 16),
    BigInt("30", 16),
    BigInt("60", 16),
    BigInt("b8", 16),
    BigInt("110", 16),
    BigInt("240", 16),
    BigInt("500", 16),
    BigInt("829", 16),
    BigInt("100d", 16),
    BigInt("2015", 16),
    BigInt("6000", 16),
    BigInt("d008", 16),
    BigInt("12000", 16),
    BigInt("20400", 16),
    BigInt("40023", 16),
    BigInt("90000", 16),
    BigInt("140000", 16),
    BigInt("300000", 16),
    BigInt("420000", 16),
    BigInt("e10000", 16),
    BigInt("1200000", 16),
    BigInt("2000023", 16),
    BigInt("4000013", 16),
    BigInt("9000000", 16),
    BigInt("14000000", 16),
    BigInt("20000029", 16),
    BigInt("48000000", 16),
    BigInt("80200003", 16),
    BigInt("100080000", 16),
    BigInt("204000003", 16),
    BigInt("500000000", 16),
    BigInt("801000000", 16),
    BigInt("100000001f", 16),
    BigInt("2000000031", 16),
    BigInt("4400000000", 16),
    BigInt("a000140000", 16),
    BigInt("12000000000", 16),
    BigInt("300000c0000", 16),
    BigInt("63000000000", 16),
    BigInt("c0000030000", 16),
    BigInt("1b0000000000", 16),
    BigInt("300003000000", 16),
    BigInt("420000000000", 16),
    BigInt("c00000180000", 16),
    BigInt("1008000000000", 16),
    BigInt("3000000c00000", 16),
    BigInt("6000c00000000", 16),
    BigInt("9000000000000", 16),
    BigInt("18003000000000", 16),
    BigInt("30000000030000", 16),
    BigInt("40000040000000", 16),
    BigInt("c0000600000000", 16),
    BigInt("102000000000000", 16),
    BigInt("200004000000000", 16),
    BigInt("600003000000000", 16),
    BigInt("c00000000000000", 16),
    BigInt("1800300000000000", 16),
    BigInt("3000000000000030", 16),
    BigInt("6000000000000000", 16),
    BigInt("d800000000000000", 16),
    BigInt("10000400000000000", 16),
    BigInt("30180000000000000", 16),
    BigInt("60300000000000000", 16),
    BigInt("80400000000000000", 16),
    BigInt("140000028000000000", 16),
    BigInt("300060000000000000", 16),
    BigInt("410000000000000000", 16),
    BigInt("820000000001040000", 16),
    BigInt("1000000800000000000", 16),
    BigInt("3000600000000000000", 16),
    BigInt("6018000000000000000", 16),
    BigInt("c000000018000000000", 16),
    BigInt("18000000600000000000", 16),
    BigInt("30000600000000000000", 16),
    BigInt("40200000000000000000", 16),
    BigInt("c0000000060000000000", 16),
    BigInt("110000000000000000000", 16),
    BigInt("240000000480000000000", 16),
    BigInt("600000000003000000000", 16),
    BigInt("800400000000000000000", 16),
    BigInt("1800000300000000000000", 16),
    BigInt("3003000000000000000000", 16),
    BigInt("4002000000000000000000", 16),
    BigInt("c000000000000000018000", 16),
    BigInt("10000000004000000000000", 16),
    BigInt("30000c00000000000000000", 16),
    BigInt("600000000000000000000c0", 16),
    BigInt("c00c0000000000000000000", 16),
    BigInt("140000000000000000000000", 16),
    BigInt("200001000000000000000000", 16),
    BigInt("400800000000000000000000", 16),
    BigInt("a00000000001400000000000", 16),
    BigInt("1040000000000000000000000", 16),
    BigInt("2004000000000000000000000", 16),
    BigInt("5000000000028000000000000", 16),
    BigInt("8000000004000000000000000", 16),
    BigInt("18600000000000000000000000", 16),
    BigInt("30000000000000000c00000000", 16),
    BigInt("40200000000000000000000000", 16),
    BigInt("c0300000000000000000000000", 16),
    BigInt("100010000000000000000000000", 16),
    BigInt("200040000000000000000000000", 16),
    BigInt("5000000000000000a0000000000", 16),
    BigInt("800000010000000000000000000", 16),
    BigInt("1860000000000000000000000000", 16),
    BigInt("3003000000000000000000000000", 16),
    BigInt("4010000000000000000000000000", 16),
    BigInt("a000000000140000000000000000", 16),
    BigInt("10080000000000000000000000000", 16),
    BigInt("30000000000000000000180000000", 16),
    BigInt("60018000000000000000000000000", 16),
    BigInt("c0000000000000000300000000000", 16),
    BigInt("140005000000000000000000000000", 16),
    BigInt("200000001000000000000000000000", 16),
    BigInt("404000000000000000000000000000", 16),
    BigInt("810000000000000000000000000102", 16),
    BigInt("1000040000000000000000000000000", 16),
    BigInt("3000000000000006000000000000000", 16),
    BigInt("5000000000000000000000000000000", 16),
    BigInt("8000000004000000000000000000000", 16),
    BigInt("18000000000000000000000000030000", 16),
    BigInt("30000000030000000000000000000000", 16),
    BigInt("60000000000000000000000000000000", 16),
    BigInt("a0000014000000000000000000000000", 16),
    BigInt("108000000000000000000000000000000", 16),
    BigInt("240000000000000000000000000000000", 16),
    BigInt("600000000000c00000000000000000000", 16),
    BigInt("800000040000000000000000000000000", 16),
    BigInt("1800000000000300000000000000000000", 16),
    BigInt("2000000000000010000000000000000000", 16),
    BigInt("4008000000000000000000000000000000", 16),
    BigInt("c000000000000000000000000000000600", 16),
    BigInt("10000080000000000000000000000000000", 16),
    BigInt("30600000000000000000000000000000000", 16),
    BigInt("4a400000000000000000000000000000000", 16),
    BigInt("80000004000000000000000000000000000", 16),
    BigInt("180000003000000000000000000000000000", 16),
    BigInt("200001000000000000000000000000000000", 16),
    BigInt("600006000000000000000000000000000000", 16),
    BigInt("c00000000000000006000000000000000000", 16),
    BigInt("1000000000000100000000000000000000000", 16),
    BigInt("3000000000000006000000000000000000000", 16),
    BigInt("6000000003000000000000000000000000000", 16),
    BigInt("8000001000000000000000000000000000000", 16),
    BigInt("1800000000000000000000000000c000000000", 16),
    BigInt("20000000000001000000000000000000000000", 16),
    BigInt("48000000000000000000000000000000000000", 16),
    BigInt("c0000000000000006000000000000000000000", 16),
    BigInt("180000000000000000000000000000000000000", 16),
    BigInt("280000000000000000000000000000005000000", 16),
    BigInt("60000000c000000000000000000000000000000", 16),
    BigInt("c00000000000000000000000000018000000000", 16),
    BigInt("1800000600000000000000000000000000000000", 16),
    BigInt("3000000c00000000000000000000000000000000", 16),
    BigInt("4000000080000000000000000000000000000000", 16),
    BigInt("c000300000000000000000000000000000000000", 16),
    BigInt("10000400000000000000000000000000000000000", 16),
    BigInt("30000000000000000000006000000000000000000", 16),
    BigInt("600000000000000c0000000000000000000000000", 16),
    BigInt("c0060000000000000000000000000000000000000", 16),
    BigInt("180000006000000000000000000000000000000000", 16),
    BigInt("3000000000c0000000000000000000000000000000", 16),
    BigInt("410000000000000000000000000000000000000000", 16),
    BigInt("a00140000000000000000000000000000000000000", 16))

  require(lfsrType == "GAL_XOR" || lfsrType == "FIB_XNOR", s"Unknown prim_lfsr LfsrType=$lfsrType")
  require(lfsrDw >= 3 && lfsrDw <= 168)
  require(entropyDw > 0 && entropyDw <= lfsrDw)
  require(stateOutDw > 0 && stateOutDw <= lfsrDw)
  require(defaultSeed >= 0 && defaultSeed < (BigInt(1) << lfsrDw))
  require(customCoeffs >= 0 && customCoeffs < (BigInt(1) << lfsrDw))
  require(customCoeffs > 0 || lfsrDw < lutOff + lfsrCoeffs.length,
    "PrimLfsr default coefficient LUT supports LfsrDw 3..168")
  require(lfsrType != "GAL_XOR" || defaultSeed > 0, "GAL_XOR DefaultSeed must be nonzero")
  require(lfsrType != "FIB_XNOR" || defaultSeed != ((BigInt(1) << lfsrDw) - 1),
    "FIB_XNOR DefaultSeed must not be all ones")
  require(!nonLinearOut || (isPow2(lfsrDw) && lfsrDw >= 16),
    "NonLinearOut requires a power-of-two width of at least 16")
  require(!statePermEn || statePerm.length >= stateOutDw)
  require(!statePermEn || statePerm.take(stateOutDw).forall(i => i >= 0 && i < lfsrDw),
    "StatePerm entries used by StateOutDw must index the LFSR state")

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val seed_en_i = IO(Input(Bool()))
  val seed_i = IO(Input(UInt(lfsrDw.W)))
  val lfsr_en_i = IO(Input(Bool()))
  val entropy_i = IO(Input(UInt(entropyDw.W)))
  val state_o = IO(Output(UInt(stateOutDw.W)))

  private val coeffsBig = if (customCoeffs > 0) customCoeffs else lfsrCoeffs(lfsrDw - lutOff)
  private val coeffs = coeffsBig.U(lfsrDw.W)
  private val defaultSeedLocal = defaultSeed.U(lfsrDw.W)
  private val princeSbox4 = VecInit(Seq(4, 13, 5, 14, 0, 8, 7, 6, 1, 9, 12, 10, 2, 3, 15, 11).map(_.U(4.W)))

  private def lrotcol(col: Seq[Int], shift: Int): Seq[Int] = {
    val out = Array.fill(col.length)(0)
    for (k <- col.indices) {
      out((k + shift) % col.length) = col(k)
    }
    out.toSeq
  }

  private def revcol(col: Seq[Int]): Seq[Int] = col.reverse

  private val sboxOutIndices = if (nonLinearOut) {
    val numSboxes = lfsrDw / 4
    val matrixIndices = Seq.tabulate(4, numSboxes) { case (row, col) => row * numSboxes + col }
    val matrixRotrevIndices = Seq(
      matrixIndices(0),
      lrotcol(matrixIndices(1), numSboxes / 2),
      revcol(matrixIndices(2)),
      revcol(lrotcol(matrixIndices(3), 1)))
    (0 until lfsrDw).map(k => matrixRotrevIndices(k % 4)(k / 4))
  } else {
    Seq.empty[Int]
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val lfsr_q = RegInit(defaultSeedLocal)
    val entropyExt = entropy_i.pad(lfsrDw)
    val lockup = if (lfsrType == "GAL_XOR") {
      !lfsr_q.orR
    } else {
      lfsr_q.andR
    }
    val next_lfsr_state = if (lfsrType == "GAL_XOR") {
      entropyExt ^ (Fill(lfsrDw, lfsr_q(0)) & coeffs) ^ (lfsr_q >> 1)
    } else {
      val feedback = ~((lfsr_q & coeffs).xorR)
      entropyExt ^ Cat(lfsr_q(lfsrDw - 2, 0), feedback)
    }
    val lfsr_d = Mux(seed_en_i,
      seed_i,
      Mux(lfsr_en_i && lockup,
        defaultSeedLocal,
        Mux(lfsr_en_i, next_lfsr_state, lfsr_q)))

    lfsr_q := lfsr_d

    val sboxOut = if (nonLinearOut) {
      Cat((0 until lfsrDw / 4).reverse.map { k =>
        val sboxIn = Cat(
          lfsr_q(sboxOutIndices(k * 4 + 3)),
          lfsr_q(sboxOutIndices(k * 4 + 2)),
          lfsr_q(sboxOutIndices(k * 4 + 1)),
          lfsr_q(sboxOutIndices(k * 4 + 0)))
        princeSbox4(sboxIn)
      })
    } else {
      lfsr_q
    }

    if (statePermEn) {
      state_o := Cat((0 until stateOutDw).reverse.map(k => sboxOut(statePerm(k))))
    } else {
      state_o := sboxOut(stateOutDw - 1, 0)
    }
  }
}
