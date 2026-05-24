package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable
import scala.util.Random

class IbexCsRegistersSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(
      mhpmCounterNum: Int = 2,
      mhpmCounterWidth: Int = 40,
      dataIndTiming: Boolean = true,
      dummyInstructions: Boolean = true,
      iCache: Boolean = true,
      pmpEnable: Boolean = false,
      pmpGranularity: Int = 0,
      pmpNumRegions: Int = 4,
      pmpRstCfg: Seq[BigInt] = IbexPkg.PmpCfgRst,
      pmpRstAddr: Seq[BigInt] = IbexPkg.PmpAddrRst,
      pmpRstMsecCfg: BigInt = IbexPkg.PmpMseccfgRst,
      dbgTriggerEn: Boolean = false,
      dbgHwBreakNum: Int = 1)
      extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val hart_id_i = Input(UInt(32.W))
      val priv_mode_id_o = Output(UInt(2.W))
      val priv_mode_lsu_o = Output(UInt(2.W))
      val csr_mstatus_tw_o = Output(Bool())
      val csr_mtvec_o = Output(UInt(32.W))
      val csr_mtvec_init_i = Input(Bool())
      val boot_addr_i = Input(UInt(32.W))
      val csr_access_i = Input(Bool())
      val csr_addr_i = Input(UInt(12.W))
      val csr_wdata_i = Input(UInt(32.W))
      val csr_op_i = Input(UInt(2.W))
      val csr_op_en_i = Input(Bool())
      val csr_rdata_o = Output(UInt(32.W))
      val irq_software_i = Input(Bool())
      val irq_timer_i = Input(Bool())
      val irq_external_i = Input(Bool())
      val irq_fast_i = Input(UInt(15.W))
      val nmi_mode_i = Input(Bool())
      val irq_pending_o = Output(Bool())
      val irqs_o = Output(new IbexPkg.Irqs)
      val csr_mstatus_mie_o = Output(Bool())
      val csr_mepc_o = Output(UInt(32.W))
      val csr_mtval_o = Output(UInt(32.W))
      val csr_pmp_cfg_o = Output(Vec(pmpNumRegions, new IbexPkg.PmpCfg))
      val csr_pmp_addr_o = Output(Vec(pmpNumRegions, UInt((IbexPkg.PMP_ADDR_MSB + 1).W)))
      val csr_pmp_mseccfg_o = Output(new IbexPkg.PmpMseccfg)
      val debug_mode_i = Input(Bool())
      val debug_mode_entering_i = Input(Bool())
      val debug_cause_i = Input(UInt(3.W))
      val debug_csr_save_i = Input(Bool())
      val csr_depc_o = Output(UInt(32.W))
      val debug_single_step_o = Output(Bool())
      val debug_ebreakm_o = Output(Bool())
      val debug_ebreaku_o = Output(Bool())
      val trigger_match_o = Output(Bool())
      val pc_if_i = Input(UInt(32.W))
      val pc_id_i = Input(UInt(32.W))
      val pc_wb_i = Input(UInt(32.W))
      val data_ind_timing_o = Output(Bool())
      val dummy_instr_en_o = Output(Bool())
      val dummy_instr_mask_o = Output(UInt(3.W))
      val dummy_instr_seed_en_o = Output(Bool())
      val dummy_instr_seed_o = Output(UInt(32.W))
      val icache_enable_o = Output(Bool())
      val csr_shadow_err_o = Output(Bool())
      val ic_scr_key_valid_i = Input(Bool())
      val csr_save_if_i = Input(Bool())
      val csr_save_id_i = Input(Bool())
      val csr_save_wb_i = Input(Bool())
      val csr_restore_mret_i = Input(Bool())
      val csr_restore_dret_i = Input(Bool())
      val csr_save_cause_i = Input(Bool())
      val csr_mcause_i = Input(UInt(7.W))
      val csr_mtval_i = Input(UInt(32.W))
      val illegal_csr_insn_o = Output(Bool())
      val double_fault_seen_o = Output(Bool())
      val instr_ret_i = Input(Bool())
      val instr_ret_compressed_i = Input(Bool())
      val instr_ret_spec_i = Input(Bool())
      val instr_ret_compressed_spec_i = Input(Bool())
      val iside_wait_i = Input(Bool())
      val jump_i = Input(Bool())
      val branch_i = Input(Bool())
      val branch_taken_i = Input(Bool())
      val mem_load_i = Input(Bool())
      val mem_store_i = Input(Bool())
      val dside_wait_i = Input(Bool())
      val mul_wait_i = Input(Bool())
      val div_wait_i = Input(Bool())
    })

    val dut = Module(new IbexCsRegisters(
      mhpmCounterNum = mhpmCounterNum,
      mhpmCounterWidth = mhpmCounterWidth,
      dataIndTiming = dataIndTiming,
      dummyInstructions = dummyInstructions,
      iCache = iCache,
      pmpEnable = pmpEnable,
      pmpGranularity = pmpGranularity,
      pmpNumRegions = pmpNumRegions,
      pmpRstCfg = pmpRstCfg,
      pmpRstAddr = pmpRstAddr,
      pmpRstMsecCfg = pmpRstMsecCfg,
      dbgTriggerEn = dbgTriggerEn,
      dbgHwBreakNum = dbgHwBreakNum))

    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.hart_id_i := io.hart_id_i
    io.priv_mode_id_o := dut.priv_mode_id_o
    io.priv_mode_lsu_o := dut.priv_mode_lsu_o
    io.csr_mstatus_tw_o := dut.csr_mstatus_tw_o
    io.csr_mtvec_o := dut.csr_mtvec_o
    dut.csr_mtvec_init_i := io.csr_mtvec_init_i
    dut.boot_addr_i := io.boot_addr_i
    dut.csr_access_i := io.csr_access_i
    dut.csr_addr_i := io.csr_addr_i
    dut.csr_wdata_i := io.csr_wdata_i
    dut.csr_op_i := io.csr_op_i
    dut.csr_op_en_i := io.csr_op_en_i
    io.csr_rdata_o := dut.csr_rdata_o
    dut.irq_software_i := io.irq_software_i
    dut.irq_timer_i := io.irq_timer_i
    dut.irq_external_i := io.irq_external_i
    dut.irq_fast_i := io.irq_fast_i
    dut.nmi_mode_i := io.nmi_mode_i
    io.irq_pending_o := dut.irq_pending_o
    io.irqs_o := dut.irqs_o
    io.csr_mstatus_mie_o := dut.csr_mstatus_mie_o
    io.csr_mepc_o := dut.csr_mepc_o
    io.csr_mtval_o := dut.csr_mtval_o
    io.csr_pmp_cfg_o := dut.csr_pmp_cfg_o
    io.csr_pmp_addr_o := dut.csr_pmp_addr_o
    io.csr_pmp_mseccfg_o := dut.csr_pmp_mseccfg_o
    dut.debug_mode_i := io.debug_mode_i
    dut.debug_mode_entering_i := io.debug_mode_entering_i
    dut.debug_cause_i := io.debug_cause_i
    dut.debug_csr_save_i := io.debug_csr_save_i
    io.csr_depc_o := dut.csr_depc_o
    io.debug_single_step_o := dut.debug_single_step_o
    io.debug_ebreakm_o := dut.debug_ebreakm_o
    io.debug_ebreaku_o := dut.debug_ebreaku_o
    io.trigger_match_o := dut.trigger_match_o
    dut.pc_if_i := io.pc_if_i
    dut.pc_id_i := io.pc_id_i
    dut.pc_wb_i := io.pc_wb_i
    io.data_ind_timing_o := dut.data_ind_timing_o
    io.dummy_instr_en_o := dut.dummy_instr_en_o
    io.dummy_instr_mask_o := dut.dummy_instr_mask_o
    io.dummy_instr_seed_en_o := dut.dummy_instr_seed_en_o
    io.dummy_instr_seed_o := dut.dummy_instr_seed_o
    io.icache_enable_o := dut.icache_enable_o
    io.csr_shadow_err_o := dut.csr_shadow_err_o
    dut.ic_scr_key_valid_i := io.ic_scr_key_valid_i
    dut.csr_save_if_i := io.csr_save_if_i
    dut.csr_save_id_i := io.csr_save_id_i
    dut.csr_save_wb_i := io.csr_save_wb_i
    dut.csr_restore_mret_i := io.csr_restore_mret_i
    dut.csr_restore_dret_i := io.csr_restore_dret_i
    dut.csr_save_cause_i := io.csr_save_cause_i
    dut.csr_mcause_i := io.csr_mcause_i
    dut.csr_mtval_i := io.csr_mtval_i
    io.illegal_csr_insn_o := dut.illegal_csr_insn_o
    io.double_fault_seen_o := dut.double_fault_seen_o
    dut.instr_ret_i := io.instr_ret_i
    dut.instr_ret_compressed_i := io.instr_ret_compressed_i
    dut.instr_ret_spec_i := io.instr_ret_spec_i
    dut.instr_ret_compressed_spec_i := io.instr_ret_compressed_spec_i
    dut.iside_wait_i := io.iside_wait_i
    dut.jump_i := io.jump_i
    dut.branch_i := io.branch_i
    dut.branch_taken_i := io.branch_taken_i
    dut.mem_load_i := io.mem_load_i
    dut.mem_store_i := io.mem_store_i
    dut.dside_wait_i := io.dside_wait_i
    dut.mul_wait_i := io.mul_wait_i
    dut.div_wait_i := io.div_wait_i
  }

  private object Op {
    val Read = 0
    val Write = 1
    val Set = 2
    val Clear = 3
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.hart_id_i.poke("h1234".U)
    dut.io.csr_mtvec_init_i.poke(false.B)
    dut.io.boot_addr_i.poke(0.U)
    dut.io.csr_access_i.poke(false.B)
    dut.io.csr_addr_i.poke(0.U)
    dut.io.csr_wdata_i.poke(0.U)
    dut.io.csr_op_i.poke(Op.Read.U)
    dut.io.csr_op_en_i.poke(false.B)
    dut.io.irq_software_i.poke(false.B)
    dut.io.irq_timer_i.poke(false.B)
    dut.io.irq_external_i.poke(false.B)
    dut.io.irq_fast_i.poke(0.U)
    dut.io.nmi_mode_i.poke(false.B)
    dut.io.debug_mode_i.poke(false.B)
    dut.io.debug_mode_entering_i.poke(false.B)
    dut.io.debug_cause_i.poke(0.U)
    dut.io.debug_csr_save_i.poke(false.B)
    dut.io.pc_if_i.poke(0.U)
    dut.io.pc_id_i.poke(0.U)
    dut.io.pc_wb_i.poke(0.U)
    dut.io.ic_scr_key_valid_i.poke(false.B)
    dut.io.csr_save_if_i.poke(false.B)
    dut.io.csr_save_id_i.poke(false.B)
    dut.io.csr_save_wb_i.poke(false.B)
    dut.io.csr_restore_mret_i.poke(false.B)
    dut.io.csr_restore_dret_i.poke(false.B)
    dut.io.csr_save_cause_i.poke(false.B)
    dut.io.csr_mcause_i.poke(0.U)
    dut.io.csr_mtval_i.poke(0.U)
    dut.io.instr_ret_i.poke(false.B)
    dut.io.instr_ret_compressed_i.poke(false.B)
    dut.io.instr_ret_spec_i.poke(false.B)
    dut.io.instr_ret_compressed_spec_i.poke(false.B)
    dut.io.iside_wait_i.poke(false.B)
    dut.io.jump_i.poke(false.B)
    dut.io.branch_i.poke(false.B)
    dut.io.branch_taken_i.poke(false.B)
    dut.io.mem_load_i.poke(false.B)
    dut.io.mem_store_i.poke(false.B)
    dut.io.dside_wait_i.poke(false.B)
    dut.io.mul_wait_i.poke(false.B)
    dut.io.div_wait_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def read(dut: Harness, addr: Int): BigInt = {
    dut.io.csr_access_i.poke(true.B)
    dut.io.csr_addr_i.poke(addr.U)
    dut.io.csr_op_i.poke(Op.Read.U)
    dut.io.csr_op_en_i.poke(false.B)
    dut.io.csr_rdata_o.peek().litValue
  }

  private def write(dut: Harness, addr: Int, data: BigInt, op: Int = Op.Write): Unit = {
    dut.io.csr_access_i.poke(true.B)
    dut.io.csr_addr_i.poke(addr.U)
    dut.io.csr_wdata_i.poke(data.U)
    dut.io.csr_op_i.poke(op.U)
    dut.io.csr_op_en_i.poke(true.B)
    dut.clock.step()
    dut.io.csr_op_en_i.poke(false.B)
  }

  private object CsrDvModel {
    private val mask32 = BigInt("ffffffff", 16)
    private val mseccfgMml = BigInt(0x1)
    private val mseccfgMmwp = BigInt(0x2)
    private val mseccfgRlb = BigInt(0x4)

    val addresses: Seq[Int] =
      (0x3a0 to 0x3a3) ++
        (0x3b0 to 0x3bf) ++
        Seq(0x320) ++
        (0x323 to 0x33f) ++
        Seq(0x747, 0x757, 0xb00, 0xb02) ++
        (0xb03 to 0xb1f) ++
        Seq(0xb80, 0xb82) ++
        (0xb83 to 0xb9f)

    sealed trait RegModel {
      val addr: Int
      protected var value: BigInt = 0

      def reset(): Unit = value = 0
      def read(): BigInt = value & mask32
      def lockMask(model: Model): BigInt = 0

      def write(data: BigInt, model: Model): BigInt = {
        val old = read()
        val lock = lockMask(model)
        value = ((value & lock) | (data & (~lock & mask32))) & mask32
        old
      }

      def set(data: BigInt, model: Model): BigInt = {
        val old = read()
        val lock = lockMask(model)
        value = (value | (data & (~lock & mask32))) & mask32
        old
      }

      def clear(data: BigInt, model: Model): BigInt = {
        val old = read()
        val lock = lockMask(model)
        value = (value & ((~data | lock) & mask32)) & mask32
        old
      }
    }

    final class Base(val addr: Int) extends RegModel

    final class NonImp(val addr: Int) extends RegModel {
      override def read(): BigInt = 0
      override def write(data: BigInt, model: Model): BigInt = 0
      override def set(data: BigInt, model: Model): BigInt = 0
      override def clear(data: BigInt, model: Model): BigInt = 0
    }

    final class Warl(val addr: Int, lockedMask: BigInt, resetValue: BigInt) extends RegModel {
      override def reset(): Unit = value = resetValue & mask32
      override def lockMask(model: Model): BigInt = lockedMask & mask32
    }

    final class Mseccfg(val addr: Int) extends RegModel {
      override def lockMask(model: Model): BigInt = {
        val anyPmpLocked = model.regs.values.exists {
          case reg: PmpCfg => (reg.read() & BigInt("80808080", 16)) != 0
          case _ => false
        }
        var lock = BigInt("fffffff8", 16)
        if (anyPmpLocked && (value & mseccfgRlb) == 0) lock |= mseccfgRlb
        if ((value & mseccfgMmwp) != 0) lock |= mseccfgMmwp
        if ((value & mseccfgMml) != 0) lock |= mseccfgMml
        lock & mask32
      }
    }

    final class PmpCfg(val addr: Int) extends RegModel {
      private def regionForShift(shift: Int): Int = (addr - 0x3a0) * 4 + (shift / 8)

      override def lockMask(model: Model): BigInt = {
        val rlb = (model.regs(0x747).read() & mseccfgRlb) != 0
        Seq(0, 8, 16, 24).foldLeft(BigInt(0)) { case (lock, shift) =>
          val byte = (value >> shift) & 0xff
          val region = regionForShift(shift)
          if (!rlb && region < model.pmpNumRegions && (byte & 0x80) != 0) lock | (BigInt(0xff) << shift) else lock
        } & mask32
      }

      override def write(data: BigInt, model: Model): BigInt = {
        val old = this.read()
        value = writePacked(data, model)
        old
      }

      override def set(data: BigInt, model: Model): BigInt = {
        val old = this.read()
        value = writePacked(value | data, model)
        old
      }

      override def clear(data: BigInt, model: Model): BigInt = {
        val old = this.read()
        value = writePacked(value & (~data & mask32), model)
        old
      }

      private def encodeByte(byte: BigInt, model: Model): BigInt = {
        val lock = byte & 0x80
        val modeBits = (byte >> 3) & 0x3
        val mode =
          if (modeBits == 0x2 && model.pmpGranularity > 0) BigInt(0)
          else modeBits
        val exec = (byte >> 2) & 0x1
        val read = byte & 0x1
        val write =
          if ((model.regs(0x747).read() & mseccfgMml) != 0) (byte >> 1) & 0x1
          else ((byte >> 1) & 0x1) & read
        lock | (mode << 3) | (exec << 2) | (write << 1) | read
      }

      private def isMmlMExecByte(byte: BigInt): Boolean = {
        val lock = (byte & 0x80) != 0
        val read = byte & 0x1
        val write = (byte >> 1) & 0x1
        val exec = (byte >> 2) & 0x1
        val rwx = (read << 2) | (write << 1) | exec
        lock && (rwx == 0x1 || rwx == 0x2 || rwx == 0x3 || rwx == 0x5)
      }

      private def writePacked(raw: BigInt, model: Model): BigInt = {
        var next = value
        val mseccfg = model.regs(0x747).read()
        val mml = (mseccfg & mseccfgMml) != 0
        val rlb = (mseccfg & mseccfgRlb) != 0
        for (shift <- Seq(0, 8, 16, 24)) {
          val region = regionForShift(shift)
          if (region < model.pmpNumRegions) {
            val current = (value >> shift) & 0xff
            val pmpLocked = (current & 0x80) != 0 && !rlb
            val byte = encodeByte((raw >> shift) & 0xff, model)
            val suppress = mml && !rlb && isMmlMExecByte(byte)
            if (!pmpLocked && !suppress) {
              next = (next & ~(BigInt(0xff) << shift)) | (byte << shift)
            }
          }
        }
        next & mask32
      }
    }

    final class PmpAddr(val addr: Int) extends RegModel {
      override def lockMask(model: Model): BigInt = {
        val region = addr & 0xf
        val cfg = model.regs(0x3a0 + region / 4).read() >> ((region & 0x3) * 8)
        val nextCfg = model.regs.get(0x3a0 + (region + 1) / 4).map(_.read()).getOrElse(BigInt(0)) >>
          (((region + 1) & 0x3) * 8)
        if ((cfg & 0x80) != 0 || (nextCfg & 0x18) == 0x8) mask32 else 0
      }
    }

    final class Model(
        pmpEnable: Boolean,
        val pmpGranularity: Int,
        val pmpNumRegions: Int,
        mhpmCounterNum: Int,
        mhpmCounterWidth: Int) {
      val regs: mutable.LinkedHashMap[Int, RegModel] = mutable.LinkedHashMap.empty

      regs += 0x747 -> new Mseccfg(0x747)
      regs += 0x757 -> new NonImp(0x757)
      for (i <- 0 until 4) {
        val addr = 0x3a0 + i
        regs += addr -> (if (pmpEnable && i < pmpNumRegions / 4) new PmpCfg(addr) else new NonImp(addr))
      }
      for (i <- 0 until 16) {
        val addr = 0x3b0 + i
        regs += addr -> (if (pmpEnable && i < pmpNumRegions) new PmpAddr(addr) else new NonImp(addr))
      }

      val mcountMask = (((~((BigInt(1) << mhpmCounterNum) - 1)) << 3) | BigInt(0x2)) & mask32
      regs += 0x320 -> new Warl(0x320, mcountMask, 0)
      for (i <- 3 until 32) {
        val addr = 0x320 + i
        regs += addr -> (if (i < mhpmCounterNum + 3) new Warl(addr, mask32, BigInt(1) << (i - 3)) else new NonImp(addr))
      }

      regs += 0xb00 -> new Base(0xb00)
      regs += 0xb02 -> new Base(0xb02)
      val counterMask =
        if (mhpmCounterWidth >= 64) BigInt(0) else (~((BigInt(1) << mhpmCounterWidth) - 1)) & ((BigInt(1) << 64) - 1)
      val counterMaskLow = counterMask & mask32
      val counterMaskHigh = (counterMask >> 32) & mask32
      for (i <- 3 until 32) {
        val addr = 0xb00 + i
        regs += addr -> (if (i < mhpmCounterNum + 3) new Warl(addr, counterMaskLow, 0) else new NonImp(addr))
      }
      regs += 0xb80 -> new Base(0xb80)
      regs += 0xb82 -> new Base(0xb82)
      for (i <- 3 until 32) {
        val addr = 0xb80 + i
        regs += addr -> (if (i < mhpmCounterNum + 3) new Warl(addr, counterMaskHigh, 0) else new NonImp(addr))
      }

      def reset(): Unit = regs.values.foreach(_.reset())

      def transact(op: Int, addr: Int, data: BigInt): Option[BigInt] = regs.get(addr).map { reg =>
        op match {
          case Op.Read => reg.read()
          case Op.Write => reg.write(data, this)
          case Op.Set => reg.set(data, this)
          case Op.Clear => reg.clear(data, this)
        }
      }
    }
  }

  "IbexCsRegisters" - {
    "exposes reset values and writable machine CSRs" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.priv_mode_id_o.expect(IbexPkg.PrivLvl.M)
        read(dut, IbexPkg.CsrNum.MHARTID) mustBe 0x1234
        read(dut, IbexPkg.CsrNum.MTVEC) mustBe 1
        read(dut, IbexPkg.CsrNum.MSTATUS) mustBe (1 << IbexPkg.CSR_MSTATUS_MPIE_BIT)

        write(dut, IbexPkg.CsrNum.MSCRATCH, BigInt("deadbeef", 16))
        read(dut, IbexPkg.CsrNum.MSCRATCH) mustBe BigInt("deadbeef", 16)

        write(dut, IbexPkg.CsrNum.MEPC, 0x1013)
        read(dut, IbexPkg.CsrNum.MEPC) mustBe 0x1012

        write(dut, IbexPkg.CsrNum.MTVEC, BigInt("80000123", 16))
        read(dut, IbexPkg.CsrNum.MTVEC) mustBe BigInt("80000101", 16)
      }
    }

    "implements CSR set and clear operations against current read data" in {
      simulate(new Harness) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.MIE, 1 << IbexPkg.CSR_MTIX_BIT)
        read(dut, IbexPkg.CsrNum.MIE) mustBe 0x80

        write(dut, IbexPkg.CsrNum.MIE, 1 << IbexPkg.CSR_MSIX_BIT, Op.Set)
        read(dut, IbexPkg.CsrNum.MIE) mustBe 0x88

        write(dut, IbexPkg.CsrNum.MIE, 1 << IbexPkg.CSR_MTIX_BIT, Op.Clear)
        read(dut, IbexPkg.CsrNum.MIE) mustBe 0x08
      }
    }

    "flags illegal CSR accesses and protects debug CSRs outside debug mode" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.csr_access_i.poke(true.B)
        dut.io.csr_addr_i.poke(IbexPkg.CsrNum.DPC.U)
        dut.io.csr_op_i.poke(Op.Read.U)
        dut.io.illegal_csr_insn_o.expect(true.B)

        dut.io.debug_mode_i.poke(true.B)
        dut.io.illegal_csr_insn_o.expect(false.B)

        dut.io.csr_addr_i.poke(IbexPkg.CsrNum.PMPADDR0.U)
        dut.io.illegal_csr_insn_o.expect(true.B)
      }
    }

    "implements PMP CSRs when PMP is enabled" in {
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 4)) { dut =>
        reset(dut)
        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe 0
        dut.io.illegal_csr_insn_o.expect(false.B)

        write(dut, IbexPkg.CsrNum.PMPADDR0, BigInt("12345678", 16))
        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe BigInt("12345678", 16)
        dut.io.csr_pmp_addr_o(0).expect(BigInt("48d159e0", 16).U)

        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x9f)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x9f
        dut.io.csr_pmp_cfg_o(0).lock.expect(true.B)
        dut.io.csr_pmp_cfg_o(0).mode.expect(IbexPkg.PmpCfgMode.Napot)
        dut.io.csr_pmp_cfg_o(0).exec.expect(true.B)
        dut.io.csr_pmp_cfg_o(0).write.expect(true.B)
        dut.io.csr_pmp_cfg_o(0).read.expect(true.B)

        write(dut, IbexPkg.CsrNum.PMPCFG0, 0)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x9f
      }
    }

    "programs an unlocked full-address NAPOT PMP region" in {
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 4)) { dut =>
        reset(dut)

        write(dut, IbexPkg.CsrNum.PMPADDR0, BigInt("7fffffff", 16))
        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x1f)

        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe BigInt("7fffffff", 16)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x1f
        dut.io.csr_pmp_addr_o(0).expect(BigInt("1fffffffc", 16).U)
        dut.io.csr_pmp_cfg_o(0).lock.expect(false.B)
        dut.io.csr_pmp_cfg_o(0).mode.expect(IbexPkg.PmpCfgMode.Napot)
        dut.io.csr_pmp_cfg_o(0).exec.expect(true.B)
        dut.io.csr_pmp_cfg_o(0).write.expect(true.B)
        dut.io.csr_pmp_cfg_o(0).read.expect(true.B)
      }
    }

    "applies PMP granularity address readback rules and outputs expanded PMP addresses" in {
      simulate(new Harness(pmpEnable = true, pmpGranularity = 2, pmpNumRegions = 1)) { dut =>
        reset(dut)

        write(dut, IbexPkg.CsrNum.PMPADDR0, BigInt("1234567b", 16))
        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe BigInt("12345678", 16)
        dut.io.csr_pmp_addr_o(0).expect(BigInt("48d159e0", 16).U)

        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x18)
        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe BigInt("1234567b", 16)
        dut.io.csr_pmp_addr_o(0).expect(BigInt("48d159ec", 16).U)
      }
    }

    "applies custom PMP reset values" in {
      val rstCfg = Seq.tabulate(IbexPkg.PMP_MAX_REGIONS) { i =>
        if (i == 0) BigInt(0x9f) else if (i == 1) BigInt(0x1f) else BigInt(0)
      }
      val rstAddr = Seq.tabulate(IbexPkg.PMP_MAX_REGIONS) { i =>
        if (i == 0) BigInt("48d159e0", 16) else if (i == 1) BigInt("00001000", 16) else BigInt(0)
      }
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 2, pmpRstCfg = rstCfg, pmpRstAddr = rstAddr, pmpRstMsecCfg = 0x5)) { dut =>
        reset(dut)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x1f9f
        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe BigInt("12345678", 16)
        read(dut, IbexPkg.CsrNum.pmpAddr(1)) mustBe BigInt("00000400", 16)
        read(dut, IbexPkg.CsrNum.MSECCFG) mustBe 0x5
        dut.io.csr_pmp_cfg_o(0).lock.expect(true.B)
        dut.io.csr_pmp_cfg_o(1).lock.expect(false.B)
        dut.io.csr_pmp_addr_o(0).expect(BigInt("48d159e0", 16).U)
        dut.io.csr_pmp_addr_o(1).expect(BigInt("00001000", 16).U)
        dut.io.csr_pmp_mseccfg_o.rlb.expect(true.B)
        dut.io.csr_pmp_mseccfg_o.mmwp.expect(false.B)
        dut.io.csr_pmp_mseccfg_o.mml.expect(true.B)
      }
    }

    "honors PMP mseccfg sticky bits and RLB lock bypass" in {
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 2)) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.MSECCFG, 0x7)
        read(dut, IbexPkg.CsrNum.MSECCFG) mustBe 0x7
        dut.io.csr_pmp_mseccfg_o.mml.expect(true.B)
        dut.io.csr_pmp_mseccfg_o.mmwp.expect(true.B)
        dut.io.csr_pmp_mseccfg_o.rlb.expect(true.B)

        write(dut, IbexPkg.CsrNum.MSECCFG, 0)
        read(dut, IbexPkg.CsrNum.MSECCFG) mustBe 0x3

        write(dut, IbexPkg.CsrNum.MSECCFG, 0x4)
        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x9f)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x9f
        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x1f)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x1f
      }
    }

    "suppresses MML M-mode execute-region PMP config writes without RLB" in {
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 1)) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.MSECCFG, 0x1)
        read(dut, IbexPkg.CsrNum.MSECCFG) mustBe 0x1

        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x89)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x89

        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x8c)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x89

        write(dut, IbexPkg.CsrNum.MSECCFG, 0x4)
        read(dut, IbexPkg.CsrNum.MSECCFG) mustBe 0x1
      }

      simulate(new Harness(pmpEnable = true, pmpNumRegions = 1)) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.MSECCFG, 0x5)
        read(dut, IbexPkg.CsrNum.MSECCFG) mustBe 0x5
        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x8c)
        read(dut, IbexPkg.CsrNum.PMPCFG0) mustBe 0x8c
      }
    }

    "suppresses writes to TOR lower address when the next PMP region is locked TOR" in {
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 2)) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.PMPADDR0, 0x1111)
        write(dut, IbexPkg.CsrNum.PMPCFG0, 0x8900)
        write(dut, IbexPkg.CsrNum.PMPADDR0, 0x2222)
        read(dut, IbexPkg.CsrNum.PMPADDR0) mustBe 0x1111
      }
    }

    "implements debug trigger CSRs when enabled" in {
      simulate(new Harness(dbgTriggerEn = true, dbgHwBreakNum = 2)) { dut =>
        reset(dut)
        dut.io.debug_mode_i.poke(true.B)

        write(dut, IbexPkg.CsrNum.TSELECT, 3)
        read(dut, IbexPkg.CsrNum.TSELECT) mustBe 1

        write(dut, IbexPkg.CsrNum.TDATA2, BigInt("80000100", 16))
        write(dut, IbexPkg.CsrNum.TDATA1, 0x4)
        read(dut, IbexPkg.CsrNum.TDATA2) mustBe BigInt("80000100", 16)
        (read(dut, IbexPkg.CsrNum.TDATA1) & 0x4) mustBe 0x4

        dut.io.pc_if_i.poke("h80000100".U)
        dut.io.trigger_match_o.expect(true.B)
        dut.io.pc_if_i.poke("h80000104".U)
        dut.io.trigger_match_o.expect(false.B)

        write(dut, IbexPkg.CsrNum.TSELECT, 0)
        write(dut, IbexPkg.CsrNum.TDATA2, BigInt("80000200", 16))
        write(dut, IbexPkg.CsrNum.TDATA1, 0x4)
        dut.io.pc_if_i.poke("h80000200".U)
        dut.io.trigger_match_o.expect(true.B)

        dut.io.debug_mode_i.poke(false.B)
        write(dut, IbexPkg.CsrNum.TDATA2, BigInt("80000300", 16))
        dut.io.pc_if_i.poke("h80000300".U)
        dut.io.trigger_match_o.expect(false.B)
        dut.io.pc_if_i.poke("h80000200".U)
        dut.io.trigger_match_o.expect(true.B)
      }
    }

    "flags trigger CSRs as illegal when debug triggers are disabled" in {
      simulate(new Harness(dbgTriggerEn = false)) { dut =>
        reset(dut)
        dut.io.debug_mode_i.poke(true.B)
        dut.io.csr_access_i.poke(true.B)
        dut.io.csr_addr_i.poke(IbexPkg.CsrNum.TDATA1.U)
        dut.io.csr_op_i.poke(Op.Read.U)
        dut.io.illegal_csr_insn_o.expect(true.B)
        dut.io.trigger_match_o.expect(false.B)
      }
    }

    "implements DCSR write filtering and debug save updates" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.debug_mode_i.poke(true.B)
        read(dut, IbexPkg.CsrNum.DCSR) mustBe BigInt("40000003", 16)

        write(dut, IbexPkg.CsrNum.DCSR, BigInt("ffffffff", 16))
        val dcsrAllOnes = read(dut, IbexPkg.CsrNum.DCSR)
        (dcsrAllOnes >> 28) mustBe 0x4
        ((dcsrAllOnes >> 16) & 0xfff) mustBe 0
        ((dcsrAllOnes >> 8) & 0x7) mustBe 0
        ((dcsrAllOnes >> 5) & 0x1) mustBe 0
        ((dcsrAllOnes >> 4) & 0x1) mustBe 0
        ((dcsrAllOnes >> 3) & 0x1) mustBe 0
        (dcsrAllOnes & 0x3) mustBe 0x3
        dut.io.debug_single_step_o.expect(true.B)
        dut.io.debug_ebreakm_o.expect(true.B)
        dut.io.debug_ebreaku_o.expect(true.B)

        write(dut, IbexPkg.CsrNum.DCSR, 0x2)
        (read(dut, IbexPkg.CsrNum.DCSR) & 0x3) mustBe 0

        dut.io.debug_mode_i.poke(false.B)
        dut.io.pc_wb_i.poke("h00001004".U)
        dut.io.debug_csr_save_i.poke(true.B)
        dut.io.csr_save_wb_i.poke(true.B)
        dut.io.csr_save_cause_i.poke(true.B)
        dut.io.debug_cause_i.poke(IbexPkg.DbgCause.Trigger)
        dut.clock.step()
        dut.io.debug_csr_save_i.poke(false.B)
        dut.io.csr_save_wb_i.poke(false.B)
        dut.io.csr_save_cause_i.poke(false.B)
        dut.io.debug_mode_i.poke(true.B)
        val dcsrAfterSave = read(dut, IbexPkg.CsrNum.DCSR)
        ((dcsrAfterSave >> 6) & 0x7) mustBe 0x2
        (dcsrAfterSave & 0x3) mustBe 0x3
        dut.io.csr_depc_o.expect("h00001004".U)
      }
    }

    "implements CPU control status feature gates and debug icache masking" in {
      simulate(new Harness(dataIndTiming = true, dummyInstructions = true, iCache = true)) { dut =>
        reset(dut)
        read(dut, IbexPkg.CsrNum.CPUCTRLSTS) mustBe 1
        dut.io.icache_enable_o.expect(true.B)

        write(dut, IbexPkg.CsrNum.CPUCTRLSTS, 0x3f)
        dut.io.icache_enable_o.expect(true.B)
        dut.io.data_ind_timing_o.expect(true.B)
        dut.io.dummy_instr_en_o.expect(true.B)
        dut.io.dummy_instr_mask_o.expect(7.U)
        read(dut, IbexPkg.CsrNum.CPUCTRLSTS) & 0x3f mustBe 0x3f

        dut.io.debug_mode_entering_i.poke(true.B)
        dut.io.icache_enable_o.expect(false.B)
        dut.io.debug_mode_entering_i.poke(false.B)
        dut.io.debug_mode_i.poke(true.B)
        dut.io.icache_enable_o.expect(false.B)
        dut.io.debug_mode_i.poke(false.B)
        dut.io.icache_enable_o.expect(true.B)

        dut.io.ic_scr_key_valid_i.poke(true.B)
        dut.clock.step()
        dut.io.ic_scr_key_valid_i.poke(false.B)
        read(dut, IbexPkg.CsrNum.CPUCTRLSTS) & 0x100 mustBe 0x100
        dut.clock.step()
        read(dut, IbexPkg.CsrNum.CPUCTRLSTS) & 0x100 mustBe 0

        write(dut, IbexPkg.CsrNum.SECURESEED, BigInt("a5a55a5a", 16))
        dut.io.dummy_instr_seed_en_o.expect(false.B)
        dut.io.csr_op_en_i.poke(true.B)
        dut.io.dummy_instr_seed_en_o.expect(true.B)
        dut.io.dummy_instr_seed_o.expect("ha5a55a5a".U)
        dut.io.csr_op_en_i.poke(false.B)
      }
    }

    "initializes mtvec from the boot address when requested" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.boot_addr_i.poke("h80001000".U)
        dut.io.csr_mtvec_init_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_mtvec_init_i.poke(false.B)
        read(dut, IbexPkg.CsrNum.MTVEC) mustBe BigInt("80001001", 16)
      }
    }

    "ties off CPU control fields for disabled optional features" in {
      simulate(new Harness(dataIndTiming = false, dummyInstructions = false, iCache = false)) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.CPUCTRLSTS, 0x3f)
        dut.io.icache_enable_o.expect(false.B)
        dut.io.data_ind_timing_o.expect(false.B)
        dut.io.dummy_instr_en_o.expect(false.B)
        dut.io.dummy_instr_mask_o.expect(0.U)
        read(dut, IbexPkg.CsrNum.CPUCTRLSTS) & 0x13f mustBe 0

        dut.io.ic_scr_key_valid_i.poke(true.B)
        dut.clock.step()
        read(dut, IbexPkg.CsrNum.CPUCTRLSTS) & 0x100 mustBe 0

        write(dut, IbexPkg.CsrNum.SECURESEED, BigInt("a5a55a5a", 16))
        dut.io.csr_op_en_i.poke(true.B)
        dut.io.dummy_instr_seed_en_o.expect(false.B)
        dut.io.csr_op_en_i.poke(false.B)
      }
    }

    "saves exception state and restores privilege with mret" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.pc_id_i.poke("h2004".U)
        dut.io.csr_mtval_i.poke("hcafebabe".U)
        dut.io.csr_mcause_i.poke(2.U(7.W))
        dut.io.csr_save_id_i.poke(true.B)
        dut.io.csr_save_cause_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_save_id_i.poke(false.B)
        dut.io.csr_save_cause_i.poke(false.B)

        dut.io.csr_mepc_o.expect("h2004".U)
        dut.io.csr_mtval_o.expect("hcafebabe".U)
        dut.io.csr_mstatus_mie_o.expect(false.B)
        read(dut, IbexPkg.CsrNum.MCAUSE) mustBe 2

        dut.io.csr_restore_mret_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_restore_mret_i.poke(false.B)
        dut.io.priv_mode_id_o.expect(IbexPkg.PrivLvl.M)
      }
    }

    "preserves or clears MPRV on mret according to the current MPP" in {
      simulate(new Harness) { dut =>
        reset(dut)

        val mprvBit = 1 << IbexPkg.CSR_MSTATUS_MPRV_BIT
        val mppM = 3 << IbexPkg.CSR_MSTATUS_MPP_BIT_LOW
        val mppU = 0

        write(dut, IbexPkg.CsrNum.MSTATUS, mprvBit | mppM)
        dut.io.csr_restore_mret_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_restore_mret_i.poke(false.B)
        ((read(dut, IbexPkg.CsrNum.MSTATUS) >> IbexPkg.CSR_MSTATUS_MPRV_BIT) & 1) mustBe 1

        write(dut, IbexPkg.CsrNum.MSTATUS, mprvBit | mppU)
        dut.io.csr_restore_mret_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_restore_mret_i.poke(false.B)
        ((read(dut, IbexPkg.CsrNum.MSTATUS) >> IbexPkg.CSR_MSTATUS_MPRV_BIT) & 1) mustBe 0
      }
    }

    "restores stacked exception state on mret while in NMI mode" in {
      simulate(new Harness) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.MEPC, 0x1000)
        write(dut, IbexPkg.CsrNum.MCAUSE, 3)

        dut.io.pc_id_i.poke("h2004".U)
        dut.io.csr_mcause_i.poke(2.U(7.W))
        dut.io.csr_save_id_i.poke(true.B)
        dut.io.csr_save_cause_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_save_id_i.poke(false.B)
        dut.io.csr_save_cause_i.poke(false.B)
        read(dut, IbexPkg.CsrNum.MEPC) mustBe 0x2004
        read(dut, IbexPkg.CsrNum.MCAUSE) mustBe 2

        dut.io.nmi_mode_i.poke(true.B)
        dut.io.csr_restore_mret_i.poke(true.B)
        dut.clock.step()
        dut.io.csr_restore_mret_i.poke(false.B)
        dut.io.nmi_mode_i.poke(false.B)
        read(dut, IbexPkg.CsrNum.MEPC) mustBe 0x1000
        read(dut, IbexPkg.CsrNum.MCAUSE) mustBe 3
      }
    }

    "qualifies pending interrupts with mie" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.irq_timer_i.poke(true.B)
        dut.io.irq_pending_o.expect(false.B)

        write(dut, IbexPkg.CsrNum.MIE, 1 << IbexPkg.CSR_MTIX_BIT)
        dut.io.irq_pending_o.expect(true.B)
        dut.io.irqs_o.irq_timer.expect(true.B)
      }
    }

    "supports cycle/minstret writes and increments" in {
      simulate(new Harness) { dut =>
        reset(dut)
        write(dut, IbexPkg.CsrNum.MCYCLE, 10)
        read(dut, IbexPkg.CsrNum.MCYCLE) mustBe 10
        dut.clock.step()
        read(dut, IbexPkg.CsrNum.MCYCLE) mustBe 11

        write(dut, IbexPkg.CsrNum.MCOUNTINHIBIT, 1)
        val held = read(dut, IbexPkg.CsrNum.MCYCLE)
        dut.clock.step(3)
        read(dut, IbexPkg.CsrNum.MCYCLE) mustBe held

        write(dut, IbexPkg.CsrNum.MCOUNTINHIBIT, 0)
        dut.io.instr_ret_i.poke(true.B)
        dut.clock.step()
        dut.io.instr_ret_i.poke(false.B)
        read(dut, IbexPkg.CsrNum.MINSTRET) mustBe 1
      }
    }

    "keeps mcycle and minstret 64-bit even when HPM counters are narrow" in {
      simulate(new Harness(mhpmCounterWidth = 32)) { dut =>
        reset(dut)

        write(dut, IbexPkg.CsrNum.MCYCLEH, BigInt("89abcdef", 16))
        write(dut, IbexPkg.CsrNum.MCYCLE, BigInt("01234567", 16))
        read(dut, IbexPkg.CsrNum.MCYCLEH) mustBe BigInt("89abcdef", 16)

        write(dut, IbexPkg.CsrNum.MINSTRETH, BigInt("76543210", 16))
        write(dut, IbexPkg.CsrNum.MINSTRET, BigInt("fedcba98", 16))
        read(dut, IbexPkg.CsrNum.MINSTRETH) mustBe BigInt("76543210", 16)

        write(dut, IbexPkg.CsrNum.MHPMCOUNTER3H, BigInt("ffffffff", 16))
        read(dut, IbexPkg.CsrNum.MHPMCOUNTER3H) mustBe 0
      }
    }

    "exposes read-only zicntr counter aliases" in {
      simulate(new Harness) { dut =>
        reset(dut)

        read(dut, IbexPkg.CsrNum.CYCLE) mustBe read(dut, IbexPkg.CsrNum.MCYCLE)
        dut.io.illegal_csr_insn_o.expect(false.B)
        read(dut, IbexPkg.CsrNum.CYCLEH) mustBe 0
        dut.io.illegal_csr_insn_o.expect(false.B)
        read(dut, IbexPkg.CsrNum.INSTRET) mustBe read(dut, IbexPkg.CsrNum.MINSTRET)
        dut.io.illegal_csr_insn_o.expect(false.B)
        read(dut, IbexPkg.CsrNum.INSTRETH) mustBe 0
        dut.io.illegal_csr_insn_o.expect(false.B)

        dut.io.csr_access_i.poke(true.B)
        dut.io.csr_addr_i.poke(IbexPkg.CsrNum.CYCLE.U)
        dut.io.csr_op_i.poke(Op.Write.U)
        dut.io.illegal_csr_insn_o.expect(true.B)
      }
    }

    "uses hardwired performance counter events and masks mcountinhibit bits" in {
      simulate(new Harness(mhpmCounterNum = 10)) { dut =>
        reset(dut)

        read(dut, 0x323) mustBe 0x001
        read(dut, 0x324) mustBe 0x002
        read(dut, 0x32c) mustBe 0x200
        write(dut, 0x323, BigInt("ffffffff", 16))
        read(dut, 0x323) mustBe 0x001

        write(dut, IbexPkg.CsrNum.MCOUNTINHIBIT, BigInt("ffffffff", 16))
        read(dut, IbexPkg.CsrNum.MCOUNTINHIBIT) mustBe 0x1ffd

        write(dut, IbexPkg.CsrNum.MCOUNTINHIBIT, 0)
        dut.io.dside_wait_i.poke(true.B)
        dut.io.iside_wait_i.poke(true.B)
        dut.io.mem_load_i.poke(true.B)
        dut.io.mem_store_i.poke(true.B)
        dut.io.jump_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.branch_taken_i.poke(true.B)
        dut.io.instr_ret_compressed_i.poke(true.B)
        dut.io.mul_wait_i.poke(true.B)
        dut.io.div_wait_i.poke(true.B)
        dut.clock.step()
        dut.io.dside_wait_i.poke(false.B)
        dut.io.iside_wait_i.poke(false.B)
        dut.io.mem_load_i.poke(false.B)
        dut.io.mem_store_i.poke(false.B)
        dut.io.jump_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.io.branch_taken_i.poke(false.B)
        dut.io.instr_ret_compressed_i.poke(false.B)
        dut.io.mul_wait_i.poke(false.B)
        dut.io.div_wait_i.poke(false.B)

        for (i <- 0 until 10) {
          read(dut, IbexPkg.CsrNum.MHPMCOUNTER3 + i) mustBe 1
        }

        write(dut, IbexPkg.CsrNum.MCOUNTINHIBIT, 1 << 3)
        dut.io.dside_wait_i.poke(true.B)
        dut.clock.step()
        dut.io.dside_wait_i.poke(false.B)
        read(dut, IbexPkg.CsrNum.MHPMCOUNTER3) mustBe 1
      }
    }

    "reads unimplemented performance counters and events as zero" in {
      simulate(new Harness(mhpmCounterNum = 2)) { dut =>
        reset(dut)
        read(dut, 0x323) mustBe 0x1
        read(dut, 0x324) mustBe 0x2
        read(dut, 0x325) mustBe 0
        read(dut, IbexPkg.CsrNum.MHPMCOUNTER3) mustBe 0
        read(dut, IbexPkg.CsrNum.MHPMCOUNTER3 + 2) mustBe 0
        write(dut, IbexPkg.CsrNum.MHPMCOUNTER3 + 2, 0x1234)
        read(dut, IbexPkg.CsrNum.MHPMCOUNTER3 + 2) mustBe 0
      }
    }

    "matches the translated CSR DV reference model on random listed transactions" in {
      simulate(new Harness(pmpEnable = true, pmpNumRegions = 16, mhpmCounterNum = 10, mhpmCounterWidth = 32)) { dut =>
        reset(dut)

        val model = new CsrDvModel.Model(
          pmpEnable = true,
          pmpGranularity = 0,
          pmpNumRegions = 16,
          mhpmCounterNum = 10,
          mhpmCounterWidth = 32)
        model.reset()

        val random = new Random(1L)
        val selfUpdatingCounters = Set(IbexPkg.CsrNum.MCYCLE, IbexPkg.CsrNum.MCYCLEH)
        val history = mutable.Queue.empty[String]

        for (idx <- 0 until 1000) {
          val addr = CsrDvModel.addresses(random.nextInt(CsrDvModel.addresses.length))
          val op = random.nextInt(4)
          val data = BigInt(32, random)

          dut.io.csr_access_i.poke(true.B)
          dut.io.csr_addr_i.poke(addr.U)
          dut.io.csr_wdata_i.poke(data.U)
          dut.io.csr_op_i.poke(op.U)
          dut.io.csr_op_en_i.poke(true.B)

          val expected = model.transact(op, addr, data)
          expected.isDefined mustBe true
          val actual = dut.io.csr_rdata_o.peek().litValue
          history.enqueue(f"$idx%04d addr=0x$addr%03x op=$op data=0x$data%08x actual=0x$actual%08x expected=0x${expected.get}%08x")
          while (history.size > 48) history.dequeue()
          if (!selfUpdatingCounters.contains(addr)) {
            withClue(f"transaction $idx addr=0x$addr%03x op=$op data=0x$data%08x\n${history.mkString("\n")}\n") {
              actual mustBe expected.get
            }
          }

          dut.clock.step()
          dut.io.csr_op_en_i.poke(false.B)
          dut.io.csr_access_i.poke(false.B)
          dut.clock.step(random.nextInt(3))
        }
      }
    }
  }
}
