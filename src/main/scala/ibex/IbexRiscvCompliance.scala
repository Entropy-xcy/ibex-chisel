// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexRiscvCompliance(
    pmpEnable: Boolean = false,
    pmpGranularity: Int = 0,
    pmpNumRegions: Int = 4,
    mhpmCounterNum: Int = 0,
    mhpmCounterWidth: Int = 40,
    rv32e: Boolean = false,
    rv32m: Int = 2,
    rv32b: Int = 0,
    rv32zc: Int = 0,
    regFile: IbexPkg.RegFile.Type = IbexPkg.RegFile.FF,
    branchTargetALU: Boolean = false,
    writebackStage: Boolean = false,
    iCache: Boolean = false,
    iCacheECC: Boolean = false,
    iCacheTweakInfection: Boolean = false,
    branchPredictor: Boolean = false,
    secureIbex: Boolean = false,
    lockstepOffset: Int = 1,
    iCacheScramble: Boolean = false,
    dbgTriggerEn: Boolean = false)
    extends RawModule {
  override def desiredName: String = "ibex_riscv_compliance"

  val IO_CLK = IO(Input(Clock()))
  val IO_RST_N = IO(Input(Bool()))

  private val testUtilHost = 0
  private val coreD = 1
  private val coreI = 2
  private val ram = 0
  private val testUtilDevice = 1
  private val nrDevices = 2
  private val nrHosts = 3
  private val ramSizeWords = 64 * 1024 / 4

  val hostReq = Wire(Vec(nrHosts, Bool()))
  val hostGnt = Wire(Vec(nrHosts, Bool()))
  val hostAddr = Wire(Vec(nrHosts, UInt(32.W)))
  val hostWe = Wire(Vec(nrHosts, Bool()))
  val hostBe = Wire(Vec(nrHosts, UInt(4.W)))
  val hostWdata = Wire(Vec(nrHosts, UInt(32.W)))
  val hostRvalid = Wire(Vec(nrHosts, Bool()))
  val hostRdata = Wire(Vec(nrHosts, UInt(32.W)))
  val hostErr = Wire(Vec(nrHosts, Bool()))

  val ibexDataRdataIntg = Wire(UInt(7.W))
  val ibexInstrRdataIntg = Wire(UInt(7.W))

  val deviceReq = Wire(Vec(nrDevices, Bool()))
  val deviceAddr = Wire(Vec(nrDevices, UInt(32.W)))
  val deviceWe = Wire(Vec(nrDevices, Bool()))
  val deviceBe = Wire(Vec(nrDevices, UInt(4.W)))
  val deviceWdata = Wire(Vec(nrDevices, UInt(32.W)))
  val deviceRvalid = Wire(Vec(nrDevices, Bool()))
  val deviceRdata = Wire(Vec(nrDevices, UInt(32.W)))
  val deviceErr = Wire(Vec(nrDevices, Bool()))

  val u_bus = Module(new Bus(nrDevices = nrDevices, nrHosts = nrHosts))
  u_bus.clk_i := IO_CLK
  u_bus.rst_ni := IO_RST_N
  u_bus.host_req_i := hostReq
  hostGnt := u_bus.host_gnt_o
  u_bus.host_addr_i := hostAddr
  u_bus.host_we_i := hostWe
  u_bus.host_be_i := hostBe
  u_bus.host_wdata_i := hostWdata
  hostRvalid := u_bus.host_rvalid_o
  hostRdata := u_bus.host_rdata_o
  hostErr := u_bus.host_err_o
  deviceReq := u_bus.device_req_o
  deviceAddr := u_bus.device_addr_o
  deviceWe := u_bus.device_we_o
  deviceBe := u_bus.device_be_o
  deviceWdata := u_bus.device_wdata_o
  u_bus.device_rvalid_i := deviceRvalid
  u_bus.device_rdata_i := deviceRdata
  u_bus.device_err_i := deviceErr
  u_bus.cfg_device_addr_base(ram) := 0.U
  u_bus.cfg_device_addr_mask(ram) := (~((ramSizeWords * 4 - 1).U(32.W))).asUInt
  u_bus.cfg_device_addr_base(testUtilDevice) := "h00020000".U
  u_bus.cfg_device_addr_mask(testUtilDevice) := "hfffffc00".U

  if (secureIbex) {
    val dataRdataIntgGen = Module(new PrimSecdedInv3932Enc)
    dataRdataIntgGen.data_i := hostRdata(coreD)
    ibexDataRdataIntg := dataRdataIntgGen.data_o(38, 32)

    val instrRdataIntgGen = Module(new PrimSecdedInv3932Enc)
    instrRdataIntgGen.data_i := hostRdata(coreI)
    ibexInstrRdataIntg := instrRdataIntgGen.data_o(38, 32)
  } else {
    ibexDataRdataIntg := 0.U
    ibexInstrRdataIntg := 0.U
  }

  val u_top = Module(new IbexTopTracing(
    pmpEnable = pmpEnable,
    pmpGranularity = pmpGranularity,
    pmpNumRegions = pmpNumRegions,
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
    secureIbex = secureIbex,
    lockstepOffset = lockstepOffset,
    memECC = secureIbex,
    iCacheScramble = iCacheScramble,
    iCacheTweakInfection = iCacheTweakInfection,
    dmBaseAddr = BigInt("00000000", 16),
    dmAddrMask = BigInt("00000003", 16),
    dmHaltAddr = BigInt("00000000", 16),
    dmExceptionAddr = BigInt("00000000", 16)))

  u_top.clk_i := IO_CLK
  u_top.rst_ni := IO_RST_N
  u_top.test_en_i := false.B
  u_top.scan_rst_ni := true.B
  u_top.ram_cfg_icache_tag_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
  u_top.ram_cfg_icache_data_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
  u_top.hart_id_i := 0.U
  u_top.boot_addr_i := 0.U
  hostReq(coreI) := u_top.instr_req_o
  u_top.instr_gnt_i := hostGnt(coreI)
  u_top.instr_rvalid_i := hostRvalid(coreI)
  hostAddr(coreI) := u_top.instr_addr_o
  u_top.instr_rdata_i := hostRdata(coreI)
  u_top.instr_rdata_intg_i := ibexInstrRdataIntg
  u_top.instr_err_i := hostErr(coreI)
  hostWe(coreI) := false.B
  hostBe(coreI) := 0.U
  hostWdata(coreI) := 0.U

  hostReq(coreD) := u_top.data_req_o
  u_top.data_gnt_i := hostGnt(coreD)
  u_top.data_rvalid_i := hostRvalid(coreD)
  hostWe(coreD) := u_top.data_we_o
  hostBe(coreD) := u_top.data_be_o
  hostAddr(coreD) := u_top.data_addr_o
  hostWdata(coreD) := u_top.data_wdata_o
  dontTouch(u_top.data_wdata_intg_o)
  u_top.data_rdata_i := hostRdata(coreD)
  u_top.data_rdata_intg_i := ibexDataRdataIntg
  u_top.data_err_i := hostErr(coreD)

  u_top.irq_software_i := false.B
  u_top.irq_timer_i := false.B
  u_top.irq_external_i := false.B
  u_top.irq_fast_i := 0.U
  u_top.irq_nm_i := false.B
  u_top.scramble_key_valid_i := true.B
  u_top.scramble_key_i := IbexPkg.RndCnstIbexKeyDefault.U(IbexPkg.SCRAMBLE_KEY_W.W)
  u_top.scramble_nonce_i := IbexPkg.RndCnstIbexNonceDefault.U(IbexPkg.SCRAMBLE_NONCE_W.W)
  dontTouch(u_top.scramble_req_o)
  u_top.debug_req_i := false.B
  dontTouch(u_top.crash_dump_o)
  dontTouch(u_top.double_fault_seen_o)
  u_top.fetch_enable_i := IbexPkg.IbexMuBiOn
  dontTouch(u_top.alert_minor_o)
  dontTouch(u_top.alert_major_internal_o)
  dontTouch(u_top.alert_major_bus_o)
  dontTouch(u_top.core_sleep_o)
  dontTouch(u_top.lockstep_cmp_en_o)
  dontTouch(u_top.data_req_shadow_o)
  dontTouch(u_top.data_we_shadow_o)
  dontTouch(u_top.data_be_shadow_o)
  dontTouch(u_top.data_addr_shadow_o)
  dontTouch(u_top.data_wdata_shadow_o)
  dontTouch(u_top.data_wdata_intg_shadow_o)
  dontTouch(u_top.instr_req_shadow_o)
  dontTouch(u_top.instr_addr_shadow_o)

  val u_ram = Module(new Ram1P(depth = ramSizeWords))
  u_ram.clk_i := IO_CLK
  u_ram.rst_ni := IO_RST_N
  u_ram.req_i := deviceReq(ram)
  u_ram.we_i := deviceWe(ram)
  u_ram.be_i := deviceBe(ram)
  u_ram.addr_i := deviceAddr(ram)
  u_ram.wdata_i := deviceWdata(ram)
  deviceRvalid(ram) := u_ram.rvalid_o
  deviceRdata(ram) := u_ram.rdata_o
  deviceErr(ram) := false.B

  val u_riscv_testutil = Module(new RiscvTestUtil)
  u_riscv_testutil.clk_i := IO_CLK
  u_riscv_testutil.rst_ni := IO_RST_N
  u_riscv_testutil.dev_req_i := deviceReq(testUtilDevice)
  u_riscv_testutil.dev_we_i := deviceWe(testUtilDevice)
  u_riscv_testutil.dev_addr_i := deviceAddr(testUtilDevice)
  u_riscv_testutil.dev_wdata_i := deviceWdata(testUtilDevice)
  u_riscv_testutil.dev_be_i := deviceBe(testUtilDevice)
  deviceRvalid(testUtilDevice) := u_riscv_testutil.dev_rvalid_o
  deviceRdata(testUtilDevice) := u_riscv_testutil.dev_rdata_o
  deviceErr(testUtilDevice) := u_riscv_testutil.dev_err_o
  hostReq(testUtilHost) := u_riscv_testutil.host_req_o
  u_riscv_testutil.host_gnt_i := hostGnt(testUtilHost)
  u_riscv_testutil.host_rvalid_i := hostRvalid(testUtilHost)
  hostAddr(testUtilHost) := u_riscv_testutil.host_addr_o
  u_riscv_testutil.host_rdata_i := hostRdata(testUtilHost)
  hostWe(testUtilHost) := false.B
  hostBe(testUtilHost) := 0.U
  hostWdata(testUtilHost) := 0.U
}
