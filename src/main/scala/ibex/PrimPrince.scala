// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

object PrimPrince {
  private val PrinceSbox = Seq(0xb, 0xf, 0x3, 0x2, 0xa, 0xc, 0x9, 0x1, 0x6, 0x7, 0x8, 0x0, 0xe, 0x5, 0xd, 0x4)
  private val PrinceSboxInv = Seq(0xb, 0x7, 0x3, 0x2, 0xf, 0xd, 0x8, 0x9, 0xa, 0x6, 0x4, 0x0, 0x5, 0xe, 0xc, 0x1)
  private val PrinceRoundConst = Seq(
    BigInt("0000000000000000", 16),
    BigInt("13198a2e03707344", 16),
    BigInt("a4093822299f31d0", 16),
    BigInt("082efa98ec4e6c89", 16),
    BigInt("452821e638d01377", 16),
    BigInt("be5466cf34e90c6c", 16),
    BigInt("7ef84f78fd955cb1", 16),
    BigInt("85840851f1ac43aa", 16),
    BigInt("c882d32f25323c54", 16),
    BigInt("64a51195e0e3610d", 16),
    BigInt("d3b5a399ca0c2399", 16),
    BigInt("c0ac29b7c97c50dd", 16))
  private val PrinceAlphaConst = BigInt("c0ac29b7c97c50dd", 16)
  private val ShiftRowsConst0 = BigInt("7bde", 16)
  private val ShiftRowsConst1 = BigInt("bde7", 16)
  private val ShiftRowsConst2 = BigInt("de7b", 16)
  private val ShiftRowsConst3 = BigInt("e7bd", 16)
  private val M0 = Seq(0x0111, 0x2220, 0x4404, 0x8088, 0x1011, 0x0222, 0x4440, 0x8808, 0x1101, 0x2022, 0x0444, 0x8880, 0x1110, 0x2202, 0x4044, 0x0888)
  private val M1 = Seq(0x1110, 0x2202, 0x4044, 0x0888, 0x0111, 0x2220, 0x4404, 0x8088, 0x1011, 0x0222, 0x4440, 0x8808, 0x1101, 0x2022, 0x0444, 0x8880)

  private def mask(width: Int): BigInt = (BigInt(1) << width) - 1
  private def bitsToUInt(bits: Seq[Bool]): UInt = VecInit(bits).asUInt
  private def roundConst(idx: Int, width: Int): UInt = (PrinceRoundConst(idx) & mask(width)).U(width.W)
  private def alpha(width: Int): UInt = (PrinceAlphaConst & mask(width)).U(width.W)

  private def sboxNibble(nibble: UInt, inverse: Boolean): UInt = {
    val table = if (inverse) PrinceSboxInv else PrinceSbox
    MuxLookup(nibble, 0.U(4.W))(table.zipWithIndex.map { case (value, idx) => idx.U(4.W) -> value.U(4.W) })
  }

  private def sbox4(data: UInt, dataWidth: Int, inverse: Boolean): UInt = {
    require(dataWidth == 32 || dataWidth == 64)
    val nibbles = (0 until dataWidth / 4).map { idx =>
      sboxNibble(data(idx * 4 + 3, idx * 4), inverse)
    }
    Cat(nibbles.reverse)
  }

  private def rot64(x: UInt, shift: Int): UInt = {
    if (shift == 0) x else (x >> shift.U) | (x << (64 - shift).U)
  }

  private def shiftRows64(data: UInt, inverse: Boolean): UInt = {
    val rowMask = BigInt("f000f000f000f000", 16)
    Seq.tabulate(4) { i =>
      val row = data & (rowMask >> (4 * i)).U(64.W)
      val shift = if (inverse) i * 16 else 64 - i * 16
      rot64(row, shift)
    }.reduce(_ | _)
  }

  private def shiftRows32(data: UInt): UInt = {
    val shifts = Seq(0xf, 0xa, 0x5, 0x0, 0xb, 0x6, 0x1, 0xc, 0x7, 0x2, 0xd, 0x8, 0x3, 0xe, 0x9, 0x4)
    Cat((0 until 16).reverse.map(k => data(shifts(k) * 2 + 1, shifts(k) * 2)))
  }

  private def shiftRows(data: UInt, dataWidth: Int, inverse: Boolean): UInt =
    if (dataWidth == 64) shiftRows64(data, inverse) else shiftRows32(data)

  private def gf2MatMult16(in: UInt, matrix: Seq[Int]): UInt = {
    require(matrix.length == 16)
    val selected = (0 until 16).map { i =>
      Mux(in(i), matrix(i).U(16.W), 0.U(16.W))
    }
    selected.reduce(_ ^ _)
  }

  private def multPrime32(data: UInt): UInt = {
    Cat(gf2MatMult16(data(31, 16), M1), gf2MatMult16(data(15, 0), M0))
  }

  private def multPrime64(data: UInt): UInt = {
    Cat(
      gf2MatMult16(data(63, 48), M0),
      gf2MatMult16(data(47, 32), M1),
      gf2MatMult16(data(31, 16), M1),
      gf2MatMult16(data(15, 0), M0))
  }

  private def multPrime(data: UInt, dataWidth: Int): UInt =
    if (dataWidth == 64) multPrime64(data) else multPrime32(data)

  private def forwardHalf(
      data: UInt,
      key: UInt,
      dataWidth: Int,
      keyWidth: Int,
      numRoundsHalf: Int,
      useOldKeySched: Boolean,
      dec: Bool): (UInt, UInt, UInt, UInt) = {
    require((dataWidth == 64 && keyWidth == 128) || (dataWidth == 32 && keyWidth == 64))
    require(numRoundsHalf > 0 && numRoundsHalf < 6)
    require(data.getWidth == dataWidth)
    require(key.getWidth == keyWidth)

    val keyHi = key(2 * dataWidth - 1, dataWidth)
    val keyLo = key(dataWidth - 1, 0)
    val k0PrimeFromHi = Cat(keyHi(0), keyHi(dataWidth - 1, 2), keyHi(dataWidth - 1) ^ keyHi(1))
    val k0 = Mux(dec, k0PrimeFromHi, keyHi)
    val k0Prime = Mux(dec, keyHi, k0PrimeFromHi)
    val k1 = Mux(dec, keyLo ^ alpha(dataWidth), keyLo)
    val k0New = if (useOldKeySched) k1 else Mux(dec, keyHi ^ alpha(dataWidth), keyHi)

    val lo0 = data ^ k0 ^ k1 ^ roundConst(0, dataWidth)
    val loStates = (1 to numRoundsHalf).foldLeft(Seq(lo0)) { case (states, round) =>
      val roundState = shiftRows(multPrime(sbox4(states.last, dataWidth, inverse = false), dataWidth), dataWidth, inverse = false)
      val keyed = roundState ^ roundConst(round, dataWidth) ^ (if (round % 2 == 1) k0New else k1)
      states :+ keyed
    }

    val middleD = sbox4(multPrime(sbox4(loStates.last, dataWidth, inverse = false), dataWidth), dataWidth, inverse = true)
    (middleD, k1, k0Prime, k0New)
  }

  private def backwardHalf(
      middle: UInt,
      k1: UInt,
      k0Prime: UInt,
      k0New: UInt,
      dataWidth: Int,
      numRoundsHalf: Int): UInt = {
    require(middle.getWidth == dataWidth)
    require(k1.getWidth == dataWidth)
    require(k0Prime.getWidth == dataWidth)
    require(k0New.getWidth == dataWidth)

    val hiStates = (1 to numRoundsHalf).foldLeft(Seq(middle)) { case (states, round) =>
      val keyXor = if ((numRoundsHalf + round + 1) % 2 == 1) k0New else k1
      val stateXor = states.last ^ keyXor ^ roundConst(10 - numRoundsHalf + round, dataWidth)
      val bwd = sbox4(multPrime(shiftRows(stateXor, dataWidth, inverse = true), dataWidth), dataWidth, inverse = true)
      states :+ bwd
    }

    hiStates.last ^ roundConst(11, dataWidth) ^ k1 ^ k0Prime
  }

  def transform(
      data: UInt,
      key: UInt,
      dataWidth: Int = 64,
      keyWidth: Int = 128,
      numRoundsHalf: Int = 5,
      useOldKeySched: Boolean = false,
      dec: Bool = false.B): UInt = {
    require((dataWidth == 64 && keyWidth == 128) || (dataWidth == 32 && keyWidth == 64))
    require(numRoundsHalf > 0 && numRoundsHalf < 6)
    require(data.getWidth == dataWidth)
    require(key.getWidth == keyWidth)

    val (middleD, k1, k0Prime, k0New) =
      forwardHalf(data, key, dataWidth, keyWidth, numRoundsHalf, useOldKeySched, dec)
    backwardHalf(middleD, k1, k0Prime, k0New, dataWidth, numRoundsHalf)
  }
}

class PrimPrince(
    dataWidth: Int = 64,
    keyWidth: Int = 128,
    numRoundsHalf: Int = 5,
    useOldKeySched: Boolean = false,
    halfwayDataReg: Boolean = false,
    halfwayKeyReg: Boolean = false)
    extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val valid_i = IO(Input(Bool()))
  val data_i = IO(Input(UInt(dataWidth.W)))
  val key_i = IO(Input(UInt(keyWidth.W)))
  val dec_i = IO(Input(Bool()))
  val valid_o = IO(Output(Bool()))
  val data_o = IO(Output(UInt(dataWidth.W)))

  val (middle_d, k1_d, k0_prime_d, k0_new_d) =
    PrimPrince.forwardHalf(data_i, key_i, dataWidth, keyWidth, numRoundsHalf, useOldKeySched, dec_i)

  val middle_q = if (halfwayDataReg) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegEnable(middle_d, 0.U(dataWidth.W), valid_i)
    }
  } else {
    middle_d
  }

  val (k1_q, k0_prime_q, k0_new_q) = if (halfwayKeyReg) {
    val k1Reg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegEnable(k1_d, 0.U(dataWidth.W), valid_i)
    }
    val k0PrimeReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegEnable(k0_prime_d, 0.U(dataWidth.W), valid_i)
    }
    val k0NewReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegEnable(k0_new_d, 0.U(dataWidth.W), valid_i)
    }
    (k1Reg, k0PrimeReg, k0NewReg)
  } else {
    (k1_d, k0_prime_d, k0_new_d)
  }

  data_o := PrimPrince.backwardHalf(middle_q, k1_q, k0_prime_q, k0_new_q, dataWidth, numRoundsHalf)
  valid_o := {
    if (halfwayDataReg) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegNext(valid_i, false.B) }
    } else {
      valid_i
    }
  }
}
