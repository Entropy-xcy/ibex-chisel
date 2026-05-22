package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexIdStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(writebackStage: Boolean = false, branchTargetALU: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val instr_valid_i = Input(Bool())
      val instr_rdata_i = Input(UInt(32.W))
      val instr_rdata_alu_i = Input(UInt(32.W))
      val instr_rdata_c_i = Input(UInt(16.W))
      val instr_is_compressed_i = Input(Bool())
      val instr_bp_taken_i = Input(Bool())
      val instr_exec_i = Input(Bool())
      val branch_decision_i = Input(Bool())
      val illegal_c_insn_i = Input(Bool())
      val instr_fetch_err_i = Input(Bool())
      val instr_fetch_err_plus2_i = Input(Bool())
      val pc_id_i = Input(UInt(32.W))
      val ex_valid_i = Input(Bool())
      val lsu_resp_valid_i = Input(Bool())
      val imd_val_we_ex_i = Input(UInt(2.W))
      val imd_val_d0_ex_i = Input(UInt(34.W))
      val imd_val_d1_ex_i = Input(UInt(34.W))
      val priv_mode_i = Input(UInt(2.W))
      val csr_mstatus_tw_i = Input(Bool())
      val illegal_csr_insn_i = Input(Bool())
      val data_ind_timing_i = Input(Bool())
      val lsu_req_done_i = Input(Bool())
      val lsu_addr_incr_req_i = Input(Bool())
      val lsu_addr_last_i = Input(UInt(32.W))
      val csr_mstatus_mie_i = Input(Bool())
      val irq_pending_i = Input(Bool())
      val irq_software_i = Input(Bool())
      val irq_timer_i = Input(Bool())
      val irq_external_i = Input(Bool())
      val irq_fast_i = Input(UInt(15.W))
      val irq_nm_i = Input(Bool())
      val lsu_load_err_i = Input(Bool())
      val lsu_load_resp_intg_err_i = Input(Bool())
      val lsu_store_err_i = Input(Bool())
      val lsu_store_resp_intg_err_i = Input(Bool())
      val debug_req_i = Input(Bool())
      val debug_single_step_i = Input(Bool())
      val debug_ebreakm_i = Input(Bool())
      val debug_ebreaku_i = Input(Bool())
      val trigger_match_i = Input(Bool())
      val result_ex_i = Input(UInt(32.W))
      val csr_rdata_i = Input(UInt(32.W))
      val rf_rdata_a_i = Input(UInt(32.W))
      val rf_rdata_b_i = Input(UInt(32.W))
      val rf_waddr_wb_i = Input(UInt(5.W))
      val rf_wdata_fwd_wb_i = Input(UInt(32.W))
      val rf_write_wb_i = Input(Bool())
      val ready_wb_i = Input(Bool())
      val outstanding_load_wb_i = Input(Bool())
      val outstanding_store_wb_i = Input(Bool())

      val ctrl_busy_o = Output(Bool())
      val illegal_insn_o = Output(Bool())
      val instr_req_o = Output(Bool())
      val instr_first_cycle_id_o = Output(Bool())
      val instr_valid_clear_o = Output(Bool())
      val id_in_ready_o = Output(Bool())
      val icache_inval_o = Output(Bool())
      val pc_set_o = Output(Bool())
      val pc_mux_o = Output(UInt(3.W))
      val nt_branch_mispredict_o = Output(Bool())
      val nt_branch_addr_o = Output(UInt(32.W))
      val exc_pc_mux_o = Output(UInt(2.W))
      val exc_cause_o = Output(UInt(7.W))
      val alu_operator_ex_o = Output(UInt(7.W))
      val alu_operand_a_ex_o = Output(UInt(32.W))
      val alu_operand_b_ex_o = Output(UInt(32.W))
      val imd_val_q0_ex_o = Output(UInt(34.W))
      val imd_val_q1_ex_o = Output(UInt(34.W))
      val mult_en_ex_o = Output(Bool())
      val div_en_ex_o = Output(Bool())
      val mult_sel_ex_o = Output(Bool())
      val div_sel_ex_o = Output(Bool())
      val multdiv_operator_ex_o = Output(UInt(2.W))
      val multdiv_signed_mode_ex_o = Output(UInt(2.W))
      val multdiv_operand_a_ex_o = Output(UInt(32.W))
      val multdiv_operand_b_ex_o = Output(UInt(32.W))
      val multdiv_ready_id_o = Output(Bool())
      val csr_access_o = Output(Bool())
      val csr_op_o = Output(UInt(2.W))
      val csr_addr_o = Output(UInt(12.W))
      val csr_op_en_o = Output(Bool())
      val csr_save_if_o = Output(Bool())
      val csr_save_id_o = Output(Bool())
      val csr_save_wb_o = Output(Bool())
      val csr_restore_mret_id_o = Output(Bool())
      val csr_restore_dret_id_o = Output(Bool())
      val csr_save_cause_o = Output(Bool())
      val csr_mtval_o = Output(UInt(32.W))
      val lsu_req_o = Output(Bool())
      val lsu_we_o = Output(Bool())
      val lsu_type_o = Output(UInt(2.W))
      val lsu_sign_ext_o = Output(Bool())
      val lsu_wdata_o = Output(UInt(32.W))
      val expecting_load_resp_o = Output(Bool())
      val expecting_store_resp_o = Output(Bool())
      val debug_mode_o = Output(Bool())
      val debug_mode_entering_o = Output(Bool())
      val debug_cause_o = Output(UInt(3.W))
      val debug_csr_save_o = Output(Bool())
      val rf_raddr_a_o = Output(UInt(5.W))
      val rf_raddr_b_o = Output(UInt(5.W))
      val rf_ren_a_o = Output(Bool())
      val rf_ren_b_o = Output(Bool())
      val rf_waddr_id_o = Output(UInt(5.W))
      val rf_wdata_id_o = Output(UInt(32.W))
      val rf_we_id_o = Output(Bool())
      val rf_rd_a_wb_match_o = Output(Bool())
      val rf_rd_b_wb_match_o = Output(Bool())
      val en_wb_o = Output(Bool())
      val instr_type_wb_o = Output(UInt(2.W))
      val instr_perf_count_id_o = Output(Bool())
      val perf_jump_o = Output(Bool())
      val perf_branch_o = Output(Bool())
      val perf_tbranch_o = Output(Bool())
      val perf_dside_wait_o = Output(Bool())
      val perf_mul_wait_o = Output(Bool())
      val perf_div_wait_o = Output(Bool())
      val instr_id_done_o = Output(Bool())
    })

    val dut = Module(new IbexIdStage(writebackStage = writebackStage, branchTargetALU = branchTargetALU))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.instr_valid_i := io.instr_valid_i
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_rdata_alu_i := io.instr_rdata_alu_i
    dut.instr_rdata_c_i := io.instr_rdata_c_i
    dut.instr_is_compressed_i := io.instr_is_compressed_i
    dut.instr_bp_taken_i := io.instr_bp_taken_i
    dut.instr_exec_i := io.instr_exec_i
    dut.branch_decision_i := io.branch_decision_i
    dut.illegal_c_insn_i := io.illegal_c_insn_i
    dut.instr_fetch_err_i := io.instr_fetch_err_i
    dut.instr_fetch_err_plus2_i := io.instr_fetch_err_plus2_i
    dut.pc_id_i := io.pc_id_i
    dut.ex_valid_i := io.ex_valid_i
    dut.lsu_resp_valid_i := io.lsu_resp_valid_i
    dut.imd_val_we_ex_i := io.imd_val_we_ex_i
    dut.imd_val_d_ex_i(0) := io.imd_val_d0_ex_i
    dut.imd_val_d_ex_i(1) := io.imd_val_d1_ex_i
    dut.priv_mode_i := io.priv_mode_i
    dut.csr_mstatus_tw_i := io.csr_mstatus_tw_i
    dut.illegal_csr_insn_i := io.illegal_csr_insn_i
    dut.data_ind_timing_i := io.data_ind_timing_i
    dut.lsu_req_done_i := io.lsu_req_done_i
    dut.lsu_addr_incr_req_i := io.lsu_addr_incr_req_i
    dut.lsu_addr_last_i := io.lsu_addr_last_i
    dut.csr_mstatus_mie_i := io.csr_mstatus_mie_i
    dut.irq_pending_i := io.irq_pending_i
    dut.irqs_i.irq_software := io.irq_software_i
    dut.irqs_i.irq_timer := io.irq_timer_i
    dut.irqs_i.irq_external := io.irq_external_i
    dut.irqs_i.irq_fast := io.irq_fast_i
    dut.irq_nm_i := io.irq_nm_i
    dut.lsu_load_err_i := io.lsu_load_err_i
    dut.lsu_load_resp_intg_err_i := io.lsu_load_resp_intg_err_i
    dut.lsu_store_err_i := io.lsu_store_err_i
    dut.lsu_store_resp_intg_err_i := io.lsu_store_resp_intg_err_i
    dut.debug_req_i := io.debug_req_i
    dut.debug_single_step_i := io.debug_single_step_i
    dut.debug_ebreakm_i := io.debug_ebreakm_i
    dut.debug_ebreaku_i := io.debug_ebreaku_i
    dut.trigger_match_i := io.trigger_match_i
    dut.result_ex_i := io.result_ex_i
    dut.csr_rdata_i := io.csr_rdata_i
    dut.rf_rdata_a_i := io.rf_rdata_a_i
    dut.rf_rdata_b_i := io.rf_rdata_b_i
    dut.rf_waddr_wb_i := io.rf_waddr_wb_i
    dut.rf_wdata_fwd_wb_i := io.rf_wdata_fwd_wb_i
    dut.rf_write_wb_i := io.rf_write_wb_i
    dut.ready_wb_i := io.ready_wb_i
    dut.outstanding_load_wb_i := io.outstanding_load_wb_i
    dut.outstanding_store_wb_i := io.outstanding_store_wb_i

    io.ctrl_busy_o := dut.ctrl_busy_o
    io.illegal_insn_o := dut.illegal_insn_o
    io.instr_req_o := dut.instr_req_o
    io.instr_first_cycle_id_o := dut.instr_first_cycle_id_o
    io.instr_valid_clear_o := dut.instr_valid_clear_o
    io.id_in_ready_o := dut.id_in_ready_o
    io.icache_inval_o := dut.icache_inval_o
    io.pc_set_o := dut.pc_set_o
    io.pc_mux_o := dut.pc_mux_o
    io.nt_branch_mispredict_o := dut.nt_branch_mispredict_o
    io.nt_branch_addr_o := dut.nt_branch_addr_o
    io.exc_pc_mux_o := dut.exc_pc_mux_o
    io.exc_cause_o := dut.exc_cause_o
    io.alu_operator_ex_o := dut.alu_operator_ex_o
    io.alu_operand_a_ex_o := dut.alu_operand_a_ex_o
    io.alu_operand_b_ex_o := dut.alu_operand_b_ex_o
    io.imd_val_q0_ex_o := dut.imd_val_q_ex_o(0)
    io.imd_val_q1_ex_o := dut.imd_val_q_ex_o(1)
    io.mult_en_ex_o := dut.mult_en_ex_o
    io.div_en_ex_o := dut.div_en_ex_o
    io.mult_sel_ex_o := dut.mult_sel_ex_o
    io.div_sel_ex_o := dut.div_sel_ex_o
    io.multdiv_operator_ex_o := dut.multdiv_operator_ex_o
    io.multdiv_signed_mode_ex_o := dut.multdiv_signed_mode_ex_o
    io.multdiv_operand_a_ex_o := dut.multdiv_operand_a_ex_o
    io.multdiv_operand_b_ex_o := dut.multdiv_operand_b_ex_o
    io.multdiv_ready_id_o := dut.multdiv_ready_id_o
    io.csr_access_o := dut.csr_access_o
    io.csr_op_o := dut.csr_op_o
    io.csr_addr_o := dut.csr_addr_o
    io.csr_op_en_o := dut.csr_op_en_o
    io.csr_save_if_o := dut.csr_save_if_o
    io.csr_save_id_o := dut.csr_save_id_o
    io.csr_save_wb_o := dut.csr_save_wb_o
    io.csr_restore_mret_id_o := dut.csr_restore_mret_id_o
    io.csr_restore_dret_id_o := dut.csr_restore_dret_id_o
    io.csr_save_cause_o := dut.csr_save_cause_o
    io.csr_mtval_o := dut.csr_mtval_o
    io.lsu_req_o := dut.lsu_req_o
    io.lsu_we_o := dut.lsu_we_o
    io.lsu_type_o := dut.lsu_type_o
    io.lsu_sign_ext_o := dut.lsu_sign_ext_o
    io.lsu_wdata_o := dut.lsu_wdata_o
    io.expecting_load_resp_o := dut.expecting_load_resp_o
    io.expecting_store_resp_o := dut.expecting_store_resp_o
    io.debug_mode_o := dut.debug_mode_o
    io.debug_mode_entering_o := dut.debug_mode_entering_o
    io.debug_cause_o := dut.debug_cause_o
    io.debug_csr_save_o := dut.debug_csr_save_o
    io.rf_raddr_a_o := dut.rf_raddr_a_o
    io.rf_raddr_b_o := dut.rf_raddr_b_o
    io.rf_ren_a_o := dut.rf_ren_a_o
    io.rf_ren_b_o := dut.rf_ren_b_o
    io.rf_waddr_id_o := dut.rf_waddr_id_o
    io.rf_wdata_id_o := dut.rf_wdata_id_o
    io.rf_we_id_o := dut.rf_we_id_o
    io.rf_rd_a_wb_match_o := dut.rf_rd_a_wb_match_o
    io.rf_rd_b_wb_match_o := dut.rf_rd_b_wb_match_o
    io.en_wb_o := dut.en_wb_o
    io.instr_type_wb_o := dut.instr_type_wb_o
    io.instr_perf_count_id_o := dut.instr_perf_count_id_o
    io.perf_jump_o := dut.perf_jump_o
    io.perf_branch_o := dut.perf_branch_o
    io.perf_tbranch_o := dut.perf_tbranch_o
    io.perf_dside_wait_o := dut.perf_dside_wait_o
    io.perf_mul_wait_o := dut.perf_mul_wait_o
    io.perf_div_wait_o := dut.perf_div_wait_o
    io.instr_id_done_o := dut.instr_id_done_o
  }

  private object Op {
    val Write = 1
  }

  private def alu(name: String): UInt = IbexPkg.AluOp.encoding(name).U

  private def init(dut: Harness): Unit = {
    dut.io.instr_valid_i.poke(false.B)
    dut.io.instr_rdata_i.poke(0.U)
    dut.io.instr_rdata_alu_i.poke(0.U)
    dut.io.instr_rdata_c_i.poke(0.U)
    dut.io.instr_is_compressed_i.poke(false.B)
    dut.io.instr_bp_taken_i.poke(false.B)
    dut.io.instr_exec_i.poke(true.B)
    dut.io.branch_decision_i.poke(false.B)
    dut.io.illegal_c_insn_i.poke(false.B)
    dut.io.instr_fetch_err_i.poke(false.B)
    dut.io.instr_fetch_err_plus2_i.poke(false.B)
    dut.io.pc_id_i.poke("h00001000".U)
    dut.io.ex_valid_i.poke(true.B)
    dut.io.lsu_resp_valid_i.poke(true.B)
    dut.io.imd_val_we_ex_i.poke(0.U)
    dut.io.imd_val_d0_ex_i.poke(0.U)
    dut.io.imd_val_d1_ex_i.poke(0.U)
    dut.io.priv_mode_i.poke(IbexPkg.PrivLvl.M)
    dut.io.csr_mstatus_tw_i.poke(false.B)
    dut.io.illegal_csr_insn_i.poke(false.B)
    dut.io.data_ind_timing_i.poke(false.B)
    dut.io.lsu_req_done_i.poke(true.B)
    dut.io.lsu_addr_incr_req_i.poke(false.B)
    dut.io.lsu_addr_last_i.poke(0.U)
    dut.io.csr_mstatus_mie_i.poke(false.B)
    dut.io.irq_pending_i.poke(false.B)
    dut.io.irq_software_i.poke(false.B)
    dut.io.irq_timer_i.poke(false.B)
    dut.io.irq_external_i.poke(false.B)
    dut.io.irq_fast_i.poke(0.U)
    dut.io.irq_nm_i.poke(false.B)
    dut.io.lsu_load_err_i.poke(false.B)
    dut.io.lsu_load_resp_intg_err_i.poke(false.B)
    dut.io.lsu_store_err_i.poke(false.B)
    dut.io.lsu_store_resp_intg_err_i.poke(false.B)
    dut.io.debug_req_i.poke(false.B)
    dut.io.debug_single_step_i.poke(false.B)
    dut.io.debug_ebreakm_i.poke(false.B)
    dut.io.debug_ebreaku_i.poke(false.B)
    dut.io.trigger_match_i.poke(false.B)
    dut.io.result_ex_i.poke(0.U)
    dut.io.csr_rdata_i.poke(0.U)
    dut.io.rf_rdata_a_i.poke(0.U)
    dut.io.rf_rdata_b_i.poke(0.U)
    dut.io.rf_waddr_wb_i.poke(0.U)
    dut.io.rf_wdata_fwd_wb_i.poke(0.U)
    dut.io.rf_write_wb_i.poke(false.B)
    dut.io.ready_wb_i.poke(true.B)
    dut.io.outstanding_load_wb_i.poke(false.B)
    dut.io.outstanding_store_wb_i.poke(false.B)
  }

  private def resetAndDecode(dut: Harness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    init(dut)
    dut.clock.step(3)
  }

  "IbexIdStage" - {
    "boots through the controller into decode" in {
      simulate(new Harness) { dut =>
        resetAndDecode(dut)
        dut.io.instr_req_o.expect(true.B)
        dut.io.id_in_ready_o.expect(true.B)
        dut.io.pc_set_o.expect(false.B)
      }
    }

    "decodes an ALU register instruction and drives register writeback" in {
      simulate(new Harness) { dut =>
        resetAndDecode(dut)
        dut.io.instr_valid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h002082b3".U) // add x5, x1, x2
        dut.io.instr_rdata_alu_i.poke("h002082b3".U)
        dut.io.rf_rdata_a_i.poke(7.U)
        dut.io.rf_rdata_b_i.poke(9.U)
        dut.io.result_ex_i.poke(16.U)

        dut.io.illegal_insn_o.expect(false.B)
        dut.io.rf_raddr_a_o.expect(1.U)
        dut.io.rf_raddr_b_o.expect(2.U)
        dut.io.rf_waddr_id_o.expect(5.U)
        dut.io.rf_ren_a_o.expect(true.B)
        dut.io.rf_ren_b_o.expect(true.B)
        dut.io.alu_operator_ex_o.expect(alu("ALU_ADD"))
        dut.io.alu_operand_a_ex_o.expect(7.U)
        dut.io.alu_operand_b_ex_o.expect(9.U)
        dut.io.rf_we_id_o.expect(true.B)
        dut.io.rf_wdata_id_o.expect(16.U)
        dut.io.en_wb_o.expect(true.B)
      }
    }

    "enables CSR operations only when the instruction completes" in {
      simulate(new Harness) { dut =>
        resetAndDecode(dut)
        dut.io.instr_valid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h300092f3".U) // csrrw x5, mstatus, x1
        dut.io.instr_rdata_alu_i.poke("h300092f3".U)
        dut.io.rf_rdata_a_i.poke("h000000aa".U)
        dut.io.csr_rdata_i.poke("h00001800".U)

        dut.io.csr_access_o.expect(true.B)
        dut.io.csr_addr_o.expect(IbexPkg.CsrNum.MSTATUS.U)
        dut.io.csr_op_o.expect(Op.Write.U)
        dut.io.csr_op_en_o.expect(true.B)
        dut.io.rf_we_id_o.expect(true.B)
        dut.io.rf_wdata_id_o.expect("h00001800".U)

        dut.io.illegal_csr_insn_i.poke(true.B)
        dut.io.illegal_insn_o.expect(true.B)
        dut.io.csr_op_en_o.expect(true.B)
        dut.io.rf_we_id_o.expect(false.B)
      }
    }

    "issues load/store requests and tracks two-stage response waits" in {
      simulate(new Harness) { dut =>
        resetAndDecode(dut)
        dut.io.instr_valid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h0040a283".U) // lw x5, 4(x1)
        dut.io.instr_rdata_alu_i.poke("h0040a283".U)
        dut.io.rf_rdata_a_i.poke("h00001000".U)
        dut.io.lsu_resp_valid_i.poke(false.B)

        dut.io.lsu_req_o.expect(true.B)
        dut.io.lsu_we_o.expect(false.B)
        dut.io.lsu_type_o.expect(0.U)
        dut.io.lsu_sign_ext_o.expect(true.B)
        dut.io.perf_dside_wait_o.expect(true.B)
        dut.io.en_wb_o.expect(false.B)

        dut.clock.step()
        dut.io.expecting_load_resp_o.expect(true.B)
        dut.io.lsu_req_o.expect(false.B)

        dut.io.lsu_resp_valid_i.poke(true.B)
        dut.io.en_wb_o.expect(true.B)
      }
    }
  }
}
