// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexTop(
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
    regFile: IbexPkg.RegFile.Type = IbexPkg.RegFile.FF,
    branchTargetALU: Boolean = false,
    writebackStage: Boolean = false,
    iCache: Boolean = false,
    iCacheECC: Boolean = false,
    branchPredictor: Boolean = false,
    dbgTriggerEn: Boolean = false,
    dbgHwBreakNum: Int = 1,
    secureIbex: Boolean = false,
    lockstepOffset: Int = 1,
    memECC: Boolean = false,
    memDataWidth: Int = 0,
    iCacheScramble: Boolean = false,
    iCacheScrNumPrinceRoundsHalf: Int = 2,
    iCacheTweakInfection: Boolean = false,
    rndCnstLfsrSeed: BigInt = 0xac533bf4L,
    rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16),
    rndCnstIbexKey: BigInt = IbexPkg.RndCnstIbexKeyDefault,
    rndCnstIbexNonce: BigInt = IbexPkg.RndCnstIbexNonceDefault,
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
  require((memECC && MemDataWidth == 39) || (!memECC && MemDataWidth == 32), "IbexTop MemDataWidth must be 39 with MemECC and 32 without MemECC")
  require(lockstepOffset >= 1, "LockstepOffset must be at least 1")
  require(iCacheScrNumPrinceRoundsHalf >= 0, "ICacheScrNumPrinceRoundsHalf must be non-negative")
  require(rndCnstIbexKey >= 0 && rndCnstIbexKey < (BigInt(1) << IbexPkg.SCRAMBLE_KEY_W), "RndCnstIbexKey must fit SCRAMBLE_KEY_W")
  require(rndCnstIbexNonce >= 0 && rndCnstIbexNonce < (BigInt(1) << IbexPkg.SCRAMBLE_NONCE_W), "RndCnstIbexNonce must fit SCRAMBLE_NONCE_W")

  private val lockstep = secureIbex
  private val resetAll = lockstep
  private val dummyInstructions = secureIbex
  private val regFileECC = false
  private val regFileLockstepECC = lockstep
  private val regFileDataWidth = 32
  private val regFileDataEccWidth = 39
  private val busSizeECC = if (iCacheECC) IbexPkg.BUS_SIZE + IbexPkg.IC_DATA_ECC_SIZE else IbexPkg.BUS_SIZE
  private val lineSizeECC = busSizeECC * IbexPkg.IC_LINE_BEATS
  private val tagSizeECC = if (iCacheECC) IbexPkg.IC_TAG_SIZE + IbexPkg.IC_TAG_ECC_SIZE else IbexPkg.IC_TAG_SIZE
  private val numAddrScrRounds = if (iCacheScramble) 2 else 0

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val test_en_i = IO(Input(Bool()))
  val ram_cfg_icache_tag_i = IO(Input(new PrimRam1PPkg.Ram1PCfg))
  val ram_cfg_rsp_icache_tag_o = IO(Output(Vec(IbexPkg.IC_NUM_WAYS, new PrimRam1PPkg.Ram1PCfgRsp)))
  val ram_cfg_icache_data_i = IO(Input(new PrimRam1PPkg.Ram1PCfg))
  val ram_cfg_rsp_icache_data_o = IO(Output(Vec(IbexPkg.IC_NUM_WAYS, new PrimRam1PPkg.Ram1PCfgRsp)))

  val hart_id_i = IO(Input(UInt(32.W)))
  val boot_addr_i = IO(Input(UInt(32.W)))

  val instr_req_o = IO(Output(Bool()))
  val instr_gnt_i = IO(Input(Bool()))
  val instr_rvalid_i = IO(Input(Bool()))
  val instr_addr_o = IO(Output(UInt(32.W)))
  val instr_rdata_i = IO(Input(UInt(32.W)))
  val instr_rdata_intg_i = IO(Input(UInt(7.W)))
  val instr_err_i = IO(Input(Bool()))

  val data_req_o = IO(Output(Bool()))
  val data_gnt_i = IO(Input(Bool()))
  val data_rvalid_i = IO(Input(Bool()))
  val data_we_o = IO(Output(Bool()))
  val data_be_o = IO(Output(UInt(4.W)))
  val data_addr_o = IO(Output(UInt(32.W)))
  val data_wdata_o = IO(Output(UInt(32.W)))
  val data_wdata_intg_o = IO(Output(UInt(7.W)))
  val data_rdata_i = IO(Input(UInt(32.W)))
  val data_rdata_intg_i = IO(Input(UInt(7.W)))
  val data_err_i = IO(Input(Bool()))

  val irq_software_i = IO(Input(Bool()))
  val irq_timer_i = IO(Input(Bool()))
  val irq_external_i = IO(Input(Bool()))
  val irq_fast_i = IO(Input(UInt(15.W)))
  val irq_nm_i = IO(Input(Bool()))

  val scramble_key_valid_i = IO(Input(Bool()))
  val scramble_key_i = IO(Input(UInt(IbexPkg.SCRAMBLE_KEY_W.W)))
  val scramble_nonce_i = IO(Input(UInt(IbexPkg.SCRAMBLE_NONCE_W.W)))
  val scramble_req_o = IO(Output(Bool()))

  val debug_req_i = IO(Input(Bool()))
  val crash_dump_o = IO(Output(new IbexPkg.CrashDump))
  val double_fault_seen_o = IO(Output(Bool()))

  val fetch_enable_i = IO(Input(UInt(IbexPkg.IbexMuBiWidth.W)))
  val alert_minor_o = IO(Output(Bool()))
  val alert_major_internal_o = IO(Output(Bool()))
  val alert_major_bus_o = IO(Output(Bool()))
  val core_sleep_o = IO(Output(Bool()))

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

  val scan_rst_ni = IO(Input(Bool()))

  val lockstep_cmp_en_o = IO(Output(UInt(IbexPkg.IbexMuBiWidth.W)))
  val data_req_shadow_o = IO(Output(Bool()))
  val data_we_shadow_o = IO(Output(Bool()))
  val data_be_shadow_o = IO(Output(UInt(4.W)))
  val data_addr_shadow_o = IO(Output(UInt(32.W)))
  val data_wdata_shadow_o = IO(Output(UInt(32.W)))
  val data_wdata_intg_shadow_o = IO(Output(UInt(7.W)))
  val instr_req_shadow_o = IO(Output(Bool()))
  val instr_addr_shadow_o = IO(Output(UInt(32.W)))

  val core_busy_d = Wire(UInt(IbexPkg.IbexMuBiWidth.W))
  val core_busy_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(core_busy_d, IbexPkg.IbexMuBiOff)
  }
  val irq_pending = Wire(Bool())
  val clock_en = (if (secureIbex) core_busy_q =/= IbexPkg.IbexMuBiOff else core_busy_q(0)) ||
    debug_req_i || irq_pending || irq_nm_i
  val clk = clk_i

  core_sleep_o := !clock_en

  val dummy_instr_id = Wire(Bool())
  val dummy_instr_wb = Wire(Bool())
  val rf_raddr_a = Wire(UInt(5.W))
  val rf_raddr_b = Wire(UInt(5.W))
  val rf_waddr_wb = Wire(UInt(5.W))
  val rf_we_wb = Wire(Bool())
  val rf_wdata_wb = Wire(UInt(regFileDataWidth.W))
  val rf_rdata_a = Wire(UInt(regFileDataWidth.W))
  val rf_rdata_b = Wire(UInt(regFileDataWidth.W))

  val core_alert_major_internal = Wire(Bool())
  val core_alert_major_bus = Wire(Bool())
  val core_alert_minor = Wire(Bool())
  val ic_tag_rdata = Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W)))
  val ic_data_rdata = Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W)))
  val instr_rdata_core = Wire(UInt(MemDataWidth.W))
  val data_rdata_core = Wire(UInt(MemDataWidth.W))
  val data_wdata_core = Wire(UInt(MemDataWidth.W))
  val scramble_key_q = Wire(UInt(IbexPkg.SCRAMBLE_KEY_W.W))
  val scramble_nonce_q = Wire(UInt(IbexPkg.SCRAMBLE_NONCE_W.W))
  val scramble_key_valid_q = Wire(Bool())
  val scramble_req_q = Wire(Bool())
  val scramble_key_valid_d = Wire(Bool())
  val scramble_req_d = Wire(Bool())

  val ibex_core_i = Module(new IbexCore(
    pmpEnable = pmpEnable,
    pmpGranularity = pmpGranularity,
    pmpNumRegions = pmpNumRegions,
    pmpRstCfg = pmpRstCfg,
    pmpRstAddr = pmpRstAddr,
    pmpRstMsecCfg = pmpRstMsecCfg,
    mhpmCounterNum = mhpmCounterNum,
    mhpmCounterWidth = mhpmCounterWidth,
    rv32e = rv32e,
    rv32m = rv32m,
    rv32b = rv32b,
    rv32zc = rv32zc,
    branchTargetALU = branchTargetALU,
    writebackStage = writebackStage,
    iCache = iCache,
    iCacheECC = iCacheECC,
    iCacheTweakInfection = iCacheTweakInfection,
    branchPredictor = branchPredictor,
    dbgTriggerEn = dbgTriggerEn,
    dbgHwBreakNum = dbgHwBreakNum,
    resetAll = resetAll,
    rndCnstLfsrSeed = rndCnstLfsrSeed,
    rndCnstLfsrPerm = rndCnstLfsrPerm,
    secureIbex = secureIbex,
    dummyInstructions = dummyInstructions,
    regFileECC = regFileECC,
    regFileDataWidth = regFileDataWidth,
    memECC = memECC,
    memDataWidth = MemDataWidth,
    dmBaseAddr = dmBaseAddr,
    dmAddrMask = dmAddrMask,
    dmHaltAddr = dmHaltAddr,
    dmExceptionAddr = dmExceptionAddr,
    csrMvendorId = csrMvendorId,
    csrMimpId = csrMimpId))
  ibex_core_i.clk_i := clk
  ibex_core_i.rst_ni := rst_ni
  ibex_core_i.hart_id_i := hart_id_i
  ibex_core_i.boot_addr_i := boot_addr_i
  instr_req_o := ibex_core_i.instr_req_o
  ibex_core_i.instr_gnt_i := instr_gnt_i
  ibex_core_i.instr_rvalid_i := instr_rvalid_i
  instr_addr_o := ibex_core_i.instr_addr_o
  ibex_core_i.instr_rdata_i := instr_rdata_core
  ibex_core_i.instr_err_i := instr_err_i
  data_req_o := ibex_core_i.data_req_o
  ibex_core_i.data_gnt_i := data_gnt_i
  ibex_core_i.data_rvalid_i := data_rvalid_i
  data_we_o := ibex_core_i.data_we_o
  data_be_o := ibex_core_i.data_be_o
  data_addr_o := ibex_core_i.data_addr_o
  data_wdata_core := ibex_core_i.data_wdata_o
  data_wdata_o := data_wdata_core(31, 0)
  ibex_core_i.data_rdata_i := data_rdata_core
  ibex_core_i.data_err_i := data_err_i
  dummy_instr_id := ibex_core_i.dummy_instr_id_o
  dummy_instr_wb := ibex_core_i.dummy_instr_wb_o
  rf_raddr_a := ibex_core_i.rf_raddr_a_o
  rf_raddr_b := ibex_core_i.rf_raddr_b_o
  rf_waddr_wb := ibex_core_i.rf_waddr_wb_o
  rf_we_wb := ibex_core_i.rf_we_wb_o
  rf_wdata_wb := ibex_core_i.rf_wdata_wb_ecc_o
  ibex_core_i.rf_rdata_a_ecc_i := rf_rdata_a
  ibex_core_i.rf_rdata_b_ecc_i := rf_rdata_b
  ibex_core_i.ic_scr_key_valid_i := scramble_key_valid_q
  ibex_core_i.irq_software_i := irq_software_i
  ibex_core_i.irq_timer_i := irq_timer_i
  ibex_core_i.irq_external_i := irq_external_i
  ibex_core_i.irq_fast_i := irq_fast_i
  ibex_core_i.irq_nm_i := irq_nm_i
  irq_pending := ibex_core_i.irq_pending_o
  ibex_core_i.debug_req_i := debug_req_i
  crash_dump_o := ibex_core_i.crash_dump_o
  double_fault_seen_o := ibex_core_i.double_fault_seen_o
  ibex_core_i.fetch_enable_i := fetch_enable_i
  core_alert_minor := ibex_core_i.alert_minor_o
  core_alert_major_internal := ibex_core_i.alert_major_internal_o
  core_alert_major_bus := ibex_core_i.alert_major_bus_o
  core_busy_d := ibex_core_i.core_busy_o
  rvfi_valid := ibex_core_i.rvfi_valid
  rvfi_order := ibex_core_i.rvfi_order
  rvfi_insn := ibex_core_i.rvfi_insn
  rvfi_trap := ibex_core_i.rvfi_trap
  rvfi_halt := ibex_core_i.rvfi_halt
  rvfi_intr := ibex_core_i.rvfi_intr
  rvfi_mode := ibex_core_i.rvfi_mode
  rvfi_ixl := ibex_core_i.rvfi_ixl
  rvfi_rs1_addr := ibex_core_i.rvfi_rs1_addr
  rvfi_rs2_addr := ibex_core_i.rvfi_rs2_addr
  rvfi_rs3_addr := ibex_core_i.rvfi_rs3_addr
  rvfi_rs1_rdata := ibex_core_i.rvfi_rs1_rdata
  rvfi_rs2_rdata := ibex_core_i.rvfi_rs2_rdata
  rvfi_rs3_rdata := ibex_core_i.rvfi_rs3_rdata
  rvfi_rd_addr := ibex_core_i.rvfi_rd_addr
  rvfi_rd_wdata := ibex_core_i.rvfi_rd_wdata
  rvfi_pc_rdata := ibex_core_i.rvfi_pc_rdata
  rvfi_pc_wdata := ibex_core_i.rvfi_pc_wdata
  rvfi_mem_addr := ibex_core_i.rvfi_mem_addr
  rvfi_mem_rmask := ibex_core_i.rvfi_mem_rmask
  rvfi_mem_wmask := ibex_core_i.rvfi_mem_wmask
  rvfi_mem_rdata := ibex_core_i.rvfi_mem_rdata
  rvfi_mem_wdata := ibex_core_i.rvfi_mem_wdata
  rvfi_ext_pre_mip := ibex_core_i.rvfi_ext_pre_mip
  rvfi_ext_post_mip := ibex_core_i.rvfi_ext_post_mip
  rvfi_ext_nmi := ibex_core_i.rvfi_ext_nmi
  rvfi_ext_nmi_int := ibex_core_i.rvfi_ext_nmi_int
  rvfi_ext_debug_req := ibex_core_i.rvfi_ext_debug_req
  rvfi_ext_debug_mode := ibex_core_i.rvfi_ext_debug_mode
  rvfi_ext_rf_wr_suppress := ibex_core_i.rvfi_ext_rf_wr_suppress
  rvfi_ext_mcycle := ibex_core_i.rvfi_ext_mcycle
  rvfi_ext_mhpmcounters := ibex_core_i.rvfi_ext_mhpmcounters
  rvfi_ext_mhpmcountersh := ibex_core_i.rvfi_ext_mhpmcountersh
  rvfi_ext_ic_scr_key_valid := ibex_core_i.rvfi_ext_ic_scr_key_valid
  rvfi_ext_irq_valid := ibex_core_i.rvfi_ext_irq_valid
  rvfi_ext_expanded_insn_valid := ibex_core_i.rvfi_ext_expanded_insn_valid
  rvfi_ext_expanded_insn := ibex_core_i.rvfi_ext_expanded_insn
  rvfi_ext_expanded_insn_last := ibex_core_i.rvfi_ext_expanded_insn_last
  probe_lsu_handle_misaligned_d := ibex_core_i.probe_lsu_handle_misaligned_d
  probe_lsu_addr_incr_req := ibex_core_i.probe_lsu_addr_incr_req
  probe_lsu_err_d := ibex_core_i.probe_lsu_err_d
  probe_lsu_data_offset := ibex_core_i.probe_lsu_data_offset
  probe_lsu_type := ibex_core_i.probe_lsu_type
  probe_priv_mode_lsu := ibex_core_i.probe_priv_mode_lsu

  def connectRegFile[T <: RawModule](rf: T): Unit = rf match {
    case ff: IbexRegisterFileFF =>
      ff.clk_i := clk
      ff.rst_ni := rst_ni
      ff.test_en_i := test_en_i
      ff.dummy_instr_id_i := dummy_instr_id
      ff.dummy_instr_wb_i := dummy_instr_wb
      ff.raddr_a_i := rf_raddr_a
      rf_rdata_a := ff.rdata_a_o
      ff.raddr_b_i := rf_raddr_b
      rf_rdata_b := ff.rdata_b_o
      ff.waddr_a_i := rf_waddr_wb
      ff.wdata_a_i := rf_wdata_wb
      ff.we_a_i := rf_we_wb
    case fpga: IbexRegisterFileFPGA =>
      fpga.clk_i := clk
      fpga.rst_ni := rst_ni
      fpga.test_en_i := test_en_i
      fpga.dummy_instr_id_i := dummy_instr_id
      fpga.dummy_instr_wb_i := dummy_instr_wb
      fpga.raddr_a_i := rf_raddr_a
      rf_rdata_a := fpga.rdata_a_o
      fpga.raddr_b_i := rf_raddr_b
      rf_rdata_b := fpga.rdata_b_o
      fpga.waddr_a_i := rf_waddr_wb
      fpga.wdata_a_i := rf_wdata_wb
      fpga.we_a_i := rf_we_wb
    case latch: IbexRegisterFileLatch =>
      latch.clk_i := clk
      latch.rst_ni := rst_ni
      latch.test_en_i := test_en_i
      latch.dummy_instr_id_i := dummy_instr_id
      latch.dummy_instr_wb_i := dummy_instr_wb
      latch.raddr_a_i := rf_raddr_a
      rf_rdata_a := latch.rdata_a_o
      latch.raddr_b_i := rf_raddr_b
      rf_rdata_b := latch.rdata_b_o
      latch.waddr_a_i := rf_waddr_wb
      latch.wdata_a_i := rf_wdata_wb
      latch.we_a_i := rf_we_wb
  }

  regFile match {
    case IbexPkg.RegFile.FF =>
      connectRegFile(Module(new IbexRegisterFileFF(
        rv32e = rv32e,
        dataWidth = regFileDataWidth,
        dummyInstructions = dummyInstructions,
        wordZeroVal = 0)))
    case IbexPkg.RegFile.FPGA =>
      connectRegFile(Module(new IbexRegisterFileFPGA(
        rv32e = rv32e,
        dataWidth = regFileDataWidth,
        dummyInstructions = dummyInstructions,
        wordZeroVal = 0)))
    case IbexPkg.RegFile.Latch =>
      connectRegFile(Module(new IbexRegisterFileLatch(
        rv32e = rv32e,
        dataWidth = regFileDataWidth,
        dummyInstructions = dummyInstructions,
        wordZeroVal = 0)))
  }

  if (iCacheScramble) {
    val scrambleKeyReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(rndCnstIbexKey.U(IbexPkg.SCRAMBLE_KEY_W.W))
    }
    val scrambleNonceReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(rndCnstIbexNonce.U(IbexPkg.SCRAMBLE_NONCE_W.W))
    }
    val scrambleKeyValidReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(true.B)
    }
    val scrambleReqReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(false.B)
    }

    scramble_key_valid_d := Mux(scrambleReqReg, scramble_key_valid_i, Mux(ibex_core_i.ic_scr_key_req_o, false.B, scrambleKeyValidReg))
    scramble_req_d := Mux(scrambleReqReg, !scramble_key_valid_i, ibex_core_i.ic_scr_key_req_o)
    when(scramble_key_valid_i) {
      scrambleKeyReg := scramble_key_i
      scrambleNonceReg := scramble_nonce_i
    }
    scrambleKeyValidReg := scramble_key_valid_d
    scrambleReqReg := scramble_req_d

    scramble_key_q := scrambleKeyReg
    scramble_nonce_q := scrambleNonceReg
    scramble_key_valid_q := scrambleKeyValidReg
    scramble_req_q := scrambleReqReg
  } else {
    scramble_key_q := 0.U
    scramble_nonce_q := 0.U
    scramble_key_valid_q := true.B
    scramble_key_valid_d := true.B
    scramble_req_q := false.B
    scramble_req_d := false.B
  }

  if (iCache) {
    for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
      if (iCacheScramble) {
        val tag_bank = Module(new PrimRam1PScr(
          width = tagSizeECC,
          depth = IbexPkg.IC_NUM_LINES,
          dataBitsPerMask = tagSizeECC,
          numPrinceRoundsHalf = iCacheScrNumPrinceRoundsHalf,
          numAddrScrRounds = numAddrScrRounds))
        tag_bank.clk_i := clk
        tag_bank.rst_ni := rst_ni
        tag_bank.key_valid_i := scramble_key_valid_q
        tag_bank.key_i := scramble_key_q
        tag_bank.nonce_i := scramble_nonce_q
        tag_bank.req_i := ibex_core_i.ic_tag_req_o(way)
        tag_bank.write_i := ibex_core_i.ic_tag_write_o
        tag_bank.addr_i := ibex_core_i.ic_tag_addr_o
        tag_bank.wdata_i := ibex_core_i.ic_tag_wdata_o
        tag_bank.wmask_i := Fill(tagSizeECC, true.B)
        tag_bank.intg_error_i := false.B
        tag_bank.cfg_i := ram_cfg_icache_tag_i
        ic_tag_rdata(way) := tag_bank.rdata_o
        ram_cfg_rsp_icache_tag_o(way) := tag_bank.cfg_rsp_o

        val data_bank = Module(new PrimRam1PScr(
          width = lineSizeECC,
          depth = IbexPkg.IC_NUM_LINES,
          dataBitsPerMask = lineSizeECC,
          numPrinceRoundsHalf = iCacheScrNumPrinceRoundsHalf,
          numAddrScrRounds = numAddrScrRounds,
          replicateKeyStream = true))
        data_bank.clk_i := clk
        data_bank.rst_ni := rst_ni
        data_bank.key_valid_i := scramble_key_valid_q
        data_bank.key_i := scramble_key_q
        data_bank.nonce_i := scramble_nonce_q
        data_bank.req_i := ibex_core_i.ic_data_req_o(way)
        data_bank.write_i := ibex_core_i.ic_data_write_o
        data_bank.addr_i := ibex_core_i.ic_data_addr_o
        data_bank.wdata_i := ibex_core_i.ic_data_wdata_o
        data_bank.wmask_i := Fill(lineSizeECC, true.B)
        data_bank.intg_error_i := false.B
        data_bank.cfg_i := ram_cfg_icache_data_i
        ic_data_rdata(way) := data_bank.rdata_o
        ram_cfg_rsp_icache_data_o(way) := data_bank.cfg_rsp_o
      } else {
        val tag_bank = Module(new PrimRam1P(
          width = tagSizeECC,
          depth = IbexPkg.IC_NUM_LINES,
          dataBitsPerMask = tagSizeECC))
        tag_bank.clk_i := clk
        tag_bank.rst_ni := rst_ni
        tag_bank.req_i := ibex_core_i.ic_tag_req_o(way)
        tag_bank.write_i := ibex_core_i.ic_tag_write_o
        tag_bank.addr_i := ibex_core_i.ic_tag_addr_o
        tag_bank.wdata_i := ibex_core_i.ic_tag_wdata_o
        tag_bank.wmask_i := Fill(tagSizeECC, true.B)
        tag_bank.cfg_i := ram_cfg_icache_tag_i
        ic_tag_rdata(way) := tag_bank.rdata_o
        ram_cfg_rsp_icache_tag_o(way) := tag_bank.cfg_rsp_o

        val data_bank = Module(new PrimRam1P(
          width = lineSizeECC,
          depth = IbexPkg.IC_NUM_LINES,
          dataBitsPerMask = lineSizeECC))
        data_bank.clk_i := clk
        data_bank.rst_ni := rst_ni
        data_bank.req_i := ibex_core_i.ic_data_req_o(way)
        data_bank.write_i := ibex_core_i.ic_data_write_o
        data_bank.addr_i := ibex_core_i.ic_data_addr_o
        data_bank.wdata_i := ibex_core_i.ic_data_wdata_o
        data_bank.wmask_i := Fill(lineSizeECC, true.B)
        data_bank.cfg_i := ram_cfg_icache_data_i
        ic_data_rdata(way) := data_bank.rdata_o
        ram_cfg_rsp_icache_data_o(way) := data_bank.cfg_rsp_o
      }
    }
  } else {
    ic_tag_rdata.foreach(_ := 0.U)
    ic_data_rdata.foreach(_ := 0.U)
    ram_cfg_rsp_icache_tag_o.foreach(_.done := false.B)
    ram_cfg_rsp_icache_data_o.foreach(_.done := false.B)
  }
  ibex_core_i.ic_tag_rdata_i := ic_tag_rdata
  ibex_core_i.ic_data_rdata_i := ic_data_rdata

  if (memECC) {
    instr_rdata_core := Cat(instr_rdata_intg_i, instr_rdata_i)
    data_rdata_core := Cat(data_rdata_intg_i, data_rdata_i)
    data_wdata_intg_o := data_wdata_core(38, 32)
  } else {
    instr_rdata_core := instr_rdata_i
    data_rdata_core := data_rdata_i
    data_wdata_intg_o := 0.U
  }
  scramble_req_o := scramble_req_q

  if (lockstep) {
    val lockstep_i = Module(new IbexLockstep(
      lockstepOffset = lockstepOffset,
      pmpEnable = pmpEnable,
      pmpGranularity = pmpGranularity,
      pmpNumRegions = pmpNumRegions,
      pmpRstCfg = pmpRstCfg,
      pmpRstAddr = pmpRstAddr,
      pmpRstMsecCfg = pmpRstMsecCfg,
      mhpmCounterNum = mhpmCounterNum,
      mhpmCounterWidth = mhpmCounterWidth,
      rv32e = rv32e,
      rv32m = rv32m,
      rv32b = rv32b,
      rv32zc = rv32zc,
      branchTargetALU = branchTargetALU,
      writebackStage = writebackStage,
      iCache = iCache,
      iCacheECC = iCacheECC,
      iCacheTweakInfection = iCacheTweakInfection,
      branchPredictor = branchPredictor,
      dbgTriggerEn = dbgTriggerEn,
      dbgHwBreakNum = dbgHwBreakNum,
      resetAll = resetAll,
      rndCnstLfsrSeed = rndCnstLfsrSeed,
      rndCnstLfsrPerm = rndCnstLfsrPerm,
      secureIbex = secureIbex,
      dummyInstructions = dummyInstructions,
      regFileECC = regFileLockstepECC,
      regFileDataWidth = regFileDataWidth,
      regFileDataEccWidth = regFileDataEccWidth,
      regFile = regFile,
      memECC = memECC,
      memDataWidth = MemDataWidth,
      dmBaseAddr = dmBaseAddr,
      dmAddrMask = dmAddrMask,
      dmHaltAddr = dmHaltAddr,
      dmExceptionAddr = dmExceptionAddr,
      csrMvendorId = csrMvendorId,
      csrMimpId = csrMimpId))

    lockstep_i.clk_i := clk
    lockstep_i.rst_ni := rst_ni
    lockstep_i.hart_id_i := hart_id_i
    lockstep_i.boot_addr_i := boot_addr_i
    lockstep_i.instr_req_i := instr_req_o
    lockstep_i.instr_gnt_i := instr_gnt_i
    lockstep_i.instr_rvalid_i := instr_rvalid_i
    lockstep_i.instr_addr_i := instr_addr_o
    lockstep_i.instr_rdata_i := instr_rdata_core
    lockstep_i.instr_err_i := instr_err_i
    lockstep_i.data_req_i := data_req_o
    lockstep_i.data_gnt_i := data_gnt_i
    lockstep_i.data_rvalid_i := data_rvalid_i
    lockstep_i.data_we_i := data_we_o
    lockstep_i.data_be_i := data_be_o
    lockstep_i.data_addr_i := data_addr_o
    lockstep_i.data_wdata_i := data_wdata_core(31, 0)
    lockstep_i.data_rdata_i := data_rdata_core
    lockstep_i.data_err_i := data_err_i
    lockstep_i.rf_rdata_a_i := rf_rdata_a
    lockstep_i.rf_rdata_b_i := rf_rdata_b
    lockstep_i.ic_tag_req_i := ibex_core_i.ic_tag_req_o
    lockstep_i.ic_tag_write_i := ibex_core_i.ic_tag_write_o
    lockstep_i.ic_tag_addr_i := ibex_core_i.ic_tag_addr_o
    lockstep_i.ic_tag_wdata_i := ibex_core_i.ic_tag_wdata_o
    lockstep_i.ic_tag_rdata_i := ic_tag_rdata
    lockstep_i.ic_data_req_i := ibex_core_i.ic_data_req_o
    lockstep_i.ic_data_write_i := ibex_core_i.ic_data_write_o
    lockstep_i.ic_data_addr_i := ibex_core_i.ic_data_addr_o
    lockstep_i.ic_data_wdata_i := ibex_core_i.ic_data_wdata_o
    lockstep_i.ic_data_rdata_i := ic_data_rdata
    lockstep_i.ic_scr_key_valid_i := scramble_key_valid_q
    lockstep_i.ic_scr_key_req_i := ibex_core_i.ic_scr_key_req_o
    lockstep_i.irq_software_i := irq_software_i
    lockstep_i.irq_timer_i := irq_timer_i
    lockstep_i.irq_external_i := irq_external_i
    lockstep_i.irq_fast_i := irq_fast_i
    lockstep_i.irq_nm_i := irq_nm_i
    lockstep_i.irq_pending_i := irq_pending
    lockstep_i.debug_req_i := debug_req_i
    lockstep_i.crash_dump_i := crash_dump_o
    lockstep_i.double_fault_seen_i := double_fault_seen_o
    lockstep_i.fetch_enable_i := fetch_enable_i
    lockstep_i.core_busy_i := core_busy_d
    lockstep_i.test_en_i := test_en_i
    lockstep_i.scan_rst_ni := scan_rst_ni

    lockstep_cmp_en_o := lockstep_i.lockstep_cmp_en_o
    data_req_shadow_o := lockstep_i.data_req_shadow_o
    data_we_shadow_o := lockstep_i.data_we_shadow_o
    data_be_shadow_o := lockstep_i.data_be_shadow_o
    data_addr_shadow_o := lockstep_i.data_addr_shadow_o
    data_wdata_shadow_o := lockstep_i.data_wdata_shadow_o
    data_wdata_intg_shadow_o := lockstep_i.data_wdata_intg_shadow_o
    instr_req_shadow_o := lockstep_i.instr_req_shadow_o
    instr_addr_shadow_o := lockstep_i.instr_addr_shadow_o

    alert_major_internal_o := core_alert_major_internal || lockstep_i.alert_major_internal_o
    alert_major_bus_o := core_alert_major_bus || lockstep_i.alert_major_bus_o
    alert_minor_o := core_alert_minor || lockstep_i.alert_minor_o
  } else {
    lockstep_cmp_en_o := IbexPkg.IbexMuBiOff
    data_req_shadow_o := false.B
    data_we_shadow_o := false.B
    data_be_shadow_o := 0.U
    data_addr_shadow_o := 0.U
    data_wdata_shadow_o := 0.U
    data_wdata_intg_shadow_o := 0.U
    instr_req_shadow_o := false.B
    instr_addr_shadow_o := 0.U

    alert_major_internal_o := core_alert_major_internal
    alert_major_bus_o := core_alert_major_bus
    alert_minor_o := core_alert_minor
  }

  dontTouch(ram_cfg_icache_tag_i)
  dontTouch(ram_cfg_icache_data_i)
  dontTouch(instr_rdata_intg_i)
  dontTouch(data_rdata_intg_i)
  dontTouch(scramble_key_valid_i)
  dontTouch(scramble_key_i)
  dontTouch(scramble_nonce_i)
  dontTouch(scan_rst_ni)
}
