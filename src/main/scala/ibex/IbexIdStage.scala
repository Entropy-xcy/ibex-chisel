// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexIdStage(
    rv32e: Boolean = false,
    rv32m: Int = 2,
    rv32b: Int = 0,
    dataIndTiming: Boolean = false,
    branchTargetALU: Boolean = false,
    writebackStage: Boolean = false,
    branchPredictor: Boolean = false,
    memECC: Boolean = false)
    extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val ctrl_busy_o = IO(Output(Bool()))
  val illegal_insn_o = IO(Output(Bool()))

  val instr_valid_i = IO(Input(Bool()))
  val instr_rdata_i = IO(Input(UInt(32.W)))
  val instr_rdata_alu_i = IO(Input(UInt(32.W)))
  val instr_rdata_c_i = IO(Input(UInt(16.W)))
  val instr_is_compressed_i = IO(Input(Bool()))
  val instr_bp_taken_i = IO(Input(Bool()))
  val instr_req_o = IO(Output(Bool()))
  val instr_first_cycle_id_o = IO(Output(Bool()))
  val instr_valid_clear_o = IO(Output(Bool()))
  val id_in_ready_o = IO(Output(Bool()))
  val rvfi_flush_next_o = IO(Output(Bool()))
  val instr_exec_i = IO(Input(Bool()))
  val icache_inval_o = IO(Output(Bool()))

  val branch_decision_i = IO(Input(Bool()))
  val pc_set_o = IO(Output(Bool()))
  val pc_mux_o = IO(Output(UInt(3.W)))
  val nt_branch_mispredict_o = IO(Output(Bool()))
  val nt_branch_addr_o = IO(Output(UInt(32.W)))
  val exc_pc_mux_o = IO(Output(UInt(2.W)))
  val exc_cause_o = IO(Output(UInt(7.W)))

  val illegal_c_insn_i = IO(Input(Bool()))
  val instr_fetch_err_i = IO(Input(Bool()))
  val instr_fetch_err_plus2_i = IO(Input(Bool()))
  val pc_id_i = IO(Input(UInt(32.W)))

  val ex_valid_i = IO(Input(Bool()))
  val lsu_resp_valid_i = IO(Input(Bool()))
  val alu_operator_ex_o = IO(Output(UInt(7.W)))
  val alu_operand_a_ex_o = IO(Output(UInt(32.W)))
  val alu_operand_b_ex_o = IO(Output(UInt(32.W)))
  val imd_val_we_ex_i = IO(Input(UInt(2.W)))
  val imd_val_d_ex_i = IO(Input(Vec(2, UInt(34.W))))
  val imd_val_q_ex_o = IO(Output(Vec(2, UInt(34.W))))
  val bt_a_operand_o = IO(Output(UInt(32.W)))
  val bt_b_operand_o = IO(Output(UInt(32.W)))

  val mult_en_ex_o = IO(Output(Bool()))
  val div_en_ex_o = IO(Output(Bool()))
  val mult_sel_ex_o = IO(Output(Bool()))
  val div_sel_ex_o = IO(Output(Bool()))
  val multdiv_operator_ex_o = IO(Output(UInt(2.W)))
  val multdiv_signed_mode_ex_o = IO(Output(UInt(2.W)))
  val multdiv_operand_a_ex_o = IO(Output(UInt(32.W)))
  val multdiv_operand_b_ex_o = IO(Output(UInt(32.W)))
  val multdiv_ready_id_o = IO(Output(Bool()))

  val csr_access_o = IO(Output(Bool()))
  val csr_op_o = IO(Output(UInt(2.W)))
  val csr_addr_o = IO(Output(UInt(12.W)))
  val csr_op_en_o = IO(Output(Bool()))
  val csr_save_if_o = IO(Output(Bool()))
  val csr_save_id_o = IO(Output(Bool()))
  val csr_save_wb_o = IO(Output(Bool()))
  val csr_restore_mret_id_o = IO(Output(Bool()))
  val csr_restore_dret_id_o = IO(Output(Bool()))
  val csr_save_cause_o = IO(Output(Bool()))
  val csr_mtval_o = IO(Output(UInt(32.W)))
  val priv_mode_i = IO(Input(UInt(2.W)))
  val csr_mstatus_tw_i = IO(Input(Bool()))
  val illegal_csr_insn_i = IO(Input(Bool()))
  val data_ind_timing_i = IO(Input(Bool()))

  val lsu_req_o = IO(Output(Bool()))
  val lsu_we_o = IO(Output(Bool()))
  val lsu_type_o = IO(Output(UInt(2.W)))
  val lsu_sign_ext_o = IO(Output(Bool()))
  val lsu_wdata_o = IO(Output(UInt(32.W)))
  val lsu_req_done_i = IO(Input(Bool()))
  val lsu_addr_incr_req_i = IO(Input(Bool()))
  val lsu_addr_last_i = IO(Input(UInt(32.W)))

  val csr_mstatus_mie_i = IO(Input(Bool()))
  val irq_pending_i = IO(Input(Bool()))
  val irqs_i = IO(Input(new IbexPkg.Irqs))
  val irq_nm_i = IO(Input(Bool()))
  val irq_nm_int_o = IO(Output(Bool()))
  val nmi_mode_o = IO(Output(Bool()))
  val lsu_load_err_i = IO(Input(Bool()))
  val lsu_load_resp_intg_err_i = IO(Input(Bool()))
  val lsu_store_err_i = IO(Input(Bool()))
  val lsu_store_resp_intg_err_i = IO(Input(Bool()))
  val expecting_load_resp_o = IO(Output(Bool()))
  val expecting_store_resp_o = IO(Output(Bool()))

  val debug_mode_o = IO(Output(Bool()))
  val debug_mode_entering_o = IO(Output(Bool()))
  val debug_cause_o = IO(Output(UInt(3.W)))
  val debug_csr_save_o = IO(Output(Bool()))
  val ebreak_into_debug_o = IO(Output(Bool()))
  val debug_req_i = IO(Input(Bool()))
  val debug_single_step_i = IO(Input(Bool()))
  val debug_ebreakm_i = IO(Input(Bool()))
  val debug_ebreaku_i = IO(Input(Bool()))
  val trigger_match_i = IO(Input(Bool()))

  val result_ex_i = IO(Input(UInt(32.W)))
  val csr_rdata_i = IO(Input(UInt(32.W)))
  val rf_raddr_a_o = IO(Output(UInt(5.W)))
  val rf_rdata_a_i = IO(Input(UInt(32.W)))
  val rf_raddr_b_o = IO(Output(UInt(5.W)))
  val rf_rdata_b_i = IO(Input(UInt(32.W)))
  val rf_ren_a_o = IO(Output(Bool()))
  val rf_ren_b_o = IO(Output(Bool()))
  val rf_waddr_id_o = IO(Output(UInt(5.W)))
  val rf_wdata_id_o = IO(Output(UInt(32.W)))
  val rf_we_id_o = IO(Output(Bool()))
  val rf_rd_a_wb_match_o = IO(Output(Bool()))
  val rf_rd_b_wb_match_o = IO(Output(Bool()))
  val rf_waddr_wb_i = IO(Input(UInt(5.W)))
  val rf_wdata_fwd_wb_i = IO(Input(UInt(32.W)))
  val rf_write_wb_i = IO(Input(Bool()))

  val en_wb_o = IO(Output(Bool()))
  val instr_type_wb_o = IO(Output(UInt(2.W)))
  val instr_perf_count_id_o = IO(Output(Bool()))
  val ready_wb_i = IO(Input(Bool()))
  val outstanding_load_wb_i = IO(Input(Bool()))
  val outstanding_store_wb_i = IO(Input(Bool()))

  val perf_jump_o = IO(Output(Bool()))
  val perf_branch_o = IO(Output(Bool()))
  val perf_tbranch_o = IO(Output(Bool()))
  val perf_dside_wait_o = IO(Output(Bool()))
  val perf_mul_wait_o = IO(Output(Bool()))
  val perf_div_wait_o = IO(Output(Bool()))
  val instr_id_done_o = IO(Output(Bool()))
  val id_exception_o = IO(Output(Bool()))
  val exc_req_lsu_o = IO(Output(Bool()))
  val ebrk_insn_o = IO(Output(Bool()))

  private object OpA {
    val RegA = 0.U(2.W)
    val Fwd = 1.U(2.W)
    val CurrPc = 2.U(2.W)
    val Imm = 3.U(2.W)
  }
  private object OpB {
    val RegB = 0.U(1.W)
    val Imm = 1.U(1.W)
  }
  private object ImmB {
    val I = 0.U(3.W)
    val S = 1.U(3.W)
    val B = 2.U(3.W)
    val U = 3.U(3.W)
    val J = 4.U(3.W)
    val IncrPc = 5.U(3.W)
    val IncrAddr = 6.U(3.W)
  }
  private object ImmA {
    val Z = 0.U(1.W)
  }
  private object RfWd {
    val Ex = 0.U(1.W)
    val Csr = 1.U(1.W)
  }
  private object WbInstr {
    val Load = 0.U(2.W)
    val Store = 1.U(2.W)
    val Other = 2.U(2.W)
  }
  private object CsrOp {
    val Read = 0.U(2.W)
    val Write = 1.U(2.W)
    val Set = 2.U(2.W)
    val Clear = 3.U(2.W)
  }

  val illegal_insn_dec = Wire(Bool())
  val ebrk_insn = Wire(Bool())
  val mret_insn_dec = Wire(Bool())
  val dret_insn_dec = Wire(Bool())
  val ecall_insn_dec = Wire(Bool())
  val wfi_insn_dec = Wire(Bool())
  val branch_in_dec = Wire(Bool())
  val jump_in_dec = Wire(Bool())
  val jump_set_dec = Wire(Bool())

  val imm_i_type = Wire(UInt(32.W))
  val imm_s_type = Wire(UInt(32.W))
  val imm_b_type = Wire(UInt(32.W))
  val imm_u_type = Wire(UInt(32.W))
  val imm_j_type = Wire(UInt(32.W))
  val zimm_rs1_type = Wire(UInt(32.W))
  val imm_a = Wire(UInt(32.W))
  val imm_b = Wire(UInt(32.W))
  val rf_wdata_sel = Wire(UInt(1.W))
  val rf_we_dec = Wire(Bool())
  val rf_we_raw = WireDefault(false.B)
  val rf_ren_a_dec = Wire(Bool())
  val rf_ren_b_dec = Wire(Bool())
  val alu_operator = Wire(UInt(7.W))
  val alu_op_a_mux_sel_dec = Wire(UInt(2.W))
  val alu_op_b_mux_sel_dec = Wire(UInt(1.W))
  val alu_multicycle_dec = Wire(Bool())
  val bt_a_mux_sel = Wire(UInt(2.W))
  val bt_b_mux_sel = Wire(UInt(3.W))
  val imm_a_mux_sel = Wire(UInt(1.W))
  val imm_b_mux_sel_dec = Wire(UInt(3.W))
  val mult_en_dec = Wire(Bool())
  val div_en_dec = Wire(Bool())
  val multdiv_operator = Wire(UInt(2.W))
  val multdiv_signed_mode = Wire(UInt(2.W))
  val lsu_we = Wire(Bool())
  val lsu_type = Wire(UInt(2.W))
  val lsu_sign_ext = Wire(Bool())
  val lsu_req_dec = Wire(Bool())

  val alu_op_a_mux_sel = Mux(lsu_addr_incr_req_i, OpA.Fwd, alu_op_a_mux_sel_dec)
  val alu_op_b_mux_sel = Mux(lsu_addr_incr_req_i, OpB.Imm, alu_op_b_mux_sel_dec)
  val imm_b_mux_sel = Mux(lsu_addr_incr_req_i, ImmB.IncrAddr, imm_b_mux_sel_dec)

  val rf_ren_a = instr_valid_i && !instr_fetch_err_i && !illegal_insn_o && rf_ren_a_dec
  val rf_ren_b = instr_valid_i && !instr_fetch_err_i && !illegal_insn_o && rf_ren_b_dec
  rf_ren_a_o := rf_ren_a
  rf_ren_b_o := rf_ren_b

  val decoder_i = Module(new IbexDecoder(rv32e = rv32e, rv32m = rv32m, rv32b = rv32b, branchTargetALU = branchTargetALU))
  decoder_i.clk_i := clk_i
  decoder_i.rst_ni := rst_ni
  illegal_insn_dec := decoder_i.illegal_insn_o
  ebrk_insn := decoder_i.ebrk_insn_o
  mret_insn_dec := decoder_i.mret_insn_o
  dret_insn_dec := decoder_i.dret_insn_o
  ecall_insn_dec := decoder_i.ecall_insn_o
  wfi_insn_dec := decoder_i.wfi_insn_o
  jump_set_dec := decoder_i.jump_set_o
  decoder_i.branch_taken_i := true.B
  icache_inval_o := decoder_i.icache_inval_o
  decoder_i.instr_first_cycle_i := instr_first_cycle_id_o
  decoder_i.instr_rdata_i := instr_rdata_i
  decoder_i.instr_rdata_alu_i := instr_rdata_alu_i
  decoder_i.illegal_c_insn_i := illegal_c_insn_i
  imm_a_mux_sel := decoder_i.imm_a_mux_sel_o
  imm_b_mux_sel_dec := decoder_i.imm_b_mux_sel_o
  bt_a_mux_sel := decoder_i.bt_a_mux_sel_o
  bt_b_mux_sel := decoder_i.bt_b_mux_sel_o
  imm_i_type := decoder_i.imm_i_type_o
  imm_s_type := decoder_i.imm_s_type_o
  imm_b_type := decoder_i.imm_b_type_o
  imm_u_type := decoder_i.imm_u_type_o
  imm_j_type := decoder_i.imm_j_type_o
  zimm_rs1_type := decoder_i.zimm_rs1_type_o
  rf_wdata_sel := decoder_i.rf_wdata_sel_o
  rf_we_dec := decoder_i.rf_we_o
  rf_raddr_a_o := decoder_i.rf_raddr_a_o
  rf_raddr_b_o := decoder_i.rf_raddr_b_o
  rf_waddr_id_o := decoder_i.rf_waddr_o
  rf_ren_a_dec := decoder_i.rf_ren_a_o
  rf_ren_b_dec := decoder_i.rf_ren_b_o
  alu_operator := decoder_i.alu_operator_o
  alu_op_a_mux_sel_dec := decoder_i.alu_op_a_mux_sel_o
  alu_op_b_mux_sel_dec := decoder_i.alu_op_b_mux_sel_o
  alu_multicycle_dec := decoder_i.alu_multicycle_o
  mult_en_dec := decoder_i.mult_en_o
  div_en_dec := decoder_i.div_en_o
  mult_sel_ex_o := decoder_i.mult_sel_o
  div_sel_ex_o := decoder_i.div_sel_o
  multdiv_operator := decoder_i.multdiv_operator_o
  multdiv_signed_mode := decoder_i.multdiv_signed_mode_o
  csr_access_o := decoder_i.csr_access_o
  csr_op_o := decoder_i.csr_op_o
  csr_addr_o := decoder_i.csr_addr_o
  lsu_req_dec := decoder_i.data_req_o
  lsu_we := decoder_i.data_we_o
  lsu_type := decoder_i.data_type_o
  lsu_sign_ext := decoder_i.data_sign_extension_o
  jump_in_dec := decoder_i.jump_in_dec_o
  branch_in_dec := decoder_i.branch_in_dec_o

  imm_a := Mux(imm_a_mux_sel === ImmA.Z, zimm_rs1_type, 0.U)
  val rf_rdata_a_fwd = Wire(UInt(32.W))
  val rf_rdata_b_fwd = Wire(UInt(32.W))
  alu_operand_a_ex_o := MuxLookup(alu_op_a_mux_sel, pc_id_i)(Seq(
    OpA.RegA -> rf_rdata_a_fwd,
    OpA.Fwd -> lsu_addr_last_i,
    OpA.CurrPc -> pc_id_i,
    OpA.Imm -> imm_a))

  if (branchTargetALU) {
    bt_a_operand_o := MuxLookup(bt_a_mux_sel, pc_id_i)(Seq(
      OpA.RegA -> rf_rdata_a_fwd,
      OpA.CurrPc -> pc_id_i))
    bt_b_operand_o := MuxLookup(bt_b_mux_sel, Mux(instr_is_compressed_i, 2.U, 4.U))(Seq(
      ImmB.I -> imm_i_type,
      ImmB.B -> imm_b_type,
      ImmB.J -> imm_j_type,
      ImmB.IncrPc -> Mux(instr_is_compressed_i, 2.U, 4.U)))
    imm_b := MuxLookup(imm_b_mux_sel, 4.U)(Seq(
      ImmB.I -> imm_i_type,
      ImmB.S -> imm_s_type,
      ImmB.U -> imm_u_type,
      ImmB.IncrPc -> Mux(instr_is_compressed_i, 2.U, 4.U),
      ImmB.IncrAddr -> 4.U))
  } else {
    bt_a_operand_o := 0.U
    bt_b_operand_o := 0.U
    imm_b := MuxLookup(imm_b_mux_sel, 4.U)(Seq(
      ImmB.I -> imm_i_type,
      ImmB.S -> imm_s_type,
      ImmB.B -> imm_b_type,
      ImmB.U -> imm_u_type,
      ImmB.J -> imm_j_type,
      ImmB.IncrPc -> Mux(instr_is_compressed_i, 2.U, 4.U),
      ImmB.IncrAddr -> 4.U))
  }
  alu_operand_b_ex_o := Mux(alu_op_b_mux_sel === OpB.Imm, imm_b, rf_rdata_b_fwd)
  alu_operator_ex_o := alu_operator

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val imd_val_q = RegInit(VecInit(Seq.fill(2)(0.U(34.W))))
    for (i <- 0 until 2) {
      when(imd_val_we_ex_i(i)) {
        imd_val_q(i) := imd_val_d_ex_i(i)
      }
      imd_val_q_ex_o(i) := imd_val_q(i)
    }
  }

  rf_wdata_id_o := Mux(rf_wdata_sel === RfWd.Csr, csr_rdata_i, result_ex_i)

  val illegal_dret_insn = dret_insn_dec && !debug_mode_o
  val illegal_umode_insn = priv_mode_i =/= IbexPkg.PrivLvl.M && (mret_insn_dec || (csr_mstatus_tw_i && wfi_insn_dec))
  illegal_insn_o := instr_valid_i && (illegal_insn_dec || illegal_csr_insn_i || illegal_dret_insn || illegal_umode_insn)
  val mem_resp_intg_err = lsu_load_resp_intg_err_i || lsu_store_resp_intg_err_i
  val no_flush_csr_addr = csr_addr_o === IbexPkg.CsrNum.MSCRATCH.U || csr_addr_o === IbexPkg.CsrNum.MEPC.U
  val csr_pipe_flush = csr_op_en_o && (csr_op_o === CsrOp.Write || csr_op_o === CsrOp.Set || csr_op_o === CsrOp.Clear) && !no_flush_csr_addr

  val branch_set = Wire(Bool())
  val branch_not_set = WireDefault(false.B)
  val jump_set = Wire(Bool())
  val stall_id = Wire(Bool())
  val stall_wb = Wire(Bool())
  val flush_id = Wire(Bool())
  val controller_run = Wire(Bool())
  val wb_exception = Wire(Bool())
  val id_exception = Wire(Bool())

  val controller_i = Module(new IbexController(writebackStage = writebackStage, branchPredictor = branchPredictor, memECC = memECC))
  controller_i.clk_i := clk_i
  controller_i.rst_ni := rst_ni
  ctrl_busy_o := controller_i.ctrl_busy_o
  controller_i.illegal_insn_i := illegal_insn_o
  controller_i.ecall_insn_i := ecall_insn_dec
  controller_i.mret_insn_i := mret_insn_dec
  controller_i.dret_insn_i := dret_insn_dec
  controller_i.wfi_insn_i := wfi_insn_dec
  controller_i.ebrk_insn_i := ebrk_insn
  controller_i.csr_pipe_flush_i := csr_pipe_flush
  controller_i.instr_valid_i := instr_valid_i
  controller_i.instr_i := instr_rdata_i
  controller_i.instr_compressed_i := instr_rdata_c_i
  controller_i.instr_is_compressed_i := instr_is_compressed_i
  controller_i.instr_bp_taken_i := instr_bp_taken_i
  controller_i.instr_fetch_err_i := instr_fetch_err_i
  controller_i.instr_fetch_err_plus2_i := instr_fetch_err_plus2_i
  controller_i.pc_id_i := pc_id_i
  instr_valid_clear_o := controller_i.instr_valid_clear_o
  id_in_ready_o := controller_i.id_in_ready_o
  rvfi_flush_next_o := controller_i.rvfi_flush_next_o
  controller_run := controller_i.controller_run_o
  controller_i.instr_exec_i := instr_exec_i
  instr_req_o := controller_i.instr_req_o
  pc_set_o := controller_i.pc_set_o
  pc_mux_o := controller_i.pc_mux_o
  nt_branch_mispredict_o := controller_i.nt_branch_mispredict_o
  exc_pc_mux_o := controller_i.exc_pc_mux_o
  exc_cause_o := controller_i.exc_cause_o
  controller_i.lsu_addr_last_i := lsu_addr_last_i
  controller_i.load_err_i := lsu_load_err_i
  controller_i.mem_resp_intg_err_i := mem_resp_intg_err
  controller_i.store_err_i := lsu_store_err_i
  wb_exception := controller_i.wb_exception_o
  id_exception := controller_i.id_exception_o
  id_exception_o := id_exception
  exc_req_lsu_o := controller_i.exc_req_lsu_o
  ebrk_insn_o := ebrk_insn
  controller_i.branch_set_i := branch_set
  controller_i.branch_not_set_i := branch_not_set
  controller_i.jump_set_i := jump_set
  controller_i.csr_mstatus_mie_i := csr_mstatus_mie_i
  controller_i.irq_pending_i := irq_pending_i
  controller_i.irqs_i := irqs_i
  controller_i.irq_nm_ext_i := irq_nm_i
  irq_nm_int_o := controller_i.irq_nm_int_o
  nmi_mode_o := controller_i.nmi_mode_o
  csr_save_if_o := controller_i.csr_save_if_o
  csr_save_id_o := controller_i.csr_save_id_o
  csr_save_wb_o := controller_i.csr_save_wb_o
  csr_restore_mret_id_o := controller_i.csr_restore_mret_id_o
  csr_restore_dret_id_o := controller_i.csr_restore_dret_id_o
  csr_save_cause_o := controller_i.csr_save_cause_o
  csr_mtval_o := controller_i.csr_mtval_o
  controller_i.priv_mode_i := priv_mode_i
  debug_mode_o := controller_i.debug_mode_o
  debug_mode_entering_o := controller_i.debug_mode_entering_o
  debug_cause_o := controller_i.debug_cause_o
  debug_csr_save_o := controller_i.debug_csr_save_o
  ebreak_into_debug_o := controller_i.ebreak_into_debug_o
  controller_i.debug_req_i := debug_req_i
  controller_i.debug_single_step_i := debug_single_step_i
  controller_i.debug_ebreakm_i := debug_ebreakm_i
  controller_i.debug_ebreaku_i := debug_ebreaku_i
  controller_i.trigger_match_i := trigger_match_i
  controller_i.stall_id_i := stall_id
  controller_i.stall_wb_i := stall_wb
  flush_id := controller_i.flush_id_o
  controller_i.ready_wb_i := ready_wb_i
  perf_jump_o := controller_i.perf_jump_o
  perf_tbranch_o := controller_i.perf_tbranch_o

  val multdiv_en_dec = mult_en_dec || div_en_dec
  val branch_set_raw_d = WireDefault(false.B)
  val branch_set_raw = Wire(Bool())
  if (branchTargetALU && !dataIndTiming) {
    branch_set_raw := branch_set_raw_d
  } else {
    val branch_set_raw_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegNext(branch_set_raw_d, false.B) }
    branch_set_raw := Mux(branchTargetALU.B && !data_ind_timing_i, branch_set_raw_d, branch_set_raw_q)
  }
  val branch_jump_set_done_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val jump_set_raw = WireDefault(false.B)
  val branch_jump_set_done_d = (branch_set_raw || jump_set_raw || branch_jump_set_done_q) && !instr_valid_clear_o
  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    branch_jump_set_done_q := branch_jump_set_done_d
  }
  jump_set := jump_set_raw && !branch_jump_set_done_q
  branch_set := branch_set_raw && !branch_jump_set_done_q

  val branch_taken =
    if (dataIndTiming) {
      val branch_taken_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegNext(branch_decision_i, false.B) }
      !data_ind_timing_i || branch_taken_q
    } else true.B
  decoder_i.branch_taken_i := branch_taken
  nt_branch_addr_o := Mux(branchPredictor.B, pc_id_i + Mux(instr_is_compressed_i, 2.U, 4.U), 0.U)

  val idFirst = 0.U(1.W)
  val idMulti = 1.U(1.W)
  val id_fsm_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(idFirst) }
  val id_fsm_d = WireDefault(id_fsm_q)
  val instr_first_cycle = instr_valid_i && id_fsm_q === idFirst
  instr_first_cycle_id_o := instr_first_cycle
  val instr_executing_spec = Wire(Bool())
  val instr_executing = Wire(Bool())
  val instr_done = Wire(Bool())
  val stall_ld_hz = Wire(Bool())
  val stall_mem = Wire(Bool())
  val stall_multdiv = WireDefault(false.B)
  val stall_branch = WireDefault(false.B)
  val stall_jump = WireDefault(false.B)
  val stall_alu = WireDefault(false.B)
  val multicycle_done = Wire(Bool())

  id_fsm_d := id_fsm_q
  rf_we_raw := rf_we_dec
  perf_branch_o := false.B
  when(instr_executing_spec) {
    when(id_fsm_q === idFirst) {
      when(lsu_req_dec) {
        when(!writebackStage.B || !lsu_req_done_i) { id_fsm_d := idMulti }
      }.elsewhen(multdiv_en_dec) {
        when(!ex_valid_i) {
          id_fsm_d := idMulti
          rf_we_raw := false.B
          stall_multdiv := true.B
        }
      }.elsewhen(branch_in_dec) {
        id_fsm_d := Mux(data_ind_timing_i || (!branchTargetALU.B && branch_decision_i), idMulti, idFirst)
        stall_branch := (!branchTargetALU.B && branch_decision_i) || data_ind_timing_i
        branch_set_raw_d := branch_decision_i || data_ind_timing_i
        branch_not_set := branchPredictor.B && !branch_decision_i
        perf_branch_o := true.B
      }.elsewhen(jump_in_dec) {
        id_fsm_d := Mux(branchTargetALU.B, idFirst, idMulti)
        stall_jump := !branchTargetALU.B
        jump_set_raw := jump_set_dec
      }.elsewhen(alu_multicycle_dec) {
        stall_alu := true.B
        id_fsm_d := idMulti
        rf_we_raw := false.B
      }
    }.otherwise {
      when(multdiv_en_dec) {
        rf_we_raw := rf_we_dec && ex_valid_i
      }
      when(multicycle_done && ready_wb_i) {
        id_fsm_d := idFirst
      }.otherwise {
        stall_multdiv := multdiv_en_dec
        stall_branch := branch_in_dec
        stall_jump := jump_in_dec
      }
    }
  }
  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    when(instr_executing) {
      id_fsm_q := id_fsm_d
    }
  }

  if (writebackStage) {
    val rf_rd_a_wb_match = (rf_waddr_wb_i === rf_raddr_a_o) && rf_raddr_a_o.orR
    val rf_rd_b_wb_match = (rf_waddr_wb_i === rf_raddr_b_o) && rf_raddr_b_o.orR
    rf_rd_a_wb_match_o := rf_rd_a_wb_match
    rf_rd_b_wb_match_o := rf_rd_b_wb_match
    val rf_rd_a_hz = rf_rd_a_wb_match && rf_ren_a
    val rf_rd_b_hz = rf_rd_b_wb_match && rf_ren_b
    rf_rdata_a_fwd := Mux(rf_rd_a_wb_match && rf_write_wb_i, rf_wdata_fwd_wb_i, rf_rdata_a_i)
    rf_rdata_b_fwd := Mux(rf_rd_b_wb_match && rf_write_wb_i, rf_wdata_fwd_wb_i, rf_rdata_b_i)
    stall_ld_hz := outstanding_load_wb_i && (rf_rd_a_hz || rf_rd_b_hz)
    val outstanding_memory_access = (outstanding_load_wb_i || outstanding_store_wb_i) && !lsu_resp_valid_i
    val instr_kill = instr_fetch_err_i || wb_exception || id_exception || !controller_run
    instr_executing_spec := instr_valid_i && !instr_fetch_err_i && controller_run && !stall_ld_hz
    instr_executing := instr_valid_i && !instr_kill && !stall_ld_hz && !outstanding_memory_access
    stall_mem := instr_valid_i && (outstanding_memory_access || (lsu_req_dec && !lsu_req_done_i))
    multicycle_done := Mux(lsu_req_dec, !stall_mem, ex_valid_i)
    instr_type_wb_o := Mux(!lsu_req_dec, WbInstr.Other, Mux(lsu_we, WbInstr.Store, WbInstr.Load))
    instr_id_done_o := en_wb_o && ready_wb_i
    stall_wb := en_wb_o && !ready_wb_i
    perf_dside_wait_o := instr_valid_i && !instr_kill && (outstanding_memory_access || stall_ld_hz)
    expecting_load_resp_o := false.B
    expecting_store_resp_o := false.B
  } else {
    multicycle_done := Mux(lsu_req_dec, lsu_resp_valid_i, ex_valid_i)
    instr_executing_spec := instr_valid_i && !instr_fetch_err_i && controller_run
    instr_executing := instr_executing_spec
    stall_mem := instr_valid_i && (lsu_req_dec && (!lsu_resp_valid_i || instr_first_cycle))
    stall_ld_hz := false.B
    rf_rdata_a_fwd := rf_rdata_a_i
    rf_rdata_b_fwd := rf_rdata_b_i
    rf_rd_a_wb_match_o := false.B
    rf_rd_b_wb_match_o := false.B
    instr_type_wb_o := WbInstr.Other
    stall_wb := false.B
    perf_dside_wait_o := instr_executing && lsu_req_dec && !lsu_resp_valid_i
    instr_id_done_o := instr_done
    expecting_load_resp_o := instr_valid_i && lsu_req_dec && !instr_first_cycle && !lsu_we
    expecting_store_resp_o := instr_valid_i && lsu_req_dec && !instr_first_cycle && lsu_we
  }

  stall_id := stall_ld_hz || stall_mem || stall_multdiv || stall_jump || stall_branch || stall_alu
  instr_done := !stall_id && !flush_id && instr_executing
  en_wb_o := instr_done

  val data_req_allowed =
    if (writebackStage) {
      val outstanding_memory_access = (outstanding_load_wb_i || outstanding_store_wb_i) && !lsu_resp_valid_i
      !outstanding_memory_access
    } else instr_first_cycle
  val lsu_req = Mux(instr_executing, data_req_allowed && lsu_req_dec, false.B)
  lsu_req_o := lsu_req
  lsu_we_o := lsu_we
  lsu_type_o := lsu_type
  lsu_sign_ext_o := lsu_sign_ext
  lsu_wdata_o := rf_rdata_b_fwd
  csr_op_en_o := csr_access_o && instr_executing && instr_id_done_o
  rf_we_id_o := rf_we_raw && instr_executing && !illegal_csr_insn_i
  mult_en_ex_o := Mux(instr_executing, mult_en_dec, false.B)
  div_en_ex_o := Mux(instr_executing, div_en_dec, false.B)
  multdiv_operator_ex_o := multdiv_operator
  multdiv_signed_mode_ex_o := multdiv_signed_mode
  multdiv_operand_a_ex_o := rf_rdata_a_fwd
  multdiv_operand_b_ex_o := rf_rdata_b_fwd
  multdiv_ready_id_o := ready_wb_i
  instr_perf_count_id_o := !ebrk_insn && !ecall_insn_dec && !illegal_insn_dec && !illegal_csr_insn_i && !instr_fetch_err_i
  perf_mul_wait_o := stall_multdiv && mult_en_dec
  perf_div_wait_o := stall_multdiv && div_en_dec

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val branchSetPrev = RegNext(branch_set && !instr_bp_taken_i, false.B)
    val jumpSetPrev = RegNext(jump_set && !instr_bp_taken_i, false.B)
    val idReadyPrev = RegNext(id_in_ready_o, false.B)

    assert(!branchSetPrev || !branch_set,
      "NeverDoubleBranch")
    assert(!jumpSetPrev || !jump_set,
      "NeverDoubleJump")
    assert(!(id_fsm_q === idFirst && id_fsm_d === idMulti) || stall_id,
      "StallIDIfMulticycle")
    assert(!(illegal_insn_o && stall_id) ||
      (stall_mem && !(stall_ld_hz || stall_multdiv || stall_jump || stall_branch || stall_alu)),
      "IllegalInsnStallMustBeMemStall")
    assert(PopCount(Seq(lsu_req_dec, multdiv_en_dec, branch_in_dec, jump_in_dec)) <= 1.U,
      "IbexMulticycleEnableUnique")
    assert(!instr_valid_i || instr_rdata_i === instr_rdata_alu_i,
      "IbexDuplicateInstrMatch")
    assert(!idReadyPrev || id_fsm_q === idFirst,
      "IbexMoveToFirstCycleWhenIdReady")
  }
}
