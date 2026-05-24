// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexMultDivSlow extends RawModule {
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

  val md_state_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(MdState.IDLE) }
  val multdiv_count_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  val op_b_shift_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(33.W)) }
  val op_a_shift_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(33.W)) }
  val div_by_zero_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }

  val md_state_d = WireDefault(md_state_q)
  val multdiv_count_d = WireDefault(multdiv_count_q)
  val op_b_shift_d = WireDefault(op_b_shift_q)
  val op_a_shift_d = WireDefault(op_a_shift_q)
  val div_by_zero_d = WireDefault(div_by_zero_q)
  val multdiv_hold = WireDefault(false.B)

  val accum_window_q = imd_val_q_i(0)(32, 0)
  val accum_window_d = WireDefault(accum_window_q)
  val op_numerator_q = imd_val_q_i(1)(31, 0)
  val op_numerator_d = WireDefault(op_numerator_q)

  val res_adder_l = alu_adder_ext_i(32, 0)
  val res_adder_h = alu_adder_ext_i(33, 1)

  val sign_a = op_a_i(31) & signed_mode_i(0)
  val sign_b = op_b_i(31) & signed_mode_i(1)
  val op_a_ext = Cat(sign_a, op_a_i)
  val op_b_ext = Cat(sign_b, op_b_i)

  val b_0 = Fill(32, op_b_shift_q(0))
  val op_a_bw_pp = Cat(~(op_a_shift_q(32) & op_b_shift_q(0)), op_a_shift_q(31, 0) & b_0)
  val op_a_bw_last_pp = Cat(op_a_shift_q(32) & op_b_shift_q(0), ~(op_a_shift_q(31, 0) & b_0))

  val is_greater_equal = Mux(
    accum_window_q(31) === op_b_shift_q(31),
    !res_adder_h(31),
    accum_window_q(31)
  )
  val one_shift = (1.U(33.W) << multdiv_count_q)(32, 0)
  val next_numerator_bit_idx = Mux(multdiv_count_q === 0.U, 0.U, multdiv_count_q - 1.U)
  val next_numerator_bit = (op_numerator_q >> next_numerator_bit_idx)(0)
  val next_remainder = Mux(is_greater_equal, res_adder_h(31, 0), accum_window_q(31, 0))
  val next_quotient = Mux(is_greater_equal, op_a_shift_q | one_shift, op_a_shift_q)
  val div_change_sign = (sign_a ^ sign_b) & !div_by_zero_q
  val rem_change_sign = sign_a

  alu_operand_a_o := accum_window_q
  alu_operand_b_o := Mux(operator_i === MdOp.MULH && md_state_q === MdState.LAST, op_a_bw_last_pp, op_a_bw_pp)

  when(operator_i === MdOp.DIV || operator_i === MdOp.REM) {
    switch(md_state_q) {
      is(MdState.IDLE) {
        alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
        alu_operand_b_o := Cat(~op_b_i, 1.U(1.W))
      }
      is(MdState.ABS_A) {
        alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
        alu_operand_b_o := Cat(~op_a_i, 1.U(1.W))
      }
      is(MdState.ABS_B) {
        alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
        alu_operand_b_o := Cat(~op_b_i, 1.U(1.W))
      }
      is(MdState.CHANGE_SIGN) {
        alu_operand_a_o := Cat(0.U(32.W), 1.U(1.W))
        alu_operand_b_o := Cat(~accum_window_q(31, 0), 1.U(1.W))
      }
      is(MdState.COMP, MdState.LAST) {
        alu_operand_a_o := Cat(accum_window_q(31, 0), 1.U(1.W))
        alu_operand_b_o := Cat(~op_b_shift_q(31, 0), 1.U(1.W))
      }
    }
  }

  when(mult_sel_i || div_sel_i) {
    switch(md_state_q) {
      is(MdState.IDLE) {
        switch(operator_i) {
          is(MdOp.MULL) {
            op_a_shift_d := (op_a_ext << 1)(32, 0)
            accum_window_d := Cat(~(op_a_ext(32) & op_b_i(0)), op_a_ext(31, 0) & Fill(32, op_b_i(0)))
            op_b_shift_d := op_b_ext >> 1
            md_state_d := Mux(!data_ind_timing_i && ((op_b_ext >> 1) === 0.U), MdState.LAST, MdState.COMP)
          }
          is(MdOp.MULH) {
            op_a_shift_d := op_a_ext
            accum_window_d := Cat(1.U(1.W), ~(op_a_ext(32) & op_b_i(0)), op_a_ext(31, 1) & Fill(31, op_b_i(0)))
            op_b_shift_d := op_b_ext >> 1
            md_state_d := MdState.COMP
          }
          is(MdOp.DIV) {
            accum_window_d := Fill(33, 1.U(1.W))
            md_state_d := Mux(!data_ind_timing_i && equal_to_zero_i, MdState.FINISH, MdState.ABS_A)
            div_by_zero_d := equal_to_zero_i
          }
          is(MdOp.REM) {
            accum_window_d := op_a_ext
            md_state_d := Mux(!data_ind_timing_i && equal_to_zero_i, MdState.FINISH, MdState.ABS_A)
          }
        }
        multdiv_count_d := 31.U
      }

      is(MdState.ABS_A) {
        op_a_shift_d := 0.U
        op_numerator_d := Mux(sign_a, alu_adder_i, op_a_i)
        md_state_d := MdState.ABS_B
      }

      is(MdState.ABS_B) {
        accum_window_d := Cat(0.U(32.W), op_numerator_q(31))
        op_b_shift_d := Mux(sign_b, Cat(0.U(1.W), alu_adder_i), Cat(0.U(1.W), op_b_i))
        md_state_d := MdState.COMP
      }

      is(MdState.COMP) {
        multdiv_count_d := multdiv_count_q - 1.U
        switch(operator_i) {
          is(MdOp.MULL) {
            accum_window_d := res_adder_l
            op_a_shift_d := (op_a_shift_q << 1)(32, 0)
            op_b_shift_d := op_b_shift_q >> 1
            md_state_d := Mux((!data_ind_timing_i && ((op_b_shift_q >> 1) === 0.U)) || multdiv_count_q === 1.U, MdState.LAST, MdState.COMP)
          }
          is(MdOp.MULH) {
            accum_window_d := res_adder_h
            op_b_shift_d := op_b_shift_q >> 1
            md_state_d := Mux(multdiv_count_q === 1.U, MdState.LAST, MdState.COMP)
          }
          is(MdOp.DIV, MdOp.REM) {
            accum_window_d := Cat(next_remainder, next_numerator_bit)
            op_a_shift_d := next_quotient
            md_state_d := Mux(multdiv_count_q === 1.U, MdState.LAST, MdState.COMP)
          }
        }
      }

      is(MdState.LAST) {
        switch(operator_i) {
          is(MdOp.MULL, MdOp.MULH) {
            accum_window_d := res_adder_l
            md_state_d := MdState.IDLE
            multdiv_hold := !multdiv_ready_id_i
          }
          is(MdOp.DIV) {
            accum_window_d := next_quotient
            md_state_d := MdState.CHANGE_SIGN
          }
          is(MdOp.REM) {
            accum_window_d := Cat(0.U(1.W), next_remainder)
            md_state_d := MdState.CHANGE_SIGN
          }
        }
      }

      is(MdState.CHANGE_SIGN) {
        md_state_d := MdState.FINISH
        when(operator_i === MdOp.DIV) {
          accum_window_d := Mux(div_change_sign, Cat(0.U(1.W), alu_adder_i), accum_window_q)
        }.elsewhen(operator_i === MdOp.REM) {
          accum_window_d := Mux(rem_change_sign, Cat(0.U(1.W), alu_adder_i), accum_window_q)
        }
      }

      is(MdState.FINISH) {
        md_state_d := MdState.IDLE
        multdiv_hold := !multdiv_ready_id_i
      }
    }
  }

  val multdiv_en = (mult_en_i || div_en_i) && !multdiv_hold
  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    when(multdiv_en) {
      multdiv_count_q := multdiv_count_d
      op_b_shift_q := op_b_shift_d
      op_a_shift_q := op_a_shift_d
      md_state_q := md_state_d
      div_by_zero_q := div_by_zero_d
    }
  }

  imd_val_d_o(0) := Cat(0.U(1.W), accum_window_d)
  imd_val_d_o(1) := Cat(0.U(2.W), op_numerator_d)
  imd_val_we_o := Cat(multdiv_en, !multdiv_hold)

  valid_o := (md_state_q === MdState.FINISH) ||
    (md_state_q === MdState.LAST && (operator_i === MdOp.MULL || operator_i === MdOp.MULH))
  multdiv_result_o := Mux(div_en_i, accum_window_q(31, 0), res_adder_l(31, 0))

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
