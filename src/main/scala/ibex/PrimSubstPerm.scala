// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

object PrimSubstPerm {
  private val PresentSbox = Seq(0xc, 0x5, 0x6, 0xb, 0x9, 0x0, 0xa, 0xd, 0x3, 0xe, 0xf, 0x8, 0x4, 0x7, 0x1, 0x2)
  private val PresentSboxInv = Seq(0x5, 0xe, 0xf, 0x8, 0xc, 0x1, 0x2, 0xd, 0xb, 0x4, 0x6, 0x3, 0x0, 0x7, 0x9, 0xa)

  private def bitsToUInt(bits: Seq[Bool]): UInt = VecInit(bits).asUInt

  private def sboxNibble(nibble: UInt, decrypt: Boolean): UInt = {
    val table = if (decrypt) PresentSboxInv else PresentSbox
    MuxLookup(nibble, 0.U(4.W))(table.zipWithIndex.map { case (value, idx) => idx.U(4.W) -> value.U(4.W) })
  }

  private def sboxLayer(data: UInt, dataWidth: Int, decrypt: Boolean): UInt = {
    val substitutedBits = (0 until dataWidth).map { bit =>
      if (bit / 4 < dataWidth / 4) {
        sboxNibble(data((bit / 4) * 4 + 3, (bit / 4) * 4), decrypt)(bit % 4)
      } else {
        data(bit)
      }
    }
    bitsToUInt(substitutedBits)
  }

  private def encRound(state: UInt, key: UInt, dataWidth: Int): UInt = {
    val sboxed = sboxLayer(state ^ key, dataWidth, decrypt = false)
    val flipped = (0 until dataWidth).map(bit => sboxed(dataWidth - 1 - bit))
    val regrouped = Array.tabulate(dataWidth)(bit => flipped(bit))
    for (k <- 0 until dataWidth / 2) {
      regrouped(k) = flipped(k * 2)
      regrouped(k + dataWidth / 2) = flipped(k * 2 + 1)
    }
    bitsToUInt(regrouped.toSeq)
  }

  private def decRound(state: UInt, key: UInt, dataWidth: Int): UInt = {
    val keyed = state ^ key
    val ungrouped = Array.tabulate(dataWidth)(bit => keyed(bit))
    for (k <- 0 until dataWidth / 2) {
      ungrouped(k * 2) = keyed(k)
      ungrouped(k * 2 + 1) = keyed(k + dataWidth / 2)
    }
    val flipped = bitsToUInt((0 until dataWidth).map(bit => ungrouped(dataWidth - 1 - bit)))
    sboxLayer(flipped, dataWidth, decrypt = true)
  }

  def transform(data: UInt, key: UInt, dataWidth: Int, numRounds: Int, decrypt: Boolean = false): UInt = {
    require(dataWidth > 0, s"DataWidth must be positive, got $dataWidth")
    require(numRounds >= 0, s"NumRounds must be non-negative, got $numRounds")
    require(data.getWidth == dataWidth, s"data width ${data.getWidth} must match DataWidth=$dataWidth")
    require(key.getWidth == dataWidth, s"key width ${key.getWidth} must match DataWidth=$dataWidth")
    val state = (0 until numRounds).foldLeft(data) { case (roundState, _) =>
      if (decrypt) decRound(roundState, key, dataWidth) else encRound(roundState, key, dataWidth)
    }
    state ^ key
  }
}

class PrimSubstPerm(
    dataWidth: Int = 64,
    numRounds: Int = 31,
    decrypt: Boolean = false)
    extends RawModule {
  val data_i = IO(Input(UInt(dataWidth.W)))
  val key_i = IO(Input(UInt(dataWidth.W)))
  val data_o = IO(Output(UInt(dataWidth.W)))

  data_o := PrimSubstPerm.transform(data_i, key_i, dataWidth, numRounds, decrypt)
}
