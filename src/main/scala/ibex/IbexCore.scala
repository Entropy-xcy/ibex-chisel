// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexCore(
    pmpEnable: Boolean = false,
    pmpGranularity: Int = 0,
    pmpNumRegions: Int = 4,
    pmpRstCfg: Seq[BigInt] = IbexPkg.PmpCfgRst,
    pmpRstAddr: Seq[BigInt] = IbexPkg.PmpAddrRst,
    pmpRstMsecCfg: BigInt = IbexPkg.PmpMseccfgRst,
    mhpmCounterNum: Int = 0,
    mhpmCounterWidth: Int = 40,
    rv32e: Boolean = false,
    rv32m: Int = 2,
    rv32b: Int = 0,
    rv32zc: Int = 3,
    branchTargetALU: Boolean = false,
    writebackStage: Boolean = false,
    iCache: Boolean = false,
    iCacheECC: Boolean = false,
    iCacheTweakInfection: Boolean = false,
    branchPredictor: Boolean = false,
    dbgTriggerEn: Boolean = false,
    dbgHwBreakNum: Int = 1,
    resetAll: Boolean = false,
    rndCnstLfsrSeed: BigInt = 0xac533bf4L,
    rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16),
    secureIbex: Boolean = false,
    dummyInstructions: Boolean = false,
    regFileECC: Boolean = false,
    regFileDataWidth: Int = 32,
    memECC: Boolean = false,
    memDataWidth: Int = 0,
    dmBaseAddr: BigInt = BigInt("1a110000", 16),
    dmAddrMask: BigInt = BigInt("00000fff", 16),
    dmHaltAddr: BigInt = BigInt("1a110800", 16),
    dmExceptionAddr: BigInt = BigInt("1a110808", 16),
    csrMvendorId: BigInt = 0,
    csrMimpId: BigInt = 0)
    extends RawModule {
  private val MemDataWidth = if (memDataWidth == 0) {
    if (memECC) 39 else 32
  } else {
    memDataWidth
  }
  private val busSizeECC = if (iCacheECC) IbexPkg.BUS_SIZE + IbexPkg.IC_DATA_ECC_SIZE else IbexPkg.BUS_SIZE
  private val tagSizeECC = if (iCacheECC) IbexPkg.IC_TAG_SIZE + IbexPkg.IC_TAG_ECC_SIZE else IbexPkg.IC_TAG_SIZE
  private val lineSizeECC = busSizeECC * IbexPkg.IC_LINE_BEATS
  require(!pmpEnable || pmpNumRegions > 0, "IbexCore PMPEnable=true requires at least one PMP region")
  require((regFileECC && regFileDataWidth == 39) || (!regFileECC && regFileDataWidth == 32), "IbexCore RegFileDataWidth must be 39 with RegFileECC and 32 without RegFileECC")
  require((memECC && MemDataWidth == 39) || (!memECC && MemDataWidth == 32), "IbexCore MemDataWidth must be 39 with MemECC and 32 without MemECC")

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val hart_id_i = IO(Input(UInt(32.W)))
  val boot_addr_i = IO(Input(UInt(32.W)))

  val instr_req_o = IO(Output(Bool()))
  val instr_gnt_i = IO(Input(Bool()))
  val instr_rvalid_i = IO(Input(Bool()))
  val instr_addr_o = IO(Output(UInt(32.W)))
  val instr_rdata_i = IO(Input(UInt(MemDataWidth.W)))
  val instr_err_i = IO(Input(Bool()))

  val data_req_o = IO(Output(Bool()))
  val data_gnt_i = IO(Input(Bool()))
  val data_rvalid_i = IO(Input(Bool()))
  val data_we_o = IO(Output(Bool()))
  val data_be_o = IO(Output(UInt(4.W)))
  val data_addr_o = IO(Output(UInt(32.W)))
  val data_wdata_o = IO(Output(UInt(MemDataWidth.W)))
  val data_rdata_i = IO(Input(UInt(MemDataWidth.W)))
  val data_err_i = IO(Input(Bool()))

  val dummy_instr_id_o = IO(Output(Bool()))
  val dummy_instr_wb_o = IO(Output(Bool()))
  val rf_raddr_a_o = IO(Output(UInt(5.W)))
  val rf_raddr_b_o = IO(Output(UInt(5.W)))
  val rf_waddr_wb_o = IO(Output(UInt(5.W)))
  val rf_we_wb_o = IO(Output(Bool()))
  val rf_wdata_wb_ecc_o = IO(Output(UInt(regFileDataWidth.W)))
  val rf_rdata_a_ecc_i = IO(Input(UInt(regFileDataWidth.W)))
  val rf_rdata_b_ecc_i = IO(Input(UInt(regFileDataWidth.W)))

  val ic_tag_req_o = IO(Output(UInt(IbexPkg.IC_NUM_WAYS.W)))
  val ic_tag_write_o = IO(Output(Bool()))
  val ic_tag_addr_o = IO(Output(UInt(IbexPkg.IC_INDEX_W.W)))
  val ic_tag_wdata_o = IO(Output(UInt(tagSizeECC.W)))
  val ic_tag_rdata_i = IO(Input(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W))))
  val ic_data_req_o = IO(Output(UInt(IbexPkg.IC_NUM_WAYS.W)))
  val ic_data_write_o = IO(Output(Bool()))
  val ic_data_addr_o = IO(Output(UInt(IbexPkg.IC_INDEX_W.W)))
  val ic_data_wdata_o = IO(Output(UInt(lineSizeECC.W)))
  val ic_data_rdata_i = IO(Input(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W))))
  val ic_scr_key_valid_i = IO(Input(Bool()))
  val ic_scr_key_req_o = IO(Output(Bool()))

  val irq_software_i = IO(Input(Bool()))
  val irq_timer_i = IO(Input(Bool()))
  val irq_external_i = IO(Input(Bool()))
  val irq_fast_i = IO(Input(UInt(15.W)))
  val irq_nm_i = IO(Input(Bool()))
  val irq_pending_o = IO(Output(Bool()))

  val debug_req_i = IO(Input(Bool()))
  val crash_dump_o = IO(Output(new IbexPkg.CrashDump))
  val double_fault_seen_o = IO(Output(Bool()))
  val fetch_enable_i = IO(Input(UInt(IbexPkg.IbexMuBiWidth.W)))
  val alert_minor_o = IO(Output(Bool()))
  val alert_major_internal_o = IO(Output(Bool()))
  val alert_major_bus_o = IO(Output(Bool()))
  val core_busy_o = IO(Output(UInt(IbexPkg.IbexMuBiWidth.W)))

  val rvfi_valid = IO(Output(Bool()))
  val rvfi_order = IO(Output(UInt(64.W)))
  val rvfi_insn = IO(Output(UInt(32.W)))
  val rvfi_trap = IO(Output(Bool()))
  val rvfi_halt = IO(Output(Bool()))
  val rvfi_intr = IO(Output(Bool()))
  val rvfi_mode = IO(Output(UInt(2.W)))
  val rvfi_ixl = IO(Output(UInt(2.W)))
  val rvfi_rs1_addr = IO(Output(UInt(5.W)))
  val rvfi_rs2_addr = IO(Output(UInt(5.W)))
  val rvfi_rs3_addr = IO(Output(UInt(5.W)))
  val rvfi_rs1_rdata = IO(Output(UInt(32.W)))
  val rvfi_rs2_rdata = IO(Output(UInt(32.W)))
  val rvfi_rs3_rdata = IO(Output(UInt(32.W)))
  val rvfi_rd_addr = IO(Output(UInt(5.W)))
  val rvfi_rd_wdata = IO(Output(UInt(32.W)))
  val rvfi_pc_rdata = IO(Output(UInt(32.W)))
  val rvfi_pc_wdata = IO(Output(UInt(32.W)))
  val rvfi_mem_addr = IO(Output(UInt(32.W)))
  val rvfi_mem_rmask = IO(Output(UInt(4.W)))
  val rvfi_mem_wmask = IO(Output(UInt(4.W)))
  val rvfi_mem_rdata = IO(Output(UInt(32.W)))
  val rvfi_mem_wdata = IO(Output(UInt(32.W)))
  val rvfi_ext_pre_mip = IO(Output(UInt(32.W)))
  val rvfi_ext_post_mip = IO(Output(UInt(32.W)))
  val rvfi_ext_nmi = IO(Output(Bool()))
  val rvfi_ext_nmi_int = IO(Output(Bool()))
  val rvfi_ext_debug_req = IO(Output(Bool()))
  val rvfi_ext_debug_mode = IO(Output(Bool()))
  val rvfi_ext_rf_wr_suppress = IO(Output(Bool()))
  val rvfi_ext_mcycle = IO(Output(UInt(64.W)))
  val rvfi_ext_mhpmcounters = IO(Output(Vec(10, UInt(32.W))))
  val rvfi_ext_mhpmcountersh = IO(Output(Vec(10, UInt(32.W))))
  val rvfi_ext_ic_scr_key_valid = IO(Output(Bool()))
  val rvfi_ext_irq_valid = IO(Output(Bool()))
  val rvfi_ext_expanded_insn_valid = IO(Output(Bool()))
  val rvfi_ext_expanded_insn = IO(Output(UInt(16.W)))
  val rvfi_ext_expanded_insn_last = IO(Output(Bool()))
  val probe_lsu_handle_misaligned_d = IO(Output(Bool()))
  val probe_lsu_addr_incr_req = IO(Output(Bool()))
  val probe_lsu_err_d = IO(Output(Bool()))
  val probe_lsu_data_offset = IO(Output(UInt(2.W)))
  val probe_lsu_type = IO(Output(UInt(2.W)))
  val probe_priv_mode_lsu = IO(Output(UInt(2.W)))
  val probe_debug_mode = IO(Output(Bool()))

  val dummy_instr_id = Wire(Bool())
  val instr_valid_id = Wire(Bool())
  val instr_new_id = Wire(Bool())
  val instr_rdata_id = Wire(UInt(32.W))
  val instr_rdata_alu_id = Wire(UInt(32.W))
  val instr_rdata_c_id = Wire(UInt(16.W))
  val instr_is_compressed_id = Wire(Bool())
  val instr_gets_expanded_id = Wire(UInt(2.W))
  val instr_expanded_id = Wire(UInt(16.W))
  val instr_perf_count_id = Wire(Bool())
  val instr_bp_taken_id = Wire(Bool())
  val instr_fetch_err = Wire(Bool())
  val instr_fetch_err_plus2 = Wire(Bool())
  val illegal_c_insn_id = Wire(Bool())
  val pc_if = Wire(UInt(32.W))
  val pc_id = Wire(UInt(32.W))
  val pc_wb = Wire(UInt(32.W))
  val imd_val_d_ex = Wire(Vec(2, UInt(34.W)))
  val imd_val_q_ex = Wire(Vec(2, UInt(34.W)))
  val imd_val_we_ex = Wire(UInt(2.W))

  val data_ind_timing = Wire(Bool())
  val dummy_instr_en = Wire(Bool())
  val dummy_instr_mask = Wire(UInt(3.W))
  val dummy_instr_seed_en = Wire(Bool())
  val dummy_instr_seed = Wire(UInt(32.W))
  val icache_enable = Wire(Bool())
  val icache_inval = Wire(Bool())
  val icache_ecc_error = Wire(Bool())
  val pc_mismatch_alert = Wire(Bool())
  val csr_shadow_err = Wire(Bool())
  val instr_first_cycle_id = Wire(Bool())
  val instr_valid_clear = Wire(Bool())
  val rvfi_flush_next = Wire(Bool())
  val pc_set = Wire(Bool())
  val nt_branch_mispredict = Wire(Bool())
  val nt_branch_addr = Wire(UInt(32.W))
  val pc_mux_id = Wire(UInt(3.W))
  val exc_pc_mux_id = Wire(UInt(2.W))
  val exc_cause = Wire(UInt(7.W))
  val instr_intg_err = Wire(Bool())
  val lsu_load_err = Wire(Bool())
  val lsu_load_err_raw = Wire(Bool())
  val lsu_store_err = Wire(Bool())
  val lsu_store_err_raw = Wire(Bool())
  val lsu_load_resp_intg_err = Wire(Bool())
  val lsu_store_resp_intg_err = Wire(Bool())
  val expecting_load_resp_id = Wire(Bool())
  val expecting_store_resp_id = Wire(Bool())
  val lsu_addr_incr_req = Wire(Bool())
  val lsu_addr_last = Wire(UInt(32.W))
  val branch_target_ex = Wire(UInt(32.W))
  val branch_decision = Wire(Bool())
  val ctrl_busy = Wire(Bool())
  val if_busy = Wire(Bool())
  val lsu_busy = Wire(Bool())
  val rf_raddr_a = Wire(UInt(5.W))
  val rf_rdata_a = Wire(UInt(32.W))
  val rf_raddr_b = Wire(UInt(5.W))
  val rf_rdata_b = Wire(UInt(32.W))
  val rf_ren_a = Wire(Bool())
  val rf_ren_b = Wire(Bool())
  val rf_waddr_wb = Wire(UInt(5.W))
  val rf_wdata_wb = Wire(UInt(32.W))
  val rf_wdata_fwd_wb = Wire(UInt(32.W))
  val rf_wdata_lsu = Wire(UInt(32.W))
  val rf_we_wb = Wire(Bool())
  val rf_we_lsu = Wire(Bool())
  val rf_ecc_err_comb = Wire(Bool())
  val rf_waddr_id = Wire(UInt(5.W))
  val rf_wdata_id = Wire(UInt(32.W))
  val rf_we_id = Wire(Bool())
  val rf_rd_a_wb_match = Wire(Bool())
  val rf_rd_b_wb_match = Wire(Bool())
  val alu_operator_ex = Wire(UInt(7.W))
  val alu_operand_a_ex = Wire(UInt(32.W))
  val alu_operand_b_ex = Wire(UInt(32.W))
  val bt_a_operand = Wire(UInt(32.W))
  val bt_b_operand = Wire(UInt(32.W))
  val alu_adder_result_ex = Wire(UInt(32.W))
  val result_ex = Wire(UInt(32.W))
  val mult_en_ex = Wire(Bool())
  val div_en_ex = Wire(Bool())
  val mult_sel_ex = Wire(Bool())
  val div_sel_ex = Wire(Bool())
  val multdiv_operator_ex = Wire(UInt(2.W))
  val multdiv_signed_mode_ex = Wire(UInt(2.W))
  val multdiv_operand_a_ex = Wire(UInt(32.W))
  val multdiv_operand_b_ex = Wire(UInt(32.W))
  val multdiv_ready_id = Wire(Bool())
  val csr_access = Wire(Bool())
  val csr_op = Wire(UInt(2.W))
  val csr_op_en = Wire(Bool())
  val csr_addr = Wire(UInt(12.W))
  val csr_rdata = Wire(UInt(32.W))
  val csr_wdata = Wire(UInt(32.W))
  val illegal_csr_insn_id = Wire(Bool())
  val lsu_we = Wire(Bool())
  val lsu_type = Wire(UInt(2.W))
  val lsu_sign_ext = Wire(Bool())
  val lsu_req = Wire(Bool())
  val lsu_rdata_valid = Wire(Bool())
  val lsu_wdata = Wire(UInt(32.W))
  val lsu_req_done = Wire(Bool())
  val id_in_ready = Wire(Bool())
  val ex_valid = Wire(Bool())
  val lsu_resp_valid = Wire(Bool())
  val lsu_resp_err = Wire(Bool())
  val instr_req_int = Wire(Bool())
  val instr_req_gated = Wire(Bool())
  val instr_exec = Wire(Bool())
  val en_wb = Wire(Bool())
  val instr_type_wb = Wire(UInt(2.W))
  val ready_wb = Wire(Bool())
  val rf_write_wb = Wire(Bool())
  val outstanding_load_wb = Wire(Bool())
  val outstanding_store_wb = Wire(Bool())
  val dummy_instr_wb = Wire(Bool())
  val nmi_mode = Wire(Bool())
  val irqs = Wire(new IbexPkg.Irqs)
  val mip = Wire(new IbexPkg.Irqs)
  val csr_mstatus_mie = Wire(Bool())
  val csr_mepc = Wire(UInt(32.W))
  val csr_depc = Wire(UInt(32.W))
  val csr_save_if = Wire(Bool())
  val csr_save_id = Wire(Bool())
  val csr_save_wb = Wire(Bool())
  val csr_restore_mret_id = Wire(Bool())
  val csr_restore_dret_id = Wire(Bool())
  val csr_save_cause = Wire(Bool())
  val csr_mtvec_init = Wire(Bool())
  val csr_mtvec = Wire(UInt(32.W))
  val csr_mtval = Wire(UInt(32.W))
  val csr_pmp_cfg = Wire(Vec(pmpNumRegions, new IbexPkg.PmpCfg))
  val csr_pmp_addr = Wire(Vec(pmpNumRegions, UInt((IbexPkg.PMP_ADDR_MSB + 1).W)))
  val csr_pmp_mseccfg = Wire(new IbexPkg.PmpMseccfg)
  val crash_dump_mtval = Wire(UInt(32.W))
  val csr_mstatus_tw = Wire(Bool())
  val priv_mode_id = Wire(UInt(2.W))
  val priv_mode_lsu = Wire(UInt(2.W))
  val debug_mode = Wire(Bool())
  val debug_mode_entering = Wire(Bool())
  val debug_cause = Wire(UInt(3.W))
  val debug_csr_save = Wire(Bool())
  val debug_single_step = Wire(Bool())
  val debug_ebreakm = Wire(Bool())
  val debug_ebreaku = Wire(Bool())
  val trigger_match = Wire(Bool())
  val instr_id_done = Wire(Bool())
  val id_exception = Wire(Bool())
  val exc_req_lsu = Wire(Bool())
  val irq_nm_int = Wire(Bool())
  val ebreak_into_debug = Wire(Bool())
  val ebrk_insn = Wire(Bool())
  val instr_done_wb = Wire(Bool())
  val perf_instr_ret_wb = Wire(Bool())
  val perf_instr_ret_compressed_wb = Wire(Bool())
  val perf_instr_ret_wb_spec = Wire(Bool())
  val perf_instr_ret_compressed_wb_spec = Wire(Bool())
  val perf_iside_wait = Wire(Bool())
  val perf_dside_wait = Wire(Bool())
  val perf_mul_wait = Wire(Bool())
  val perf_div_wait = Wire(Bool())
  val perf_jump = Wire(Bool())
  val perf_branch = Wire(Bool())
  val perf_tbranch = Wire(Bool())
  val perf_load = Wire(Bool())
  val perf_store = Wire(Bool())
  val pmp_req_err = Wire(Vec(3, Bool()))

  if (secureIbex) {
    val busy_bits_buf = Wire(Vec(IbexPkg.IbexMuBiWidth, Vec(3, Bool())))
    val core_busy_secure = Wire(Vec(IbexPkg.IbexMuBiWidth, Bool()))
    for (i <- 0 until IbexPkg.IbexMuBiWidth) {
      busy_bits_buf(i)(0) := ctrl_busy
      busy_bits_buf(i)(1) := if_busy
      busy_bits_buf(i)(2) := lsu_busy
      if (((IbexPkg.IbexMuBiOn.litValue >> i) & 1) == 1) {
        core_busy_secure(i) := busy_bits_buf(i).asUInt.orR
      } else {
        core_busy_secure(i) := !busy_bits_buf(i).asUInt.orR
      }
    }
    core_busy_o := core_busy_secure.asUInt
  } else {
    core_busy_o := Mux(ctrl_busy || if_busy || lsu_busy, IbexPkg.IbexMuBiOn, IbexPkg.IbexMuBiOff)
  }
  if (secureIbex) {
    instr_req_gated := instr_req_int && (fetch_enable_i === IbexPkg.IbexMuBiOn)
    instr_exec := fetch_enable_i === IbexPkg.IbexMuBiOn
  } else {
    instr_req_gated := instr_req_int && fetch_enable_i(0)
    instr_exec := fetch_enable_i(0)
  }
  perf_iside_wait := id_in_ready && !instr_valid_id

  val if_stage_i = Module(new IbexIfStage(
    dmHaltAddr = dmHaltAddr,
    dmExceptionAddr = dmExceptionAddr,
    dummyInstructions = dummyInstructions,
    iCache = iCache,
    iCacheECC = iCacheECC,
    iCacheTweakInfection = iCacheTweakInfection,
    busSizeECC = busSizeECC,
    tagSizeECC = tagSizeECC,
    lineSizeECC = lineSizeECC,
    rv32zc = rv32zc,
    pcIncrCheck = secureIbex,
    resetAll = resetAll,
    rndCnstLfsrSeed = rndCnstLfsrSeed,
    rndCnstLfsrPerm = rndCnstLfsrPerm,
    branchPredictor = branchPredictor,
    memECC = memECC,
    memDataWidth = MemDataWidth))
  if_stage_i.clk_i := clk_i
  if_stage_i.rst_ni := rst_ni
  if_stage_i.boot_addr_i := boot_addr_i
  if_stage_i.req_i := instr_req_gated
  instr_req_o := if_stage_i.instr_req_o
  instr_addr_o := if_stage_i.instr_addr_o
  if_stage_i.instr_gnt_i := instr_gnt_i
  if_stage_i.instr_rvalid_i := instr_rvalid_i
  if_stage_i.instr_rdata_i := instr_rdata_i
  if_stage_i.instr_bus_err_i := instr_err_i
  instr_intg_err := if_stage_i.instr_intg_err_o
  ic_tag_req_o := if_stage_i.ic_tag_req_o
  ic_tag_write_o := if_stage_i.ic_tag_write_o
  ic_tag_addr_o := if_stage_i.ic_tag_addr_o
  ic_tag_wdata_o := if_stage_i.ic_tag_wdata_o
  if_stage_i.ic_tag_rdata_i := ic_tag_rdata_i
  ic_data_req_o := if_stage_i.ic_data_req_o
  ic_data_write_o := if_stage_i.ic_data_write_o
  ic_data_addr_o := if_stage_i.ic_data_addr_o
  ic_data_wdata_o := if_stage_i.ic_data_wdata_o
  if_stage_i.ic_data_rdata_i := ic_data_rdata_i
  if_stage_i.ic_scr_key_valid_i := ic_scr_key_valid_i
  ic_scr_key_req_o := if_stage_i.ic_scr_key_req_o
  instr_valid_id := if_stage_i.instr_valid_id_o
  instr_new_id := if_stage_i.instr_new_id_o
  val instr_valid_id_d = if_stage_i.instr_valid_id_d_o
  val instr_new_id_d = if_stage_i.instr_new_id_d_o
  instr_rdata_id := if_stage_i.instr_rdata_id_o
  instr_rdata_alu_id := if_stage_i.instr_rdata_alu_id_o
  instr_rdata_c_id := if_stage_i.instr_rdata_c_id_o
  instr_is_compressed_id := if_stage_i.instr_is_compressed_id_o
  instr_gets_expanded_id := if_stage_i.instr_gets_expanded_id_o
  instr_expanded_id := if_stage_i.instr_expanded_id_o
  instr_bp_taken_id := if_stage_i.instr_bp_taken_o
  instr_fetch_err := if_stage_i.instr_fetch_err_o
  instr_fetch_err_plus2 := if_stage_i.instr_fetch_err_plus2_o
  illegal_c_insn_id := if_stage_i.illegal_c_insn_id_o
  dummy_instr_id := if_stage_i.dummy_instr_id_o
  pc_if := if_stage_i.pc_if_o
  pc_id := if_stage_i.pc_id_o
  if_stage_i.pmp_err_if_i := pmp_req_err(IbexPkg.PMP_I)
  if_stage_i.pmp_err_if_plus2_i := pmp_req_err(IbexPkg.PMP_I2)
  if_stage_i.instr_valid_clear_i := instr_valid_clear
  if_stage_i.pc_set_i := pc_set
  if_stage_i.pc_mux_i := pc_mux_id
  if_stage_i.nt_branch_mispredict_i := nt_branch_mispredict
  if_stage_i.nt_branch_addr_i := nt_branch_addr
  if_stage_i.exc_pc_mux_i := exc_pc_mux_id
  if_stage_i.exc_cause := exc_cause
  if_stage_i.dummy_instr_en_i := dummy_instr_en
  if_stage_i.dummy_instr_mask_i := dummy_instr_mask
  if_stage_i.dummy_instr_seed_en_i := dummy_instr_seed_en
  if_stage_i.dummy_instr_seed_i := dummy_instr_seed
  if_stage_i.icache_enable_i := icache_enable
  if_stage_i.icache_inval_i := icache_inval
  icache_ecc_error := if_stage_i.icache_ecc_error_o
  if_stage_i.branch_target_ex_i := branch_target_ex
  if_stage_i.csr_mepc_i := csr_mepc
  if_stage_i.csr_depc_i := csr_depc
  if_stage_i.csr_mtvec_i := csr_mtvec
  csr_mtvec_init := if_stage_i.csr_mtvec_init_o
  if_stage_i.id_in_ready_i := id_in_ready
  pc_mismatch_alert := if_stage_i.pc_mismatch_alert_o
  if_busy := if_stage_i.if_busy_o

  val id_stage_i = Module(new IbexIdStage(
    rv32e = rv32e,
    rv32m = rv32m,
    rv32b = rv32b,
    dataIndTiming = secureIbex,
    branchTargetALU = branchTargetALU,
    writebackStage = writebackStage,
    branchPredictor = branchPredictor,
    memECC = memECC))
  id_stage_i.clk_i := clk_i
  id_stage_i.rst_ni := rst_ni
  ctrl_busy := id_stage_i.ctrl_busy_o
  id_stage_i.instr_valid_i := instr_valid_id
  id_stage_i.instr_rdata_i := instr_rdata_id
  id_stage_i.instr_rdata_alu_i := instr_rdata_alu_id
  id_stage_i.instr_rdata_c_i := instr_rdata_c_id
  id_stage_i.instr_is_compressed_i := instr_is_compressed_id
  id_stage_i.instr_bp_taken_i := instr_bp_taken_id
  id_stage_i.branch_decision_i := branch_decision
  instr_first_cycle_id := id_stage_i.instr_first_cycle_id_o
  instr_valid_clear := id_stage_i.instr_valid_clear_o
  rvfi_flush_next := id_stage_i.rvfi_flush_next_o
  id_in_ready := id_stage_i.id_in_ready_o
  id_stage_i.instr_exec_i := instr_exec
  instr_req_int := id_stage_i.instr_req_o
  pc_set := id_stage_i.pc_set_o
  pc_mux_id := id_stage_i.pc_mux_o
  nt_branch_mispredict := id_stage_i.nt_branch_mispredict_o
  nt_branch_addr := id_stage_i.nt_branch_addr_o
  exc_pc_mux_id := id_stage_i.exc_pc_mux_o
  exc_cause := id_stage_i.exc_cause_o
  icache_inval := id_stage_i.icache_inval_o
  id_stage_i.instr_fetch_err_i := instr_fetch_err
  id_stage_i.instr_fetch_err_plus2_i := instr_fetch_err_plus2
  id_stage_i.illegal_c_insn_i := illegal_c_insn_id
  id_stage_i.pc_id_i := pc_id
  id_stage_i.ex_valid_i := ex_valid
  id_stage_i.lsu_resp_valid_i := lsu_resp_valid
  alu_operator_ex := id_stage_i.alu_operator_ex_o
  alu_operand_a_ex := id_stage_i.alu_operand_a_ex_o
  alu_operand_b_ex := id_stage_i.alu_operand_b_ex_o
  imd_val_q_ex := id_stage_i.imd_val_q_ex_o
  id_stage_i.imd_val_d_ex_i := imd_val_d_ex
  id_stage_i.imd_val_we_ex_i := imd_val_we_ex
  bt_a_operand := id_stage_i.bt_a_operand_o
  bt_b_operand := id_stage_i.bt_b_operand_o
  mult_en_ex := id_stage_i.mult_en_ex_o
  div_en_ex := id_stage_i.div_en_ex_o
  mult_sel_ex := id_stage_i.mult_sel_ex_o
  div_sel_ex := id_stage_i.div_sel_ex_o
  multdiv_operator_ex := id_stage_i.multdiv_operator_ex_o
  multdiv_signed_mode_ex := id_stage_i.multdiv_signed_mode_ex_o
  multdiv_operand_a_ex := id_stage_i.multdiv_operand_a_ex_o
  multdiv_operand_b_ex := id_stage_i.multdiv_operand_b_ex_o
  multdiv_ready_id := id_stage_i.multdiv_ready_id_o
  csr_access := id_stage_i.csr_access_o
  csr_op := id_stage_i.csr_op_o
  csr_addr := id_stage_i.csr_addr_o
  csr_op_en := id_stage_i.csr_op_en_o
  csr_save_if := id_stage_i.csr_save_if_o
  csr_save_id := id_stage_i.csr_save_id_o
  csr_save_wb := id_stage_i.csr_save_wb_o
  csr_restore_mret_id := id_stage_i.csr_restore_mret_id_o
  csr_restore_dret_id := id_stage_i.csr_restore_dret_id_o
  csr_save_cause := id_stage_i.csr_save_cause_o
  csr_mtval := id_stage_i.csr_mtval_o
  id_stage_i.priv_mode_i := priv_mode_id
  id_stage_i.csr_mstatus_tw_i := csr_mstatus_tw
  id_stage_i.illegal_csr_insn_i := illegal_csr_insn_id
  id_stage_i.data_ind_timing_i := data_ind_timing
  lsu_req := id_stage_i.lsu_req_o
  lsu_we := id_stage_i.lsu_we_o
  lsu_type := id_stage_i.lsu_type_o
  lsu_sign_ext := id_stage_i.lsu_sign_ext_o
  lsu_wdata := id_stage_i.lsu_wdata_o
  id_stage_i.lsu_req_done_i := lsu_req_done
  id_stage_i.lsu_addr_incr_req_i := lsu_addr_incr_req
  id_stage_i.lsu_addr_last_i := lsu_addr_last
  id_stage_i.lsu_load_err_i := lsu_load_err
  id_stage_i.lsu_load_resp_intg_err_i := lsu_load_resp_intg_err
  id_stage_i.lsu_store_err_i := lsu_store_err
  id_stage_i.lsu_store_resp_intg_err_i := lsu_store_resp_intg_err
  expecting_load_resp_id := id_stage_i.expecting_load_resp_o
  expecting_store_resp_id := id_stage_i.expecting_store_resp_o
  id_stage_i.csr_mstatus_mie_i := csr_mstatus_mie
  id_stage_i.irq_pending_i := irq_pending_o
  id_stage_i.irqs_i := irqs
  id_stage_i.irq_nm_i := irq_nm_i
  irq_nm_int := id_stage_i.irq_nm_int_o
  nmi_mode := id_stage_i.nmi_mode_o
  debug_mode := id_stage_i.debug_mode_o
  debug_mode_entering := id_stage_i.debug_mode_entering_o
  debug_cause := id_stage_i.debug_cause_o
  debug_csr_save := id_stage_i.debug_csr_save_o
  ebreak_into_debug := id_stage_i.ebreak_into_debug_o
  id_stage_i.debug_req_i := debug_req_i
  id_stage_i.debug_single_step_i := debug_single_step
  id_stage_i.debug_ebreakm_i := debug_ebreakm
  id_stage_i.debug_ebreaku_i := debug_ebreaku
  id_stage_i.trigger_match_i := trigger_match
  id_stage_i.result_ex_i := result_ex
  id_stage_i.csr_rdata_i := csr_rdata
  rf_raddr_a := id_stage_i.rf_raddr_a_o
  id_stage_i.rf_rdata_a_i := rf_rdata_a
  rf_raddr_b := id_stage_i.rf_raddr_b_o
  id_stage_i.rf_rdata_b_i := rf_rdata_b
  rf_ren_a := id_stage_i.rf_ren_a_o
  rf_ren_b := id_stage_i.rf_ren_b_o
  rf_waddr_id := id_stage_i.rf_waddr_id_o
  rf_wdata_id := id_stage_i.rf_wdata_id_o
  rf_we_id := id_stage_i.rf_we_id_o
  rf_rd_a_wb_match := id_stage_i.rf_rd_a_wb_match_o
  rf_rd_b_wb_match := id_stage_i.rf_rd_b_wb_match_o
  id_stage_i.rf_waddr_wb_i := rf_waddr_wb
  id_stage_i.rf_wdata_fwd_wb_i := rf_wdata_fwd_wb
  id_stage_i.rf_write_wb_i := rf_write_wb
  en_wb := id_stage_i.en_wb_o
  instr_type_wb := id_stage_i.instr_type_wb_o
  instr_perf_count_id := id_stage_i.instr_perf_count_id_o
  id_stage_i.ready_wb_i := ready_wb
  id_stage_i.outstanding_load_wb_i := outstanding_load_wb
  id_stage_i.outstanding_store_wb_i := outstanding_store_wb
  perf_jump := id_stage_i.perf_jump_o
  perf_branch := id_stage_i.perf_branch_o
  perf_tbranch := id_stage_i.perf_tbranch_o
  perf_dside_wait := id_stage_i.perf_dside_wait_o
  perf_mul_wait := id_stage_i.perf_mul_wait_o
  perf_div_wait := id_stage_i.perf_div_wait_o
  instr_id_done := id_stage_i.instr_id_done_o
  id_exception := id_stage_i.id_exception_o
  exc_req_lsu := id_stage_i.exc_req_lsu_o
  ebrk_insn := id_stage_i.ebrk_insn_o

  val ex_block_i = Module(new IbexExBlock(rv32m = rv32m, rv32b = rv32b, branchTargetALU = branchTargetALU))
  ex_block_i.clk_i := clk_i
  ex_block_i.rst_ni := rst_ni
  ex_block_i.alu_operator_i := alu_operator_ex
  ex_block_i.alu_operand_a_i := alu_operand_a_ex
  ex_block_i.alu_operand_b_i := alu_operand_b_ex
  ex_block_i.alu_instr_first_cycle_i := instr_first_cycle_id
  ex_block_i.bt_a_operand_i := bt_a_operand
  ex_block_i.bt_b_operand_i := bt_b_operand
  ex_block_i.multdiv_operator_i := multdiv_operator_ex
  ex_block_i.mult_en_i := mult_en_ex
  ex_block_i.div_en_i := div_en_ex
  ex_block_i.mult_sel_i := mult_sel_ex
  ex_block_i.div_sel_i := div_sel_ex
  ex_block_i.multdiv_signed_mode_i := multdiv_signed_mode_ex
  ex_block_i.multdiv_operand_a_i := multdiv_operand_a_ex
  ex_block_i.multdiv_operand_b_i := multdiv_operand_b_ex
  ex_block_i.multdiv_ready_id_i := multdiv_ready_id
  ex_block_i.data_ind_timing_i := data_ind_timing
  imd_val_we_ex := ex_block_i.imd_val_we_o
  imd_val_d_ex := ex_block_i.imd_val_d_o
  ex_block_i.imd_val_q_i := imd_val_q_ex
  alu_adder_result_ex := ex_block_i.alu_adder_result_ex_o
  result_ex := ex_block_i.result_ex_o
  branch_target_ex := ex_block_i.branch_target_o
  branch_decision := ex_block_i.branch_decision_o
  ex_valid := ex_block_i.ex_valid_o

  val load_store_unit_i = Module(new IbexLoadStoreUnit(memECC = memECC, memDataWidth = MemDataWidth))
  load_store_unit_i.clk_i := clk_i
  load_store_unit_i.rst_ni := rst_ni
  val data_req_out = Wire(Bool())
  data_req_out := load_store_unit_i.data_req_o
  data_req_o := data_req_out && !pmp_req_err(IbexPkg.PMP_D)
  load_store_unit_i.data_gnt_i := data_gnt_i
  load_store_unit_i.data_rvalid_i := data_rvalid_i
  load_store_unit_i.data_bus_err_i := data_err_i
  load_store_unit_i.data_pmp_err_i := pmp_req_err(IbexPkg.PMP_D)
  data_addr_o := load_store_unit_i.data_addr_o
  data_we_o := load_store_unit_i.data_we_o
  data_be_o := load_store_unit_i.data_be_o
  data_wdata_o := load_store_unit_i.data_wdata_o
  load_store_unit_i.data_rdata_i := data_rdata_i
  load_store_unit_i.lsu_we_i := lsu_we
  load_store_unit_i.lsu_type_i := lsu_type
  load_store_unit_i.lsu_wdata_i := lsu_wdata
  load_store_unit_i.lsu_sign_ext_i := lsu_sign_ext
  rf_wdata_lsu := load_store_unit_i.lsu_rdata_o
  lsu_rdata_valid := load_store_unit_i.lsu_rdata_valid_o
  load_store_unit_i.lsu_req_i := lsu_req
  lsu_req_done := load_store_unit_i.lsu_req_done_o
  load_store_unit_i.adder_result_ex_i := alu_adder_result_ex
  lsu_addr_incr_req := load_store_unit_i.addr_incr_req_o
  lsu_addr_last := load_store_unit_i.addr_last_o
  lsu_resp_valid := load_store_unit_i.lsu_resp_valid_o
  lsu_load_err_raw := load_store_unit_i.load_err_o
  lsu_load_resp_intg_err := load_store_unit_i.load_resp_intg_err_o
  lsu_store_err_raw := load_store_unit_i.store_err_o
  lsu_store_resp_intg_err := load_store_unit_i.store_resp_intg_err_o
  lsu_busy := load_store_unit_i.busy_o
  perf_load := load_store_unit_i.perf_load_o
  perf_store := load_store_unit_i.perf_store_o
  probe_lsu_handle_misaligned_d := load_store_unit_i.probe_handle_misaligned_d_o
  probe_lsu_addr_incr_req := load_store_unit_i.addr_incr_req_o
  probe_lsu_err_d := load_store_unit_i.probe_lsu_err_d_o
  probe_lsu_data_offset := load_store_unit_i.probe_data_offset_o
  probe_lsu_type := load_store_unit_i.probe_lsu_type_o
  probe_priv_mode_lsu := priv_mode_lsu
  probe_debug_mode := debug_mode
  lsu_resp_err := lsu_load_err || lsu_store_err
  if (secureIbex) {
    lsu_load_err := lsu_load_err_raw && (outstanding_load_wb || expecting_load_resp_id)
    lsu_store_err := lsu_store_err_raw && (outstanding_store_wb || expecting_store_resp_id)
    rf_we_lsu := lsu_rdata_valid && (outstanding_load_wb || expecting_load_resp_id)
  } else {
    lsu_load_err := lsu_load_err_raw
    lsu_store_err := lsu_store_err_raw
    rf_we_lsu := lsu_rdata_valid
  }

  val wb_stage_i = Module(new IbexWbStage(resetAll = resetAll, writebackStage = writebackStage, dummyInstructions = dummyInstructions))
  wb_stage_i.clk_i := clk_i
  wb_stage_i.rst_ni := rst_ni
  wb_stage_i.en_wb_i := en_wb
  wb_stage_i.instr_type_wb_i := instr_type_wb
  wb_stage_i.pc_id_i := pc_id
  wb_stage_i.instr_is_compressed_id_i := instr_is_compressed_id
  wb_stage_i.instr_perf_count_id_i := instr_perf_count_id
  ready_wb := wb_stage_i.ready_wb_o
  rf_write_wb := wb_stage_i.rf_write_wb_o
  outstanding_load_wb := wb_stage_i.outstanding_load_wb_o
  outstanding_store_wb := wb_stage_i.outstanding_store_wb_o
  pc_wb := wb_stage_i.pc_wb_o
  perf_instr_ret_wb := wb_stage_i.perf_instr_ret_wb_o
  perf_instr_ret_compressed_wb := wb_stage_i.perf_instr_ret_compressed_wb_o
  perf_instr_ret_wb_spec := wb_stage_i.perf_instr_ret_wb_spec_o
  perf_instr_ret_compressed_wb_spec := wb_stage_i.perf_instr_ret_compressed_wb_spec_o
  wb_stage_i.rf_waddr_id_i := rf_waddr_id
  wb_stage_i.rf_wdata_id_i := rf_wdata_id
  wb_stage_i.rf_we_id_i := rf_we_id
  wb_stage_i.dummy_instr_id_i := dummy_instr_id
  wb_stage_i.rf_wdata_lsu_i := rf_wdata_lsu
  wb_stage_i.rf_we_lsu_i := rf_we_lsu
  rf_wdata_fwd_wb := wb_stage_i.rf_wdata_fwd_wb_o
  rf_waddr_wb := wb_stage_i.rf_waddr_wb_o
  rf_wdata_wb := wb_stage_i.rf_wdata_wb_o
  rf_we_wb := wb_stage_i.rf_we_wb_o
  dummy_instr_wb := wb_stage_i.dummy_instr_wb_o
  wb_stage_i.lsu_resp_valid_i := lsu_resp_valid
  wb_stage_i.lsu_resp_err_i := lsu_resp_err
  instr_done_wb := wb_stage_i.instr_done_wb_o

  csr_wdata := alu_operand_a_ex
  val cs_registers_i = Module(new IbexCsRegisters(
    dbgTriggerEn = dbgTriggerEn,
    dbgHwBreakNum = dbgHwBreakNum,
    dataIndTiming = secureIbex,
    dummyInstructions = dummyInstructions,
    shadowCSR = false,
    iCache = iCache,
    iCacheOutputEnable = false,
    mhpmCounterNum = mhpmCounterNum,
    mhpmCounterWidth = mhpmCounterWidth,
    pmpEnable = pmpEnable,
    pmpGranularity = pmpGranularity,
    pmpNumRegions = pmpNumRegions,
    pmpRstCfg = pmpRstCfg,
    pmpRstAddr = pmpRstAddr,
    pmpRstMsecCfg = pmpRstMsecCfg,
    rv32e = rv32e,
    rv32m = rv32m,
    rv32b = rv32b,
    csrMvendorId = csrMvendorId,
    csrMimpId = csrMimpId))
  cs_registers_i.clk_i := clk_i
  cs_registers_i.rst_ni := rst_ni
  cs_registers_i.hart_id_i := hart_id_i
  priv_mode_id := cs_registers_i.priv_mode_id_o
  priv_mode_lsu := cs_registers_i.priv_mode_lsu_o
  csr_mtvec := cs_registers_i.csr_mtvec_o
  cs_registers_i.csr_mtvec_init_i := csr_mtvec_init
  cs_registers_i.boot_addr_i := boot_addr_i
  cs_registers_i.csr_access_i := csr_access
  cs_registers_i.csr_addr_i := csr_addr
  cs_registers_i.csr_wdata_i := csr_wdata
  cs_registers_i.csr_op_i := csr_op
  cs_registers_i.csr_op_en_i := csr_op_en
  csr_rdata := cs_registers_i.csr_rdata_o
  cs_registers_i.irq_software_i := irq_software_i
  cs_registers_i.irq_timer_i := irq_timer_i
  cs_registers_i.irq_external_i := irq_external_i
  cs_registers_i.irq_fast_i := irq_fast_i
  cs_registers_i.nmi_mode_i := nmi_mode
  irq_pending_o := cs_registers_i.irq_pending_o
  irqs := cs_registers_i.irqs_o
  mip := cs_registers_i.mip_o
  csr_mstatus_mie := cs_registers_i.csr_mstatus_mie_o
  csr_mstatus_tw := cs_registers_i.csr_mstatus_tw_o
  csr_mepc := cs_registers_i.csr_mepc_o
  crash_dump_mtval := cs_registers_i.csr_mtval_o
  cs_registers_i.debug_mode_i := debug_mode
  cs_registers_i.debug_mode_entering_i := debug_mode_entering
  cs_registers_i.debug_cause_i := debug_cause
  cs_registers_i.debug_csr_save_i := debug_csr_save
  csr_depc := cs_registers_i.csr_depc_o
  debug_single_step := cs_registers_i.debug_single_step_o
  debug_ebreakm := cs_registers_i.debug_ebreakm_o
  debug_ebreaku := cs_registers_i.debug_ebreaku_o
  trigger_match := cs_registers_i.trigger_match_o
  cs_registers_i.pc_if_i := pc_if
  cs_registers_i.pc_id_i := pc_id
  cs_registers_i.pc_wb_i := pc_wb
  data_ind_timing := cs_registers_i.data_ind_timing_o
  dummy_instr_en := cs_registers_i.dummy_instr_en_o
  dummy_instr_mask := cs_registers_i.dummy_instr_mask_o
  dummy_instr_seed_en := cs_registers_i.dummy_instr_seed_en_o
  dummy_instr_seed := cs_registers_i.dummy_instr_seed_o
  icache_enable := cs_registers_i.icache_enable_o
  csr_shadow_err := cs_registers_i.csr_shadow_err_o
  cs_registers_i.ic_scr_key_valid_i := ic_scr_key_valid_i
  cs_registers_i.csr_save_if_i := csr_save_if
  cs_registers_i.csr_save_id_i := csr_save_id
  cs_registers_i.csr_save_wb_i := csr_save_wb
  cs_registers_i.csr_restore_mret_i := csr_restore_mret_id
  cs_registers_i.csr_restore_dret_i := csr_restore_dret_id
  cs_registers_i.csr_save_cause_i := csr_save_cause
  cs_registers_i.csr_mcause_i := exc_cause
  cs_registers_i.csr_mtval_i := csr_mtval
  illegal_csr_insn_id := cs_registers_i.illegal_csr_insn_o
  double_fault_seen_o := cs_registers_i.double_fault_seen_o
  cs_registers_i.instr_ret_i := perf_instr_ret_wb
  cs_registers_i.instr_ret_compressed_i := perf_instr_ret_compressed_wb
  cs_registers_i.instr_ret_spec_i := perf_instr_ret_wb_spec
  cs_registers_i.instr_ret_compressed_spec_i := perf_instr_ret_compressed_wb_spec
  cs_registers_i.iside_wait_i := perf_iside_wait
  cs_registers_i.jump_i := perf_jump
  cs_registers_i.branch_i := perf_branch
  cs_registers_i.branch_taken_i := perf_tbranch
  cs_registers_i.mem_load_i := perf_load
  cs_registers_i.mem_store_i := perf_store
  cs_registers_i.dside_wait_i := perf_dside_wait
  cs_registers_i.mul_wait_i := perf_mul_wait
  cs_registers_i.div_wait_i := perf_div_wait
  csr_pmp_cfg := cs_registers_i.csr_pmp_cfg_o
  csr_pmp_addr := cs_registers_i.csr_pmp_addr_o
  csr_pmp_mseccfg := cs_registers_i.csr_pmp_mseccfg_o

  if (pmpEnable) {
    val pmp_i = Module(new IbexPmp(
      dmBaseAddr = dmBaseAddr,
      dmAddrMask = dmAddrMask,
      pmpGranularity = pmpGranularity,
      pmpNumChan = 3,
      pmpNumRegions = pmpNumRegions))
    pmp_i.csr_pmp_cfg_i := csr_pmp_cfg
    pmp_i.csr_pmp_addr_i := csr_pmp_addr
    pmp_i.csr_pmp_mseccfg_i := csr_pmp_mseccfg
    pmp_i.debug_mode_i := debug_mode
    pmp_i.priv_mode_i(IbexPkg.PMP_I) := priv_mode_id
    pmp_i.priv_mode_i(IbexPkg.PMP_I2) := priv_mode_id
    pmp_i.priv_mode_i(IbexPkg.PMP_D) := priv_mode_lsu
    pmp_i.pmp_req_addr_i(IbexPkg.PMP_I) := Cat(0.U(2.W), pc_if)
    pmp_i.pmp_req_addr_i(IbexPkg.PMP_I2) := Cat(0.U(2.W), pc_if + 2.U)
    pmp_i.pmp_req_addr_i(IbexPkg.PMP_D) := Cat(0.U(2.W), data_addr_o)
    pmp_i.pmp_req_type_i(IbexPkg.PMP_I) := IbexPkg.PmpReq.Exec
    pmp_i.pmp_req_type_i(IbexPkg.PMP_I2) := IbexPkg.PmpReq.Exec
    pmp_i.pmp_req_type_i(IbexPkg.PMP_D) := Mux(data_we_o, IbexPkg.PmpReq.Write, IbexPkg.PmpReq.Read)
    pmp_req_err := pmp_i.pmp_req_err_o
  } else {
    pmp_req_err.foreach(_ := false.B)
  }

  dummy_instr_id_o := dummy_instr_id
  dummy_instr_wb_o := dummy_instr_wb
  rf_raddr_a_o := rf_raddr_a
  rf_raddr_b_o := rf_raddr_b
  rf_waddr_wb_o := rf_waddr_wb
  rf_we_wb_o := rf_we_wb
  if (regFileECC) {
    val regfile_ecc_enc = Module(new PrimSecdedInv3932Enc)
    regfile_ecc_enc.data_i := rf_wdata_wb
    rf_wdata_wb_ecc_o := regfile_ecc_enc.data_o

    val regfile_ecc_dec_a = Module(new PrimSecdedInv3932Dec)
    regfile_ecc_dec_a.data_i := rf_rdata_a_ecc_i
    val regfile_ecc_dec_b = Module(new PrimSecdedInv3932Dec)
    regfile_ecc_dec_b.data_i := rf_rdata_b_ecc_i

    rf_rdata_a := rf_rdata_a_ecc_i(31, 0)
    rf_rdata_b := rf_rdata_b_ecc_i(31, 0)

    val rf_ecc_err_a_id = regfile_ecc_dec_a.err_o.orR && rf_ren_a && !(rf_rd_a_wb_match && rf_write_wb)
    val rf_ecc_err_b_id = regfile_ecc_dec_b.err_o.orR && rf_ren_b && !(rf_rd_b_wb_match && rf_write_wb)
    rf_ecc_err_comb := instr_valid_id && (rf_ecc_err_a_id || rf_ecc_err_b_id)
  } else {
    rf_wdata_wb_ecc_o := rf_wdata_wb
    rf_rdata_a := rf_rdata_a_ecc_i
    rf_rdata_b := rf_rdata_b_ecc_i
    rf_ecc_err_comb := false.B
  }

  crash_dump_o.current_pc := pc_id
  crash_dump_o.next_pc := pc_if
  crash_dump_o.last_data_addr := lsu_addr_last
  crash_dump_o.exception_pc := csr_mepc
  crash_dump_o.exception_addr := crash_dump_mtval
  alert_minor_o := icache_ecc_error
  alert_major_internal_o := rf_ecc_err_comb || pc_mismatch_alert || csr_shadow_err
  alert_major_bus_o := lsu_load_resp_intg_err || lsu_store_resp_intg_err || instr_intg_err

  val rvfi_retire = if (writebackStage) instr_done_wb else instr_id_done
  val rvfi_insn_id = Mux(
    instr_is_compressed_id && instr_gets_expanded_id === IbexPkg.InstrExp.NotExpanded.asUInt,
    Cat(0.U(16.W), instr_rdata_c_id),
    instr_rdata_id)
  val rvfi_mem_mask_int = MuxLookup(lsu_type, 0.U(4.W))(Seq(
    0.U -> "b1111".U(4.W),
    1.U -> "b0011".U(4.W),
    2.U -> "b0001".U(4.W)))
  val rvfi_mem_mask_aligned = Mux(lsu_req, data_be_o, rvfi_mem_mask_int)
  val rvfi_rd_we_wb = rf_we_wb || rf_we_lsu
  val rvfi_rd_addr_wb = rf_waddr_wb
  val rvfi_rd_wdata_wb = Mux(rf_we_wb, rf_wdata_wb, rf_wdata_lsu)
  val rvfi_mem_rmask_d = Mux(lsu_req && !data_we_o, rvfi_mem_mask_aligned, 0.U)
  val rvfi_mem_wmask_d = Mux(lsu_req && data_we_o, rvfi_mem_mask_aligned, 0.U)
  val rvfi_expanded_insn_valid_d = instr_gets_expanded_id =/= IbexPkg.InstrExp.NotExpanded.asUInt

  val rvfi_intr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfi_rs1_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  val rvfi_rs2_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  val rvfi_rs3_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  val rvfi_rs1_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_rs2_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_rs3_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_rd_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  val rvfi_rd_wdata_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_mem_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_mem_rdata_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_mem_wdata_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_set_trap_pc_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfi_irq_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfi_captured_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfi_captured_mip_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rvfi_captured_nmi_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfi_captured_nmi_int_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfi_captured_debug_req_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val rvfiStages = if (writebackStage) 2 else 1
  val rvfi_stage_valid = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(false.B))) }
  val rvfi_stage_order = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(64.W)))) }
  val rvfi_stage_insn = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_trap = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(false.B))) }
  val rvfi_stage_halt = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(false.B))) }
  val rvfi_stage_intr = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(false.B))) }
  val rvfi_stage_mode = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(IbexPkg.PrivLvl.M))) }
  val rvfi_stage_ixl = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(IbexPkg.CSR_MISA_MXL)))
  }
  val rvfi_stage_rs1_addr = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(5.W)))) }
  val rvfi_stage_rs2_addr = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(5.W)))) }
  val rvfi_stage_rs3_addr = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(5.W)))) }
  val rvfi_stage_rs1_rdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_rs2_rdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_rs3_rdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_rd_addr = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(5.W)))) }
  val rvfi_stage_rd_wdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_pc_rdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_pc_wdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_mem_addr = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_mem_rmask = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(4.W)))) }
  val rvfi_stage_mem_wmask = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(4.W)))) }
  val rvfi_stage_mem_rdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_stage_mem_wdata = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W)))) }
  val rvfi_ext_stage_pre_mip = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages + 1)(0.U(32.W))))
  }
  val rvfi_ext_stage_post_mip = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(0.U(32.W))))
  }
  val rvfi_ext_stage_nmi = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages + 1)(false.B)))
  }
  val rvfi_ext_stage_nmi_int = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages + 1)(false.B)))
  }
  val rvfi_ext_stage_debug_req = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages + 1)(false.B)))
  }
  val rvfi_ext_stage_debug_mode = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(false.B)))
  }
  val rvfi_ext_stage_mcycle = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(0.U(64.W))))
  }
  val rvfi_ext_stage_mhpmcounters = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(VecInit(Seq.fill(10)(0.U(32.W))))))
  }
  val rvfi_ext_stage_mhpmcountersh = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(VecInit(Seq.fill(10)(0.U(32.W))))))
  }
  val rvfi_ext_stage_ic_scr_key_valid = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(false.B)))
  }
  val rvfi_ext_stage_irq_valid = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages + 1)(false.B)))
  }
  val rvfi_ext_stage_expanded_insn_valid = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(false.B)))
  }
  val rvfi_ext_stage_expanded_insn = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(0.U(16.W))))
  }
  val rvfi_ext_stage_expanded_insn_last = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(VecInit(Seq.fill(rvfiStages)(false.B)))
  }
  val rvfi_ext_rf_wr_suppress_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }

  val rvfi_mip_bits = Cat(0.U(1.W), mip.irq_fast, 0.U(4.W), mip.irq_external, 0.U(3.W),
    mip.irq_timer, 0.U(3.W), mip.irq_software, 0.U(3.W))
  val rvfi_ext_rf_wr_suppress_wb =
    if (writebackStage) instr_done_wb && !rf_we_wb && outstanding_load_wb && lsu_load_resp_intg_err else false.B

  val rvfi_rs1_data_d = Mux(instr_first_cycle_id, Mux(rf_ren_a, multdiv_operand_a_ex, 0.U), rvfi_rs1_data_q)
  val rvfi_rs2_data_d = Mux(instr_first_cycle_id, Mux(rf_ren_b, multdiv_operand_b_ex, 0.U), rvfi_rs2_data_q)
  val rvfi_rs3_data_d = Mux(instr_first_cycle_id, 0.U, multdiv_operand_a_ex)
  val rvfi_rs1_addr_d = Mux(instr_first_cycle_id, Mux(rf_ren_a, rf_raddr_a, 0.U), rvfi_rs1_addr_q)
  val rvfi_rs2_addr_d = Mux(instr_first_cycle_id, Mux(rf_ren_b, rf_raddr_b, 0.U), rvfi_rs2_addr_q)
  val rvfi_rs3_addr_d = Mux(instr_first_cycle_id, 0.U, rf_raddr_a)
  val rvfi_mem_addr_d = Mux(instr_first_cycle_id, alu_adder_result_ex, rvfi_mem_addr_q)
  val rvfi_mem_wdata_hold_d = Mux(instr_first_cycle_id, lsu_wdata, rvfi_mem_wdata_q)
  val rvfi_mem_rdata_d = Mux(lsu_resp_valid, rf_wdata_lsu, rvfi_mem_rdata_q)

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    rvfi_rs1_addr_q := rvfi_rs1_addr_d
    rvfi_rs2_addr_q := rvfi_rs2_addr_d
    rvfi_rs3_addr_q := rvfi_rs3_addr_d
    rvfi_rs1_data_q := rvfi_rs1_data_d
    rvfi_rs2_data_q := rvfi_rs2_data_d
    rvfi_rs3_data_q := rvfi_rs3_data_d
    rvfi_mem_addr_q := rvfi_mem_addr_d
    rvfi_mem_wdata_q := rvfi_mem_wdata_hold_d
    rvfi_mem_rdata_q := rvfi_mem_rdata_d
  }

  val rvfi_id_done = instr_id_done || (rvfi_flush_next && id_exception)
  val rvfi_trap_id =
    (if (writebackStage) id_exception else id_exception || exc_req_lsu) && !(ebrk_insn && ebreak_into_debug)
  val rvfi_trap_wb = if (writebackStage) exc_req_lsu else false.B
  val rvfi_wb_done =
    if (writebackStage) rvfi_stage_valid(0) && (instr_done_wb || rvfi_stage_trap(0)) else instr_done_wb
  val rvfi_stage_valid_d = Wire(Vec(rvfiStages, Bool()))
  if (writebackStage) {
    rvfi_stage_valid_d(0) := (rvfi_id_done && !dummy_instr_id) || (rvfi_stage_valid(0) && !rvfi_wb_done)
    rvfi_stage_valid_d(1) := rvfi_wb_done
  } else {
    rvfi_stage_valid_d(0) := rvfi_id_done && !dummy_instr_id
  }
  val rvfi_stage_order_d = Mux(dummy_instr_id, rvfi_stage_order(0), rvfi_stage_order(0) + 1.U)
  val rvfi_intr_d = Mux(instr_first_cycle_id, rvfi_set_trap_pc_q, rvfi_intr_q)
  val rvfi_instr_new_wb = Wire(Bool())
  if (writebackStage) {
    val rvfi_instr_new_wb_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    rvfi_instr_new_wb := rvfi_instr_new_wb_q || (rvfi_stage_valid(0) && rvfi_stage_trap(0))
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      rvfi_instr_new_wb_q := rvfi_id_done
    }
  } else {
    rvfi_instr_new_wb := instr_new_id
  }

  val rvfi_rd_addr_d = Wire(UInt(5.W))
  val rvfi_rd_wdata_d = Wire(UInt(32.W))
  rvfi_rd_addr_d := Mux(
    rvfi_rd_we_wb,
    rvfi_rd_addr_wb,
    Mux(rvfi_instr_new_wb, 0.U, rvfi_rd_addr_q))
  rvfi_rd_wdata_d := Mux(
    rvfi_rd_we_wb,
    Mux(rvfi_rd_addr_wb === 0.U, 0.U, rvfi_rd_wdata_wb),
    Mux(rvfi_instr_new_wb, 0.U, rvfi_rd_wdata_q))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    rvfi_ext_stage_irq_valid(0) := rvfi_irq_valid_q
    for (i <- 1 to rvfiStages) {
      rvfi_ext_stage_irq_valid(i) := rvfi_ext_stage_irq_valid(i - 1)
    }

    when((instr_new_id_d && instr_valid_id_d) || rvfi_irq_valid_q) {
      rvfi_ext_stage_pre_mip(0) := Mux(instr_valid_id || !rvfi_captured_valid_q, rvfi_mip_bits, rvfi_captured_mip_q)
      rvfi_ext_stage_nmi(0) := Mux(instr_valid_id || !rvfi_captured_valid_q, irq_nm_i, rvfi_captured_nmi_q)
      rvfi_ext_stage_nmi_int(0) := Mux(instr_valid_id || !rvfi_captured_valid_q, irq_nm_int, rvfi_captured_nmi_int_q)
      rvfi_ext_stage_debug_req(0) := Mux(instr_valid_id || !rvfi_captured_valid_q, debug_req_i, rvfi_captured_debug_req_q)
    }

    for (i <- 0 until rvfiStages) {
      rvfi_stage_valid(i) := rvfi_stage_valid_d(i)
    }

    when(rvfi_id_done) {
      rvfi_stage_halt(0) := false.B
      rvfi_stage_trap(0) := rvfi_trap_id
      rvfi_stage_intr(0) := rvfi_intr_d
      rvfi_stage_order(0) := rvfi_stage_order_d
      rvfi_stage_insn(0) := rvfi_insn_id
      rvfi_stage_mode(0) := priv_mode_id
      rvfi_stage_ixl(0) := IbexPkg.CSR_MISA_MXL
      rvfi_stage_rs1_addr(0) := rvfi_rs1_addr_d
      rvfi_stage_rs2_addr(0) := rvfi_rs2_addr_d
      rvfi_stage_rs3_addr(0) := rvfi_rs3_addr_d
      rvfi_stage_rs1_rdata(0) := rvfi_rs1_data_d
      rvfi_stage_rs2_rdata(0) := rvfi_rs2_data_d
      rvfi_stage_rs3_rdata(0) := rvfi_rs3_data_d
      rvfi_stage_rd_addr(0) := rvfi_rd_addr_d
      rvfi_stage_rd_wdata(0) := rvfi_rd_wdata_d
      rvfi_stage_pc_rdata(0) := pc_id
      rvfi_stage_pc_wdata(0) := Mux(pc_set, branch_target_ex, pc_if)
      rvfi_stage_mem_addr(0) := rvfi_mem_addr_d
      rvfi_stage_mem_rmask(0) := Mux(data_we_o, 0.U, rvfi_mem_mask_aligned)
      rvfi_stage_mem_wmask(0) := Mux(data_we_o, rvfi_mem_mask_aligned, 0.U)
      rvfi_stage_mem_rdata(0) := rvfi_mem_rdata_d
      rvfi_stage_mem_wdata(0) := rvfi_mem_wdata_hold_d
      rvfi_ext_stage_debug_mode(0) := debug_mode
      rvfi_ext_stage_mcycle(0) := cs_registers_i.mcycle_o
      rvfi_ext_stage_ic_scr_key_valid(0) := cs_registers_i.ic_scr_key_valid_o
      rvfi_ext_stage_expanded_insn_valid(0) := rvfi_expanded_insn_valid_d
      rvfi_ext_stage_expanded_insn(0) := instr_expanded_id
      rvfi_ext_stage_expanded_insn_last(0) := instr_gets_expanded_id === IbexPkg.InstrExp.ExpandedLast.asUInt
      for (i <- 0 until 10) {
        rvfi_ext_stage_mhpmcounters(0)(i) := cs_registers_i.mhpmcounter_o(i)(31, 0)
        rvfi_ext_stage_mhpmcountersh(0)(i) := cs_registers_i.mhpmcounter_o(i)(63, 32)
      }
    }

    when(rvfi_id_done || rvfi_ext_stage_irq_valid(0)) {
      rvfi_ext_stage_pre_mip(1) := rvfi_ext_stage_pre_mip(0)
      rvfi_ext_stage_post_mip(0) := rvfi_mip_bits
      rvfi_ext_stage_nmi(1) := rvfi_ext_stage_nmi(0)
      rvfi_ext_stage_nmi_int(1) := rvfi_ext_stage_nmi_int(0)
      rvfi_ext_stage_debug_req(1) := rvfi_ext_stage_debug_req(0)
    }

    if (writebackStage) {
      when(rvfi_wb_done) {
        rvfi_stage_halt(1) := rvfi_stage_halt(0)
        rvfi_stage_trap(1) := rvfi_stage_trap(0) || rvfi_trap_wb
        rvfi_stage_intr(1) := rvfi_stage_intr(0)
        rvfi_stage_order(1) := rvfi_stage_order(0)
        rvfi_stage_insn(1) := rvfi_stage_insn(0)
        rvfi_stage_mode(1) := rvfi_stage_mode(0)
        rvfi_stage_ixl(1) := rvfi_stage_ixl(0)
        rvfi_stage_rs1_addr(1) := rvfi_stage_rs1_addr(0)
        rvfi_stage_rs2_addr(1) := rvfi_stage_rs2_addr(0)
        rvfi_stage_rs3_addr(1) := rvfi_stage_rs3_addr(0)
        rvfi_stage_rs1_rdata(1) := rvfi_stage_rs1_rdata(0)
        rvfi_stage_rs2_rdata(1) := rvfi_stage_rs2_rdata(0)
        rvfi_stage_rs3_rdata(1) := rvfi_stage_rs3_rdata(0)
        rvfi_stage_rd_addr(1) := rvfi_rd_addr_d
        rvfi_stage_rd_wdata(1) := rvfi_rd_wdata_d
        rvfi_stage_pc_rdata(1) := rvfi_stage_pc_rdata(0)
        rvfi_stage_pc_wdata(1) := rvfi_stage_pc_wdata(0)
        rvfi_stage_mem_addr(1) := rvfi_stage_mem_addr(0)
        rvfi_stage_mem_rmask(1) := rvfi_stage_mem_rmask(0)
        rvfi_stage_mem_wmask(1) := rvfi_stage_mem_wmask(0)
        rvfi_stage_mem_rdata(1) := rvfi_mem_rdata_d
        rvfi_stage_mem_wdata(1) := rvfi_stage_mem_wdata(0)
        rvfi_ext_stage_debug_mode(1) := rvfi_ext_stage_debug_mode(0)
        rvfi_ext_stage_mcycle(1) := rvfi_ext_stage_mcycle(0)
        rvfi_ext_stage_ic_scr_key_valid(1) := rvfi_ext_stage_ic_scr_key_valid(0)
        rvfi_ext_stage_mhpmcounters(1) := rvfi_ext_stage_mhpmcounters(0)
        rvfi_ext_stage_mhpmcountersh(1) := rvfi_ext_stage_mhpmcountersh(0)
        rvfi_ext_stage_expanded_insn_valid(1) := rvfi_ext_stage_expanded_insn_valid(0)
        rvfi_ext_stage_expanded_insn(1) := rvfi_ext_stage_expanded_insn(0)
        rvfi_ext_stage_expanded_insn_last(1) := rvfi_ext_stage_expanded_insn_last(0)
      }

      when(rvfi_wb_done || rvfi_ext_stage_irq_valid(1)) {
        rvfi_ext_stage_pre_mip(2) := rvfi_ext_stage_pre_mip(1)
        rvfi_ext_stage_post_mip(1) := rvfi_ext_stage_post_mip(0)
        rvfi_ext_stage_nmi(2) := rvfi_ext_stage_nmi(1)
        rvfi_ext_stage_nmi_int(2) := rvfi_ext_stage_nmi_int(1)
        rvfi_ext_stage_debug_req(2) := rvfi_ext_stage_debug_req(1)
      }
    }
  }

  if (writebackStage) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      when(rvfi_wb_done) {
        rvfi_ext_rf_wr_suppress_q := rvfi_ext_rf_wr_suppress_wb
      }
    }
  }

  val new_debug_req = debug_req_i && !debug_mode
  val new_nmi = irq_nm_i && !nmi_mode && !debug_mode
  val new_nmi_int = irq_nm_int && !nmi_mode && !debug_mode
  val new_irq = irq_pending_o && (csr_mstatus_mie || priv_mode_id === IbexPkg.PrivLvl.U) && !nmi_mode && !debug_mode
  val rvfi_capture_event = !instr_valid_id && (new_debug_req || new_irq || new_nmi || new_nmi_int)
  val rvfi_capture_take =
    rvfi_capture_event && ((!rvfi_captured_valid_q) ||
      (new_debug_req && !rvfi_captured_debug_req_q) ||
      (new_nmi && !rvfi_captured_nmi_q && !rvfi_captured_debug_req_q))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    rvfi_set_trap_pc_q := Mux(
      pc_set && pc_mux_id === IbexPkg.PcSel.Exc.asUInt &&
        (exc_pc_mux_id === IbexPkg.ExcPcSel.Exc.asUInt || exc_pc_mux_id === IbexPkg.ExcPcSel.Irq.asUInt),
      true.B,
      Mux(rvfi_set_trap_pc_q && rvfi_id_done, false.B, rvfi_set_trap_pc_q))
    rvfi_intr_q := Mux(instr_first_cycle_id, rvfi_set_trap_pc_q, rvfi_intr_q)
    rvfi_irq_valid_q := !instr_valid_id && !new_debug_req && (new_irq || new_nmi || new_nmi_int) &&
      ready_wb && !rvfi_captured_valid_q
    when(rvfi_capture_take) {
      rvfi_captured_valid_q := true.B
      rvfi_captured_mip_q := rvfi_mip_bits
      rvfi_captured_nmi_q := irq_nm_i
      rvfi_captured_nmi_int_q := irq_nm_int
      rvfi_captured_debug_req_q := debug_req_i
    }
    when(instr_valid_id_d) {
      rvfi_captured_valid_q := false.B
    }
  }

  rvfi_valid := rvfi_stage_valid(rvfiStages - 1)
  rvfi_order := rvfi_stage_order(rvfiStages - 1)
  rvfi_insn := rvfi_stage_insn(rvfiStages - 1)
  rvfi_trap := rvfi_stage_trap(rvfiStages - 1)
  rvfi_halt := rvfi_stage_halt(rvfiStages - 1)
  rvfi_intr := rvfi_stage_intr(rvfiStages - 1)
  rvfi_mode := rvfi_stage_mode(rvfiStages - 1)
  rvfi_ixl := rvfi_stage_ixl(rvfiStages - 1)
  rvfi_rs1_addr := rvfi_stage_rs1_addr(rvfiStages - 1)
  rvfi_rs2_addr := rvfi_stage_rs2_addr(rvfiStages - 1)
  rvfi_rs3_addr := rvfi_stage_rs3_addr(rvfiStages - 1)
  rvfi_rs1_rdata := rvfi_stage_rs1_rdata(rvfiStages - 1)
  rvfi_rs2_rdata := rvfi_stage_rs2_rdata(rvfiStages - 1)
  rvfi_rs3_rdata := rvfi_stage_rs3_rdata(rvfiStages - 1)
  rvfi_rd_addr := rvfi_stage_rd_addr(rvfiStages - 1)
  rvfi_rd_wdata := rvfi_stage_rd_wdata(rvfiStages - 1)
  rvfi_pc_rdata := rvfi_stage_pc_rdata(rvfiStages - 1)
  rvfi_pc_wdata := rvfi_stage_pc_wdata(rvfiStages - 1)
  rvfi_mem_addr := rvfi_stage_mem_addr(rvfiStages - 1)
  rvfi_mem_rmask := rvfi_stage_mem_rmask(rvfiStages - 1)
  rvfi_mem_wmask := rvfi_stage_mem_wmask(rvfiStages - 1)
  rvfi_mem_rdata := rvfi_stage_mem_rdata(rvfiStages - 1)
  rvfi_mem_wdata := rvfi_stage_mem_wdata(rvfiStages - 1)
  rvfi_ext_pre_mip := rvfi_ext_stage_pre_mip(rvfiStages)
  rvfi_ext_post_mip := rvfi_ext_stage_post_mip(rvfiStages - 1)
  rvfi_ext_nmi := rvfi_ext_stage_nmi(rvfiStages)
  rvfi_ext_nmi_int := rvfi_ext_stage_nmi_int(rvfiStages)
  rvfi_ext_debug_req := rvfi_ext_stage_debug_req(rvfiStages)
  rvfi_ext_debug_mode := rvfi_ext_stage_debug_mode(rvfiStages - 1)
  rvfi_ext_rf_wr_suppress := rvfi_ext_rf_wr_suppress_q
  rvfi_ext_mcycle := rvfi_ext_stage_mcycle(rvfiStages - 1)
  for (i <- 0 until 10) {
    rvfi_ext_mhpmcounters(i) := rvfi_ext_stage_mhpmcounters(rvfiStages - 1)(i)
    rvfi_ext_mhpmcountersh(i) := rvfi_ext_stage_mhpmcountersh(rvfiStages - 1)(i)
  }
  rvfi_ext_ic_scr_key_valid := rvfi_ext_stage_ic_scr_key_valid(rvfiStages - 1)
  rvfi_ext_irq_valid := rvfi_ext_stage_irq_valid(rvfiStages)
  rvfi_ext_expanded_insn_valid := rvfi_ext_stage_expanded_insn_valid(rvfiStages - 1)
  rvfi_ext_expanded_insn := rvfi_ext_stage_expanded_insn(rvfiStages - 1)
  rvfi_ext_expanded_insn_last := rvfi_ext_stage_expanded_insn_last(rvfiStages - 1)

  // Keep unused bookkeeping signals live for elaboration hygiene, matching SV unused wires.
  dontTouch(priv_mode_lsu)
  dontTouch(instr_new_id)
  dontTouch(instr_gets_expanded_id)
  dontTouch(instr_expanded_id)
  dontTouch(expecting_load_resp_id)
  dontTouch(expecting_store_resp_id)

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(!dummy_instr_wb || !rf_we_wb || rf_waddr_wb === 0.U,
      "WaddrAZeroForDummyInstr")
    assert(crash_dump_o.current_pc === pc_id,
      "CrashDumpCurrentPCConn")
    assert(crash_dump_o.next_pc === pc_if,
      "CrashDumpNextPCConn")
    assert(crash_dump_o.last_data_addr === lsu_addr_last,
      "CrashDumpLastDataAddrConn")
    assert(crash_dump_o.exception_pc === csr_mepc,
      "CrashDumpExceptionPCConn")
    assert(crash_dump_o.exception_addr === crash_dump_mtval,
      "CrashDumpExceptionAddrConn")
    assert(!instr_valid_id ||
      csr_op === IbexPkg.CsrOp.READ.asUInt ||
      csr_op === IbexPkg.CsrOp.WRITE.asUInt ||
      csr_op === IbexPkg.CsrOp.SET.asUInt ||
      csr_op === IbexPkg.CsrOp.CLEAR.asUInt,
      "IbexCsrOpValid")
  }
}
