// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class PrimSecdedInv3932Enc extends RawModule {
  val data_i = IO(Input(UInt(32.W)))
  val data_o = IO(Output(UInt(39.W)))

  private def parity(value: UInt, mask: BigInt): Bool = (value & mask.U(39.W)).xorR

  val data = Wire(UInt(39.W))
  data := Cat(0.U(7.W), data_i)
  data_o := Cat(
    parity(data, BigInt("0098505586", 16)),
    parity(data, BigInt("002DCC624C", 16)),
    parity(data, BigInt("00C2C1323B", 16)),
    parity(data, BigInt("0031234ED1", 16)),
    parity(data, BigInt("00413D89AA", 16)),
    parity(data, BigInt("00DEBA8050", 16)),
    parity(data, BigInt("002606BD25", 16)),
    data_i
  ) ^ BigInt("2A00000000", 16).U(39.W)
}

class PrimSecdedInv3932Dec extends RawModule {
  val data_i = IO(Input(UInt(39.W)))
  val data_o = IO(Output(UInt(32.W)))
  val syndrome_o = IO(Output(UInt(7.W)))
  val err_o = IO(Output(UInt(2.W)))

  private def parity(value: UInt, mask: BigInt): Bool = (value & mask.U(39.W)).xorR

  private val corrected = data_i ^ BigInt("2A00000000", 16).U(39.W)
  private val syndrome = Cat(
    parity(corrected, BigInt("4098505586", 16)),
    parity(corrected, BigInt("202DCC624C", 16)),
    parity(corrected, BigInt("10C2C1323B", 16)),
    parity(corrected, BigInt("0831234ED1", 16)),
    parity(corrected, BigInt("04413D89AA", 16)),
    parity(corrected, BigInt("02DEBA8050", 16)),
    parity(corrected, BigInt("012606BD25", 16))
  )
  syndrome_o := syndrome

  private val dataSyndromes = Seq(
    0x19, 0x54, 0x61, 0x34, 0x1a, 0x15, 0x2a, 0x4c,
    0x45, 0x38, 0x49, 0x0d, 0x51, 0x31, 0x68, 0x07,
    0x1c, 0x0b, 0x25, 0x26, 0x46, 0x0e, 0x70, 0x32,
    0x2c, 0x13, 0x23, 0x62, 0x4a, 0x29, 0x16, 0x52)
  data_o := Cat(dataSyndromes.zipWithIndex.reverse.map { case (syn, bit) =>
    (syndrome === syn.U(7.W)) ^ data_i(bit)
  })

  err_o := Cat(!syndrome.xorR && syndrome.orR, syndrome.xorR)
}

class PrimSecdedInv2822Enc extends RawModule {
  val data_i = IO(Input(UInt(22.W)))
  val data_o = IO(Output(UInt(28.W)))

  private def parity(value: UInt, mask: BigInt): Bool = (value & mask.U(28.W)).xorR

  val data = Wire(UInt(28.W))
  data := Cat(0.U(6.W), data_i)
  data_o := Cat(
    parity(data, BigInt("03ED348", 16)),
    parity(data, BigInt("03DAAA4", 16)),
    parity(data, BigInt("03B6592", 16)),
    parity(data, BigInt("0271C71", 16)),
    parity(data, BigInt("010FC0F", 16)),
    parity(data, BigInt("03003FF", 16)),
    data_i
  ) ^ BigInt("A800000", 16).U(28.W)
}

class PrimSecdedInv2822Dec extends RawModule {
  val data_i = IO(Input(UInt(28.W)))
  val data_o = IO(Output(UInt(22.W)))
  val syndrome_o = IO(Output(UInt(6.W)))
  val err_o = IO(Output(UInt(2.W)))

  private def parity(value: UInt, mask: BigInt): Bool = (value & mask.U(28.W)).xorR

  private val corrected = data_i ^ BigInt("A800000", 16).U(28.W)
  private val syndrome = Cat(
    parity(corrected, BigInt("83ED348", 16)),
    parity(corrected, BigInt("43DAAA4", 16)),
    parity(corrected, BigInt("23B6592", 16)),
    parity(corrected, BigInt("1271C71", 16)),
    parity(corrected, BigInt("090FC0F", 16)),
    parity(corrected, BigInt("07003FF", 16))
  )
  syndrome_o := syndrome

  private val dataSyndromes = Seq(
    0x07, 0x0b, 0x13, 0x23, 0x0d, 0x15, 0x25, 0x19,
    0x29, 0x31, 0x0e, 0x16, 0x26, 0x1a, 0x2a, 0x32,
    0x1c, 0x2c, 0x34, 0x38, 0x3b, 0x3d)
  data_o := Cat(dataSyndromes.zipWithIndex.reverse.map { case (syn, bit) =>
    (syndrome === syn.U(6.W)) ^ data_i(bit)
  })

  err_o := Cat(!syndrome.xorR && syndrome.orR, syndrome.xorR)
}
