// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexAlu(rv32b: Int = 0) extends RawModule {
  val operator_i = IO(Input(UInt(7.W)))
  val operand_a_i = IO(Input(UInt(32.W)))
  val operand_b_i = IO(Input(UInt(32.W)))

  val instr_first_cycle_i = IO(Input(Bool()))

  val multdiv_operand_a_i = IO(Input(UInt(33.W)))
  val multdiv_operand_b_i = IO(Input(UInt(33.W)))

  val multdiv_sel_i = IO(Input(Bool()))

  val imd_val_q_i = IO(Input(Vec(2, UInt(32.W))))
  val imd_val_d_o = IO(Output(Vec(2, UInt(32.W))))
  val imd_val_we_o = IO(Output(UInt(2.W)))

  val adder_result_o = IO(Output(UInt(32.W)))
  val adder_result_ext_o = IO(Output(UInt(34.W)))

  val result_o = IO(Output(UInt(32.W)))
  val comparison_result_o = IO(Output(Bool()))
  val is_equal_result_o = IO(Output(Bool()))

  private val op = IbexPkg.AluOp.encoding.map { case (name, value) => name -> value.U(7.W) }
  private def isOp(name: String): Bool = operator_i === op(name)
  private def rvbAny: Boolean = rv32b != 0
  private def rvbOtOrFull: Boolean = rv32b == 2 || rv32b == 3
  private def rvbFull: Boolean = rv32b == 3

  private def reverse(x: UInt): UInt = Cat((0 until x.getWidth).map(i => x(i)))
  private def mask32(x: UInt): UInt = x(31, 0)
  private def shl32(x: UInt, shamt: UInt): UInt = mask32(x << shamt)
  private def signedRightShift33(x: UInt, shamt: UInt): UInt = (x.asSInt >> shamt).asUInt

  private def rol(x: UInt, shamt: UInt): UInt = {
    val s = shamt(4, 0)
    Mux(s === 0.U, x, mask32(x << s) | (x >> (32.U - s)))
  }

  private def ror(x: UInt, shamt: UInt): UInt = {
    val s = shamt(4, 0)
    Mux(s === 0.U, x, (x >> s) | mask32(x << (32.U - s)))
  }

  private def clmulRaw(a: UInt, b: UInt): UInt = {
    (0 until 32).map { i =>
      Mux(b(i), mask32(a << i), 0.U(32.W))
    }.reduce(_ ^ _)
  }

  private def xperm(width: Int): UInt = {
    val groups = 32 / width
    val out = Wire(Vec(groups, UInt(width.W)))
    for (i <- 0 until groups) {
      val selWidth = log2Ceil(groups)
      val sel = operand_b_i(i * width + selWidth - 1, i * width)
      val valid = if (width == selWidth) true.B else operand_b_i(i * width + width - 1, i * width + selWidth) === 0.U
      val candidates = VecInit((0 until groups).map(j => operand_a_i(j * width + width - 1, j * width)))
      out(i) := Mux(valid, candidates(sel), 0.U)
    }
    out.asUInt
  }

  private def bcompress(a: UInt, mask: UInt): UInt = {
    val bits = (0 until 32).map { j =>
      (0 until 32).map { i =>
        val lowerCount = if (i == 0) 0.U(6.W) else PopCount(mask(i - 1, 0))
        mask(i) && lowerCount === j.U && a(i)
      }.reduce(_ || _)
    }
    Cat(bits.reverse)
  }

  private def bdecompress(a: UInt, mask: UInt): UInt = {
    val bits = (0 until 32).map { i =>
      val lowerCount = if (i == 0) 0.U(6.W) else PopCount(mask(i - 1, 0))
      val selected = (0 until 32).map(j => (lowerCount === j.U) && a(j)).reduce(_ || _)
      mask(i) && selected
    }
    Cat(bits.reverse)
  }

  private def bitcntPartialUnpacked(bitcnt_partial_lsb_q: UInt, bitcnt_partial_msb_q: UInt): Seq[UInt] = {
    val bitcnt_partial_q = Array.fill[UInt](32)(0.U(6.W))

    for (i <- 0 until 32) {
      bitcnt_partial_q(i) = Cat(0.U(5.W), bitcnt_partial_lsb_q(i))
    }
    for (i <- 0 until 16) {
      bitcnt_partial_q(2 * i + 1) = bitcnt_partial_q(2 * i + 1) | Cat(0.U(4.W), bitcnt_partial_msb_q(i), 0.U(1.W))
    }
    for (i <- 0 until 8) {
      bitcnt_partial_q(4 * i + 3) = bitcnt_partial_q(4 * i + 3) | Cat(0.U(3.W), bitcnt_partial_msb_q(16 + i), 0.U(2.W))
    }
    for (i <- 0 until 4) {
      bitcnt_partial_q(8 * i + 7) = bitcnt_partial_q(8 * i + 7) | Cat(0.U(2.W), bitcnt_partial_msb_q(24 + i), 0.U(3.W))
    }
    for (i <- 0 until 2) {
      bitcnt_partial_q(16 * i + 15) = bitcnt_partial_q(16 * i + 15) | Cat(0.U(1.W), bitcnt_partial_msb_q(28 + i), 0.U(4.W))
    }
    bitcnt_partial_q(31) = bitcnt_partial_q(31) | Cat(bitcnt_partial_msb_q(30), 0.U(5.W))

    bitcnt_partial_q.toIndexedSeq
  }

  private def bitcntPartialPacked(bitcnt_bits: UInt): (UInt, UInt) = {
    val bitcnt_partial = (0 until 32).map { i =>
      (0 to i).map(j => bitcnt_bits(j).asUInt).foldLeft(0.U(6.W))(_ +& _)(5, 0)
    }
    val bitcnt_partial_lsb_d = Cat((0 until 32).reverse.map(i => bitcnt_partial(i)(0)))
    val msbBits = Array.fill[UInt](32)(0.U(1.W))

    for (i <- 0 until 16) {
      msbBits(i) = bitcnt_partial(2 * i + 1)(1)
    }
    for (i <- 0 until 8) {
      msbBits(16 + i) = bitcnt_partial(4 * i + 3)(2)
    }
    for (i <- 0 until 4) {
      msbBits(24 + i) = bitcnt_partial(8 * i + 7)(3)
    }
    for (i <- 0 until 2) {
      msbBits(28 + i) = bitcnt_partial(16 * i + 15)(4)
    }
    msbBits(30) = bitcnt_partial(31)(5)
    msbBits(31) = 0.U

    (bitcnt_partial_lsb_d, Cat(msbBits.reverse.toIndexedSeq))
  }

  private def bcompressWithPartial(a: UInt, mask: UInt, bitcnt_partial_q: Seq[UInt]): UInt = {
    val bits = (0 until 32).map { j =>
      (0 until 32).map { i =>
        mask(i) && (bitcnt_partial_q(i) === (j + 1).U) && a(i)
      }.reduce(_ || _)
    }
    Cat(bits.reverse)
  }

  private def bdecompressWithPartial(a: UInt, mask: UInt, bitcnt_partial_q: Seq[UInt]): UInt = {
    val bits = (0 until 32).map { i =>
      val selected = (0 until 32).map { j =>
        (bitcnt_partial_q(i) === (j + 1).U) && a(j)
      }.reduce(_ || _)
      mask(i) && selected
    }
    Cat(bits.reverse)
  }

  private def butterflyMasks(bitcnt_partial_q: Seq[UInt]): Seq[(UInt, UInt, UInt)] =
    (0 until 5).map { stg =>
      val n = 16 >> stg
      var mask_l = 0.U(32.W)
      var mask_r = 0.U(32.W)

      for (seg <- 0 until (1 << stg)) {
        val idx = n * (2 * seg + 1) - 1
        val count = bitcnt_partial_q(idx)(log2Ceil(n), 0)
        val lrotc = (Cat(0.U(n.W), Fill(n, 1.U(1.W))) << count)(2 * n - 1, 0)
        val ctrl = ~lrotc(2 * n - 1, n)

        mask_l = mask_l | mask32(ctrl.asUInt.pad(32) << (n * (2 * seg + 1)))
        mask_r = mask_r | mask32(ctrl.asUInt.pad(32) << (n * (2 * seg)))
      }

      val mask_not = ~(mask_l | mask_r)
      (mask_l, mask_r, mask_not)
    }

  private def bcompressButterfly(a: UInt, mask: UInt, bitcnt_partial_q: Seq[UInt]): UInt = {
    val masks = butterflyMasks(bitcnt_partial_q)
    var result = a
    for (stg <- 0 until 5) {
      val shift = 16 >> stg
      val (mask_l, mask_r, mask_not) = masks(stg)
      result = (result & mask_not) | ((result & mask_l) >> shift) | ((result & mask_r) << shift)(31, 0)
    }
    result & mask
  }

  private def bdecompressButterfly(a: UInt, mask: UInt, bitcnt_partial_q: Seq[UInt]): UInt = {
    val masks = butterflyMasks(bitcnt_partial_q)
    var result = a & mask
    for (stg <- 4 to 0 by -1) {
      val shift = 16 >> stg
      val (mask_l, mask_r, mask_not) = masks(stg)
      result = (result & mask_not) | ((result & mask_l) >> shift) | ((result & mask_r) << shift)(31, 0)
    }
    result
  }

  private val operand_a_rev = reverse(operand_a_i)
  private val operand_b_neg = Cat(operand_b_i, 0.U(1.W)) ^ Fill(33, 1.U)

  private val adder_op_a_shift1 = WireDefault(false.B)
  private val adder_op_a_shift2 = WireDefault(false.B)
  private val adder_op_a_shift3 = WireDefault(false.B)
  private val adder_op_b_negate = WireDefault(false.B)

  when(isOp("ALU_SUB") || isOp("ALU_EQ") || isOp("ALU_NE") || isOp("ALU_GE") || isOp("ALU_GEU") ||
    isOp("ALU_LT") || isOp("ALU_LTU") || isOp("ALU_SLT") || isOp("ALU_SLTU") ||
    isOp("ALU_MIN") || isOp("ALU_MINU") || isOp("ALU_MAX") || isOp("ALU_MAXU")) {
    adder_op_b_negate := true.B
  }
  if (rvbAny) {
    when(isOp("ALU_SH1ADD")) { adder_op_a_shift1 := true.B }
    when(isOp("ALU_SH2ADD")) { adder_op_a_shift2 := true.B }
    when(isOp("ALU_SH3ADD")) { adder_op_a_shift3 := true.B }
  }

  private val adder_in_a = MuxCase(Cat(operand_a_i, 1.U(1.W)), Seq(
    multdiv_sel_i -> multdiv_operand_a_i,
    adder_op_a_shift1 -> Cat(operand_a_i(30, 0), "b01".U(2.W)),
    adder_op_a_shift2 -> Cat(operand_a_i(29, 0), "b001".U(3.W)),
    adder_op_a_shift3 -> Cat(operand_a_i(28, 0), "b0001".U(4.W))
  ))
  private val adder_in_b = MuxCase(Cat(operand_b_i, 0.U(1.W)), Seq(
    multdiv_sel_i -> multdiv_operand_b_i,
    adder_op_b_negate -> operand_b_neg
  ))
  adder_result_ext_o := Cat(0.U(1.W), adder_in_a) + Cat(0.U(1.W), adder_in_b)
  private val adder_result = adder_result_ext_o(32, 1)
  adder_result_o := adder_result

  private val cmp_signed = isOp("ALU_GE") || isOp("ALU_LT") || isOp("ALU_SLT") || isOp("ALU_MIN") || isOp("ALU_MAX")
  private val is_equal = adder_result === 0.U
  is_equal_result_o := is_equal

  private val is_greater_equal = Wire(Bool())
  when((operand_a_i(31) ^ operand_b_i(31)) === 0.U) {
    is_greater_equal := !adder_result(31)
  }.otherwise {
    is_greater_equal := operand_a_i(31) ^ cmp_signed
  }

  private val cmp_result = MuxCase(is_equal, Seq(
    isOp("ALU_EQ") -> is_equal,
    isOp("ALU_NE") -> !is_equal,
    (isOp("ALU_GE") || isOp("ALU_GEU") || isOp("ALU_MAX") || isOp("ALU_MAXU")) -> is_greater_equal,
    (isOp("ALU_LT") || isOp("ALU_LTU") || isOp("ALU_MIN") || isOp("ALU_MINU") || isOp("ALU_SLT") || isOp("ALU_SLTU")) -> !is_greater_equal
  ))
  comparison_result_o := cmp_result

  private val bfp_op = if (rvbAny) isOp("ALU_BFP") else false.B
  private val bfp_len = Cat(!operand_b_i(27, 24).orR, operand_b_i(27, 24))
  private val bfp_off = operand_b_i(20, 16)
  private val bfp_mask = if (rvbAny) ~(("hffffffff".U(32.W)) << bfp_len)(31, 0) else 0.U(32.W)
  private val bfp_mask_rev = reverse(bfp_mask)

  private val shift_funnel = if (rvbAny) isOp("ALU_FSL") || isOp("ALU_FSR") else false.B
  private val shift_amt_compl = 32.U(6.W) - operand_b_i(4, 0)
  private val shift_amt = Wire(UInt(6.W))
  shift_amt := Cat(operand_b_i(5) & shift_funnel, Mux(
    bfp_op,
    bfp_off,
    Mux(instr_first_cycle_i,
      Mux(operand_b_i(5) && shift_funnel, shift_amt_compl(4, 0), operand_b_i(4, 0)),
      Mux(operand_b_i(5) && shift_funnel, operand_b_i(4, 0), shift_amt_compl(4, 0)))
  ))

  private val shift_sbmode = if (rvbAny) isOp("ALU_BSET") || isOp("ALU_BCLR") || isOp("ALU_BINV") else false.B
  private val shift_left = WireDefault(false.B)
  when(isOp("ALU_SLL")) { shift_left := true.B }
  if (rvbOtOrFull) {
    when(isOp("ALU_SLO")) { shift_left := true.B }
  }
  if (rvbAny) {
    when(isOp("ALU_BFP")) { shift_left := true.B }
    when(isOp("ALU_ROL")) { shift_left := instr_first_cycle_i }
    when(isOp("ALU_ROR")) { shift_left := !instr_first_cycle_i }
    when(isOp("ALU_FSL")) { shift_left := Mux(shift_amt(5), !instr_first_cycle_i, instr_first_cycle_i) }
    when(isOp("ALU_FSR")) { shift_left := Mux(shift_amt(5), instr_first_cycle_i, !instr_first_cycle_i) }
    when(shift_sbmode) { shift_left := true.B }
  }

  private val shift_arith = isOp("ALU_SRA")
  private val shift_ones = if (rvbOtOrFull) isOp("ALU_SLO") || isOp("ALU_SRO") else false.B
  private val shift_operand = Wire(UInt(32.W))
  shift_operand := Mux(shift_left, operand_a_rev, operand_a_i)
  if (rvbAny) {
    when(bfp_op) {
      shift_operand := bfp_mask_rev
    }.elsewhen(shift_sbmode) {
      shift_operand := "h80000000".U
    }
  }
  private val shift_result_ext = signedRightShift33(Cat(shift_ones || (shift_arith && shift_operand(31)), shift_operand), shift_amt(4, 0))
  private val shift_result_right = shift_result_ext(31, 0)
  private val shift_result = Mux(shift_left, reverse(shift_result_right), shift_result_right)
  private val bfp_result = if (rvbAny) (~shift_result & operand_a_i) | mask32((operand_b_i & bfp_mask) << bfp_off) else 0.U(32.W)

  private val bwlogic_op_b_negate = WireDefault(false.B)
  if (rvbAny) {
    when(isOp("ALU_XNOR") || isOp("ALU_ORN") || isOp("ALU_ANDN")) { bwlogic_op_b_negate := true.B }
    when(isOp("ALU_CMIX")) { bwlogic_op_b_negate := !instr_first_cycle_i }
  }
  private val bwlogic_operand_b = Mux(bwlogic_op_b_negate, operand_b_neg(32, 1), operand_b_i)
  private val bwlogic_or_result = operand_a_i | bwlogic_operand_b
  private val bwlogic_and_result = operand_a_i & bwlogic_operand_b
  private val bwlogic_xor_result = operand_a_i ^ bwlogic_operand_b
  private val bwlogic_or = isOp("ALU_OR") || isOp("ALU_ORN")
  private val bwlogic_and = isOp("ALU_AND") || isOp("ALU_ANDN")
  private val bwlogic_result = MuxCase(bwlogic_xor_result, Seq(
    bwlogic_or -> bwlogic_or_result,
    bwlogic_and -> bwlogic_and_result
  ))

  private val clz_result = Mux(operand_a_i === 0.U, 32.U(6.W), PriorityEncoder(Reverse(operand_a_i)))
  private val ctz_result = Mux(operand_a_i === 0.U, 32.U(6.W), PriorityEncoder(operand_a_i))
  private val cpop_result = PopCount(operand_a_i)
  private val bitcnt_result = if (rvbAny) MuxCase(cpop_result, Seq(
    isOp("ALU_CLZ") -> clz_result,
    isOp("ALU_CTZ") -> ctz_result
  )) else 0.U(6.W)
  private val minmax_result = Mux(cmp_result, operand_a_i, operand_b_i)
  private val pack_result = MuxCase(Cat(operand_b_i(15, 0), operand_a_i(15, 0)), Seq(
    isOp("ALU_PACKU") -> Cat(operand_b_i(31, 16), operand_a_i(31, 16)),
    isOp("ALU_PACKH") -> Cat(0.U(16.W), operand_b_i(7, 0), operand_a_i(7, 0))
  ))
  private val sext_result = Mux(isOp("ALU_SEXTB"), Cat(Fill(24, operand_a_i(7)), operand_a_i(7, 0)), Cat(Fill(16, operand_a_i(15)), operand_a_i(15, 0)))
  private val singlebit_result = MuxCase(Cat(0.U(31.W), shift_result(0)), Seq(
    isOp("ALU_BSET") -> (operand_a_i | shift_result),
    isOp("ALU_BCLR") -> (operand_a_i & ~shift_result),
    isOp("ALU_BINV") -> (operand_a_i ^ shift_result)
  ))

  private val zbp_shift_amt = if (rvbOtOrFull) shift_amt(4, 0) else Cat(Fill(2, shift_amt(3)), Fill(3, shift_amt(0)))
  private val gorc_op = isOp("ALU_GORC")
  private val rev_result = {
    val result = Wire(UInt(32.W))
    var r = operand_a_i
    val masks = Seq(
      ("h55555555".U(32.W), "haaaaaaaa".U(32.W), 1),
      ("h33333333".U(32.W), "hcccccccc".U(32.W), 2),
      ("h0f0f0f0f".U(32.W), "hf0f0f0f0".U(32.W), 4),
      ("h00ff00ff".U(32.W), "hff00ff00".U(32.W), 8),
      ("h0000ffff".U(32.W), "hffff0000".U(32.W), 16)
    )
    for (((lo, hi, sh), idx) <- masks.zipWithIndex) {
      val keep = if (idx >= 3) gorc_op && rvbOtOrFull.B else gorc_op
      r = Mux(zbp_shift_amt(idx), Mux(keep, r, 0.U) | mask32((r & lo) << sh) | ((r & hi) >> sh), r)
    }
    result := r
    result
  }

  private val shuffle_result = if (rvbOtOrFull) {
    val shuffle_flip = isOp("ALU_UNSHFL")
    val shuffle_mode = Mux(shuffle_flip, Reverse(shift_amt(3, 0)), shift_amt(3, 0))
    val res = Wire(UInt(32.W))
    val maskL = Seq(BigInt("00ff0000", 16), BigInt("0f000f00", 16), BigInt("30303030", 16), BigInt("44444444", 16))
    val maskR = Seq(BigInt("0000ff00", 16), BigInt("00f000f0", 16), BigInt("0c0c0c0c", 16), BigInt("22222222", 16))
    val flipL = Seq(BigInt("22001100", 16), BigInt("00440000", 16), BigInt("44110000", 16), BigInt("11000000", 16))
    val flipR = Seq(BigInt("00880044", 16), BigInt("00002200", 16), BigInt("00008822", 16), BigInt("00000088", 16))
    def flip(x: UInt): UInt = (x & "h88224411".U) |
      mask32((x << 6) & flipL(0).U) | ((x >> 6) & flipR(0).U) |
      mask32((x << 9) & flipL(1).U) | ((x >> 9) & flipR(1).U) |
      mask32((x << 15) & flipL(2).U) | ((x >> 15) & flipR(2).U) |
      mask32((x << 21) & flipL(3).U) | ((x >> 21) & flipR(3).U)
    var r = Mux(shuffle_flip, flip(operand_a_i), operand_a_i)
    for (i <- 0 until 4) {
      val notMask = (~(maskL(i) | maskR(i)) & BigInt("ffffffff", 16)).U(32.W)
      val sh = Seq(8, 4, 2, 1)(i)
      r = Mux(shuffle_mode(3 - i), (r & notMask) | mask32((r << sh) & maskL(i).U) | ((r >> sh) & maskR(i).U), r)
    }
    res := Mux(shuffle_flip, flip(r), r)
    res
  } else 0.U(32.W)

  private val xperm_result = if (rvbOtOrFull) MuxCase(0.U(32.W), Seq(
    isOp("ALU_XPERM_N") -> xperm(4),
    isOp("ALU_XPERM_B") -> xperm(8),
    isOp("ALU_XPERM_H") -> xperm(16)
  )) else 0.U(32.W)

  private val operand_b_rev = reverse(operand_b_i)
  private val crc_op = isOp("ALU_CRC32C_W") || isOp("ALU_CRC32_W") ||
    isOp("ALU_CRC32C_H") || isOp("ALU_CRC32_H") ||
    isOp("ALU_CRC32C_B") || isOp("ALU_CRC32_B")
  private val crc_cpoly = isOp("ALU_CRC32C_W") || isOp("ALU_CRC32C_H") || isOp("ALU_CRC32C_B")
  private val crc_hmode = isOp("ALU_CRC32_H") || isOp("ALU_CRC32C_H")
  private val crc_bmode = isOp("ALU_CRC32_B") || isOp("ALU_CRC32C_B")
  private val crc_poly = Mux(crc_cpoly, "h1edc6f41".U(32.W), "h04c11db7".U(32.W))
  private val crc_mu_rev = Mux(crc_cpoly, "hdea713f1".U(32.W), "hf7011641".U(32.W))
  private val crc_operand = MuxCase(operand_a_i, Seq(
    crc_bmode -> Cat(operand_a_i(7, 0), 0.U(24.W)),
    crc_hmode -> Cat(operand_a_i(15, 0), 0.U(16.W))
  ))
  private val clmul_rmode = isOp("ALU_CLMULR")
  private val clmul_hmode = isOp("ALU_CLMULH")
  private val clmul_op_a = Mux(
    crc_op,
    Mux(instr_first_cycle_i, crc_operand, imd_val_q_i(0)),
    Mux(clmul_rmode || clmul_hmode, operand_a_rev, operand_a_i)
  )
  private val clmul_op_b = Mux(
    crc_op,
    Mux(instr_first_cycle_i, crc_mu_rev, crc_poly),
    Mux(clmul_rmode || clmul_hmode, operand_b_rev, operand_b_i)
  )
  private val clmul_result_raw = clmulRaw(clmul_op_a, clmul_op_b)
  private val clmul_result_rev = reverse(clmul_result_raw)
  private val clmul_result = if (rvbOtOrFull) MuxCase(clmul_result_raw, Seq(
    isOp("ALU_CLMULR") -> clmul_result_rev,
    isOp("ALU_CLMULH") -> Cat(0.U(1.W), clmul_result_rev(31, 1))
  )) else 0.U(32.W)

  private val crc_result = if (rvbOtOrFull) MuxCase(clmul_result_rev, Seq(
    crc_bmode -> (clmul_result_rev ^ (operand_a_i >> 8)),
    crc_hmode -> (clmul_result_rev ^ (operand_a_i >> 16))
  )) else 0.U(32.W)
  private val (bitcnt_partial_lsb_d, bitcnt_partial_msb_d) = bitcntPartialPacked(operand_b_i)
  private val bitcnt_partial_q = bitcntPartialUnpacked(imd_val_q_i(0), imd_val_q_i(1))
  private val compress_result = if (rvbFull) bdecompressButterfly(operand_a_i, operand_b_i, bitcnt_partial_q) else 0.U(32.W)
  private val decompress_result = if (rvbFull) bcompressButterfly(operand_a_i, operand_b_i, bitcnt_partial_q) else 0.U(32.W)
  private val multicycle_result = WireDefault(0.U(32.W))
  imd_val_d_o(0) := operand_a_i
  imd_val_d_o(1) := 0.U
  imd_val_we_o := 0.U
  if (rvbAny) {
    when(isOp("ALU_CMOV")) {
      multicycle_result := Mux(operand_b_i === 0.U, operand_a_i, imd_val_q_i(0))
      imd_val_d_o(0) := operand_a_i
      imd_val_we_o := Mux(instr_first_cycle_i, "b01".U, 0.U)
    }.elsewhen(isOp("ALU_CMIX")) {
      multicycle_result := imd_val_q_i(0) | bwlogic_and_result
      imd_val_d_o(0) := bwlogic_and_result
      imd_val_we_o := Mux(instr_first_cycle_i, "b01".U, 0.U)
    }.elsewhen(isOp("ALU_FSR") || isOp("ALU_FSL") || isOp("ALU_ROL") || isOp("ALU_ROR")) {
      multicycle_result := Mux(shift_amt(4, 0) === 0.U, Mux(shift_amt(5), operand_a_i, imd_val_q_i(0)), imd_val_q_i(0) | shift_result)
      imd_val_d_o(0) := shift_result
      imd_val_we_o := Mux(instr_first_cycle_i, "b01".U, 0.U)
    }.elsewhen(isOp("ALU_BCOMPRESS") || isOp("ALU_BDECOMPRESS")) {
      multicycle_result := Mux(isOp("ALU_BDECOMPRESS"), decompress_result, compress_result)
      imd_val_d_o(0) := bitcnt_partial_lsb_d
      imd_val_d_o(1) := bitcnt_partial_msb_d
      imd_val_we_o := Mux(instr_first_cycle_i && rvbFull.B, "b11".U, 0.U)
    }.elsewhen(isOp("ALU_CRC32_W") || isOp("ALU_CRC32C_W") || isOp("ALU_CRC32_H") || isOp("ALU_CRC32C_H") ||
      isOp("ALU_CRC32_B") || isOp("ALU_CRC32C_B")) {
      multicycle_result := crc_result
      imd_val_d_o(0) := clmul_result_rev
      imd_val_we_o := Mux(instr_first_cycle_i && rvbOtOrFull.B, "b01".U, 0.U)
    }
  } else {
    imd_val_d_o(0) := 0.U
    imd_val_d_o(1) := 0.U
  }

  result_o := MuxCase(0.U(32.W), Seq(
    (isOp("ALU_XOR") || isOp("ALU_XNOR") || isOp("ALU_OR") || isOp("ALU_ORN") || isOp("ALU_AND") || isOp("ALU_ANDN")) -> bwlogic_result,
    (isOp("ALU_ADD") || isOp("ALU_SUB") || isOp("ALU_SH1ADD") || isOp("ALU_SH2ADD") || isOp("ALU_SH3ADD")) -> adder_result,
    (isOp("ALU_SLL") || isOp("ALU_SRL") || isOp("ALU_SRA") || isOp("ALU_SLO") || isOp("ALU_SRO")) -> shift_result,
    (isOp("ALU_SHFL") || isOp("ALU_UNSHFL")) -> shuffle_result,
    (isOp("ALU_XPERM_N") || isOp("ALU_XPERM_B") || isOp("ALU_XPERM_H")) -> xperm_result,
    (isOp("ALU_EQ") || isOp("ALU_NE") || isOp("ALU_GE") || isOp("ALU_GEU") || isOp("ALU_LT") || isOp("ALU_LTU") || isOp("ALU_SLT") || isOp("ALU_SLTU")) -> Cat(0.U(31.W), cmp_result),
    (isOp("ALU_MIN") || isOp("ALU_MAX") || isOp("ALU_MINU") || isOp("ALU_MAXU")) -> minmax_result,
    (isOp("ALU_CLZ") || isOp("ALU_CTZ") || isOp("ALU_CPOP")) -> Cat(0.U(26.W), bitcnt_result),
    (isOp("ALU_PACK") || isOp("ALU_PACKH") || isOp("ALU_PACKU")) -> pack_result,
    (isOp("ALU_SEXTB") || isOp("ALU_SEXTH")) -> sext_result,
    (isOp("ALU_CMIX") || isOp("ALU_CMOV") || isOp("ALU_FSL") || isOp("ALU_FSR") || isOp("ALU_ROL") || isOp("ALU_ROR") ||
      isOp("ALU_CRC32_W") || isOp("ALU_CRC32C_W") || isOp("ALU_CRC32_H") || isOp("ALU_CRC32C_H") || isOp("ALU_CRC32_B") ||
      isOp("ALU_CRC32C_B") || isOp("ALU_BCOMPRESS") || isOp("ALU_BDECOMPRESS")) -> multicycle_result,
    (isOp("ALU_BSET") || isOp("ALU_BCLR") || isOp("ALU_BINV") || isOp("ALU_BEXT")) -> singlebit_result,
    (isOp("ALU_GREV") || isOp("ALU_GORC")) -> rev_result,
    isOp("ALU_BFP") -> bfp_result,
    (isOp("ALU_CLMUL") || isOp("ALU_CLMULR") || isOp("ALU_CLMULH")) -> clmul_result
  ))
}
