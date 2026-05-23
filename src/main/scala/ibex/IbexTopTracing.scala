// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexTopTracing(
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
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val test_en_i = IO(Input(Bool()))
  val scan_rst_ni = IO(Input(Bool()))
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

  val lockstep_cmp_en_o = IO(Output(UInt(IbexPkg.IbexMuBiWidth.W)))
  val data_req_shadow_o = IO(Output(Bool()))
  val data_we_shadow_o = IO(Output(Bool()))
  val data_be_shadow_o = IO(Output(UInt(4.W)))
  val data_addr_shadow_o = IO(Output(UInt(32.W)))
  val data_wdata_shadow_o = IO(Output(UInt(32.W)))
  val data_wdata_intg_shadow_o = IO(Output(UInt(7.W)))
  val instr_req_shadow_o = IO(Output(Bool()))
  val instr_addr_shadow_o = IO(Output(UInt(32.W)))

  dontTouch(test_en_i)
  dontTouch(scan_rst_ni)
  dontTouch(ram_cfg_icache_tag_i)
  dontTouch(ram_cfg_rsp_icache_tag_o)
  dontTouch(ram_cfg_icache_data_i)
  dontTouch(ram_cfg_rsp_icache_data_o)
  dontTouch(hart_id_i)
  dontTouch(boot_addr_i)
  dontTouch(instr_rdata_intg_i)
  dontTouch(data_rdata_intg_i)
  dontTouch(irq_software_i)
  dontTouch(irq_timer_i)
  dontTouch(irq_external_i)
  dontTouch(irq_fast_i)
  dontTouch(irq_nm_i)
  dontTouch(scramble_key_valid_i)
  dontTouch(scramble_key_i)
  dontTouch(scramble_nonce_i)
  dontTouch(debug_req_i)
  dontTouch(fetch_enable_i)
  dontTouch(scramble_req_o)
  dontTouch(crash_dump_o)
  dontTouch(double_fault_seen_o)
  dontTouch(alert_minor_o)
  dontTouch(alert_major_internal_o)
  dontTouch(alert_major_bus_o)
  dontTouch(core_sleep_o)
  dontTouch(rvfi_valid)
  dontTouch(rvfi_order)
  dontTouch(rvfi_insn)
  dontTouch(rvfi_trap)
  dontTouch(rvfi_halt)
  dontTouch(rvfi_intr)
  dontTouch(rvfi_mode)
  dontTouch(rvfi_ixl)
  dontTouch(rvfi_rs1_addr)
  dontTouch(rvfi_rs2_addr)
  dontTouch(rvfi_rs3_addr)
  dontTouch(rvfi_rs1_rdata)
  dontTouch(rvfi_rs2_rdata)
  dontTouch(rvfi_rs3_rdata)
  dontTouch(rvfi_rd_addr)
  dontTouch(rvfi_rd_wdata)
  dontTouch(rvfi_pc_rdata)
  dontTouch(rvfi_pc_wdata)
  dontTouch(rvfi_mem_addr)
  dontTouch(rvfi_mem_rmask)
  dontTouch(rvfi_mem_wmask)
  dontTouch(rvfi_mem_rdata)
  dontTouch(rvfi_mem_wdata)
  dontTouch(rvfi_ext_pre_mip)
  dontTouch(rvfi_ext_post_mip)
  dontTouch(rvfi_ext_nmi)
  dontTouch(rvfi_ext_nmi_int)
  dontTouch(rvfi_ext_debug_req)
  dontTouch(rvfi_ext_debug_mode)
  dontTouch(rvfi_ext_rf_wr_suppress)
  dontTouch(rvfi_ext_mcycle)
  dontTouch(rvfi_ext_mhpmcounters)
  dontTouch(rvfi_ext_mhpmcountersh)
  dontTouch(rvfi_ext_ic_scr_key_valid)
  dontTouch(rvfi_ext_irq_valid)
  dontTouch(rvfi_ext_expanded_insn_valid)
  dontTouch(rvfi_ext_expanded_insn)
  dontTouch(rvfi_ext_expanded_insn_last)
  dontTouch(lockstep_cmp_en_o)
  dontTouch(data_req_shadow_o)
  dontTouch(data_we_shadow_o)
  dontTouch(data_be_shadow_o)
  dontTouch(data_addr_shadow_o)
  dontTouch(data_wdata_shadow_o)
  dontTouch(data_wdata_intg_shadow_o)
  dontTouch(instr_req_shadow_o)
  dontTouch(instr_addr_shadow_o)

  val ibex_top = Module(new IbexTop(
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
    regFile = regFile,
    branchTargetALU = branchTargetALU,
    writebackStage = writebackStage,
    iCache = iCache,
    iCacheECC = iCacheECC,
    branchPredictor = branchPredictor,
    dbgTriggerEn = dbgTriggerEn,
    dbgHwBreakNum = dbgHwBreakNum,
    secureIbex = secureIbex,
    lockstepOffset = lockstepOffset,
    memECC = memECC,
    memDataWidth = memDataWidth,
    iCacheScramble = iCacheScramble,
    iCacheScrNumPrinceRoundsHalf = iCacheScrNumPrinceRoundsHalf,
    iCacheTweakInfection = iCacheTweakInfection,
    rndCnstLfsrSeed = rndCnstLfsrSeed,
    rndCnstLfsrPerm = rndCnstLfsrPerm,
    rndCnstIbexKey = rndCnstIbexKey,
    rndCnstIbexNonce = rndCnstIbexNonce,
    dmBaseAddr = dmBaseAddr,
    dmAddrMask = dmAddrMask,
    dmHaltAddr = dmHaltAddr,
    dmExceptionAddr = dmExceptionAddr,
    csrMvendorId = csrMvendorId,
    csrMimpId = csrMimpId))

  ibex_top.clk_i := clk_i
  ibex_top.rst_ni := rst_ni
  ibex_top.test_en_i := test_en_i
  ibex_top.scan_rst_ni := scan_rst_ni
  ibex_top.ram_cfg_icache_tag_i := ram_cfg_icache_tag_i
  ram_cfg_rsp_icache_tag_o := ibex_top.ram_cfg_rsp_icache_tag_o
  ibex_top.ram_cfg_icache_data_i := ram_cfg_icache_data_i
  ram_cfg_rsp_icache_data_o := ibex_top.ram_cfg_rsp_icache_data_o

  ibex_top.hart_id_i := hart_id_i
  ibex_top.boot_addr_i := boot_addr_i

  instr_req_o := ibex_top.instr_req_o
  ibex_top.instr_gnt_i := instr_gnt_i
  ibex_top.instr_rvalid_i := instr_rvalid_i
  instr_addr_o := ibex_top.instr_addr_o
  ibex_top.instr_rdata_i := instr_rdata_i
  ibex_top.instr_rdata_intg_i := instr_rdata_intg_i
  ibex_top.instr_err_i := instr_err_i

  data_req_o := ibex_top.data_req_o
  ibex_top.data_gnt_i := data_gnt_i
  ibex_top.data_rvalid_i := data_rvalid_i
  data_we_o := ibex_top.data_we_o
  data_be_o := ibex_top.data_be_o
  data_addr_o := ibex_top.data_addr_o
  data_wdata_o := ibex_top.data_wdata_o
  data_wdata_intg_o := ibex_top.data_wdata_intg_o
  ibex_top.data_rdata_i := data_rdata_i
  ibex_top.data_rdata_intg_i := data_rdata_intg_i
  ibex_top.data_err_i := data_err_i

  ibex_top.irq_software_i := irq_software_i
  ibex_top.irq_timer_i := irq_timer_i
  ibex_top.irq_external_i := irq_external_i
  ibex_top.irq_fast_i := irq_fast_i
  ibex_top.irq_nm_i := irq_nm_i

  ibex_top.scramble_key_valid_i := scramble_key_valid_i
  ibex_top.scramble_key_i := scramble_key_i
  ibex_top.scramble_nonce_i := scramble_nonce_i
  scramble_req_o := ibex_top.scramble_req_o

  ibex_top.debug_req_i := debug_req_i
  crash_dump_o := ibex_top.crash_dump_o
  double_fault_seen_o := ibex_top.double_fault_seen_o
  ibex_top.fetch_enable_i := fetch_enable_i
  alert_minor_o := ibex_top.alert_minor_o
  alert_major_internal_o := ibex_top.alert_major_internal_o
  alert_major_bus_o := ibex_top.alert_major_bus_o
  core_sleep_o := ibex_top.core_sleep_o

  rvfi_valid := ibex_top.rvfi_valid
  rvfi_order := ibex_top.rvfi_order
  rvfi_insn := ibex_top.rvfi_insn
  rvfi_trap := ibex_top.rvfi_trap
  rvfi_halt := ibex_top.rvfi_halt
  rvfi_intr := ibex_top.rvfi_intr
  rvfi_mode := ibex_top.rvfi_mode
  rvfi_ixl := ibex_top.rvfi_ixl
  rvfi_rs1_addr := ibex_top.rvfi_rs1_addr
  rvfi_rs2_addr := ibex_top.rvfi_rs2_addr
  rvfi_rs3_addr := ibex_top.rvfi_rs3_addr
  rvfi_rs1_rdata := ibex_top.rvfi_rs1_rdata
  rvfi_rs2_rdata := ibex_top.rvfi_rs2_rdata
  rvfi_rs3_rdata := ibex_top.rvfi_rs3_rdata
  rvfi_rd_addr := ibex_top.rvfi_rd_addr
  rvfi_rd_wdata := ibex_top.rvfi_rd_wdata
  rvfi_pc_rdata := ibex_top.rvfi_pc_rdata
  rvfi_pc_wdata := ibex_top.rvfi_pc_wdata
  rvfi_mem_addr := ibex_top.rvfi_mem_addr
  rvfi_mem_rmask := ibex_top.rvfi_mem_rmask
  rvfi_mem_wmask := ibex_top.rvfi_mem_wmask
  rvfi_mem_rdata := ibex_top.rvfi_mem_rdata
  rvfi_mem_wdata := ibex_top.rvfi_mem_wdata
  rvfi_ext_pre_mip := ibex_top.rvfi_ext_pre_mip
  rvfi_ext_post_mip := ibex_top.rvfi_ext_post_mip
  rvfi_ext_nmi := ibex_top.rvfi_ext_nmi
  rvfi_ext_nmi_int := ibex_top.rvfi_ext_nmi_int
  rvfi_ext_debug_req := ibex_top.rvfi_ext_debug_req
  rvfi_ext_debug_mode := ibex_top.rvfi_ext_debug_mode
  rvfi_ext_rf_wr_suppress := ibex_top.rvfi_ext_rf_wr_suppress
  rvfi_ext_mcycle := ibex_top.rvfi_ext_mcycle
  rvfi_ext_mhpmcounters := ibex_top.rvfi_ext_mhpmcounters
  rvfi_ext_mhpmcountersh := ibex_top.rvfi_ext_mhpmcountersh
  rvfi_ext_ic_scr_key_valid := ibex_top.rvfi_ext_ic_scr_key_valid
  rvfi_ext_irq_valid := ibex_top.rvfi_ext_irq_valid
  rvfi_ext_expanded_insn_valid := ibex_top.rvfi_ext_expanded_insn_valid
  rvfi_ext_expanded_insn := ibex_top.rvfi_ext_expanded_insn
  rvfi_ext_expanded_insn_last := ibex_top.rvfi_ext_expanded_insn_last

  lockstep_cmp_en_o := ibex_top.lockstep_cmp_en_o
  data_req_shadow_o := ibex_top.data_req_shadow_o
  data_we_shadow_o := ibex_top.data_we_shadow_o
  data_be_shadow_o := ibex_top.data_be_shadow_o
  data_addr_shadow_o := ibex_top.data_addr_shadow_o
  data_wdata_shadow_o := ibex_top.data_wdata_shadow_o
  data_wdata_intg_shadow_o := ibex_top.data_wdata_intg_shadow_o
  instr_req_shadow_o := ibex_top.instr_req_shadow_o
  instr_addr_shadow_o := ibex_top.instr_addr_shadow_o

  val tracer = Module(new IbexTracer)
  tracer.clk_i := clk_i
  tracer.rst_ni := rst_ni
  tracer.hart_id_i := hart_id_i
  tracer.rvfi_valid := ibex_top.rvfi_valid
  tracer.rvfi_order := ibex_top.rvfi_order
  tracer.rvfi_insn := ibex_top.rvfi_insn
  tracer.rvfi_trap := ibex_top.rvfi_trap
  tracer.rvfi_halt := ibex_top.rvfi_halt
  tracer.rvfi_intr := ibex_top.rvfi_intr
  tracer.rvfi_mode := ibex_top.rvfi_mode
  tracer.rvfi_ixl := ibex_top.rvfi_ixl
  tracer.rvfi_rs1_addr := ibex_top.rvfi_rs1_addr
  tracer.rvfi_rs2_addr := ibex_top.rvfi_rs2_addr
  tracer.rvfi_rs3_addr := ibex_top.rvfi_rs3_addr
  tracer.rvfi_rs1_rdata := ibex_top.rvfi_rs1_rdata
  tracer.rvfi_rs2_rdata := ibex_top.rvfi_rs2_rdata
  tracer.rvfi_rs3_rdata := ibex_top.rvfi_rs3_rdata
  tracer.rvfi_rd_addr := ibex_top.rvfi_rd_addr
  tracer.rvfi_rd_wdata := ibex_top.rvfi_rd_wdata
  tracer.rvfi_pc_rdata := ibex_top.rvfi_pc_rdata
  tracer.rvfi_pc_wdata := ibex_top.rvfi_pc_wdata
  tracer.rvfi_mem_addr := ibex_top.rvfi_mem_addr
  tracer.rvfi_mem_rmask := ibex_top.rvfi_mem_rmask
  tracer.rvfi_mem_wmask := ibex_top.rvfi_mem_wmask
  tracer.rvfi_mem_rdata := ibex_top.rvfi_mem_rdata
  tracer.rvfi_mem_wdata := ibex_top.rvfi_mem_wdata
  tracer.rvfi_ext_expanded_insn_valid := ibex_top.rvfi_ext_expanded_insn_valid
  tracer.rvfi_ext_expanded_insn := ibex_top.rvfi_ext_expanded_insn
}
