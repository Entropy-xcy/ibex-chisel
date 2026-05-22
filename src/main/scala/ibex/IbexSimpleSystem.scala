// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexSimpleSystem(
    secureIbex: Boolean = false,
    lockstepOffset: Int = 1,
    iCacheScramble: Boolean = false,
    pmpEnable: Boolean = false,
    pmpGranularity: Int = 0,
    pmpNumRegions: Int = 4,
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
    dbgTriggerEn: Boolean = false,
    iCacheECC: Boolean = false,
    iCacheTweakInfection: Boolean = false,
    branchPredictor: Boolean = false,
    instrCycleDelay: Int = 0,
    sramInitFile: String = "",
    ramDepth: Int = (1024 * 1024) / 4)
    extends RawModule {
  override def desiredName: String = "ibex_simple_system"

  val IO_CLK = IO(Input(Clock()))
  val IO_RST_N = IO(Input(Bool()))

  private val nrDevices = 3
  private val nrHosts = 1
  private val coreD = 0
  private val ramBaseAddr = "h00100000".U(32.W)
  private val ramMask = "hfff00000".U(32.W)
  private val simCtrlBaseAddr = "h00020000".U(32.W)
  private val simCtrlMask = "hfffffc00".U(32.W)
  private val timerBaseAddr = "h00030000".U(32.W)
  private val timerMask = "hfffffc00".U(32.W)

  val hostRdata = Wire(UInt(32.W))
  val hostErr = Wire(Bool())
  val instrRvalid = Wire(Bool())
  val instrRdata = Wire(UInt(32.W))
  val dataRdataIntg = Wire(UInt(7.W))
  val instrRdataIntg = Wire(UInt(7.W))
  val deviceReq = Wire(Vec(nrDevices, Bool()))
  val deviceAddr = Wire(Vec(nrDevices, UInt(32.W)))
  val deviceWe = Wire(Vec(nrDevices, Bool()))
  val deviceBe = Wire(Vec(nrDevices, UInt(4.W)))
  val deviceWdata = Wire(Vec(nrDevices, UInt(32.W)))
  val deviceRvalid = Wire(Vec(nrDevices, Bool()))
  val deviceRdata = Wire(Vec(nrDevices, UInt(32.W)))
  val deviceErr = Wire(Vec(nrDevices, Bool()))
  val timerIrq = Wire(Bool())

  val u_bus = Module(new Bus(nrDevices = nrDevices, nrHosts = nrHosts))
  u_bus.clk_i := IO_CLK
  u_bus.rst_ni := IO_RST_N
  val u_ram = Module(new Ram2P(depth = ramDepth, bExtraDelay = instrCycleDelay, memInitFile = sramInitFile))
  val u_simulator_ctrl = Module(new SimulatorCtrl(logName = "ibex_simple_system.log"))
  val u_timer = Module(new Timer())

  val u_top = Module(new IbexTopTracing(
    secureIbex = secureIbex,
    lockstepOffset = lockstepOffset,
    iCacheScramble = iCacheScramble,
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
    iCacheTweakInfection = iCacheTweakInfection,
    memECC = secureIbex,
    dmBaseAddr = BigInt("00100000", 16),
    dmAddrMask = BigInt("00000003", 16),
    dmHaltAddr = BigInt("00100000", 16),
    dmExceptionAddr = BigInt("00100000", 16)))

  u_top.clk_i := IO_CLK
  u_top.rst_ni := IO_RST_N
  u_top.test_en_i := false.B
  u_top.scan_rst_ni := true.B
  u_top.ram_cfg_icache_tag_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
  u_top.ram_cfg_icache_data_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
  u_top.hart_id_i := 0.U
  u_top.boot_addr_i := ramBaseAddr
  u_top.instr_gnt_i := u_top.instr_req_o
  u_top.instr_rvalid_i := instrRvalid
  u_top.instr_rdata_i := instrRdata
  u_top.instr_rdata_intg_i := instrRdataIntg
  u_top.instr_err_i := false.B
  u_top.data_gnt_i := u_bus.host_gnt_o(0)
  u_top.data_rvalid_i := u_bus.host_rvalid_o(0)
  u_top.data_rdata_i := hostRdata
  u_top.data_rdata_intg_i := dataRdataIntg
  u_top.data_err_i := hostErr
  u_top.irq_software_i := false.B
  u_top.irq_timer_i := timerIrq
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

  u_bus.host_req_i(0) := u_top.data_req_o
  u_bus.host_addr_i(0) := u_top.data_addr_o
  u_bus.host_we_i(0) := u_top.data_we_o
  u_bus.host_be_i(0) := u_top.data_be_o
  u_bus.host_wdata_i(0) := u_top.data_wdata_o
  hostRdata := u_bus.host_rdata_o(0)
  hostErr := u_bus.host_err_o(0)
  deviceReq := u_bus.device_req_o
  deviceAddr := u_bus.device_addr_o
  deviceWe := u_bus.device_we_o
  deviceBe := u_bus.device_be_o
  deviceWdata := u_bus.device_wdata_o
  u_bus.device_rvalid_i := deviceRvalid
  u_bus.device_rdata_i := deviceRdata
  u_bus.device_err_i := deviceErr
  u_bus.cfg_device_addr_base(0) := ramBaseAddr
  u_bus.cfg_device_addr_mask(0) := ramMask
  u_bus.cfg_device_addr_base(1) := simCtrlBaseAddr
  u_bus.cfg_device_addr_mask(1) := simCtrlMask
  u_bus.cfg_device_addr_base(2) := timerBaseAddr
  u_bus.cfg_device_addr_mask(2) := timerMask

  u_ram.clk_i := IO_CLK
  u_ram.rst_ni := IO_RST_N
  u_ram.a_req_i := deviceReq(0)
  u_ram.a_we_i := deviceWe(0)
  u_ram.a_be_i := deviceBe(0)
  u_ram.a_addr_i := deviceAddr(0)
  u_ram.a_wdata_i := deviceWdata(0)
  deviceRvalid(0) := u_ram.a_rvalid_o
  deviceRdata(0) := u_ram.a_rdata_o
  deviceErr(0) := false.B
  u_ram.b_req_i := u_top.instr_req_o
  u_ram.b_we_i := false.B
  u_ram.b_be_i := 0.U
  u_ram.b_addr_i := u_top.instr_addr_o
  u_ram.b_wdata_i := 0.U
  instrRvalid := u_ram.b_rvalid_o
  instrRdata := u_ram.b_rdata_o

  u_simulator_ctrl.clk_i := IO_CLK
  u_simulator_ctrl.rst_ni := IO_RST_N
  u_simulator_ctrl.req_i := deviceReq(1)
  u_simulator_ctrl.we_i := deviceWe(1)
  u_simulator_ctrl.be_i := deviceBe(1)
  u_simulator_ctrl.addr_i := deviceAddr(1)
  u_simulator_ctrl.wdata_i := deviceWdata(1)
  deviceRvalid(1) := u_simulator_ctrl.rvalid_o
  deviceRdata(1) := u_simulator_ctrl.rdata_o
  deviceErr(1) := false.B
  dontTouch(u_simulator_ctrl.halt_o)

  u_timer.clk_i := IO_CLK
  u_timer.rst_ni := IO_RST_N
  u_timer.timer_req_i := deviceReq(2)
  u_timer.timer_we_i := deviceWe(2)
  u_timer.timer_be_i := deviceBe(2)
  u_timer.timer_addr_i := deviceAddr(2)
  u_timer.timer_wdata_i := deviceWdata(2)
  deviceRvalid(2) := u_timer.timer_rvalid_o
  deviceRdata(2) := u_timer.timer_rdata_o
  deviceErr(2) := u_timer.timer_err_o
  timerIrq := u_timer.timer_intr_o

  if (secureIbex) {
    val dataRdataIntgGen = Module(new PrimSecdedInv3932Enc)
    dataRdataIntgGen.data_i := hostRdata
    dataRdataIntg := dataRdataIntgGen.data_o(38, 32)

    val instrRdataIntgGen = Module(new PrimSecdedInv3932Enc)
    instrRdataIntgGen.data_i := instrRdata
    instrRdataIntg := instrRdataIntgGen.data_o(38, 32)
  } else {
    dataRdataIntg := 0.U
    instrRdataIntg := 0.U
  }
}
