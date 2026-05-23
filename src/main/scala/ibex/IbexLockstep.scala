// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexLockstep(
    lockstepOffset: Int = 1,
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
    regFileDataEccWidth: Int = 39,
    regFile: IbexPkg.RegFile.Type = IbexPkg.RegFile.FF,
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
  require(lockstepOffset >= 1, "IbexLockstep LockstepOffset must be at least 1")
  require(regFileDataWidth == 32, "IbexLockstep requires 32-bit register-file data")
  require(!regFileECC || regFileDataEccWidth == 39, "IbexLockstep RegFileECC requires 39-bit SECDED register-file data")
  require(regFileDataEccWidth >= regFileDataWidth)
  require((memECC && MemDataWidth == 39) || (!memECC && MemDataWidth == 32), "IbexLockstep MemDataWidth must be 39 with MemECC and 32 without MemECC")
  private val shadowRegFileDataWidth = if (regFileECC) regFileDataEccWidth else regFileDataWidth
  private val regFileIntgWidth = regFileDataEccWidth - regFileDataWidth
  private val busSizeECC = if (iCacheECC) IbexPkg.BUS_SIZE + IbexPkg.IC_DATA_ECC_SIZE else IbexPkg.BUS_SIZE
  private val tagSizeECC = if (iCacheECC) IbexPkg.IC_TAG_SIZE + IbexPkg.IC_TAG_ECC_SIZE else IbexPkg.IC_TAG_SIZE
  private val lineSizeECC = busSizeECC * IbexPkg.IC_LINE_BEATS

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val hart_id_i = IO(Input(UInt(32.W)))
  val boot_addr_i = IO(Input(UInt(32.W)))

  val instr_req_i = IO(Input(Bool()))
  val instr_gnt_i = IO(Input(Bool()))
  val instr_rvalid_i = IO(Input(Bool()))
  val instr_addr_i = IO(Input(UInt(32.W)))
  val instr_rdata_i = IO(Input(UInt(MemDataWidth.W)))
  val instr_err_i = IO(Input(Bool()))

  val data_req_i = IO(Input(Bool()))
  val data_gnt_i = IO(Input(Bool()))
  val data_rvalid_i = IO(Input(Bool()))
  val data_we_i = IO(Input(Bool()))
  val data_be_i = IO(Input(UInt(4.W)))
  val data_addr_i = IO(Input(UInt(32.W)))
  val data_wdata_i = IO(Input(UInt(32.W)))
  val data_rdata_i = IO(Input(UInt(MemDataWidth.W)))
  val data_err_i = IO(Input(Bool()))

  val rf_rdata_a_i = IO(Input(UInt(regFileDataWidth.W)))
  val rf_rdata_b_i = IO(Input(UInt(regFileDataWidth.W)))

  val ic_tag_req_i = IO(Input(UInt(IbexPkg.IC_NUM_WAYS.W)))
  val ic_tag_write_i = IO(Input(Bool()))
  val ic_tag_addr_i = IO(Input(UInt(IbexPkg.IC_INDEX_W.W)))
  val ic_tag_wdata_i = IO(Input(UInt(tagSizeECC.W)))
  val ic_tag_rdata_i = IO(Input(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W))))
  val ic_data_req_i = IO(Input(UInt(IbexPkg.IC_NUM_WAYS.W)))
  val ic_data_write_i = IO(Input(Bool()))
  val ic_data_addr_i = IO(Input(UInt(IbexPkg.IC_INDEX_W.W)))
  val ic_data_wdata_i = IO(Input(UInt(lineSizeECC.W)))
  val ic_data_rdata_i = IO(Input(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W))))
  val ic_scr_key_valid_i = IO(Input(Bool()))
  val ic_scr_key_req_i = IO(Input(Bool()))

  val irq_software_i = IO(Input(Bool()))
  val irq_timer_i = IO(Input(Bool()))
  val irq_external_i = IO(Input(Bool()))
  val irq_fast_i = IO(Input(UInt(15.W)))
  val irq_nm_i = IO(Input(Bool()))
  val irq_pending_i = IO(Input(Bool()))

  val debug_req_i = IO(Input(Bool()))
  val crash_dump_i = IO(Input(new IbexPkg.CrashDump))
  val double_fault_seen_i = IO(Input(Bool()))

  val fetch_enable_i = IO(Input(UInt(IbexPkg.IbexMuBiWidth.W)))
  val alert_minor_o = IO(Output(Bool()))
  val alert_major_internal_o = IO(Output(Bool()))
  val alert_major_bus_o = IO(Output(Bool()))
  val core_busy_i = IO(Input(UInt(IbexPkg.IbexMuBiWidth.W)))
  val test_en_i = IO(Input(Bool()))
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

  class DelayedInputs extends Bundle {
    val instr_gnt = Bool()
    val instr_rvalid = Bool()
    val instr_rdata = UInt(MemDataWidth.W)
    val instr_err = Bool()
    val data_gnt = Bool()
    val data_rvalid = Bool()
    val data_rdata = UInt(MemDataWidth.W)
    val data_err = Bool()
    val rf_rdata_a = UInt(regFileDataWidth.W)
    val rf_rdata_b = UInt(regFileDataWidth.W)
    val irq_software = Bool()
    val irq_timer = Bool()
    val irq_external = Bool()
    val irq_fast = UInt(15.W)
    val irq_nm = Bool()
    val debug_req = Bool()
    val fetch_enable = UInt(IbexPkg.IbexMuBiWidth.W)
    val ic_scr_key_valid = Bool()
  }

  class DelayedOutputs extends Bundle {
    val instr_req = Bool()
    val instr_addr = UInt(32.W)
    val data_req = Bool()
    val data_we = Bool()
    val data_be = UInt(4.W)
    val data_addr = UInt(32.W)
    val data_wdata = UInt(32.W)
    val ic_tag_req = UInt(IbexPkg.IC_NUM_WAYS.W)
    val ic_tag_write = Bool()
    val ic_tag_addr = UInt(IbexPkg.IC_INDEX_W.W)
    val ic_tag_wdata = UInt(tagSizeECC.W)
    val ic_data_req = UInt(IbexPkg.IC_NUM_WAYS.W)
    val ic_data_write = Bool()
    val ic_data_addr = UInt(IbexPkg.IC_INDEX_W.W)
    val ic_data_wdata = UInt(lineSizeECC.W)
    val ic_scr_key_req = Bool()
    val irq_pending = Bool()
    val crash_dump = new IbexPkg.CrashDump
    val double_fault_seen = Bool()
    val core_busy = UInt(IbexPkg.IbexMuBiWidth.W)
  }

  val rst_shadow_set_d = Wire(UInt(IbexPkg.IbexMuBiWidth.W))
  val enable_cmp_d = Wire(UInt(IbexPkg.IbexMuBiWidth.W))
  val rst_shadow_set_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(rst_shadow_set_d, IbexPkg.IbexMuBiOff)
  }
  val enable_cmp_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(enable_cmp_d, IbexPkg.IbexMuBiOff)
  }
  if (lockstepOffset == 1) {
    rst_shadow_set_d := IbexPkg.IbexMuBiOn
    enable_cmp_d := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegNext(IbexPkg.IbexMuBiOn, IbexPkg.IbexMuBiOff)
    }
  } else {
    val rstShadowCnt = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(0.U(log2Ceil(lockstepOffset + 1).W))
    }
    when(rstShadowCnt < lockstepOffset.U) {
      rstShadowCnt := rstShadowCnt + 1.U
    }
    rst_shadow_set_d := Mux(rstShadowCnt >= (lockstepOffset - 1).U, IbexPkg.IbexMuBiOn, IbexPkg.IbexMuBiOff)
    enable_cmp_d := rst_shadow_set_q
  }
  val rst_shadow_n = Mux(test_en_i, scan_rst_ni, rst_shadow_set_q(0))

  val shadow_inputs_in = Wire(new DelayedInputs)
  shadow_inputs_in.instr_gnt := instr_gnt_i
  shadow_inputs_in.instr_rvalid := instr_rvalid_i
  shadow_inputs_in.instr_rdata := instr_rdata_i
  shadow_inputs_in.instr_err := instr_err_i
  shadow_inputs_in.data_gnt := data_gnt_i
  shadow_inputs_in.data_rvalid := data_rvalid_i
  shadow_inputs_in.data_rdata := data_rdata_i
  shadow_inputs_in.data_err := data_err_i
  shadow_inputs_in.rf_rdata_a := rf_rdata_a_i
  shadow_inputs_in.rf_rdata_b := rf_rdata_b_i
  shadow_inputs_in.irq_software := irq_software_i
  shadow_inputs_in.irq_timer := irq_timer_i
  shadow_inputs_in.irq_external := irq_external_i
  shadow_inputs_in.irq_fast := irq_fast_i
  shadow_inputs_in.irq_nm := irq_nm_i
  shadow_inputs_in.debug_req := debug_req_i
  shadow_inputs_in.fetch_enable := fetch_enable_i
  shadow_inputs_in.ic_scr_key_valid := ic_scr_key_valid_i

  val shadow_inputs_pipe = Seq.fill(lockstepOffset)(Wire(new DelayedInputs))
  val shadow_tag_rdata_pipe = Seq.fill(lockstepOffset)(Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W))))
  val shadow_data_rdata_pipe = Seq.fill(lockstepOffset)(Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W))))
  shadow_inputs_pipe(lockstepOffset - 1) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(shadow_inputs_in, 0.U.asTypeOf(new DelayedInputs))
  }
  shadow_tag_rdata_pipe(lockstepOffset - 1) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(ic_tag_rdata_i, 0.U.asTypeOf(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W))))
  }
  shadow_data_rdata_pipe(lockstepOffset - 1) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(ic_data_rdata_i, 0.U.asTypeOf(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W))))
  }
  for (i <- (0 until lockstepOffset - 1).reverse) {
    shadow_inputs_pipe(i) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegNext(shadow_inputs_pipe(i + 1), 0.U.asTypeOf(new DelayedInputs))
    }
    shadow_tag_rdata_pipe(i) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegNext(shadow_tag_rdata_pipe(i + 1), 0.U.asTypeOf(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W))))
    }
    shadow_data_rdata_pipe(i) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegNext(shadow_data_rdata_pipe(i + 1), 0.U.asTypeOf(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W))))
    }
  }
  val shadow_inputs_q = shadow_inputs_pipe(0)
  val shadow_tag_rdata_q = shadow_tag_rdata_pipe(0)
  val shadow_data_rdata_q = shadow_data_rdata_pipe(0)

  val core_outputs_in = Wire(new DelayedOutputs)
  core_outputs_in.instr_req := instr_req_i
  core_outputs_in.instr_addr := instr_addr_i
  core_outputs_in.data_req := data_req_i
  core_outputs_in.data_we := data_we_i
  core_outputs_in.data_be := data_be_i
  core_outputs_in.data_addr := data_addr_i
  core_outputs_in.data_wdata := data_wdata_i
  core_outputs_in.ic_tag_req := ic_tag_req_i
  core_outputs_in.ic_tag_write := ic_tag_write_i
  core_outputs_in.ic_tag_addr := ic_tag_addr_i
  core_outputs_in.ic_tag_wdata := ic_tag_wdata_i
  core_outputs_in.ic_data_req := ic_data_req_i
  core_outputs_in.ic_data_write := ic_data_write_i
  core_outputs_in.ic_data_addr := ic_data_addr_i
  core_outputs_in.ic_data_wdata := ic_data_wdata_i
  core_outputs_in.ic_scr_key_req := ic_scr_key_req_i
  core_outputs_in.irq_pending := irq_pending_i
  core_outputs_in.crash_dump := crash_dump_i
  core_outputs_in.double_fault_seen := double_fault_seen_i
  core_outputs_in.core_busy := core_busy_i

  private val outputsOffset = lockstepOffset + 1
  val core_outputs_q = Seq.fill(outputsOffset)(Wire(new DelayedOutputs))
  core_outputs_q(outputsOffset - 1) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(core_outputs_in, 0.U.asTypeOf(new DelayedOutputs))
  }
  for (i <- (0 until outputsOffset - 1).reverse) {
    core_outputs_q(i) := withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegNext(core_outputs_q(i + 1), 0.U.asTypeOf(new DelayedOutputs))
    }
  }
  val shadow_outputs_d = Wire(new DelayedOutputs)
  val shadow_outputs_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegNext(shadow_outputs_d, 0.U.asTypeOf(new DelayedOutputs))
  }

  val shadow_core = Module(new IbexCore(
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
    regFileDataWidth = shadowRegFileDataWidth,
    memECC = memECC,
    memDataWidth = MemDataWidth,
    dmBaseAddr = dmBaseAddr,
    dmAddrMask = dmAddrMask,
    dmHaltAddr = dmHaltAddr,
    dmExceptionAddr = dmExceptionAddr,
    csrMvendorId = csrMvendorId,
    csrMimpId = csrMimpId))
  shadow_core.clk_i := clk_i
  shadow_core.rst_ni := rst_shadow_n
  shadow_core.hart_id_i := hart_id_i
  shadow_core.boot_addr_i := boot_addr_i
  shadow_outputs_d.instr_req := shadow_core.instr_req_o
  shadow_core.instr_gnt_i := shadow_inputs_q.instr_gnt
  shadow_core.instr_rvalid_i := shadow_inputs_q.instr_rvalid
  shadow_outputs_d.instr_addr := shadow_core.instr_addr_o
  shadow_core.instr_rdata_i := shadow_inputs_q.instr_rdata
  shadow_core.instr_err_i := shadow_inputs_q.instr_err
  shadow_outputs_d.data_req := shadow_core.data_req_o
  shadow_core.data_gnt_i := shadow_inputs_q.data_gnt
  shadow_core.data_rvalid_i := shadow_inputs_q.data_rvalid
  shadow_outputs_d.data_we := shadow_core.data_we_o
  shadow_outputs_d.data_be := shadow_core.data_be_o
  shadow_outputs_d.data_addr := shadow_core.data_addr_o
  shadow_outputs_d.data_wdata := shadow_core.data_wdata_o(31, 0)
  shadow_core.data_rdata_i := shadow_inputs_q.data_rdata
  shadow_core.data_err_i := shadow_inputs_q.data_err

  val shadow_rf_raddr_a = shadow_core.rf_raddr_a_o
  val shadow_rf_raddr_b = shadow_core.rf_raddr_b_o
  val shadow_rf_waddr_wb = shadow_core.rf_waddr_wb_o
  val shadow_rf_we_wb = shadow_core.rf_we_wb_o
  val shadow_rf_wdata_wb_ecc = shadow_core.rf_wdata_wb_ecc_o
  val shadow_dummy_instr_id = shadow_core.dummy_instr_id_o
  val shadow_dummy_instr_wb = shadow_core.dummy_instr_wb_o
  val shadow_rf_rdata_a_intg = Wire(UInt(regFileIntgWidth.W))
  val shadow_rf_rdata_b_intg = Wire(UInt(regFileIntgWidth.W))
  if (regFileECC) {
    shadow_core.rf_rdata_a_ecc_i := Cat(shadow_rf_rdata_a_intg, shadow_inputs_q.rf_rdata_a)
    shadow_core.rf_rdata_b_ecc_i := Cat(shadow_rf_rdata_b_intg, shadow_inputs_q.rf_rdata_b)
  } else {
    shadow_core.rf_rdata_a_ecc_i := shadow_inputs_q.rf_rdata_a
    shadow_core.rf_rdata_b_ecc_i := shadow_inputs_q.rf_rdata_b
    shadow_rf_rdata_a_intg := 0.U
    shadow_rf_rdata_b_intg := 0.U
  }
  shadow_outputs_d.ic_tag_req := shadow_core.ic_tag_req_o
  shadow_outputs_d.ic_tag_write := shadow_core.ic_tag_write_o
  shadow_outputs_d.ic_tag_addr := shadow_core.ic_tag_addr_o
  shadow_outputs_d.ic_tag_wdata := shadow_core.ic_tag_wdata_o
  shadow_core.ic_tag_rdata_i := shadow_tag_rdata_q
  shadow_outputs_d.ic_data_req := shadow_core.ic_data_req_o
  shadow_outputs_d.ic_data_write := shadow_core.ic_data_write_o
  shadow_outputs_d.ic_data_addr := shadow_core.ic_data_addr_o
  shadow_outputs_d.ic_data_wdata := shadow_core.ic_data_wdata_o
  shadow_core.ic_data_rdata_i := shadow_data_rdata_q
  shadow_core.ic_scr_key_valid_i := shadow_inputs_q.ic_scr_key_valid
  shadow_outputs_d.ic_scr_key_req := shadow_core.ic_scr_key_req_o
  shadow_core.irq_software_i := shadow_inputs_q.irq_software
  shadow_core.irq_timer_i := shadow_inputs_q.irq_timer
  shadow_core.irq_external_i := shadow_inputs_q.irq_external
  shadow_core.irq_fast_i := shadow_inputs_q.irq_fast
  shadow_core.irq_nm_i := shadow_inputs_q.irq_nm
  shadow_outputs_d.irq_pending := shadow_core.irq_pending_o
  shadow_core.debug_req_i := shadow_inputs_q.debug_req
  shadow_outputs_d.crash_dump := shadow_core.crash_dump_o
  shadow_outputs_d.double_fault_seen := shadow_core.double_fault_seen_o
  shadow_core.fetch_enable_i := shadow_inputs_q.fetch_enable
  shadow_outputs_d.core_busy := shadow_core.core_busy_o

  if (regFileECC) {
    def connectShadowRegFile[T <: RawModule](rf: T): Unit = rf match {
      case ff: IbexRegisterFileFF =>
        ff.clk_i := clk_i
        ff.rst_ni := rst_shadow_n
        ff.test_en_i := test_en_i
        ff.dummy_instr_id_i := shadow_dummy_instr_id
        ff.dummy_instr_wb_i := shadow_dummy_instr_wb
        ff.raddr_a_i := shadow_rf_raddr_a
        shadow_rf_rdata_a_intg := ff.rdata_a_o
        ff.raddr_b_i := shadow_rf_raddr_b
        shadow_rf_rdata_b_intg := ff.rdata_b_o
        ff.waddr_a_i := shadow_rf_waddr_wb
        ff.wdata_a_i := shadow_rf_wdata_wb_ecc(regFileDataEccWidth - 1, regFileDataWidth)
        ff.we_a_i := shadow_rf_we_wb
      case fpga: IbexRegisterFileFPGA =>
        fpga.clk_i := clk_i
        fpga.rst_ni := rst_shadow_n
        fpga.test_en_i := test_en_i
        fpga.dummy_instr_id_i := shadow_dummy_instr_id
        fpga.dummy_instr_wb_i := shadow_dummy_instr_wb
        fpga.raddr_a_i := shadow_rf_raddr_a
        shadow_rf_rdata_a_intg := fpga.rdata_a_o
        fpga.raddr_b_i := shadow_rf_raddr_b
        shadow_rf_rdata_b_intg := fpga.rdata_b_o
        fpga.waddr_a_i := shadow_rf_waddr_wb
        fpga.wdata_a_i := shadow_rf_wdata_wb_ecc(regFileDataEccWidth - 1, regFileDataWidth)
        fpga.we_a_i := shadow_rf_we_wb
      case latch: IbexRegisterFileLatch =>
        latch.clk_i := clk_i
        latch.rst_ni := rst_shadow_n
        latch.test_en_i := test_en_i
        latch.dummy_instr_id_i := shadow_dummy_instr_id
        latch.dummy_instr_wb_i := shadow_dummy_instr_wb
        latch.raddr_a_i := shadow_rf_raddr_a
        shadow_rf_rdata_a_intg := latch.rdata_a_o
        latch.raddr_b_i := shadow_rf_raddr_b
        shadow_rf_rdata_b_intg := latch.rdata_b_o
        latch.waddr_a_i := shadow_rf_waddr_wb
        latch.wdata_a_i := shadow_rf_wdata_wb_ecc(regFileDataEccWidth - 1, regFileDataWidth)
        latch.we_a_i := shadow_rf_we_wb
    }

    regFile match {
      case IbexPkg.RegFile.FF =>
        connectShadowRegFile(Module(new IbexRegisterFileFF(
          rv32e = rv32e,
          dataWidth = regFileIntgWidth,
          dummyInstructions = dummyInstructions,
          wordZeroVal = 0x2a)))
      case IbexPkg.RegFile.FPGA =>
        connectShadowRegFile(Module(new IbexRegisterFileFPGA(
          rv32e = rv32e,
          dataWidth = regFileIntgWidth,
          dummyInstructions = dummyInstructions,
          wordZeroVal = 0x2a)))
      case IbexPkg.RegFile.Latch =>
        connectShadowRegFile(Module(new IbexRegisterFileLatch(
          rv32e = rv32e,
          dataWidth = regFileIntgWidth,
          dummyInstructions = dummyInstructions,
          wordZeroVal = 0x2a)))
    }
  }

  val outputs_mismatch = enable_cmp_q =/= IbexPkg.IbexMuBiOff && (shadow_outputs_q.asUInt =/= core_outputs_q(0).asUInt)
  alert_major_internal_o := outputs_mismatch || shadow_core.alert_major_internal_o
  alert_major_bus_o := shadow_core.alert_major_bus_o
  alert_minor_o := shadow_core.alert_minor_o
  lockstep_cmp_en_o := enable_cmp_q

  val shadow_data_wdata_intg = if (memECC) shadow_core.data_wdata_o(38, 32) else 0.U(7.W)
  data_req_shadow_o := shadow_outputs_d.data_req
  data_we_shadow_o := shadow_outputs_d.data_we
  data_be_shadow_o := shadow_outputs_d.data_be
  data_addr_shadow_o := shadow_outputs_d.data_addr
  data_wdata_shadow_o := shadow_outputs_d.data_wdata
  data_wdata_intg_shadow_o := shadow_data_wdata_intg
  instr_req_shadow_o := shadow_outputs_d.instr_req
  instr_addr_shadow_o := shadow_outputs_d.instr_addr
}
