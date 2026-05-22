// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexMultDivFast(rv32m: Int = 2) extends RawModule {
  require(rv32m == 2 || rv32m == 3, "IbexMultDivFast supports RV32MFast(2) and RV32MSingleCycle(3)")

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val mult_en_i = IO(Input(Bool()))
  val div_en_i = IO(Input(Bool()))
  val mult_sel_i = IO(Input(Bool()))
  val div_sel_i = IO(Input(Bool()))
  val operator_i = IO(Input(UInt(2.W)))
  val signed_mode_i = IO(Input(UInt(2.W)))
  val op_a_i = IO(Input(UInt(32.W)))
  val op_b_i = IO(Input(UInt(32.W)))
  val alu_adder_ext_i = IO(Input(UInt(34.W)))
  val alu_adder_i = IO(Input(UInt(32.W)))
  val equal_to_zero_i = IO(Input(Bool()))
  val data_ind_timing_i = IO(Input(Bool()))

  val alu_operand_a_o = IO(Output(UInt(33.W)))
  val alu_operand_b_o = IO(Output(UInt(33.W)))

  val imd_val_q_i = IO(Input(Vec(2, UInt(34.W))))
  val imd_val_d_o = IO(Output(Vec(2, UInt(34.W))))
  val imd_val_we_o = IO(Output(UInt(2.W)))

  val multdiv_ready_id_i = IO(Input(Bool()))

  val multdiv_result_o = IO(Output(UInt(32.W)))
  val valid_o = IO(Output(Bool()))

  private object MdOp {
    val MULL = 0.U(2.W)
    val MULH = 1.U(2.W)
    val DIV = 2.U(2.W)
    val REM = 3.U(2.W)
  }

  private object MdState {
    private val states = Enum(7)
    val IDLE = states(0)
    val ABS_A = states(1)
    val ABS_B = states(2)
    val COMP = states(3)
    val LAST = states(4)
    val CHANGE_SIGN = states(5)
    val FINISH = states(6)
  }

  private def sext(value: UInt, width: Int): UInt = {
    val w = value.getWidth
    if (w >= width) value(width - 1, 0) else Cat(Fill(width - w, value(w - 1)), value)
  }

  private def mul17(signA: Bool, opA: UInt, signB: Bool, opB: UInt): UInt = {
    val product = (Cat(signA, opA).asSInt * Cat(signB, opB).asSInt).asUInt
    product(33, 0)
  }

  private def signedAdd3(a: UInt, b: UInt, c: UInt): UInt = {
    val sum = sext(a, 35).asSInt +& sext(b, 35).asSInt +& sext(c, 35).asSInt
    sum.asUInt(34, 0)
  }

  val div_counter_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  val md_state_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(MdState.IDLE) }
  val op_numerator_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val op_quotient_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val div_by_zero_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }

  val mult_valid = WireDefault(false.B)
  val div_valid = WireDefault(false.B)
  val mult_hold = WireDefault(false.B)
  val div_hold = WireDefault(false.B)
  val mac_res_d = WireDefault(0.U(34.W))
  val op_remainder_d = WireDefault(imd_val_q_i(0))
  val op_denominator_d = WireDefault(imd_val_q_i(1)(31, 0))
  val op_numerator_d = WireDefault(op_numerator_q)
  val op_quotient_d = WireDefault(op_quotient_q)
  val div_counter_d = WireDefault(div_counter_q - 1.U)
  val md_state_d = WireDefault(md_state_q)
  val div_by_zero_d = WireDefault(div_by_zero_q)

  val mult_en_internal = mult_en_i && !mult_hold
  val div_en_internal = div_en_i && !div_hold
  val multdiv_en = mult_en_internal || div_en_internal

  val signed_mult = signed_mode_i =/= 0.U
  multdiv_result_o := Mux(div_sel_i, imd_val_q_i(0)(31, 0), mac_res_d(31, 0))

  if (rv32m == 3) {
    object MultState {
      private val states = Enum(2)
      val MULL = states(0)
      val MULH = states(1)
    }

    val mult_state_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(MultState.MULL) }
    val mult_state_d = WireDefault(MultState.MULL)
    val sign_a = signed_mode_i(0) && op_a_i(31)
    val sign_b = signed_mode_i(1) && op_b_i(31)

    val mult1_res = mul17(false.B, op_a_i(15, 0), false.B, op_b_i(15, 0))
    val mult2_res = mul17(false.B, op_a_i(15, 0), sign_b, op_b_i(31, 16))
    val mult3_sign_a = WireDefault(sign_a)
    val mult3_sign_b = WireDefault(false.B)
    val mult3_op_a = WireDefault(op_a_i(31, 16))
    val mult3_op_b = WireDefault(op_b_i(15, 0))
    val mult3_res = mul17(mult3_sign_a, mult3_op_a, mult3_sign_b, mult3_op_b)
    val accum = Cat(Fill(16, signed_mult && imd_val_q_i(0)(33)), imd_val_q_i(0)(33, 16))
    val mac_res_ext = signedAdd3(Cat(0.U(18.W), mult1_res(31, 16)), mult2_res, mult3_res)
    val mac_res = mac_res_ext(33, 0)

    mac_res_d := Cat(0.U(2.W), mac_res(15, 0), mult1_res(15, 0))
    mult_valid := mult_en_i

    switch(mult_state_q) {
      is(MultState.MULL) {
        when(operator_i =/= MdOp.MULL) {
          mac_res_d := mac_res
          mult_valid := false.B
          mult_state_d := MultState.MULH
        }.otherwise {
          mult_hold := !multdiv_ready_id_i
        }
      }
      is(MultState.MULH) {
        mult3_sign_a := sign_a
        mult3_sign_b := sign_b
        mult3_op_a := op_a_i(31, 16)
        mult3_op_b := op_b_i(31, 16)
        val macResMulh = signedAdd3(0.U(34.W), accum, mult3_res)
        mac_res_d := macResMulh(33, 0)
        mult_state_d := MultState.MULL
        mult_valid := true.B
        mult_hold := !multdiv_ready_id_i
      }
    }

    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      when(mult_en_internal) {
        mult_state_q := mult_state_d
      }
    }
  } else {
    object MultState {
      private val states = Enum(4)
      val ALBL = states(0)
      val ALBH = states(1)
      val AHBL = states(2)
      val AHBH = states(3)
    }

    val mult_state_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(MultState.ALBL) }
    val mult_state_d = WireDefault(mult_state_q)
    val mult_op_a = Wire(UInt(16.W))
    val mult_op_b = Wire(UInt(16.W))
    val sign_a = Wire(Bool())
    val sign_b = Wire(Bool())
    val accum = Wire(UInt(34.W))
    mult_op_a := op_a_i(15, 0)
    mult_op_b := op_b_i(15, 0)
    sign_a := false.B
    sign_b := false.B
    accum := imd_val_q_i(0)
    val mac_res_ext = signedAdd3(mul17(sign_a, mult_op_a, sign_b, mult_op_b), accum, 0.U(34.W))
    val mac_res = mac_res_ext(33, 0)

    mac_res_d := mac_res

    switch(mult_state_q) {
      is(MultState.ALBL) {
        mult_op_a := op_a_i(15, 0)
        mult_op_b := op_b_i(15, 0)
        sign_a := false.B
        sign_b := false.B
        accum := 0.U
        mac_res_d := mac_res
        mult_state_d := MultState.ALBH
      }
      is(MultState.ALBH) {
        mult_op_a := op_a_i(15, 0)
        mult_op_b := op_b_i(31, 16)
        sign_a := false.B
        sign_b := signed_mode_i(1) && op_b_i(31)
        accum := Cat(0.U(18.W), imd_val_q_i(0)(31, 16))
        mac_res_d := Mux(operator_i === MdOp.MULL, Cat(0.U(2.W), mac_res(15, 0), imd_val_q_i(0)(15, 0)), mac_res)
        mult_state_d := MultState.AHBL
      }
      is(MultState.AHBL) {
        mult_op_a := op_a_i(31, 16)
        mult_op_b := op_b_i(15, 0)
        sign_a := signed_mode_i(0) && op_a_i(31)
        sign_b := false.B
        when(operator_i === MdOp.MULL) {
          accum := Cat(0.U(18.W), imd_val_q_i(0)(31, 16))
          mac_res_d := Cat(0.U(2.W), mac_res(15, 0), imd_val_q_i(0)(15, 0))
          mult_valid := true.B
          mult_state_d := MultState.ALBL
          mult_hold := !multdiv_ready_id_i
        }.otherwise {
          accum := imd_val_q_i(0)
          mac_res_d := mac_res
          mult_state_d := MultState.AHBH
        }
      }
      is(MultState.AHBH) {
        mult_op_a := op_a_i(31, 16)
        mult_op_b := op_b_i(31, 16)
        sign_a := signed_mode_i(0) && op_a_i(31)
        sign_b := signed_mode_i(1) && op_b_i(31)
        accum := Cat(Fill(16, signed_mult && imd_val_q_i(0)(33)), imd_val_q_i(0)(33, 16))
        mac_res_d := mac_res
        mult_valid := true.B
        mult_state_d := MultState.ALBL
        mult_hold := !multdiv_ready_id_i
      }
    }

    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      when(mult_en_internal) {
        mult_state_q := mult_state_d
      }
    }
  }

  val op_denominator_q = imd_val_q_i(1)(31, 0)
  val res_adder_h = alu_adder_ext_i(32, 1)
  val is_greater_equal = Mux(
    imd_val_q_i(0)(31) === op_denominator_q(31),
    !res_adder_h(31),
    imd_val_q_i(0)(31)
  )
  val next_remainder = Mux(is_greater_equal, res_adder_h(31, 0), imd_val_q_i(0)(31, 0))
  val one_shift = (1.U(32.W) << div_counter_q)(31, 0)
  val next_quotient = Mux(is_greater_equal, Cat(0.U(1.W), op_quotient_q) | Cat(0.U(1.W), one_shift), Cat(0.U(1.W), op_quotient_q))
  val div_sign_a = op_a_i(31) && signed_mode_i(0)
  val div_sign_b = op_b_i(31) && signed_mode_i(1)
  val div_change_sign = (div_sign_a ^ div_sign_b) && !div_by_zero_q
  val rem_change_sign = div_sign_a

  alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
  alu_operand_b_o := Cat(~op_b_i, 1.U(1.W))

  switch(md_state_q) {
    is(MdState.IDLE) {
      when(operator_i === MdOp.DIV) {
        op_remainder_d := Fill(34, 1.U)
        md_state_d := Mux(!data_ind_timing_i && equal_to_zero_i, MdState.FINISH, MdState.ABS_A)
        div_by_zero_d := equal_to_zero_i
      }.otherwise {
        op_remainder_d := Cat(0.U(2.W), op_a_i)
        md_state_d := Mux(!data_ind_timing_i && equal_to_zero_i, MdState.FINISH, MdState.ABS_A)
      }
      alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
      alu_operand_b_o := Cat(~op_b_i, 1.U(1.W))
      div_counter_d := 31.U
    }
    is(MdState.ABS_A) {
      op_quotient_d := 0.U
      op_numerator_d := Mux(div_sign_a, alu_adder_i, op_a_i)
      md_state_d := MdState.ABS_B
      div_counter_d := 31.U
      alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
      alu_operand_b_o := Cat(~op_a_i, 1.U(1.W))
    }
    is(MdState.ABS_B) {
      op_remainder_d := Cat(0.U(33.W), op_numerator_q(31))
      op_denominator_d := Mux(div_sign_b, alu_adder_i, op_b_i)
      md_state_d := MdState.COMP
      div_counter_d := 31.U
      alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
      alu_operand_b_o := Cat(~op_b_i, 1.U(1.W))
    }
    is(MdState.COMP) {
      op_remainder_d := Cat(0.U(1.W), next_remainder, op_numerator_q(div_counter_d))
      op_quotient_d := next_quotient(31, 0)
      md_state_d := Mux(div_counter_q === 1.U, MdState.LAST, MdState.COMP)
      alu_operand_a_o := Cat(imd_val_q_i(0)(31, 0), 1.U(1.W))
      alu_operand_b_o := Cat(~op_denominator_q, 1.U(1.W))
    }
    is(MdState.LAST) {
      op_remainder_d := Mux(operator_i === MdOp.DIV, Cat(0.U(1.W), next_quotient), Cat(0.U(2.W), next_remainder))
      alu_operand_a_o := Cat(imd_val_q_i(0)(31, 0), 1.U(1.W))
      alu_operand_b_o := Cat(~op_denominator_q, 1.U(1.W))
      md_state_d := MdState.CHANGE_SIGN
    }
    is(MdState.CHANGE_SIGN) {
      md_state_d := MdState.FINISH
      when(operator_i === MdOp.DIV) {
        op_remainder_d := Mux(div_change_sign, Cat(0.U(2.W), alu_adder_i), imd_val_q_i(0))
      }.otherwise {
        op_remainder_d := Mux(rem_change_sign, Cat(0.U(2.W), alu_adder_i), imd_val_q_i(0))
      }
      alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
      alu_operand_b_o := Cat(~imd_val_q_i(0)(31, 0), 1.U(1.W))
    }
    is(MdState.FINISH) {
      md_state_d := MdState.IDLE
      div_hold := !multdiv_ready_id_i
      div_valid := true.B
    }
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    when(div_en_internal) {
      div_counter_q := div_counter_d
      op_numerator_q := op_numerator_d
      op_quotient_q := op_quotient_d
      md_state_q := md_state_d
      div_by_zero_q := div_by_zero_d
    }
  }

  imd_val_d_o(0) := Mux(div_sel_i, op_remainder_d, mac_res_d)
  imd_val_d_o(1) := Cat(0.U(2.W), op_denominator_d)
  imd_val_we_o := Cat(div_en_internal, multdiv_en)
  valid_o := mult_valid || div_valid

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(md_state_q === MdState.IDLE ||
      md_state_q === MdState.ABS_A ||
      md_state_q === MdState.ABS_B ||
      md_state_q === MdState.COMP ||
      md_state_q === MdState.LAST ||
      md_state_q === MdState.CHANGE_SIGN ||
      md_state_q === MdState.FINISH,
      "IbexMultDivStateValid")
  }
}
