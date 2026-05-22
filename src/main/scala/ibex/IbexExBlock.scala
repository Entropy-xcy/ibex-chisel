// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util.Cat

class IbexExBlock(rv32m: Int = 2, rv32b: Int = 0, branchTargetALU: Boolean = false) extends RawModule {
  require(0 <= rv32m && rv32m <= 3)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val alu_operator_i = IO(Input(UInt(7.W)))
  val alu_operand_a_i = IO(Input(UInt(32.W)))
  val alu_operand_b_i = IO(Input(UInt(32.W)))
  val alu_instr_first_cycle_i = IO(Input(Bool()))

  val bt_a_operand_i = IO(Input(UInt(32.W)))
  val bt_b_operand_i = IO(Input(UInt(32.W)))

  val multdiv_operator_i = IO(Input(UInt(2.W)))
  val mult_en_i = IO(Input(Bool()))
  val div_en_i = IO(Input(Bool()))
  val mult_sel_i = IO(Input(Bool()))
  val div_sel_i = IO(Input(Bool()))
  val multdiv_signed_mode_i = IO(Input(UInt(2.W)))
  val multdiv_operand_a_i = IO(Input(UInt(32.W)))
  val multdiv_operand_b_i = IO(Input(UInt(32.W)))
  val multdiv_ready_id_i = IO(Input(Bool()))
  val data_ind_timing_i = IO(Input(Bool()))

  val imd_val_we_o = IO(Output(UInt(2.W)))
  val imd_val_d_o = IO(Output(Vec(2, UInt(34.W))))
  val imd_val_q_i = IO(Input(Vec(2, UInt(34.W))))

  val alu_adder_result_ex_o = IO(Output(UInt(32.W)))
  val result_ex_o = IO(Output(UInt(32.W)))
  val branch_target_o = IO(Output(UInt(32.W)))
  val branch_decision_o = IO(Output(Bool()))
  val ex_valid_o = IO(Output(Bool()))

  val alu_result = Wire(UInt(32.W))
  val multdiv_result = WireDefault(0.U(32.W))
  val multdiv_alu_operand_b = WireDefault(0.U(33.W))
  val multdiv_alu_operand_a = WireDefault(0.U(33.W))
  val alu_adder_result_ext = Wire(UInt(34.W))
  val alu_cmp_result = Wire(Bool())
  val alu_is_equal_result = Wire(Bool())
  val multdiv_valid = WireDefault(false.B)
  val multdiv_sel = WireDefault(false.B)
  val alu_imd_val_q = Wire(Vec(2, UInt(32.W)))
  val alu_imd_val_d = Wire(Vec(2, UInt(32.W)))
  val alu_imd_val_we = Wire(UInt(2.W))
  val multdiv_imd_val_d = WireDefault(VecInit(Seq.fill(2)(0.U(34.W))))
  val multdiv_imd_val_we = WireDefault(0.U(2.W))

  if (rv32m != 0) {
    multdiv_sel := mult_sel_i || div_sel_i
  } else {
    multdiv_sel := false.B
  }

  imd_val_d_o(0) := Mux(multdiv_sel, multdiv_imd_val_d(0), Cat(0.U(2.W), alu_imd_val_d(0)))
  imd_val_d_o(1) := Mux(multdiv_sel, multdiv_imd_val_d(1), Cat(0.U(2.W), alu_imd_val_d(1)))
  imd_val_we_o := Mux(multdiv_sel, multdiv_imd_val_we, alu_imd_val_we)

  alu_imd_val_q(0) := imd_val_q_i(0)(31, 0)
  alu_imd_val_q(1) := imd_val_q_i(1)(31, 0)
  result_ex_o := Mux(multdiv_sel, multdiv_result, alu_result)
  branch_decision_o := alu_cmp_result

  if (branchTargetALU) {
    val bt_alu_result = bt_a_operand_i +& bt_b_operand_i
    branch_target_o := bt_alu_result(31, 0)
  } else {
    branch_target_o := alu_adder_result_ex_o
  }

  val alu_i = Module(new IbexAlu(rv32b = rv32b))
  alu_i.operator_i := alu_operator_i
  alu_i.operand_a_i := alu_operand_a_i
  alu_i.operand_b_i := alu_operand_b_i
  alu_i.instr_first_cycle_i := alu_instr_first_cycle_i
  alu_i.imd_val_q_i := alu_imd_val_q
  alu_imd_val_we := alu_i.imd_val_we_o
  alu_imd_val_d := alu_i.imd_val_d_o
  alu_i.multdiv_operand_a_i := multdiv_alu_operand_a
  alu_i.multdiv_operand_b_i := multdiv_alu_operand_b
  alu_i.multdiv_sel_i := multdiv_sel
  alu_adder_result_ex_o := alu_i.adder_result_o
  alu_adder_result_ext := alu_i.adder_result_ext_o
  alu_result := alu_i.result_o
  alu_cmp_result := alu_i.comparison_result_o
  alu_is_equal_result := alu_i.is_equal_result_o

  if (rv32m == 1) {
    val multdiv_i = Module(new IbexMultDivSlow)
    multdiv_i.clk_i := clk_i
    multdiv_i.rst_ni := rst_ni
    multdiv_i.mult_en_i := mult_en_i
    multdiv_i.div_en_i := div_en_i
    multdiv_i.mult_sel_i := mult_sel_i
    multdiv_i.div_sel_i := div_sel_i
    multdiv_i.operator_i := multdiv_operator_i
    multdiv_i.signed_mode_i := multdiv_signed_mode_i
    multdiv_i.op_a_i := multdiv_operand_a_i
    multdiv_i.op_b_i := multdiv_operand_b_i
    multdiv_i.alu_adder_ext_i := alu_adder_result_ext
    multdiv_i.alu_adder_i := alu_adder_result_ex_o
    multdiv_i.equal_to_zero_i := alu_is_equal_result
    multdiv_i.data_ind_timing_i := data_ind_timing_i
    multdiv_valid := multdiv_i.valid_o
    multdiv_alu_operand_a := multdiv_i.alu_operand_a_o
    multdiv_alu_operand_b := multdiv_i.alu_operand_b_o
    multdiv_i.imd_val_q_i := imd_val_q_i
    multdiv_imd_val_d := multdiv_i.imd_val_d_o
    multdiv_imd_val_we := multdiv_i.imd_val_we_o
    multdiv_i.multdiv_ready_id_i := multdiv_ready_id_i
    multdiv_result := multdiv_i.multdiv_result_o
  } else if (rv32m == 2 || rv32m == 3) {
    val multdiv_i = Module(new IbexMultDivFast(rv32m = rv32m))
    multdiv_i.clk_i := clk_i
    multdiv_i.rst_ni := rst_ni
    multdiv_i.mult_en_i := mult_en_i
    multdiv_i.div_en_i := div_en_i
    multdiv_i.mult_sel_i := mult_sel_i
    multdiv_i.div_sel_i := div_sel_i
    multdiv_i.operator_i := multdiv_operator_i
    multdiv_i.signed_mode_i := multdiv_signed_mode_i
    multdiv_i.op_a_i := multdiv_operand_a_i
    multdiv_i.op_b_i := multdiv_operand_b_i
    multdiv_alu_operand_a := multdiv_i.alu_operand_a_o
    multdiv_alu_operand_b := multdiv_i.alu_operand_b_o
    multdiv_i.alu_adder_ext_i := alu_adder_result_ext
    multdiv_i.alu_adder_i := alu_adder_result_ex_o
    multdiv_i.equal_to_zero_i := alu_is_equal_result
    multdiv_i.data_ind_timing_i := data_ind_timing_i
    multdiv_i.imd_val_q_i := imd_val_q_i
    multdiv_imd_val_d := multdiv_i.imd_val_d_o
    multdiv_imd_val_we := multdiv_i.imd_val_we_o
    multdiv_i.multdiv_ready_id_i := multdiv_ready_id_i
    multdiv_valid := multdiv_i.valid_o
    multdiv_result := multdiv_i.multdiv_result_o
  }

  ex_valid_o := Mux(multdiv_sel, multdiv_valid, !(alu_imd_val_we.orR))
}
