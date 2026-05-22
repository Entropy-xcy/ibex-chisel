package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexControllerSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(writebackStage: Boolean = false, branchPredictor: Boolean = false, memECC: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val illegal_insn_i = Input(Bool())
      val ecall_insn_i = Input(Bool())
      val mret_insn_i = Input(Bool())
      val dret_insn_i = Input(Bool())
      val wfi_insn_i = Input(Bool())
      val ebrk_insn_i = Input(Bool())
      val csr_pipe_flush_i = Input(Bool())
      val instr_valid_i = Input(Bool())
      val instr_i = Input(UInt(32.W))
      val instr_compressed_i = Input(UInt(16.W))
      val instr_is_compressed_i = Input(Bool())
      val instr_bp_taken_i = Input(Bool())
      val instr_fetch_err_i = Input(Bool())
      val instr_fetch_err_plus2_i = Input(Bool())
      val pc_id_i = Input(UInt(32.W))
      val instr_exec_i = Input(Bool())
      val lsu_addr_last_i = Input(UInt(32.W))
      val load_err_i = Input(Bool())
      val store_err_i = Input(Bool())
      val mem_resp_intg_err_i = Input(Bool())
      val branch_set_i = Input(Bool())
      val branch_not_set_i = Input(Bool())
      val jump_set_i = Input(Bool())
      val csr_mstatus_mie_i = Input(Bool())
      val irq_pending_i = Input(Bool())
      val irq_software_i = Input(Bool())
      val irq_timer_i = Input(Bool())
      val irq_external_i = Input(Bool())
      val irq_fast_i = Input(UInt(15.W))
      val irq_nm_ext_i = Input(Bool())
      val debug_req_i = Input(Bool())
      val debug_single_step_i = Input(Bool())
      val debug_ebreakm_i = Input(Bool())
      val debug_ebreaku_i = Input(Bool())
      val trigger_match_i = Input(Bool())
      val priv_mode_i = Input(UInt(2.W))
      val stall_id_i = Input(Bool())
      val stall_wb_i = Input(Bool())
      val ready_wb_i = Input(Bool())

      val ctrl_busy_o = Output(Bool())
      val instr_valid_clear_o = Output(Bool())
      val id_in_ready_o = Output(Bool())
      val controller_run_o = Output(Bool())
      val rvfi_flush_next_o = Output(Bool())
      val instr_req_o = Output(Bool())
      val pc_set_o = Output(Bool())
      val pc_mux_o = Output(UInt(3.W))
      val nt_branch_mispredict_o = Output(Bool())
      val exc_pc_mux_o = Output(UInt(2.W))
      val exc_cause_o = Output(UInt(7.W))
      val wb_exception_o = Output(Bool())
      val id_exception_o = Output(Bool())
      val exc_req_lsu_o = Output(Bool())
      val nmi_mode_o = Output(Bool())
      val irq_nm_int_o = Output(Bool())
      val debug_cause_o = Output(UInt(3.W))
      val debug_csr_save_o = Output(Bool())
      val debug_mode_o = Output(Bool())
      val debug_mode_entering_o = Output(Bool())
      val ebreak_into_debug_o = Output(Bool())
      val csr_save_if_o = Output(Bool())
      val csr_save_id_o = Output(Bool())
      val csr_save_wb_o = Output(Bool())
      val csr_restore_mret_id_o = Output(Bool())
      val csr_restore_dret_id_o = Output(Bool())
      val csr_save_cause_o = Output(Bool())
      val csr_mtval_o = Output(UInt(32.W))
      val flush_id_o = Output(Bool())
      val perf_jump_o = Output(Bool())
      val perf_tbranch_o = Output(Bool())
    })

    val dut = Module(new IbexController(writebackStage = writebackStage, branchPredictor = branchPredictor, memECC = memECC))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.illegal_insn_i := io.illegal_insn_i
    dut.ecall_insn_i := io.ecall_insn_i
    dut.mret_insn_i := io.mret_insn_i
    dut.dret_insn_i := io.dret_insn_i
    dut.wfi_insn_i := io.wfi_insn_i
    dut.ebrk_insn_i := io.ebrk_insn_i
    dut.csr_pipe_flush_i := io.csr_pipe_flush_i
    dut.instr_valid_i := io.instr_valid_i
    dut.instr_i := io.instr_i
    dut.instr_compressed_i := io.instr_compressed_i
    dut.instr_is_compressed_i := io.instr_is_compressed_i
    dut.instr_bp_taken_i := io.instr_bp_taken_i
    dut.instr_fetch_err_i := io.instr_fetch_err_i
    dut.instr_fetch_err_plus2_i := io.instr_fetch_err_plus2_i
    dut.pc_id_i := io.pc_id_i
    dut.instr_exec_i := io.instr_exec_i
    dut.lsu_addr_last_i := io.lsu_addr_last_i
    dut.load_err_i := io.load_err_i
    dut.store_err_i := io.store_err_i
    dut.mem_resp_intg_err_i := io.mem_resp_intg_err_i
    dut.branch_set_i := io.branch_set_i
    dut.branch_not_set_i := io.branch_not_set_i
    dut.jump_set_i := io.jump_set_i
    dut.csr_mstatus_mie_i := io.csr_mstatus_mie_i
    dut.irq_pending_i := io.irq_pending_i
    dut.irqs_i.irq_software := io.irq_software_i
    dut.irqs_i.irq_timer := io.irq_timer_i
    dut.irqs_i.irq_external := io.irq_external_i
    dut.irqs_i.irq_fast := io.irq_fast_i
    dut.irq_nm_ext_i := io.irq_nm_ext_i
    dut.debug_req_i := io.debug_req_i
    dut.debug_single_step_i := io.debug_single_step_i
    dut.debug_ebreakm_i := io.debug_ebreakm_i
    dut.debug_ebreaku_i := io.debug_ebreaku_i
    dut.trigger_match_i := io.trigger_match_i
    dut.priv_mode_i := io.priv_mode_i
    dut.stall_id_i := io.stall_id_i
    dut.stall_wb_i := io.stall_wb_i
    dut.ready_wb_i := io.ready_wb_i

    io.ctrl_busy_o := dut.ctrl_busy_o
    io.instr_valid_clear_o := dut.instr_valid_clear_o
    io.id_in_ready_o := dut.id_in_ready_o
    io.controller_run_o := dut.controller_run_o
    io.rvfi_flush_next_o := dut.rvfi_flush_next_o
    io.instr_req_o := dut.instr_req_o
    io.pc_set_o := dut.pc_set_o
    io.pc_mux_o := dut.pc_mux_o
    io.nt_branch_mispredict_o := dut.nt_branch_mispredict_o
    io.exc_pc_mux_o := dut.exc_pc_mux_o
    io.exc_cause_o := dut.exc_cause_o
    io.wb_exception_o := dut.wb_exception_o
    io.id_exception_o := dut.id_exception_o
    io.exc_req_lsu_o := dut.exc_req_lsu_o
    io.nmi_mode_o := dut.nmi_mode_o
    io.irq_nm_int_o := dut.irq_nm_int_o
    io.debug_cause_o := dut.debug_cause_o
    io.debug_csr_save_o := dut.debug_csr_save_o
    io.debug_mode_o := dut.debug_mode_o
    io.debug_mode_entering_o := dut.debug_mode_entering_o
    io.ebreak_into_debug_o := dut.ebreak_into_debug_o
    io.csr_save_if_o := dut.csr_save_if_o
    io.csr_save_id_o := dut.csr_save_id_o
    io.csr_save_wb_o := dut.csr_save_wb_o
    io.csr_restore_mret_id_o := dut.csr_restore_mret_id_o
    io.csr_restore_dret_id_o := dut.csr_restore_dret_id_o
    io.csr_save_cause_o := dut.csr_save_cause_o
    io.csr_mtval_o := dut.csr_mtval_o
    io.flush_id_o := dut.flush_id_o
    io.perf_jump_o := dut.perf_jump_o
    io.perf_tbranch_o := dut.perf_tbranch_o
  }

  private object PcSel {
    val Boot = 0
    val Jump = 1
    val Exc = 2
    val Eret = 3
    val Dret = 4
  }

  private object ExcPcSel {
    val Exc = 0
    val Irq = 1
    val Dbd = 2
  }

  private def excCause(irqExt: Boolean, irqInt: Boolean, lowerCause: Int): Int =
    (if (irqExt) 0x40 else 0) | (if (irqInt) 0x20 else 0) | lowerCause

  private def init(dut: Harness): Unit = {
    dut.io.illegal_insn_i.poke(false.B)
    dut.io.ecall_insn_i.poke(false.B)
    dut.io.mret_insn_i.poke(false.B)
    dut.io.dret_insn_i.poke(false.B)
    dut.io.wfi_insn_i.poke(false.B)
    dut.io.ebrk_insn_i.poke(false.B)
    dut.io.csr_pipe_flush_i.poke(false.B)
    dut.io.instr_valid_i.poke(false.B)
    dut.io.instr_i.poke(0.U)
    dut.io.instr_compressed_i.poke(0.U)
    dut.io.instr_is_compressed_i.poke(false.B)
    dut.io.instr_bp_taken_i.poke(false.B)
    dut.io.instr_fetch_err_i.poke(false.B)
    dut.io.instr_fetch_err_plus2_i.poke(false.B)
    dut.io.pc_id_i.poke("h00001000".U)
    dut.io.instr_exec_i.poke(true.B)
    dut.io.lsu_addr_last_i.poke(0.U)
    dut.io.load_err_i.poke(false.B)
    dut.io.store_err_i.poke(false.B)
    dut.io.mem_resp_intg_err_i.poke(false.B)
    dut.io.branch_set_i.poke(false.B)
    dut.io.branch_not_set_i.poke(false.B)
    dut.io.jump_set_i.poke(false.B)
    dut.io.csr_mstatus_mie_i.poke(false.B)
    dut.io.irq_pending_i.poke(false.B)
    dut.io.irq_software_i.poke(false.B)
    dut.io.irq_timer_i.poke(false.B)
    dut.io.irq_external_i.poke(false.B)
    dut.io.irq_fast_i.poke(0.U)
    dut.io.irq_nm_ext_i.poke(false.B)
    dut.io.debug_req_i.poke(false.B)
    dut.io.debug_single_step_i.poke(false.B)
    dut.io.debug_ebreakm_i.poke(false.B)
    dut.io.debug_ebreaku_i.poke(false.B)
    dut.io.trigger_match_i.poke(false.B)
    dut.io.priv_mode_i.poke("b11".U)
    dut.io.stall_id_i.poke(false.B)
    dut.io.stall_wb_i.poke(false.B)
    dut.io.ready_wb_i.poke(true.B)
  }

  private def bootToDecode(dut: Harness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    init(dut)
    dut.io.pc_set_o.expect(true.B)
    dut.io.pc_mux_o.expect(PcSel.Boot.U)
    dut.clock.step()
    dut.io.pc_set_o.expect(true.B)
    dut.io.pc_mux_o.expect(PcSel.Boot.U)
    dut.clock.step()
    dut.io.id_in_ready_o.expect(true.B)
    dut.clock.step()
    dut.io.controller_run_o.expect(true.B)
  }

  "IbexController" - {
    "boots through reset, boot-set, first-fetch, and decode" in {
      simulate(new Harness) { dut =>
        bootToDecode(dut)
        dut.io.instr_req_o.expect(true.B)
        dut.io.id_in_ready_o.expect(true.B)
        dut.io.instr_valid_clear_o.expect(true.B)
      }
    }

    "sets PC and performance pulses for jumps and branches" in {
      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.branch_set_i.poke(true.B)
        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Jump.U)
        dut.io.perf_tbranch_o.expect(true.B)
        dut.io.perf_jump_o.expect(false.B)

        dut.io.branch_set_i.poke(false.B)
        dut.io.jump_set_i.poke(true.B)
        dut.io.pc_set_o.expect(true.B)
        dut.io.perf_jump_o.expect(true.B)
      }
    }

    "reports a not-taken branch predictor mispredict when enabled" in {
      simulate(new Harness(branchPredictor = true)) { dut =>
        bootToDecode(dut)

        dut.io.instr_bp_taken_i.poke(true.B)
        dut.io.branch_not_set_i.poke(true.B)
        dut.io.nt_branch_mispredict_o.expect(true.B)
        dut.io.instr_valid_clear_o.expect(true.B)
      }
    }

    "takes illegal instruction exceptions through FLUSH" in {
      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.instr_valid_i.poke(true.B)
        dut.io.illegal_insn_i.poke(true.B)
        dut.io.instr_i.poke("hffffffff".U)
        dut.io.id_exception_o.expect(true.B)
        dut.io.rvfi_flush_next_o.expect(true.B)
        dut.clock.step()

        dut.io.illegal_insn_i.poke(false.B)
        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Exc.U)
        dut.io.exc_pc_mux_o.expect(ExcPcSel.Exc.U)
        dut.io.csr_save_cause_o.expect(true.B)
        dut.io.exc_cause_o.expect(excCause(irqExt = false, irqInt = false, lowerCause = 2).U)
        dut.io.csr_mtval_o.expect("hffffffff".U)
        dut.io.flush_id_o.expect(true.B)
      }
    }

    "exposes RVFI exception helper signals" in {
      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.instr_valid_i.poke(true.B)
        dut.io.load_err_i.poke(true.B)
        dut.io.exc_req_lsu_o.expect(true.B)
      }

      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.debug_ebreakm_i.poke(true.B)
        dut.io.ebreak_into_debug_o.expect(true.B)
      }
    }

    "enters IRQ and debug handlers from first fetch/decode paths" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)
        dut.clock.step()
        dut.io.irq_pending_i.poke(true.B)
        dut.io.irq_external_i.poke(true.B)
        dut.io.csr_mstatus_mie_i.poke(true.B)
        dut.clock.step()
        dut.clock.step()
        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Exc.U)
        dut.io.exc_pc_mux_o.expect(ExcPcSel.Irq.U)
        dut.io.csr_save_if_o.expect(true.B)
        dut.io.csr_save_cause_o.expect(true.B)
        dut.io.exc_cause_o.expect(excCause(irqExt = true, irqInt = false, lowerCause = 11).U)
      }

      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.debug_req_i.poke(true.B)
        dut.clock.step()
        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Exc.U)
        dut.io.exc_pc_mux_o.expect(ExcPcSel.Dbd.U)
        dut.io.debug_csr_save_o.expect(true.B)
        dut.io.debug_mode_entering_o.expect(true.B)
        dut.io.flush_id_o.expect(true.B)
      }
    }

    "converts a memory response integrity error into an internal NMI" in {
      simulate(new Harness(memECC = true)) { dut =>
        bootToDecode(dut)

        dut.io.lsu_addr_last_i.poke("h20000004".U)
        dut.io.mem_resp_intg_err_i.poke(true.B)
        dut.clock.step()

        dut.io.mem_resp_intg_err_i.poke(false.B)
        dut.io.irq_nm_int_o.expect(true.B)
        dut.clock.step()

        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Exc.U)
        dut.io.exc_pc_mux_o.expect(ExcPcSel.Irq.U)
        dut.io.csr_save_if_o.expect(true.B)
        dut.io.csr_save_cause_o.expect(true.B)
        dut.io.exc_cause_o.expect(excCause(irqExt = false, irqInt = true, lowerCause = 0).U)
        dut.io.csr_mtval_o.expect("h20000004".U)
      }
    }

    "prioritizes fast interrupts by the lowest set fast IRQ ID" in {
      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.irq_pending_i.poke(true.B)
        dut.io.csr_mstatus_mie_i.poke(true.B)
        dut.io.irq_external_i.poke(true.B)
        dut.io.irq_software_i.poke(true.B)
        dut.io.irq_timer_i.poke(true.B)
        dut.io.irq_fast_i.poke(((1 << 12) | (1 << 3)).U)
        dut.clock.step()

        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Exc.U)
        dut.io.exc_pc_mux_o.expect(ExcPcSel.Irq.U)
        dut.io.csr_save_if_o.expect(true.B)
        dut.io.csr_save_cause_o.expect(true.B)
        dut.io.exc_cause_o.expect(excCause(irqExt = true, irqInt = false, lowerCause = 16 + 3).U)
      }
    }

    "handles WFI sleep and MRET/DRET restore paths" in {
      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.instr_valid_i.poke(true.B)
        dut.io.wfi_insn_i.poke(true.B)
        dut.clock.step()
        dut.io.flush_id_o.expect(true.B)
        dut.clock.step()
        dut.io.wfi_insn_i.poke(false.B)
        dut.io.ctrl_busy_o.expect(false.B)
        dut.io.instr_req_o.expect(false.B)
      }

      simulate(new Harness) { dut =>
        bootToDecode(dut)

        dut.io.instr_valid_i.poke(true.B)
        dut.io.mret_insn_i.poke(true.B)
        dut.clock.step()
        dut.io.pc_set_o.expect(true.B)
        dut.io.pc_mux_o.expect(PcSel.Eret.U)
        dut.io.csr_restore_mret_id_o.expect(true.B)
        dut.io.mret_insn_i.poke(false.B)
      }
    }
  }
}
