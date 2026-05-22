// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexController(writebackStage: Boolean = false, branchPredictor: Boolean = false, memECC: Boolean = false)
    extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val ctrl_busy_o = IO(Output(Bool()))

  val illegal_insn_i = IO(Input(Bool()))
  val ecall_insn_i = IO(Input(Bool()))
  val mret_insn_i = IO(Input(Bool()))
  val dret_insn_i = IO(Input(Bool()))
  val wfi_insn_i = IO(Input(Bool()))
  val ebrk_insn_i = IO(Input(Bool()))
  val csr_pipe_flush_i = IO(Input(Bool()))

  val instr_valid_i = IO(Input(Bool()))
  val instr_i = IO(Input(UInt(32.W)))
  val instr_compressed_i = IO(Input(UInt(16.W)))
  val instr_is_compressed_i = IO(Input(Bool()))
  val instr_bp_taken_i = IO(Input(Bool()))
  val instr_fetch_err_i = IO(Input(Bool()))
  val instr_fetch_err_plus2_i = IO(Input(Bool()))
  val pc_id_i = IO(Input(UInt(32.W)))

  val instr_valid_clear_o = IO(Output(Bool()))
  val id_in_ready_o = IO(Output(Bool()))
  val controller_run_o = IO(Output(Bool()))
  val rvfi_flush_next_o = IO(Output(Bool()))
  val instr_exec_i = IO(Input(Bool()))

  val instr_req_o = IO(Output(Bool()))
  val pc_set_o = IO(Output(Bool()))
  val pc_mux_o = IO(Output(UInt(3.W)))
  val nt_branch_mispredict_o = IO(Output(Bool()))
  val exc_pc_mux_o = IO(Output(UInt(2.W)))
  val exc_cause_o = IO(Output(UInt(7.W)))

  val lsu_addr_last_i = IO(Input(UInt(32.W)))
  val load_err_i = IO(Input(Bool()))
  val store_err_i = IO(Input(Bool()))
  val mem_resp_intg_err_i = IO(Input(Bool()))
  val wb_exception_o = IO(Output(Bool()))
  val id_exception_o = IO(Output(Bool()))
  val exc_req_lsu_o = IO(Output(Bool()))

  val branch_set_i = IO(Input(Bool()))
  val branch_not_set_i = IO(Input(Bool()))
  val jump_set_i = IO(Input(Bool()))

  val csr_mstatus_mie_i = IO(Input(Bool()))
  val irq_pending_i = IO(Input(Bool()))
  val irqs_i = IO(Input(new IbexPkg.Irqs))
  val irq_nm_ext_i = IO(Input(Bool()))
  val irq_nm_int_o = IO(Output(Bool()))
  val nmi_mode_o = IO(Output(Bool()))

  val debug_req_i = IO(Input(Bool()))
  val debug_cause_o = IO(Output(UInt(3.W)))
  val debug_csr_save_o = IO(Output(Bool()))
  val debug_mode_o = IO(Output(Bool()))
  val debug_mode_entering_o = IO(Output(Bool()))
  val ebreak_into_debug_o = IO(Output(Bool()))
  val debug_single_step_i = IO(Input(Bool()))
  val debug_ebreakm_i = IO(Input(Bool()))
  val debug_ebreaku_i = IO(Input(Bool()))
  val trigger_match_i = IO(Input(Bool()))

  val csr_save_if_o = IO(Output(Bool()))
  val csr_save_id_o = IO(Output(Bool()))
  val csr_save_wb_o = IO(Output(Bool()))
  val csr_restore_mret_id_o = IO(Output(Bool()))
  val csr_restore_dret_id_o = IO(Output(Bool()))
  val csr_save_cause_o = IO(Output(Bool()))
  val csr_mtval_o = IO(Output(UInt(32.W)))
  val priv_mode_i = IO(Input(UInt(2.W)))

  val stall_id_i = IO(Input(Bool()))
  val stall_wb_i = IO(Input(Bool()))
  val flush_id_o = IO(Output(Bool()))
  val ready_wb_i = IO(Input(Bool()))

  val perf_jump_o = IO(Output(Bool()))
  val perf_tbranch_o = IO(Output(Bool()))

  private object CtrlFsm {
    val RESET = 0.U(4.W)
    val BOOT_SET = 1.U(4.W)
    val WAIT_SLEEP = 2.U(4.W)
    val SLEEP = 3.U(4.W)
    val FIRST_FETCH = 4.U(4.W)
    val DECODE = 5.U(4.W)
    val FLUSH = 6.U(4.W)
    val IRQ_TAKEN = 7.U(4.W)
    val DBG_TAKEN_IF = 8.U(4.W)
    val DBG_TAKEN_ID = 9.U(4.W)
  }

  private object PcSel {
    val BOOT = 0.U(3.W)
    val JUMP = 1.U(3.W)
    val EXC = 2.U(3.W)
    val ERET = 3.U(3.W)
    val DRET = 4.U(3.W)
    val BP = 5.U(3.W)
  }

  private object ExcPcSel {
    val EXC = 0.U(2.W)
    val IRQ = 1.U(2.W)
    val DBD = 2.U(2.W)
    val DBG_EXC = 3.U(2.W)
  }

  private object PrivLvl {
    val M = "b11".U(2.W)
    val U = "b00".U(2.W)
  }

  private object ExcCause {
    private def apply(irqExt: Boolean, irqInt: Boolean, lowerCause: Int): UInt =
      Cat(irqExt.B, irqInt.B, lowerCause.U(5.W))

    val IrqSoftwareM = apply(irqExt = true, irqInt = false, 3)
    val IrqTimerM = apply(irqExt = true, irqInt = false, 7)
    val IrqExternalM = apply(irqExt = true, irqInt = false, 11)
    val IrqNm = apply(irqExt = true, irqInt = false, 31)
    val InsnAddrMisa = apply(irqExt = false, irqInt = false, 0)
    val InstrAccessFault = apply(irqExt = false, irqInt = false, 1)
    val IllegalInsn = apply(irqExt = false, irqInt = false, 2)
    val Breakpoint = apply(irqExt = false, irqInt = false, 3)
    val LoadAccessFault = apply(irqExt = false, irqInt = false, 5)
    val StoreAccessFault = apply(irqExt = false, irqInt = false, 7)
    val EcallUMode = apply(irqExt = false, irqInt = false, 8)
    val EcallMMode = apply(irqExt = false, irqInt = false, 11)
  }

  private object DbgCause {
    val NONE = "h0".U(3.W)
    val EBREAK = "h1".U(3.W)
    val TRIGGER = "h2".U(3.W)
    val HALTREQ = "h3".U(3.W)
    val STEP = "h4".U(3.W)
  }

  val ctrl_fsm_cs = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(CtrlFsm.RESET) }
  val ctrl_fsm_ns = WireDefault(ctrl_fsm_cs)
  val nmi_mode_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val nmi_mode_d = WireDefault(nmi_mode_q)
  val debug_mode_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val debug_mode_d = WireDefault(debug_mode_q)
  val debug_cause_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(DbgCause.NONE) }
  val debug_cause_d = WireDefault(DbgCause.NONE)
  val load_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val store_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val exc_req_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val illegal_insn_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val do_single_step_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val enter_debug_mode_prio_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }

  val load_err_d = load_err_i
  val store_err_d = store_err_i
  val ecall_insn = ecall_insn_i && instr_valid_i
  val mret_insn = mret_insn_i && instr_valid_i
  val dret_insn = dret_insn_i && instr_valid_i
  val wfi_insn = wfi_insn_i && instr_valid_i
  val ebrk_insn = ebrk_insn_i && instr_valid_i
  val csr_pipe_flush = csr_pipe_flush_i && instr_valid_i
  val instr_fetch_err = instr_fetch_err_i && instr_valid_i
  val illegal_insn_d = illegal_insn_i && ctrl_fsm_cs =/= CtrlFsm.FLUSH
  val exc_req_d = (ecall_insn || ebrk_insn || illegal_insn_d || instr_fetch_err) && ctrl_fsm_cs =/= CtrlFsm.FLUSH
  val exc_req_lsu = store_err_i || load_err_i
  exc_req_lsu_o := exc_req_lsu
  id_exception_o := exc_req_d && !wb_exception_o

  val special_req_flush_only = wfi_insn || csr_pipe_flush
  val special_req_pc_change = mret_insn || dret_insn || exc_req_d || exc_req_lsu
  val special_req = special_req_pc_change || special_req_flush_only
  val id_wb_pending = instr_valid_i || !ready_wb_i

  val instr_fetch_err_prio = WireDefault(false.B)
  val illegal_insn_prio = WireDefault(false.B)
  val ecall_insn_prio = WireDefault(false.B)
  val ebrk_insn_prio = WireDefault(false.B)
  val store_err_prio = WireDefault(false.B)
  val load_err_prio = WireDefault(false.B)

  if (writebackStage) {
    when(store_err_q) {
      store_err_prio := true.B
    }.elsewhen(load_err_q) {
      load_err_prio := true.B
    }.elsewhen(instr_fetch_err) {
      instr_fetch_err_prio := true.B
    }.elsewhen(illegal_insn_q) {
      illegal_insn_prio := true.B
    }.elsewhen(ecall_insn) {
      ecall_insn_prio := true.B
    }.elsewhen(ebrk_insn) {
      ebrk_insn_prio := true.B
    }
    wb_exception_o := load_err_q || store_err_q || load_err_i || store_err_i
  } else {
    when(instr_fetch_err) {
      instr_fetch_err_prio := true.B
    }.elsewhen(illegal_insn_q) {
      illegal_insn_prio := true.B
    }.elsewhen(ecall_insn) {
      ecall_insn_prio := true.B
    }.elsewhen(ebrk_insn) {
      ebrk_insn_prio := true.B
    }.elsewhen(store_err_q) {
      store_err_prio := true.B
    }.elsewhen(load_err_q) {
      load_err_prio := true.B
    }
    wb_exception_o := false.B
  }

  val mem_resp_intg_err_irq_pending_q =
    if (memECC) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else false.B
  val mem_resp_intg_err_addr_q =
    if (memECC) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) } else 0.U(32.W)
  val mem_resp_intg_err_irq_pending_d = WireDefault(mem_resp_intg_err_irq_pending_q)
  val mem_resp_intg_err_addr_d = WireDefault(mem_resp_intg_err_addr_q)
  val irq_nm_int = WireDefault(false.B)
  val irq_nm_int_mtval = WireDefault(0.U(32.W))
  val irq_nm_int_cause = WireDefault(0.U(5.W))

  val entering_nmi = nmi_mode_d && !nmi_mode_q
  if (memECC) {
    when(mem_resp_intg_err_irq_pending_q) {
      when(entering_nmi && !irq_nm_ext_i) {
        mem_resp_intg_err_irq_pending_d := false.B
      }
    }.elsewhen(mem_resp_intg_err_i) {
      mem_resp_intg_err_irq_pending_d := true.B
      mem_resp_intg_err_addr_d := lsu_addr_last_i
    }
    irq_nm_int := mem_resp_intg_err_irq_pending_q
    irq_nm_int_mtval := mem_resp_intg_err_addr_q
  }
  irq_nm_int_o := irq_nm_int

  val do_single_step_d = Mux(instr_valid_i, !debug_mode_q && debug_single_step_i, do_single_step_q)
  val enter_debug_mode_prio_d = (debug_req_i || do_single_step_d) && !debug_mode_q
  val enter_debug_mode = enter_debug_mode_prio_d || (trigger_match_i && !debug_mode_q)
  val ebreak_into_debug = Mux(priv_mode_i === PrivLvl.M, debug_ebreakm_i, Mux(priv_mode_i === PrivLvl.U, debug_ebreaku_i, false.B))
  ebreak_into_debug_o := ebreak_into_debug
  val irq_nm = irq_nm_ext_i || irq_nm_int
  val irq_enabled = csr_mstatus_mie_i || priv_mode_i === PrivLvl.U
  val handle_irq = !debug_mode_q && !debug_single_step_i && !nmi_mode_q && (irq_nm || (irq_pending_i && irq_enabled))

  val mfip_id = WireDefault(0.U(4.W))
  for (i <- 14 to 0 by -1) {
    when(irqs_i.irq_fast(i)) {
      mfip_id := i.U
    }
  }

  debug_cause_d := MuxCase(DbgCause.NONE, Seq(
    trigger_match_i -> DbgCause.TRIGGER,
    (ebrk_insn_prio && ebreak_into_debug) -> DbgCause.EBREAK,
    debug_req_i -> DbgCause.HALTREQ,
    do_single_step_d -> DbgCause.STEP
  ))
  debug_cause_o := debug_cause_q

  instr_req_o := true.B
  csr_save_if_o := false.B
  csr_save_id_o := false.B
  csr_save_wb_o := false.B
  csr_restore_mret_id_o := false.B
  csr_restore_dret_id_o := false.B
  csr_save_cause_o := false.B
  csr_mtval_o := 0.U
  pc_mux_o := PcSel.BOOT
  pc_set_o := false.B
  nt_branch_mispredict_o := false.B
  exc_pc_mux_o := ExcPcSel.IRQ
  exc_cause_o := ExcCause.InsnAddrMisa
  ctrl_busy_o := true.B

  val halt_if = WireDefault(false.B)
  val retain_id = WireDefault(false.B)
  val flush_id = WireDefault(false.B)
  debug_csr_save_o := false.B
  debug_mode_entering_o := false.B
  perf_tbranch_o := false.B
  perf_jump_o := false.B
  controller_run_o := false.B
  rvfi_flush_next_o := ctrl_fsm_ns === CtrlFsm.FLUSH

  val stall = stall_id_i || stall_wb_i

  switch(ctrl_fsm_cs) {
    is(CtrlFsm.RESET) {
      instr_req_o := false.B
      pc_mux_o := PcSel.BOOT
      pc_set_o := true.B
      ctrl_fsm_ns := CtrlFsm.BOOT_SET
    }
    is(CtrlFsm.BOOT_SET) {
      instr_req_o := true.B
      pc_mux_o := PcSel.BOOT
      pc_set_o := true.B
      ctrl_fsm_ns := CtrlFsm.FIRST_FETCH
    }
    is(CtrlFsm.WAIT_SLEEP) {
      ctrl_busy_o := false.B
      instr_req_o := false.B
      halt_if := true.B
      flush_id := true.B
      ctrl_fsm_ns := CtrlFsm.SLEEP
    }
    is(CtrlFsm.SLEEP) {
      instr_req_o := false.B
      halt_if := true.B
      flush_id := true.B
      when(irq_nm || irq_pending_i || debug_req_i || debug_mode_q || debug_single_step_i) {
        ctrl_fsm_ns := CtrlFsm.FIRST_FETCH
      }.otherwise {
        ctrl_busy_o := false.B
      }
    }
    is(CtrlFsm.FIRST_FETCH) {
      when(id_in_ready_o) {
        ctrl_fsm_ns := CtrlFsm.DECODE
      }
      when(handle_irq) {
        ctrl_fsm_ns := CtrlFsm.IRQ_TAKEN
        halt_if := true.B
      }
      when(enter_debug_mode) {
        ctrl_fsm_ns := CtrlFsm.DBG_TAKEN_IF
        halt_if := true.B
      }
    }
    is(CtrlFsm.DECODE) {
      controller_run_o := true.B
      pc_mux_o := PcSel.JUMP
      when(special_req) {
        retain_id := true.B
        when(ready_wb_i || wb_exception_o) {
          ctrl_fsm_ns := CtrlFsm.FLUSH
        }
      }
      when(branch_set_i || jump_set_i) {
        pc_set_o := (if (branchPredictor) !instr_bp_taken_i else true.B)
        perf_tbranch_o := branch_set_i
        perf_jump_o := jump_set_i
      }
      if (branchPredictor) {
        when(instr_bp_taken_i && branch_not_set_i) {
          nt_branch_mispredict_o := true.B
        }
      }
      when((enter_debug_mode || handle_irq) && (stall || id_wb_pending)) {
        halt_if := true.B
      }
      when(!stall && !special_req && !id_wb_pending) {
        when(enter_debug_mode) {
          ctrl_fsm_ns := CtrlFsm.DBG_TAKEN_IF
          halt_if := true.B
        }.elsewhen(handle_irq) {
          ctrl_fsm_ns := CtrlFsm.IRQ_TAKEN
          halt_if := true.B
        }
      }
    }
    is(CtrlFsm.IRQ_TAKEN) {
      pc_mux_o := PcSel.EXC
      exc_pc_mux_o := ExcPcSel.IRQ
      when(handle_irq) {
        pc_set_o := true.B
        csr_save_if_o := true.B
        csr_save_cause_o := true.B
        when(irq_nm && !nmi_mode_q) {
          exc_cause_o := Mux(irq_nm_ext_i, ExcCause.IrqNm, Cat(0.U(1.W), 1.U(1.W), irq_nm_int_cause))
          when(irq_nm_int && !irq_nm_ext_i) {
            csr_mtval_o := irq_nm_int_mtval
          }
          nmi_mode_d := true.B
        }.elsewhen(irqs_i.irq_fast =/= 0.U) {
          exc_cause_o := Cat(1.U(1.W), 0.U(1.W), Cat(1.U(1.W), mfip_id))
        }.elsewhen(irqs_i.irq_external) {
          exc_cause_o := ExcCause.IrqExternalM
        }.elsewhen(irqs_i.irq_software) {
          exc_cause_o := ExcCause.IrqSoftwareM
        }.otherwise {
          exc_cause_o := ExcCause.IrqTimerM
        }
      }
      ctrl_fsm_ns := CtrlFsm.DECODE
    }
    is(CtrlFsm.DBG_TAKEN_IF) {
      pc_mux_o := PcSel.EXC
      exc_pc_mux_o := ExcPcSel.DBD
      flush_id := true.B
      pc_set_o := true.B
      csr_save_if_o := true.B
      debug_csr_save_o := true.B
      csr_save_cause_o := true.B
      debug_mode_d := true.B
      debug_mode_entering_o := true.B
      ctrl_fsm_ns := CtrlFsm.DECODE
    }
    is(CtrlFsm.DBG_TAKEN_ID) {
      flush_id := true.B
      pc_mux_o := PcSel.EXC
      pc_set_o := true.B
      exc_pc_mux_o := ExcPcSel.DBD
      when(ebreak_into_debug && !debug_mode_q) {
        csr_save_cause_o := true.B
        csr_save_id_o := true.B
        debug_csr_save_o := true.B
      }
      debug_mode_d := true.B
      debug_mode_entering_o := true.B
      ctrl_fsm_ns := CtrlFsm.DECODE
    }
    is(CtrlFsm.FLUSH) {
      halt_if := true.B
      flush_id := true.B
      ctrl_fsm_ns := CtrlFsm.DECODE
      when(exc_req_q || store_err_q || load_err_q) {
        pc_set_o := true.B
        pc_mux_o := PcSel.EXC
        exc_pc_mux_o := Mux(debug_mode_q, ExcPcSel.DBG_EXC, ExcPcSel.EXC)
        if (writebackStage) {
          csr_save_id_o := !(store_err_q || load_err_q)
          csr_save_wb_o := store_err_q || load_err_q
        } else {
          csr_save_id_o := false.B
        }
        csr_save_cause_o := true.B
        when(instr_fetch_err_prio) {
          exc_cause_o := ExcCause.InstrAccessFault
          csr_mtval_o := Mux(instr_fetch_err_plus2_i, pc_id_i + 2.U, pc_id_i)
        }.elsewhen(illegal_insn_prio) {
          exc_cause_o := ExcCause.IllegalInsn
          csr_mtval_o := Mux(instr_is_compressed_i, Cat(0.U(16.W), instr_compressed_i), instr_i)
        }.elsewhen(ecall_insn_prio) {
          exc_cause_o := Mux(priv_mode_i === PrivLvl.M, ExcCause.EcallMMode, ExcCause.EcallUMode)
        }.elsewhen(ebrk_insn_prio) {
          when(debug_mode_q || ebreak_into_debug) {
            pc_set_o := false.B
            csr_save_id_o := false.B
            csr_save_cause_o := false.B
            ctrl_fsm_ns := CtrlFsm.DBG_TAKEN_ID
            flush_id := false.B
          }.otherwise {
            exc_cause_o := ExcCause.Breakpoint
          }
        }.elsewhen(store_err_prio) {
          exc_cause_o := ExcCause.StoreAccessFault
          csr_mtval_o := lsu_addr_last_i
        }.elsewhen(load_err_prio) {
          exc_cause_o := ExcCause.LoadAccessFault
          csr_mtval_o := lsu_addr_last_i
        }
      }.otherwise {
        when(mret_insn) {
          pc_mux_o := PcSel.ERET
          pc_set_o := true.B
          csr_restore_mret_id_o := true.B
          when(nmi_mode_q) {
            nmi_mode_d := false.B
          }
        }.elsewhen(dret_insn) {
          pc_mux_o := PcSel.DRET
          pc_set_o := true.B
          debug_mode_d := false.B
          csr_restore_dret_id_o := true.B
        }.elsewhen(wfi_insn) {
          ctrl_fsm_ns := CtrlFsm.WAIT_SLEEP
        }
      }
      when(enter_debug_mode_prio_q && !(ebrk_insn_prio && ebreak_into_debug)) {
        ctrl_fsm_ns := CtrlFsm.DBG_TAKEN_IF
      }
    }
  }

  when(!instr_exec_i) {
    halt_if := true.B
  }

  flush_id_o := flush_id
  debug_mode_o := debug_mode_q
  nmi_mode_o := nmi_mode_q
  id_in_ready_o := !stall && !halt_if && !retain_id
  instr_valid_clear_o := !(stall || retain_id) || flush_id

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(!illegal_insn_i || instr_valid_i,
      "IllegalInsnOnlyIfInsnValid")
    assert(!nt_branch_mispredict_o || instr_valid_clear_o,
      "AlwaysInstrClearOnMispredict")
    assert(ctrl_fsm_cs === CtrlFsm.RESET ||
      ctrl_fsm_cs === CtrlFsm.BOOT_SET ||
      ctrl_fsm_cs === CtrlFsm.WAIT_SLEEP ||
      ctrl_fsm_cs === CtrlFsm.SLEEP ||
      ctrl_fsm_cs === CtrlFsm.FIRST_FETCH ||
      ctrl_fsm_cs === CtrlFsm.DECODE ||
      ctrl_fsm_cs === CtrlFsm.FLUSH ||
      ctrl_fsm_cs === CtrlFsm.IRQ_TAKEN ||
      ctrl_fsm_cs === CtrlFsm.DBG_TAKEN_IF ||
      ctrl_fsm_cs === CtrlFsm.DBG_TAKEN_ID,
      "IbexCtrlStateValid")
    assert(debug_mode_d === debug_mode_q || (flush_id_o && pc_set_o),
      "IbexPipelineFlushOnChangingDebugMode")
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    ctrl_fsm_cs := ctrl_fsm_ns
    nmi_mode_q := nmi_mode_d
    do_single_step_q := do_single_step_d
    debug_mode_q := debug_mode_d
    enter_debug_mode_prio_q := enter_debug_mode_prio_d
    load_err_q := load_err_d
    store_err_q := store_err_d
    exc_req_q := exc_req_d
    illegal_insn_q := illegal_insn_d
    debug_cause_q := debug_cause_d
    if (memECC) {
      mem_resp_intg_err_irq_pending_q := mem_resp_intg_err_irq_pending_d
      mem_resp_intg_err_addr_q := mem_resp_intg_err_addr_d
    }
  }
}
