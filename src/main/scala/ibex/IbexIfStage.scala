// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexIfStage(
    dmHaltAddr: BigInt = BigInt("1a110800", 16),
    dmExceptionAddr: BigInt = BigInt("1a110808", 16),
    dummyInstructions: Boolean = false,
    iCache: Boolean = false,
    rv32zc: Int = 3,
    iCacheECC: Boolean = false,
    iCacheTweakInfection: Boolean = false,
    busSizeECC: Int = IbexPkg.BUS_SIZE,
    tagSizeECC: Int = IbexPkg.IC_TAG_SIZE,
    lineSizeECC: Int = IbexPkg.IC_LINE_SIZE,
    pcIncrCheck: Boolean = false,
    resetAll: Boolean = false,
    rndCnstLfsrSeed: BigInt = 0xac533bf4L,
    rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16),
    branchPredictor: Boolean = false,
    memECC: Boolean = false,
    memDataWidth: Int = 0)
    extends RawModule {
  private val MemDataWidth = if (memDataWidth == 0) {
    if (memECC) 39 else 32
  } else {
    memDataWidth
  }

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val boot_addr_i = IO(Input(UInt(32.W)))
  val req_i = IO(Input(Bool()))

  val instr_req_o = IO(Output(Bool()))
  val instr_addr_o = IO(Output(UInt(32.W)))
  val instr_gnt_i = IO(Input(Bool()))
  val instr_rvalid_i = IO(Input(Bool()))
  val instr_rdata_i = IO(Input(UInt(MemDataWidth.W)))
  val instr_bus_err_i = IO(Input(Bool()))
  val instr_intg_err_o = IO(Output(Bool()))

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

  val instr_valid_id_o = IO(Output(Bool()))
  val instr_new_id_o = IO(Output(Bool()))
  val instr_valid_id_d_o = IO(Output(Bool()))
  val instr_new_id_d_o = IO(Output(Bool()))
  val instr_rdata_id_o = IO(Output(UInt(32.W)))
  val instr_rdata_alu_id_o = IO(Output(UInt(32.W)))
  val instr_rdata_c_id_o = IO(Output(UInt(16.W)))
  val instr_is_compressed_id_o = IO(Output(Bool()))
  val instr_gets_expanded_id_o = IO(Output(UInt(2.W)))
  val instr_expanded_id_o = IO(Output(UInt(16.W)))
  val instr_bp_taken_o = IO(Output(Bool()))
  val instr_fetch_err_o = IO(Output(Bool()))
  val instr_fetch_err_plus2_o = IO(Output(Bool()))
  val illegal_c_insn_id_o = IO(Output(Bool()))
  val dummy_instr_id_o = IO(Output(Bool()))
  val pc_if_o = IO(Output(UInt(32.W)))
  val pc_id_o = IO(Output(UInt(32.W)))
  val pmp_err_if_i = IO(Input(Bool()))
  val pmp_err_if_plus2_i = IO(Input(Bool()))

  val instr_valid_clear_i = IO(Input(Bool()))
  val pc_set_i = IO(Input(Bool()))
  val pc_mux_i = IO(Input(UInt(3.W)))
  val nt_branch_mispredict_i = IO(Input(Bool()))
  val nt_branch_addr_i = IO(Input(UInt(32.W)))
  val exc_pc_mux_i = IO(Input(UInt(2.W)))
  val exc_cause = IO(Input(UInt(7.W)))
  val dummy_instr_en_i = IO(Input(Bool()))
  val dummy_instr_mask_i = IO(Input(UInt(3.W)))
  val dummy_instr_seed_en_i = IO(Input(Bool()))
  val dummy_instr_seed_i = IO(Input(UInt(32.W)))
  val icache_enable_i = IO(Input(Bool()))
  val icache_inval_i = IO(Input(Bool()))
  val icache_ecc_error_o = IO(Output(Bool()))

  val branch_target_ex_i = IO(Input(UInt(32.W)))
  val csr_mepc_i = IO(Input(UInt(32.W)))
  val csr_depc_i = IO(Input(UInt(32.W)))
  val csr_mtvec_i = IO(Input(UInt(32.W)))
  val csr_mtvec_init_o = IO(Output(Bool()))

  val id_in_ready_i = IO(Input(Bool()))

  val pc_mismatch_alert_o = IO(Output(Bool()))
  val if_busy_o = IO(Output(Bool()))

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

  private object InstrExp {
    val NOT_EXPANDED = 0.U(2.W)
    val EXPANDED = 1.U(2.W)
    val EXPANDED_LAST = 2.U(2.W)
  }

  val instr_valid_id_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val instr_new_id_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }

  val instr_intg_err = Wire(Bool())
  if (memECC) {
    val instr_intg_dec = Module(new PrimSecdedInv3932Dec)
    instr_intg_dec.data_i := instr_rdata_i
    instr_intg_err := instr_intg_dec.err_o.orR
  } else {
    instr_intg_err := false.B
  }
  val instr_err = instr_intg_err || instr_bus_err_i
  instr_intg_err_o := instr_intg_err && instr_rvalid_i

  val irq_vec = WireDefault(exc_cause(4, 0))
  when(exc_cause(5)) {
    irq_vec := 31.U
  }
  val exc_pc = Wire(UInt(32.W))
  exc_pc := Cat(csr_mtvec_i(31, 8), 0.U(8.W))
  switch(exc_pc_mux_i) {
    is(ExcPcSel.EXC) {
      exc_pc := Cat(csr_mtvec_i(31, 8), 0.U(8.W))
    }
    is(ExcPcSel.IRQ) {
      exc_pc := Cat(csr_mtvec_i(31, 8), 0.U(1.W), irq_vec, 0.U(2.W))
    }
    is(ExcPcSel.DBD) {
      exc_pc := dmHaltAddr.U(32.W)
    }
    is(ExcPcSel.DBG_EXC) {
      exc_pc := dmExceptionAddr.U(32.W)
    }
  }

  val predict_branch_taken = WireDefault(false.B)
  val predict_branch_pc = WireDefault(0.U(32.W))
  val pc_mux_internal = Mux(branchPredictor.B && predict_branch_taken && !pc_set_i, PcSel.BP, pc_mux_i)
  val fetch_addr_n = Wire(UInt(32.W))
  fetch_addr_n := Cat(boot_addr_i(31, 8), "h80".U(8.W))
  switch(pc_mux_internal) {
    is(PcSel.BOOT) { fetch_addr_n := Cat(boot_addr_i(31, 8), "h80".U(8.W)) }
    is(PcSel.JUMP) { fetch_addr_n := branch_target_ex_i }
    is(PcSel.EXC) { fetch_addr_n := exc_pc }
    is(PcSel.ERET) { fetch_addr_n := csr_mepc_i }
    is(PcSel.DRET) { fetch_addr_n := csr_depc_i }
    is(PcSel.BP) { fetch_addr_n := Mux(branchPredictor.B, predict_branch_pc, Cat(boot_addr_i(31, 8), "h80".U(8.W))) }
  }
  csr_mtvec_init_o := pc_mux_i === PcSel.BOOT && pc_set_i

  val branch_req = pc_set_i || predict_branch_taken
  val prefetch_branch = branch_req || nt_branch_mispredict_i
  val prefetch_addr = Mux(branch_req, Cat(fetch_addr_n(31, 1), 0.U(1.W)), nt_branch_addr_i)

  val prefetch_busy = Wire(Bool())
  val fetch_valid_raw = Wire(Bool())
  val fetch_valid = fetch_valid_raw && !nt_branch_mispredict_i
  val fetch_ready = Wire(Bool())
  val fetch_rdata = Wire(UInt(32.W))
  val fetch_addr = Wire(UInt(32.W))
  val fetch_err = Wire(Bool())
  val fetch_err_plus2 = Wire(Bool())

  if (iCache) {
    val icache_i = Module(new IbexIcache(
      iCacheECC = iCacheECC,
      resetAll = resetAll,
      busSizeECC = busSizeECC,
      tagSizeECC = tagSizeECC,
      lineSizeECC = lineSizeECC,
      tweakInfection = iCacheTweakInfection))
    icache_i.clk_i := clk_i
    icache_i.rst_ni := rst_ni
    icache_i.req_i := req_i
    icache_i.branch_i := prefetch_branch
    icache_i.addr_i := prefetch_addr
    icache_i.ready_i := fetch_ready
    fetch_valid_raw := icache_i.valid_o
    fetch_rdata := icache_i.rdata_o
    fetch_addr := icache_i.addr_o
    fetch_err := icache_i.err_o
    fetch_err_plus2 := icache_i.err_plus2_o
    instr_req_o := icache_i.instr_req_o
    instr_addr_o := icache_i.instr_addr_o
    icache_i.instr_gnt_i := instr_gnt_i
    icache_i.instr_rvalid_i := instr_rvalid_i
    icache_i.instr_rdata_i := instr_rdata_i(31, 0)
    icache_i.instr_err_i := instr_err
    ic_tag_req_o := icache_i.ic_tag_req_o
    ic_tag_write_o := icache_i.ic_tag_write_o
    ic_tag_addr_o := icache_i.ic_tag_addr_o
    ic_tag_wdata_o := icache_i.ic_tag_wdata_o
    icache_i.ic_tag_rdata_i := ic_tag_rdata_i
    ic_data_req_o := icache_i.ic_data_req_o
    ic_data_write_o := icache_i.ic_data_write_o
    ic_data_addr_o := icache_i.ic_data_addr_o
    ic_data_wdata_o := icache_i.ic_data_wdata_o
    icache_i.ic_data_rdata_i := ic_data_rdata_i
    icache_i.ic_scr_key_valid_i := ic_scr_key_valid_i
    ic_scr_key_req_o := icache_i.ic_scr_key_req_o
    icache_i.icache_enable_i := icache_enable_i
    icache_i.icache_inval_i := icache_inval_i
    prefetch_busy := icache_i.busy_o
    icache_ecc_error_o := icache_i.ecc_error_o
  } else {
    val prefetch_buffer_i = Module(new IbexPrefetchBuffer(resetAll = resetAll))
    prefetch_buffer_i.clk_i := clk_i
    prefetch_buffer_i.rst_ni := rst_ni
    prefetch_buffer_i.req_i := req_i
    prefetch_buffer_i.branch_i := prefetch_branch
    prefetch_buffer_i.addr_i := prefetch_addr
    prefetch_buffer_i.ready_i := fetch_ready
    fetch_valid_raw := prefetch_buffer_i.valid_o
    fetch_rdata := prefetch_buffer_i.rdata_o
    fetch_addr := prefetch_buffer_i.addr_o
    fetch_err := prefetch_buffer_i.err_o
    fetch_err_plus2 := prefetch_buffer_i.err_plus2_o
    instr_req_o := prefetch_buffer_i.instr_req_o
    instr_addr_o := prefetch_buffer_i.instr_addr_o
    prefetch_buffer_i.instr_gnt_i := instr_gnt_i
    prefetch_buffer_i.instr_rvalid_i := instr_rvalid_i
    prefetch_buffer_i.instr_rdata_i := instr_rdata_i(31, 0)
    prefetch_buffer_i.instr_err_i := instr_err
    prefetch_busy := prefetch_buffer_i.busy_o

    ic_tag_req_o := 0.U
    ic_tag_write_o := false.B
    ic_tag_addr_o := 0.U
    ic_tag_wdata_o := 0.U
    ic_data_req_o := 0.U
    ic_data_write_o := false.B
    ic_data_addr_o := 0.U
    ic_data_wdata_o := 0.U
    ic_scr_key_req_o := false.B
    icache_ecc_error_o := false.B
  }

  val instr_decompressed = Wire(UInt(32.W))
  val illegal_c_insn = Wire(Bool())
  val instr_is_compressed = Wire(Bool())
  val instr_gets_expanded = Wire(UInt(2.W))
  val if_instr_valid = Wire(Bool())
  val if_instr_rdata = Wire(UInt(32.W))
  val if_instr_addr = Wire(UInt(32.W))
  val if_instr_bus_err = Wire(Bool())

  pc_if_o := if_instr_addr
  if_busy_o := prefetch_busy

  val compressed_decoder_i = Module(new IbexCompressedDecoder(rv32zc = rv32zc, resetAll = resetAll))
  compressed_decoder_i.clk_i := clk_i
  compressed_decoder_i.rst_ni := rst_ni
  compressed_decoder_i.valid_i := fetch_valid && !fetch_err
  compressed_decoder_i.id_in_ready_i := id_in_ready_i && !pc_set_i
  compressed_decoder_i.instr_i := if_instr_rdata
  instr_decompressed := compressed_decoder_i.instr_o
  instr_is_compressed := compressed_decoder_i.is_compressed_o
  instr_gets_expanded := compressed_decoder_i.gets_expanded_o
  illegal_c_insn := compressed_decoder_i.illegal_instr_o

  val if_instr_pmp_err = pmp_err_if_i || (if_instr_addr(1) && !instr_is_compressed && pmp_err_if_plus2_i)
  val if_instr_err = if_instr_bus_err || if_instr_pmp_err
  val if_instr_err_plus2 = ((if_instr_addr(1) && !instr_is_compressed && pmp_err_if_plus2_i) || fetch_err_plus2) && !pmp_err_if_i

  val stall_dummy_instr = WireDefault(false.B)
  val instr_out = WireDefault(instr_decompressed)
  val instr_is_compressed_out = WireDefault(instr_is_compressed)
  val instr_gets_expanded_out = WireDefault(instr_gets_expanded)
  val illegal_c_instr_out = WireDefault(illegal_c_insn)
  val instr_err_out = WireDefault(if_instr_err)
  val if_id_pipe_reg_we = Wire(Bool())

  if (dummyInstructions) {
    val dummy_instr_i = Module(new IbexDummyInstr(rndCnstLfsrSeed = rndCnstLfsrSeed, rndCnstLfsrPerm = rndCnstLfsrPerm))
    dummy_instr_i.clk_i := clk_i
    dummy_instr_i.rst_ni := rst_ni
    dummy_instr_i.dummy_instr_en_i := dummy_instr_en_i
    dummy_instr_i.dummy_instr_mask_i := dummy_instr_mask_i
    dummy_instr_i.dummy_instr_seed_en_i := dummy_instr_seed_en_i
    dummy_instr_i.dummy_instr_seed_i := dummy_instr_seed_i
    dummy_instr_i.fetch_valid_i := fetch_valid
    dummy_instr_i.id_in_ready_i := id_in_ready_i
    val insert_dummy_instr = dummy_instr_i.insert_dummy_instr_o
    instr_out := Mux(insert_dummy_instr, dummy_instr_i.dummy_instr_data_o, instr_decompressed)
    instr_is_compressed_out := Mux(insert_dummy_instr, false.B, instr_is_compressed)
    instr_gets_expanded_out := Mux(insert_dummy_instr, InstrExp.NOT_EXPANDED, instr_gets_expanded)
    illegal_c_instr_out := Mux(insert_dummy_instr, false.B, illegal_c_insn)
    instr_err_out := Mux(insert_dummy_instr, false.B, if_instr_err)
    stall_dummy_instr := insert_dummy_instr
    val dummy_instr_id_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    when(if_id_pipe_reg_we) {
      dummy_instr_id_q := insert_dummy_instr
    }
    dummy_instr_id_o := dummy_instr_id_q
  } else {
    dummy_instr_id_o := false.B
  }

  val instr_bp_taken_d = WireDefault(false.B)
  if (branchPredictor) {
    val instr_skid_data_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) } else withClock(clk_i) { Reg(UInt(32.W)) }
    val instr_skid_addr_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) } else withClock(clk_i) { Reg(UInt(32.W)) }
    val instr_skid_bp_taken_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else withClock(clk_i) { Reg(Bool()) }
    val instr_skid_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    val instr_bp_taken_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else withClock(clk_i) { Reg(Bool()) }
    val predict_branch_taken_raw = Wire(Bool())

    val branch_predict_i = Module(new IbexBranchPredict)
    branch_predict_i.clk_i := clk_i
    branch_predict_i.rst_ni := rst_ni
    branch_predict_i.fetch_rdata_i := fetch_rdata
    branch_predict_i.fetch_pc_i := fetch_addr
    branch_predict_i.fetch_valid_i := fetch_valid
    predict_branch_taken_raw := branch_predict_i.predict_branch_taken_o
    predict_branch_pc := branch_predict_i.predict_branch_pc_o
    predict_branch_taken := predict_branch_taken_raw && !instr_skid_valid_q && !fetch_err

    val instr_skid_en = predict_branch_taken && !pc_set_i && !id_in_ready_i && !instr_skid_valid_q
    val instr_skid_valid_d = (instr_skid_valid_q && !id_in_ready_i && !stall_dummy_instr &&
      instr_gets_expanded =/= InstrExp.EXPANDED) || instr_skid_en
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      instr_skid_valid_q := instr_skid_valid_d
    }
    withClock(clk_i) {
      when(instr_skid_en) {
        instr_skid_bp_taken_q := predict_branch_taken
        instr_skid_data_q := fetch_rdata
        instr_skid_addr_q := fetch_addr
      }
      when(if_id_pipe_reg_we) {
        instr_bp_taken_q := instr_bp_taken_d
      }
    }

    if_instr_valid := fetch_valid || (instr_skid_valid_q && !nt_branch_mispredict_i)
    if_instr_rdata := Mux(instr_skid_valid_q, instr_skid_data_q, fetch_rdata)
    if_instr_addr := Mux(instr_skid_valid_q, instr_skid_addr_q, fetch_addr)
    if_instr_bus_err := !instr_skid_valid_q && fetch_err
    instr_bp_taken_d := Mux(instr_skid_valid_q, instr_skid_bp_taken_q, predict_branch_taken)
    fetch_ready := id_in_ready_i && !stall_dummy_instr && instr_gets_expanded =/= InstrExp.EXPANDED && !instr_skid_valid_q
    instr_bp_taken_o := instr_bp_taken_q
  } else {
    predict_branch_taken := false.B
    predict_branch_pc := 0.U
    if_instr_valid := fetch_valid
    if_instr_rdata := fetch_rdata
    if_instr_addr := fetch_addr
    if_instr_bus_err := fetch_err
    fetch_ready := id_in_ready_i && !stall_dummy_instr && instr_gets_expanded =/= InstrExp.EXPANDED
    instr_bp_taken_o := false.B
  }

  val instr_valid_id_d = (if_instr_valid && id_in_ready_i && !pc_set_i) ||
    (instr_valid_id_q && !instr_valid_clear_i)
  val instr_new_id_d = if_instr_valid && id_in_ready_i && !pc_set_i

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    instr_valid_id_q := instr_valid_id_d
    instr_new_id_q := instr_new_id_d
  }

  instr_valid_id_o := instr_valid_id_q
  instr_new_id_o := instr_new_id_q
  instr_valid_id_d_o := instr_valid_id_d
  instr_new_id_d_o := instr_new_id_d
  if_id_pipe_reg_we := instr_new_id_d

  val instr_rdata_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) } else withClock(clk_i) { Reg(UInt(32.W)) }
  val instr_rdata_alu_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) } else withClock(clk_i) { Reg(UInt(32.W)) }
  val instr_fetch_err_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else withClock(clk_i) { Reg(Bool()) }
  val instr_fetch_err_plus2_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else withClock(clk_i) { Reg(Bool()) }
  val instr_rdata_c_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(16.W)) } else withClock(clk_i) { Reg(UInt(16.W)) }
  val instr_is_compressed_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else withClock(clk_i) { Reg(Bool()) }
  val instr_gets_expanded_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(InstrExp.NOT_EXPANDED) } else withClock(clk_i) { Reg(UInt(2.W)) }
  val instr_expanded_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(16.W)) } else withClock(clk_i) { Reg(UInt(16.W)) }
  val illegal_c_insn_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) } else withClock(clk_i) { Reg(Bool()) }
  val pc_id_q = if (resetAll) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) } else withClock(clk_i) { Reg(UInt(32.W)) }

  withClock(clk_i) {
    when(if_id_pipe_reg_we) {
      instr_rdata_id_q := instr_out
      instr_rdata_alu_id_q := instr_out
      instr_fetch_err_q := instr_err_out
      instr_fetch_err_plus2_q := if_instr_err_plus2
      instr_rdata_c_id_q := if_instr_rdata(15, 0)
      instr_is_compressed_id_q := instr_is_compressed_out
      instr_gets_expanded_id_q := instr_gets_expanded_out
      instr_expanded_id_q := if_instr_rdata(15, 0)
      illegal_c_insn_id_q := illegal_c_instr_out
      pc_id_q := pc_if_o
    }
  }

  instr_rdata_id_o := instr_rdata_id_q
  instr_rdata_alu_id_o := instr_rdata_alu_id_q
  instr_fetch_err_o := instr_fetch_err_q
  instr_fetch_err_plus2_o := instr_fetch_err_plus2_q
  instr_rdata_c_id_o := instr_rdata_c_id_q
  instr_is_compressed_id_o := instr_is_compressed_id_q
  instr_gets_expanded_id_o := instr_gets_expanded_id_q
  instr_expanded_id_o := instr_expanded_id_q
  illegal_c_insn_id_o := illegal_c_insn_id_q
  pc_id_o := pc_id_q

  if (pcIncrCheck) {
    val prev_instr_seq_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    val prev_instr_seq_d = (prev_instr_seq_q || instr_new_id_d) && !branch_req && !if_instr_err &&
      !stall_dummy_instr && instr_gets_expanded =/= InstrExp.EXPANDED
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      prev_instr_seq_q := prev_instr_seq_d
    }
    val prev_instr_addr_incr = pc_id_o + Mux(instr_is_compressed_id_o, 2.U, 4.U)
    pc_mismatch_alert_o := prev_instr_seq_q && pc_if_o =/= prev_instr_addr_incr
  } else {
    pc_mismatch_alert_o := false.B
  }

  val pc_mux_valid = if (branchPredictor) {
    pc_mux_internal === PcSel.BOOT ||
      pc_mux_internal === PcSel.JUMP ||
      pc_mux_internal === PcSel.EXC ||
      pc_mux_internal === PcSel.ERET ||
      pc_mux_internal === PcSel.DRET ||
      pc_mux_internal === PcSel.BP
  } else {
    pc_mux_internal === PcSel.BOOT ||
      pc_mux_internal === PcSel.JUMP ||
      pc_mux_internal === PcSel.EXC ||
      pc_mux_internal === PcSel.ERET ||
      pc_mux_internal === PcSel.DRET
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(!nt_branch_mispredict_i || !branch_req,
      "NoMispredBranch")
    assert(!pc_set_i || pc_mux_valid,
      "IbexPcMuxValid")
    assert(boot_addr_i(7, 0) === 0.U,
      "IbexBootAddrUnaligned")
    assert(!instr_req_o || instr_addr_o(1, 0) === 0.U,
      "IbexInstrAddrUnaligned")
  }
}
