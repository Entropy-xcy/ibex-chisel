// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexCsRegisters(
    dbgTriggerEn: Boolean = false,
    dbgHwBreakNum: Int = 1,
    dataIndTiming: Boolean = false,
    dummyInstructions: Boolean = false,
    shadowCSR: Boolean = false,
    iCache: Boolean = false,
    mhpmCounterNum: Int = 10,
    mhpmCounterWidth: Int = 40,
    pmpEnable: Boolean = false,
    pmpGranularity: Int = 0,
    pmpNumRegions: Int = 4,
    pmpRstCfg: Seq[BigInt] = IbexPkg.PmpCfgRst,
    pmpRstAddr: Seq[BigInt] = IbexPkg.PmpAddrRst,
    pmpRstMsecCfg: BigInt = IbexPkg.PmpMseccfgRst,
    rv32e: Boolean = false,
    rv32m: Int = 2,
    rv32b: Int = 0,
    csrMvendorId: BigInt = 0,
    csrMimpId: BigInt = 0)
    extends RawModule {
  require(dbgHwBreakNum >= 0)
  require(!dbgTriggerEn || dbgHwBreakNum > 0, "DbgHwBreakNum must be positive when debug triggers are enabled")
  require(mhpmCounterNum >= 0 && mhpmCounterNum <= 29)
  require(mhpmCounterWidth > 0 && mhpmCounterWidth <= 64)
  require(pmpNumRegions >= 0 && pmpNumRegions <= IbexPkg.PMP_MAX_REGIONS)
  require(pmpGranularity >= 0)
  require(pmpRstCfg.length == IbexPkg.PMP_MAX_REGIONS)
  require(pmpRstAddr.length == IbexPkg.PMP_MAX_REGIONS)
  require(pmpRstCfg.forall(cfg => cfg >= 0 && cfg < (BigInt(1) << IbexPkg.PMP_CFG_W)), "PMP reset config values must fit PMP_CFG_W")
  require(pmpRstAddr.forall(addr => addr >= 0 && addr < (BigInt(1) << (IbexPkg.PMP_ADDR_MSB + 1))), "PMP reset addresses must fit PMP address width")
  require(pmpRstMsecCfg >= 0 && pmpRstMsecCfg < 8, "PMP reset mseccfg must fit 3 bits")

  private object CsrOp {
    val Read = 0.U(2.W)
    val Write = 1.U(2.W)
    val Set = 2.U(2.W)
    val Clear = 3.U(2.W)
  }

  private def csr(n: Int): UInt = n.U(12.W)
  private def isDebugCsr(addr: UInt): Bool =
    addr === csr(IbexPkg.CsrNum.DCSR) || addr === csr(IbexPkg.CsrNum.DPC) ||
      addr === csr(IbexPkg.CsrNum.DSCRATCH0) || addr === csr(IbexPkg.CsrNum.DSCRATCH1)
  private def isPmpCsr(addr: UInt): Bool =
    (addr >= csr(IbexPkg.CsrNum.PMPCFG0) && addr <= csr(IbexPkg.CsrNum.PMPCFG3)) ||
      (addr >= csr(IbexPkg.CsrNum.PMPADDR0) && addr <= csr(IbexPkg.CsrNum.PMPADDR15)) ||
      addr === csr(IbexPkg.CsrNum.MSECCFG) || addr === csr(IbexPkg.CsrNum.MSECCFGH)
  private def isTriggerCsr(addr: UInt): Bool =
    addr === csr(IbexPkg.CsrNum.TSELECT) || addr === csr(IbexPkg.CsrNum.TDATA1) ||
      addr === csr(IbexPkg.CsrNum.TDATA2) || addr === csr(IbexPkg.CsrNum.TDATA3) ||
      addr === csr(IbexPkg.CsrNum.MCONTEXT) || addr === csr(IbexPkg.CsrNum.SCONTEXT) ||
      addr === csr(IbexPkg.CsrNum.MSCONTEXT)
  private def isMhpmCounter(addr: UInt): Bool =
    addr >= csr(IbexPkg.CsrNum.MHPMCOUNTER3) && addr <= csr(IbexPkg.CsrNum.MHPMCOUNTER31)
  private def isMhpmCounterH(addr: UInt): Bool =
    addr >= csr(IbexPkg.CsrNum.MHPMCOUNTER3H) && addr <= csr(IbexPkg.CsrNum.MHPMCOUNTER31H)
  private def isMhpmEvent(addr: UInt): Bool =
    addr >= 0x323.U(12.W) && addr <= 0x33f.U(12.W)
  private def pmpCfgByte(cfg: UInt): UInt = Cat(cfg(5), 0.U(2.W), cfg(4, 3), cfg(2), cfg(1), cfg(0))
  private def pmpCfgLocked(cfg: UInt, mseccfg: UInt): Bool = cfg(5) && !mseccfg(2)
  private def isMmlMExecCfg(cfg: UInt): Bool = {
    val rwx = Cat(cfg(0), cfg(1), cfg(2))
    cfg(5) && (rwx === "b001".U || rwx === "b010".U || rwx === "b011".U || rwx === "b101".U)
  }
  private def pmpCfgResetValue(cfg: BigInt): UInt = {
    val lock = (cfg >> 7) & 1
    val modeExecWriteRead = cfg & 0x1f
    ((lock << 5) | modeExecWriteRead).U(6.W)
  }
  private def pmpCfgResetBigInt(cfg: BigInt): BigInt = {
    val lock = (cfg >> 7) & 1
    val modeExecWriteRead = cfg & 0x1f
    (lock << 5) | modeExecWriteRead
  }
  private val pmpAddrWidth = if (pmpGranularity > 0) IbexPkg.PMP_ADDR_MSB - pmpGranularity else 32
  private val pmpAddrResetShift = if (pmpGranularity > 0) pmpGranularity + 1 else IbexPkg.PMP_ADDR_LSB
  private def pmpAddrResetValue(addr: BigInt): UInt =
    ((addr >> pmpAddrResetShift) & ((BigInt(1) << pmpAddrWidth) - 1)).U(pmpAddrWidth.W)
  private def pmpAddrResetBigInt(addr: BigInt): BigInt =
    (addr >> pmpAddrResetShift) & ((BigInt(1) << pmpAddrWidth) - 1)

  private def misaValue: BigInt = {
    val rv32bExtra = if (rv32b != 0) 1 else 0
    val rv32mEnabled = if (rv32m == 0) 0 else 1
    (1 << 2) |
      ((if (rv32e) 1 else 0) << 4) |
      ((if (rv32e) 0 else 1) << 8) |
      (rv32mEnabled << 12) |
      (1 << 20) |
      (rv32bExtra << 23) |
      (1 << 30)
  }

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val hart_id_i = IO(Input(UInt(32.W)))

  val priv_mode_id_o = IO(Output(UInt(2.W)))
  val priv_mode_lsu_o = IO(Output(UInt(2.W)))
  val csr_mstatus_tw_o = IO(Output(Bool()))

  val csr_mtvec_o = IO(Output(UInt(32.W)))
  val csr_mtvec_init_i = IO(Input(Bool()))
  val boot_addr_i = IO(Input(UInt(32.W)))

  val csr_access_i = IO(Input(Bool()))
  val csr_addr_i = IO(Input(UInt(12.W)))
  val csr_wdata_i = IO(Input(UInt(32.W)))
  val csr_op_i = IO(Input(UInt(2.W)))
  val csr_op_en_i = IO(Input(Bool()))
  val csr_rdata_o = IO(Output(UInt(32.W)))

  val irq_software_i = IO(Input(Bool()))
  val irq_timer_i = IO(Input(Bool()))
  val irq_external_i = IO(Input(Bool()))
  val irq_fast_i = IO(Input(UInt(15.W)))
  val nmi_mode_i = IO(Input(Bool()))
  val irq_pending_o = IO(Output(Bool()))
  val mip_o = IO(Output(new IbexPkg.Irqs))
  val irqs_o = IO(Output(new IbexPkg.Irqs))
  val csr_mstatus_mie_o = IO(Output(Bool()))
  val csr_mepc_o = IO(Output(UInt(32.W)))
  val csr_mtval_o = IO(Output(UInt(32.W)))

  val csr_pmp_cfg_o = IO(Output(Vec(pmpNumRegions, new IbexPkg.PmpCfg)))
  val csr_pmp_addr_o = IO(Output(Vec(pmpNumRegions, UInt((IbexPkg.PMP_ADDR_MSB + 1).W))))
  val csr_pmp_mseccfg_o = IO(Output(new IbexPkg.PmpMseccfg))

  val debug_mode_i = IO(Input(Bool()))
  val debug_mode_entering_i = IO(Input(Bool()))
  val debug_cause_i = IO(Input(UInt(3.W)))
  val debug_csr_save_i = IO(Input(Bool()))
  val csr_depc_o = IO(Output(UInt(32.W)))
  val debug_single_step_o = IO(Output(Bool()))
  val debug_ebreakm_o = IO(Output(Bool()))
  val debug_ebreaku_o = IO(Output(Bool()))
  val trigger_match_o = IO(Output(Bool()))

  val pc_if_i = IO(Input(UInt(32.W)))
  val pc_id_i = IO(Input(UInt(32.W)))
  val pc_wb_i = IO(Input(UInt(32.W)))

  val data_ind_timing_o = IO(Output(Bool()))
  val dummy_instr_en_o = IO(Output(Bool()))
  val dummy_instr_mask_o = IO(Output(UInt(3.W)))
  val dummy_instr_seed_en_o = IO(Output(Bool()))
  val dummy_instr_seed_o = IO(Output(UInt(32.W)))
  val icache_enable_o = IO(Output(Bool()))
  val csr_shadow_err_o = IO(Output(Bool()))
  val ic_scr_key_valid_i = IO(Input(Bool()))
  val ic_scr_key_valid_o = IO(Output(Bool()))
  val mcycle_o = IO(Output(UInt(64.W)))
  val mhpmcounter_o = IO(Output(Vec(10, UInt(64.W))))

  val csr_save_if_i = IO(Input(Bool()))
  val csr_save_id_i = IO(Input(Bool()))
  val csr_save_wb_i = IO(Input(Bool()))
  val csr_restore_mret_i = IO(Input(Bool()))
  val csr_restore_dret_i = IO(Input(Bool()))
  val csr_save_cause_i = IO(Input(Bool()))
  val csr_mcause_i = IO(Input(UInt(7.W)))
  val csr_mtval_i = IO(Input(UInt(32.W)))
  val illegal_csr_insn_o = IO(Output(Bool()))
  val double_fault_seen_o = IO(Output(Bool()))

  val instr_ret_i = IO(Input(Bool()))
  val instr_ret_compressed_i = IO(Input(Bool()))
  val instr_ret_spec_i = IO(Input(Bool()))
  val instr_ret_compressed_spec_i = IO(Input(Bool()))
  val iside_wait_i = IO(Input(Bool()))
  val jump_i = IO(Input(Bool()))
  val branch_i = IO(Input(Bool()))
  val branch_taken_i = IO(Input(Bool()))
  val mem_load_i = IO(Input(Bool()))
  val mem_store_i = IO(Input(Bool()))
  val dside_wait_i = IO(Input(Bool()))
  val mul_wait_i = IO(Input(Bool()))
  val div_wait_i = IO(Input(Bool()))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val privLvl = RegInit(IbexPkg.PrivLvl.M)
    val mstatusMie = RegInit(false.B)
    val mstatusMpie = RegInit(true.B)
    val mstatusMpp = RegInit(IbexPkg.PrivLvl.U)
    val mstatusMprv = RegInit(false.B)
    val mstatusTw = RegInit(false.B)
    val mstatusShadow = RegInit("hffffff7f".U(32.W))
    val mieSoftware = RegInit(false.B)
    val mieTimer = RegInit(false.B)
    val mieExternal = RegInit(false.B)
    val mieFast = RegInit(0.U(15.W))
    val mscratch = RegInit(0.U(32.W))
    val mepc = RegInit(0.U(32.W))
    val mcause = RegInit(0.U(7.W))
    val mtval = RegInit(0.U(32.W))
    val mtvec = RegInit(1.U(32.W))
    val mtvecShadow = RegInit("hfffffffe".U(32.W))
    val dcsr = RegInit("h40000003".U(32.W))
    val depc = RegInit(0.U(32.W))
    val dscratch0 = RegInit(0.U(32.W))
    val dscratch1 = RegInit(0.U(32.W))
    val mstackMpie = RegInit(true.B)
    val mstackMpp = RegInit(IbexPkg.PrivLvl.U)
    val mstackEpc = RegInit(0.U(32.W))
    val mstackCause = RegInit(0.U(7.W))
    val syncExcSeen = RegInit(false.B)
    val doubleFaultSeen = RegInit(false.B)
    val cpuDataIndTiming = RegInit(false.B)
    val cpuDummyInstrEn = RegInit(false.B)
    val cpuDummyInstrMask = RegInit(0.U(3.W))
    val cpuIcacheEnable = RegInit(false.B)
    val cpuIcScrKeyValid = RegInit(false.B)
    val cpuctrlstsPartShadow = RegInit("h1ff".U(9.W))
    val cpuIcScrKeyValidShadow = RegInit(1.U(1.W))
    val mcountinhibit = RegInit(0.U(32.W))
    val mcycle = RegInit(0.U(64.W))
    val minstret = RegInit(0.U(64.W))
    val mhpm = RegInit(VecInit(Seq.fill(29)(0.U(mhpmCounterWidth.W))))
    val pmpCfg = RegInit(VecInit(pmpRstCfg.map(pmpCfgResetValue)))
    val pmpAddr = RegInit(VecInit(pmpRstAddr.map(pmpAddrResetValue)))
    val pmpMseccfg = RegInit(pmpRstMsecCfg.U(3.W))
    val pmpCfgShadow = RegInit(VecInit(pmpRstCfg.map(cfg => (~pmpCfgResetBigInt(cfg) & 0x3f).U(6.W))))
    val pmpAddrShadow = RegInit(VecInit(pmpRstAddr.map(addr => (~pmpAddrResetBigInt(addr) & ((BigInt(1) << pmpAddrWidth) - 1)).U(pmpAddrWidth.W))))
    val pmpMseccfgShadow = RegInit((~pmpRstMsecCfg & BigInt(0x7)).U(3.W))
    val dbgHwNumLen = if (dbgHwBreakNum > 1) log2Ceil(dbgHwBreakNum) else 1
    val dbgTriggerSlots = math.max(dbgHwBreakNum, 1)
    val tselect = RegInit(0.U(dbgHwNumLen.W))
    val tmatchControl = RegInit(VecInit(Seq.fill(dbgTriggerSlots)(false.B)))
    val tmatchValue = RegInit(VecInit(Seq.fill(dbgTriggerSlots)(0.U(32.W))))

    val dcsrStep = dcsr(2)
    val dcsrPrv = dcsr(1, 0)
    val dcsrEbreaku = dcsr(12)
    val dcsrEbreakm = dcsr(15)
    val selectedTmatchControl = if (dbgHwBreakNum > 1) tmatchControl(tselect) else tmatchControl(0)
    val selectedTmatchValue = if (dbgHwBreakNum > 1) tmatchValue(tselect) else tmatchValue(0)
    val tmatchControlRdata = Cat(
      "h2".U(4.W), 1.U(1.W), 0.U(6.W), 0.U(1.W), 0.U(1.W), 0.U(1.W),
      0.U(2.W), "h1".U(4.W), 0.U(1.W), 0.U(4.W), 1.U(1.W), 0.U(1.W),
      0.U(1.W), 1.U(1.W), selectedTmatchControl, 0.U(1.W), 0.U(1.W))
    def packMstatus(mie: Bool, mpie: Bool, mpp: UInt, mprv: Bool, tw: Bool): UInt =
      Cat(0.U(10.W), tw, 0.U(3.W), mprv, 0.U(4.W), mpp,
        0.U(3.W), mpie, 0.U(3.W), mie, 0.U(3.W))
    def packCpuctrlstsPart(
        doubleFaultSeenBit: Bool,
        syncExcSeenBit: Bool,
        dummyInstrMask: UInt,
        dummyInstrEn: Bool,
        dataIndTimingBit: Bool,
        icacheEnable: Bool): UInt =
      Cat(doubleFaultSeenBit, syncExcSeenBit, dummyInstrMask, dummyInstrEn, dataIndTimingBit, icacheEnable)
    val mcountinhibitMask = {
      val implementedBits = (BigInt(1) << (mhpmCounterNum + 3)) - 1
      (implementedBits & ~BigInt(2)).U(32.W)
    }
    val minstretRdata = Mux(instr_ret_spec_i && !mcountinhibit(2), (minstret + 1.U).pad(64), minstret.pad(64))
    val mhpmRdata = Wire(Vec(29, UInt(64.W)))
    for (i <- 0 until 29) {
      val raw = mhpm(i).pad(64)
      if (i == 7) {
        mhpmRdata(i) := Mux(instr_ret_compressed_spec_i && !mcountinhibit(10), (mhpm(i) + 1.U).pad(64), raw)
      } else {
        mhpmRdata(i) := raw
      }
    }
    mcycle_o := mcycle.pad(64)
    for (i <- 0 until 10) {
      mhpmcounter_o(i) := Mux(i.U < mhpmCounterNum.U, mhpm(i).pad(64), 0.U)
    }
    val mhpmEventRdata = VecInit((0 until 29).map { i =>
      if (i < mhpmCounterNum) (BigInt(1) << i).U(32.W) else 0.U(32.W)
    })

    val mstatusRdata = packMstatus(mstatusMie, mstatusMpie, mstatusMpp, mstatusMprv, mstatusTw)

    val mieRdata = Cat(
      0.U(1.W), mieFast, 0.U(4.W), mieExternal, 0.U(3.W), mieTimer,
      0.U(3.W), mieSoftware, 0.U(3.W))

    val mipRdata = Cat(
      0.U(1.W), irq_fast_i, 0.U(4.W), irq_external_i, 0.U(3.W), irq_timer_i,
      0.U(3.W), irq_software_i, 0.U(3.W))

    val mcauseRdata = Cat(mcause(6) | mcause(5), Fill(26, mcause(5)), mcause(4, 0))
    val cpuctrlstsPart = packCpuctrlstsPart(doubleFaultSeen, syncExcSeen, cpuDummyInstrMask, cpuDummyInstrEn, cpuDataIndTiming, cpuIcacheEnable)
    val cpuctrlstsRdata = Cat(0.U(22.W), Mux(iCache.B, cpuIcScrKeyValid, false.B), cpuctrlstsPart)
    val pmpMseccfgRdata = Cat(0.U(29.W), pmpMseccfg(2), pmpMseccfg(1), pmpMseccfg(0))
    val pmpCfgRdata = Wire(Vec(4, UInt(32.W)))
    for (cfgCsr <- 0 until 4) {
      pmpCfgRdata(cfgCsr) := Cat((0 until 4).reverse.map { byte =>
        val idx = cfgCsr * 4 + byte
        Mux((pmpEnable && idx < pmpNumRegions).B, pmpCfgByte(pmpCfg(idx)), 0.U(8.W))
      })
    }
    val pmpAddrRdata = Wire(Vec(IbexPkg.PMP_MAX_REGIONS, UInt(32.W)))
    for (i <- 0 until IbexPkg.PMP_MAX_REGIONS) {
      val implemented = (pmpEnable && i < pmpNumRegions).B
      val raw = pmpAddr(i)
      if (pmpGranularity == 0) {
        pmpAddrRdata(i) := Mux(implemented, raw, 0.U)
      } else if (pmpGranularity == 1) {
        pmpAddrRdata(i) := Mux(
          implemented,
          Mux(pmpCfg(i)(4, 3) === IbexPkg.PmpCfgMode.Napot, raw, Cat(raw(31, 1), 0.U(1.W))),
          0.U)
      } else {
        val napotRead = Cat(raw, Fill(pmpGranularity - 1, 1.U(1.W)))
        val offTorRead = Cat(raw, 0.U((pmpGranularity - 1).W)) & ~((BigInt(1) << pmpGranularity) - 1).U(32.W)
        pmpAddrRdata(i) := Mux(
          implemented,
          Mux(pmpCfg(i)(4, 3) === IbexPkg.PmpCfgMode.Napot, napotRead, offTorRead),
          0.U)
      }
    }

    val rawRdata = WireDefault(0.U(32.W))
    val illegalCsr = WireDefault(false.B)
    switch(csr_addr_i) {
      is(csr(IbexPkg.CsrNum.MVENDORID)) { rawRdata := csrMvendorId.U(32.W) }
      is(csr(IbexPkg.CsrNum.MARCHID)) { rawRdata := IbexPkg.CSR_MARCHID_VALUE }
      is(csr(IbexPkg.CsrNum.MIMPID)) { rawRdata := csrMimpId.U(32.W) }
      is(csr(IbexPkg.CsrNum.MHARTID)) { rawRdata := hart_id_i }
      is(csr(IbexPkg.CsrNum.MCONFIGPTR)) { rawRdata := IbexPkg.CSR_MCONFIGPTR_VALUE }
      is(csr(IbexPkg.CsrNum.MSTATUS)) { rawRdata := mstatusRdata }
      is(csr(IbexPkg.CsrNum.MSTATUSH), csr(IbexPkg.CsrNum.MENVCFG), csr(IbexPkg.CsrNum.MENVCFGH), csr(IbexPkg.CsrNum.MCOUNTEREN)) { rawRdata := 0.U }
      is(csr(IbexPkg.CsrNum.MISA)) { rawRdata := misaValue.U(32.W) }
      is(csr(IbexPkg.CsrNum.MIE)) { rawRdata := mieRdata }
      is(csr(IbexPkg.CsrNum.MSCRATCH)) { rawRdata := mscratch }
      is(csr(IbexPkg.CsrNum.MTVEC)) { rawRdata := mtvec }
      is(csr(IbexPkg.CsrNum.MEPC)) { rawRdata := mepc }
      is(csr(IbexPkg.CsrNum.MCAUSE)) { rawRdata := mcauseRdata }
      is(csr(IbexPkg.CsrNum.MTVAL)) { rawRdata := mtval }
      is(csr(IbexPkg.CsrNum.MIP)) { rawRdata := mipRdata }
      is(csr(IbexPkg.CsrNum.PMPCFG0)) { rawRdata := pmpCfgRdata(0) }
      is(csr(IbexPkg.CsrNum.PMPCFG1)) { rawRdata := pmpCfgRdata(1) }
      is(csr(IbexPkg.CsrNum.PMPCFG2)) { rawRdata := pmpCfgRdata(2) }
      is(csr(IbexPkg.CsrNum.PMPCFG3)) { rawRdata := pmpCfgRdata(3) }
      is(csr(IbexPkg.CsrNum.MSECCFG)) { rawRdata := pmpMseccfgRdata }
      is(csr(IbexPkg.CsrNum.MSECCFGH)) { rawRdata := 0.U }
      is(csr(IbexPkg.CsrNum.DCSR)) { rawRdata := dcsr }
      is(csr(IbexPkg.CsrNum.DPC)) { rawRdata := depc }
      is(csr(IbexPkg.CsrNum.DSCRATCH0)) { rawRdata := dscratch0 }
      is(csr(IbexPkg.CsrNum.DSCRATCH1)) { rawRdata := dscratch1 }
      is(csr(IbexPkg.CsrNum.TSELECT)) { rawRdata := tselect.pad(32) }
      is(csr(IbexPkg.CsrNum.TDATA1)) { rawRdata := tmatchControlRdata }
      is(csr(IbexPkg.CsrNum.TDATA2)) { rawRdata := selectedTmatchValue }
      is(csr(IbexPkg.CsrNum.TDATA3), csr(IbexPkg.CsrNum.MCONTEXT), csr(IbexPkg.CsrNum.SCONTEXT), csr(IbexPkg.CsrNum.MSCONTEXT)) { rawRdata := 0.U }
      is(csr(IbexPkg.CsrNum.MCOUNTINHIBIT)) { rawRdata := mcountinhibit }
      is(csr(IbexPkg.CsrNum.MCYCLE)) { rawRdata := mcycle.pad(64)(31, 0) }
      is(csr(IbexPkg.CsrNum.MCYCLEH)) { rawRdata := mcycle.pad(64)(63, 32) }
      is(csr(IbexPkg.CsrNum.MINSTRET)) { rawRdata := minstretRdata(31, 0) }
      is(csr(IbexPkg.CsrNum.MINSTRETH)) { rawRdata := minstretRdata(63, 32) }
      is(csr(IbexPkg.CsrNum.CYCLE)) { rawRdata := mcycle.pad(64)(31, 0) }
      is(csr(IbexPkg.CsrNum.CYCLEH)) { rawRdata := mcycle.pad(64)(63, 32) }
      is(csr(IbexPkg.CsrNum.INSTRET)) { rawRdata := minstretRdata(31, 0) }
      is(csr(IbexPkg.CsrNum.INSTRETH)) { rawRdata := minstretRdata(63, 32) }
      is(csr(IbexPkg.CsrNum.CPUCTRLSTS)) { rawRdata := cpuctrlstsRdata }
      is(csr(IbexPkg.CsrNum.SECURESEED)) { rawRdata := 0.U }
    }
    when(isMhpmCounter(csr_addr_i)) {
      val idx = csr_addr_i(4, 0) - 3.U
      val mhpmLo = MuxLookup(idx, 0.U(32.W))((0 until 29).map(i => i.U -> mhpmRdata(i)(31, 0)))
      rawRdata := Mux(idx < mhpmCounterNum.U, mhpmLo, 0.U)
    }.elsewhen(isMhpmCounterH(csr_addr_i)) {
      val idx = csr_addr_i(4, 0) - 3.U
      val mhpmHi = MuxLookup(idx, 0.U(32.W))((0 until 29).map(i => i.U -> mhpmRdata(i)(63, 32)))
      rawRdata := Mux(idx < mhpmCounterNum.U, mhpmHi, 0.U)
    }.elsewhen(isMhpmEvent(csr_addr_i)) {
      val idx = csr_addr_i(4, 0) - 3.U
      rawRdata := Mux(idx < mhpmCounterNum.U, mhpmEventRdata(idx), 0.U)
    }.elsewhen(csr_addr_i >= csr(IbexPkg.CsrNum.PMPADDR0) && csr_addr_i <= csr(IbexPkg.CsrNum.PMPADDR15)) {
      val idx = csr_addr_i(3, 0)
      rawRdata := pmpAddrRdata(idx)
      illegalCsr := !pmpEnable.B
    }.elsewhen(isPmpCsr(csr_addr_i)) {
      illegalCsr := !pmpEnable.B
    }.elsewhen(isTriggerCsr(csr_addr_i)) {
      illegalCsr := !dbgTriggerEn.B
    }.elsewhen(!(csr_addr_i === csr(IbexPkg.CsrNum.MVENDORID) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MARCHID) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MIMPID) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MHARTID) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCONFIGPTR) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MSTATUS) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MSTATUSH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MENVCFG) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MENVCFGH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MISA) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MIE) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCOUNTEREN) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MSCRATCH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MTVEC) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MEPC) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCAUSE) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MTVAL) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MIP) ||
      csr_addr_i === csr(IbexPkg.CsrNum.PMPCFG0) ||
      csr_addr_i === csr(IbexPkg.CsrNum.PMPCFG1) ||
      csr_addr_i === csr(IbexPkg.CsrNum.PMPCFG2) ||
      csr_addr_i === csr(IbexPkg.CsrNum.PMPCFG3) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MSECCFG) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MSECCFGH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.DCSR) ||
      csr_addr_i === csr(IbexPkg.CsrNum.DPC) ||
      csr_addr_i === csr(IbexPkg.CsrNum.DSCRATCH0) ||
      csr_addr_i === csr(IbexPkg.CsrNum.DSCRATCH1) ||
      csr_addr_i === csr(IbexPkg.CsrNum.TSELECT) ||
      csr_addr_i === csr(IbexPkg.CsrNum.TDATA1) ||
      csr_addr_i === csr(IbexPkg.CsrNum.TDATA2) ||
      csr_addr_i === csr(IbexPkg.CsrNum.TDATA3) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCONTEXT) ||
      csr_addr_i === csr(IbexPkg.CsrNum.SCONTEXT) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MSCONTEXT) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCOUNTINHIBIT) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCYCLE) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MCYCLEH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MINSTRET) ||
      csr_addr_i === csr(IbexPkg.CsrNum.MINSTRETH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.CYCLE) ||
      csr_addr_i === csr(IbexPkg.CsrNum.CYCLEH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.INSTRET) ||
      csr_addr_i === csr(IbexPkg.CsrNum.INSTRETH) ||
      csr_addr_i === csr(IbexPkg.CsrNum.CPUCTRLSTS) ||
      csr_addr_i === csr(IbexPkg.CsrNum.SECURESEED))) {
      illegalCsr := true.B
    }

    val csrWr = csr_op_i === CsrOp.Write || csr_op_i === CsrOp.Set || csr_op_i === CsrOp.Clear
    val wdata = MuxLookup(csr_op_i, csr_wdata_i)(
      Seq(
        CsrOp.Write -> csr_wdata_i,
        CsrOp.Set -> (csr_wdata_i | rawRdata),
        CsrOp.Clear -> (~csr_wdata_i & rawRdata),
        CsrOp.Read -> csr_wdata_i))
    val illegalWrite = csrWr && csr_addr_i(11, 10) === 3.U
    val illegalPriv = csr_addr_i(9, 8) > privLvl
    val illegalDebug = isDebugCsr(csr_addr_i) && !debug_mode_i
    val illegalInsn = csr_access_i && (illegalCsr || illegalWrite || illegalPriv || illegalDebug)
    val csrWe = csrWr && csr_op_en_i && !illegalInsn

    when(!mcountinhibit(0)) { mcycle := mcycle + 1.U }
    when(instr_ret_i && !mcountinhibit(2)) { minstret := minstret + 1.U }
    for (i <- 0 until 29) {
      val eventHit = i match {
        case 0 => dside_wait_i
        case 1 => iside_wait_i
        case 2 => mem_load_i
        case 3 => mem_store_i
        case 4 => jump_i
        case 5 => branch_i
        case 6 => branch_taken_i
        case 7 => instr_ret_compressed_i
        case 8 => mul_wait_i
        case 9 => div_wait_i
        case _ => false.B
      }
      when(i.U < mhpmCounterNum.U && eventHit && !mcountinhibit(i + 3)) {
        mhpm(i) := mhpm(i) + 1.U
      }
    }

    val exceptionPc = Mux(csr_save_if_i, pc_if_i, Mux(csr_save_wb_i, pc_wb_i, pc_id_i))
    double_fault_seen_o := false.B

    when(csr_mtvec_init_i) {
      val mtvecNext = Cat(boot_addr_i(31, 8), 0.U(6.W), 1.U(2.W))
      mtvec := mtvecNext
      mtvecShadow := ~mtvecNext
    }
    val cpuIcScrKeyValidNext = iCache.B && ic_scr_key_valid_i
    cpuIcScrKeyValid := cpuIcScrKeyValidNext
    cpuIcScrKeyValidShadow := ~cpuIcScrKeyValidNext

    when(csrWe) {
      switch(csr_addr_i) {
        is(csr(IbexPkg.CsrNum.MSTATUS)) {
          val mieNext = wdata(IbexPkg.CSR_MSTATUS_MIE_BIT)
          val mpieNext = wdata(IbexPkg.CSR_MSTATUS_MPIE_BIT)
          val mpp = wdata(IbexPkg.CSR_MSTATUS_MPP_BIT_HIGH, IbexPkg.CSR_MSTATUS_MPP_BIT_LOW)
          val mppNext = Mux(mpp === IbexPkg.PrivLvl.M || mpp === IbexPkg.PrivLvl.U, mpp, IbexPkg.PrivLvl.U)
          val mprvNext = wdata(IbexPkg.CSR_MSTATUS_MPRV_BIT)
          val twNext = wdata(IbexPkg.CSR_MSTATUS_TW_BIT)
          mstatusMie := mieNext
          mstatusMpie := mpieNext
          mstatusMpp := mppNext
          mstatusMprv := mprvNext
          mstatusTw := twNext
          mstatusShadow := ~packMstatus(mieNext, mpieNext, mppNext, mprvNext, twNext)
        }
        is(csr(IbexPkg.CsrNum.MIE)) {
          mieSoftware := wdata(IbexPkg.CSR_MSIX_BIT)
          mieTimer := wdata(IbexPkg.CSR_MTIX_BIT)
          mieExternal := wdata(IbexPkg.CSR_MEIX_BIT)
          mieFast := wdata(IbexPkg.CSR_MFIX_BIT_HIGH, IbexPkg.CSR_MFIX_BIT_LOW)
        }
        is(csr(IbexPkg.CsrNum.MSCRATCH)) { mscratch := wdata }
        is(csr(IbexPkg.CsrNum.MEPC)) { mepc := Cat(wdata(31, 1), 0.U(1.W)) }
        is(csr(IbexPkg.CsrNum.MCAUSE)) { mcause := Cat(wdata(31, 30), wdata(4, 0)) }
        is(csr(IbexPkg.CsrNum.MTVAL)) { mtval := wdata }
        is(csr(IbexPkg.CsrNum.MTVEC)) {
          val mtvecNext = Cat(wdata(31, 8), 0.U(6.W), 1.U(2.W))
          mtvec := mtvecNext
          mtvecShadow := ~mtvecNext
        }
        is(csr(IbexPkg.CsrNum.DCSR)) {
          val prv = Mux(wdata(1, 0) === IbexPkg.PrivLvl.M || wdata(1, 0) === IbexPkg.PrivLvl.U, wdata(1, 0), IbexPkg.PrivLvl.U)
          dcsr := Cat(
            IbexPkg.XDebugVer.Std, 0.U(12.W), wdata(15), 0.U(1.W), wdata(13), wdata(12),
            0.U(3.W), dcsr(8, 6), 0.U(3.W), wdata(2), prv)
        }
        is(csr(IbexPkg.CsrNum.DPC)) { depc := Cat(wdata(31, 1), 0.U(1.W)) }
        is(csr(IbexPkg.CsrNum.DSCRATCH0)) { dscratch0 := wdata }
        is(csr(IbexPkg.CsrNum.DSCRATCH1)) { dscratch1 := wdata }
        is(csr(IbexPkg.CsrNum.MCOUNTINHIBIT)) { mcountinhibit := wdata & mcountinhibitMask }
        is(csr(IbexPkg.CsrNum.MCYCLE)) { mcycle := Cat(mcycle(63, 32), wdata) }
        is(csr(IbexPkg.CsrNum.MCYCLEH)) { mcycle := Cat(wdata, mcycle(31, 0)) }
        is(csr(IbexPkg.CsrNum.MINSTRET)) { minstret := Cat(minstret(63, 32), wdata) }
        is(csr(IbexPkg.CsrNum.MINSTRETH)) { minstret := Cat(wdata, minstret(31, 0)) }
        is(csr(IbexPkg.CsrNum.CPUCTRLSTS)) {
          val icacheEnableNext = iCache.B && wdata(0)
          val dataIndTimingNext = dataIndTiming.B && wdata(1)
          val dummyInstrEnNext = dummyInstructions.B && wdata(2)
          val dummyInstrMaskNext = Mux(dummyInstructions.B, wdata(5, 3), 0.U)
          val syncExcSeenNext = wdata(6)
          val doubleFaultSeenNext = wdata(7)
          cpuIcacheEnable := icacheEnableNext
          cpuDataIndTiming := dataIndTimingNext
          cpuDummyInstrEn := dummyInstrEnNext
          cpuDummyInstrMask := dummyInstrMaskNext
          syncExcSeen := syncExcSeenNext
          doubleFaultSeen := doubleFaultSeenNext
          cpuctrlstsPartShadow := ~packCpuctrlstsPart(
            doubleFaultSeenNext, syncExcSeenNext, dummyInstrMaskNext, dummyInstrEnNext,
            dataIndTimingNext, icacheEnableNext)
        }
      }
      when(isMhpmCounter(csr_addr_i)) {
        val idx = csr_addr_i(4, 0) - 3.U
        when(idx < mhpmCounterNum.U) { mhpm(idx) := Cat(mhpm(idx).pad(64)(63, 32), wdata)(mhpmCounterWidth - 1, 0) }
      }.elsewhen(isMhpmCounterH(csr_addr_i)) {
        val idx = csr_addr_i(4, 0) - 3.U
        when(idx < mhpmCounterNum.U) { mhpm(idx) := Cat(wdata, mhpm(idx).pad(64)(31, 0))(mhpmCounterWidth - 1, 0) }
      }.elsewhen(isMhpmEvent(csr_addr_i)) {
        // Event selector CSRs are hardwired in Ibex and ignore writes.
      }.elsewhen(pmpEnable.B && csr_addr_i >= csr(IbexPkg.CsrNum.PMPCFG0) && csr_addr_i <= csr(IbexPkg.CsrNum.PMPCFG3)) {
        val cfgCsr = csr_addr_i - csr(IbexPkg.CsrNum.PMPCFG0)
        for (i <- 0 until IbexPkg.PMP_MAX_REGIONS) {
          val byteSel = i % 4
          val cfgAddrMatch = cfgCsr === (i / 4).U
          val implemented = (i < pmpNumRegions).B
          val locked = pmpCfgLocked(pmpCfg(i), pmpMseccfg)
          val byte = wdata(byteSel * 8 + 7, byteSel * 8)
          val mode = Mux(byte(4, 3) === IbexPkg.PmpCfgMode.Na4 && pmpGranularity.U =/= 0.U, IbexPkg.PmpCfgMode.Off, byte(4, 3))
          val write = Mux(pmpMseccfg(0), byte(1), byte(1) && byte(0))
          val cfgWdata = Cat(byte(7), mode, byte(2), write, byte(0))
          val wrSuppress = pmpMseccfg(0) && !pmpMseccfg(2) && isMmlMExecCfg(cfgWdata)
          when(implemented && cfgAddrMatch && !locked && !wrSuppress) {
            pmpCfg(i) := cfgWdata
            pmpCfgShadow(i) := ~cfgWdata
          }
        }
      }.elsewhen(pmpEnable.B && csr_addr_i >= csr(IbexPkg.CsrNum.PMPADDR0) && csr_addr_i <= csr(IbexPkg.CsrNum.PMPADDR15)) {
        val idx = csr_addr_i(3, 0)
        for (i <- 0 until IbexPkg.PMP_MAX_REGIONS) {
          val implemented = (i < pmpNumRegions).B
          val thisLocked = pmpCfgLocked(pmpCfg(i), pmpMseccfg)
          val nextAllowsWrite =
            if (i < IbexPkg.PMP_MAX_REGIONS - 1)
              !pmpCfgLocked(pmpCfg(i + 1), pmpMseccfg) || pmpCfg(i + 1)(4, 3) =/= IbexPkg.PmpCfgMode.Tor
            else true.B
          val pmpAddrWdata = if (pmpAddrWidth == 32) wdata else wdata(31, 32 - pmpAddrWidth)
          when(implemented && idx === i.U && !thisLocked && nextAllowsWrite) {
            pmpAddr(i) := pmpAddrWdata
            pmpAddrShadow(i) := ~pmpAddrWdata
          }
        }
      }.elsewhen(pmpEnable.B && csr_addr_i === csr(IbexPkg.CsrNum.MSECCFG)) {
        val anyLocked = pmpCfg.map(cfg => pmpCfgLocked(cfg, pmpMseccfg)).reduce(_ || _)
        val pmpMseccfgNext = Cat(Mux(anyLocked, false.B, wdata(IbexPkg.CSR_MSECCFG_RLB_BIT)), pmpMseccfg(1) || wdata(IbexPkg.CSR_MSECCFG_MMWP_BIT), pmpMseccfg(0) || wdata(IbexPkg.CSR_MSECCFG_MML_BIT))
        pmpMseccfg := pmpMseccfgNext
        pmpMseccfgShadow := ~pmpMseccfgNext
      }.elsewhen(dbgTriggerEn.B && debug_mode_i && csr_addr_i === csr(IbexPkg.CsrNum.TSELECT)) {
        if (dbgHwBreakNum > 1) {
          tselect := Mux(wdata < dbgHwBreakNum.U, wdata(dbgHwNumLen - 1, 0), (dbgHwBreakNum - 1).U(dbgHwNumLen.W))
        } else {
          tselect := 0.U
        }
      }.elsewhen(dbgTriggerEn.B && debug_mode_i && csr_addr_i === csr(IbexPkg.CsrNum.TDATA1)) {
        for (i <- 0 until dbgTriggerSlots) {
          when(tselect === i.U) {
            tmatchControl(i) := wdata(2)
          }
        }
      }.elsewhen(dbgTriggerEn.B && debug_mode_i && csr_addr_i === csr(IbexPkg.CsrNum.TDATA2)) {
        for (i <- 0 until dbgTriggerSlots) {
          when(tselect === i.U) {
            tmatchValue(i) := wdata
          }
        }
      }
    }

    when(csr_save_cause_i) {
      privLvl := IbexPkg.PrivLvl.M
      when(debug_csr_save_i) {
        dcsr := Cat(
          dcsr(31, 9), debug_cause_i, dcsr(5, 2), privLvl)
        depc := exceptionPc
      }.elsewhen(!debug_mode_i) {
        val syncExcSeenNext = Mux(!csr_mcause_i(6) && !csr_mcause_i(5), true.B, syncExcSeen)
        val doubleFaultSeenNext = Mux(!csr_mcause_i(6) && !csr_mcause_i(5), doubleFaultSeen || syncExcSeen, doubleFaultSeen)
        mtval := csr_mtval_i
        mstatusMie := false.B
        mstatusMpie := mstatusMie
        mstatusMpp := privLvl
        mstatusShadow := ~packMstatus(false.B, mstatusMie, privLvl, mstatusMprv, mstatusTw)
        mepc := exceptionPc
        mcause := csr_mcause_i
        mstackMpie := mstatusMpie
        mstackMpp := mstatusMpp
        mstackEpc := mepc
        mstackCause := mcause
        when(!csr_mcause_i(6) && !csr_mcause_i(5)) {
          double_fault_seen_o := syncExcSeen
          doubleFaultSeen := doubleFaultSeenNext
          syncExcSeen := syncExcSeenNext
        }
        cpuctrlstsPartShadow := ~packCpuctrlstsPart(
          doubleFaultSeenNext, syncExcSeenNext, cpuDummyInstrMask, cpuDummyInstrEn,
          cpuDataIndTiming, cpuIcacheEnable)
      }
    }.elsewhen(csr_restore_dret_i) {
      privLvl := dcsrPrv
    }.elsewhen(csr_restore_mret_i) {
      privLvl := mstatusMpp
      mstatusMie := mstatusMpie
      when(mstatusMpp =/= IbexPkg.PrivLvl.M) { mstatusMprv := false.B }
      syncExcSeen := false.B
      val mstatusMprvNext = Mux(mstatusMpp =/= IbexPkg.PrivLvl.M, false.B, mstatusMprv)
      when(nmi_mode_i) {
        mstatusMpie := mstackMpie
        mstatusMpp := mstackMpp
        mepc := mstackEpc
        mcause := mstackCause
        mstatusShadow := ~packMstatus(mstatusMpie, mstackMpie, mstackMpp, mstatusMprvNext, mstatusTw)
      }.otherwise {
        mstatusMpie := true.B
        mstatusMpp := IbexPkg.PrivLvl.U
        mstatusShadow := ~packMstatus(mstatusMpie, true.B, IbexPkg.PrivLvl.U, mstatusMprvNext, mstatusTw)
      }
      cpuctrlstsPartShadow := ~packCpuctrlstsPart(
        doubleFaultSeen, false.B, cpuDummyInstrMask, cpuDummyInstrEn,
        cpuDataIndTiming, cpuIcacheEnable)
    }

    csr_rdata_o := rawRdata
    illegal_csr_insn_o := illegalInsn

    priv_mode_id_o := privLvl
    priv_mode_lsu_o := Mux(mstatusMprv, mstatusMpp, privLvl)
    csr_mstatus_tw_o := mstatusTw
    csr_mtvec_o := mtvec
    csr_mstatus_mie_o := mstatusMie
    csr_mepc_o := mepc
    csr_mtval_o := mtval
    csr_depc_o := depc
    debug_single_step_o := dcsrStep
    debug_ebreakm_o := dcsrEbreakm
    debug_ebreaku_o := dcsrEbreaku
    trigger_match_o := dbgTriggerEn.B && tmatchControl.zip(tmatchValue).map { case (control, value) =>
      control && pc_if_i === value
    }.reduce(_ || _)
    data_ind_timing_o := cpuDataIndTiming
    dummy_instr_en_o := cpuDummyInstrEn
    dummy_instr_mask_o := cpuDummyInstrMask
    dummy_instr_seed_en_o := dummyInstructions.B && csrWe && csr_addr_i === csr(IbexPkg.CsrNum.SECURESEED)
    dummy_instr_seed_o := wdata
    icache_enable_o := cpuIcacheEnable && !(debug_mode_i || debug_mode_entering_i)
    ic_scr_key_valid_o := cpuIcScrKeyValid
    val mstatusErr = shadowCSR.B && (mstatusRdata =/= ~mstatusShadow)
    val mtvecErr = shadowCSR.B && (mtvec =/= ~mtvecShadow)
    val cpuctrlstsPartErr = shadowCSR.B && (cpuctrlstsPart =/= ~cpuctrlstsPartShadow)
    val cpuctrlstsIcScrKeyErr = shadowCSR.B && iCache.B && (cpuIcScrKeyValid =/= ~cpuIcScrKeyValidShadow)
    val pmpCsrErr = shadowCSR.B && pmpEnable.B && (
      (0 until IbexPkg.PMP_MAX_REGIONS).map { i =>
        (i < pmpNumRegions).B && ((pmpCfg(i) =/= ~pmpCfgShadow(i)) || (pmpAddr(i) =/= ~pmpAddrShadow(i)))
      }.reduce(_ || _) || (pmpMseccfg =/= ~pmpMseccfgShadow))
    csr_shadow_err_o := mstatusErr || mtvecErr || pmpCsrErr || cpuctrlstsPartErr || cpuctrlstsIcScrKeyErr

    mip_o.irq_software := irq_software_i
    mip_o.irq_timer := irq_timer_i
    mip_o.irq_external := irq_external_i
    mip_o.irq_fast := irq_fast_i

    irqs_o.irq_software := irq_software_i && mieSoftware
    irqs_o.irq_timer := irq_timer_i && mieTimer
    irqs_o.irq_external := irq_external_i && mieExternal
    irqs_o.irq_fast := irq_fast_i & mieFast
    irq_pending_o := irqs_o.irq_software || irqs_o.irq_timer || irqs_o.irq_external || irqs_o.irq_fast.orR

    for (i <- 0 until pmpNumRegions) {
      csr_pmp_cfg_o(i).lock := pmpEnable.B && pmpCfg(i)(5)
      csr_pmp_cfg_o(i).mode := Mux(pmpEnable.B, pmpCfg(i)(4, 3), 0.U)
      csr_pmp_cfg_o(i).exec := pmpEnable.B && pmpCfg(i)(2)
      csr_pmp_cfg_o(i).write := pmpEnable.B && pmpCfg(i)(1)
      csr_pmp_cfg_o(i).read := pmpEnable.B && pmpCfg(i)(0)
      csr_pmp_addr_o(i) := Mux(pmpEnable.B, Cat(pmpAddrRdata(i), 0.U(2.W)), 0.U)
    }
    csr_pmp_mseccfg_o.rlb := pmpEnable.B && pmpMseccfg(2)
    csr_pmp_mseccfg_o.mmwp := pmpEnable.B && pmpMseccfg(1)
    csr_pmp_mseccfg_o.mml := pmpEnable.B && pmpMseccfg(0)
  }
}
