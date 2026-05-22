// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

object IbexPkg {
  final class CrashDump extends Bundle {
    val current_pc = UInt(32.W)
    val next_pc = UInt(32.W)
    val last_data_addr = UInt(32.W)
    val exception_pc = UInt(32.W)
    val exception_addr = UInt(32.W)
  }

  final class Core2Rf extends Bundle {
    val dummy_instr_id = Bool()
    val raddr_a = UInt(5.W)
    val waddr_a = UInt(5.W)
    val we_a = Bool()
    val raddr_b = UInt(5.W)
  }

  object RegFile extends ChiselEnum {
    val FF, FPGA, Latch = Value
  }

  object RV32M extends ChiselEnum {
    val None_, Slow, Fast, SingleCycle = Value
  }

  object RV32B extends ChiselEnum {
    val None_, Balanced, OTEarlGrey, Full = Value
  }

  object RV32ZC extends ChiselEnum {
    val Zca, ZcaZcb, ZcaZcmp, ZcaZcbZcmp = Value
  }

  object Opcode {
    val LOAD = "h03".U(7.W)
    val MISC_MEM = "h0f".U(7.W)
    val OP_IMM = "h13".U(7.W)
    val AUIPC = "h17".U(7.W)
    val STORE = "h23".U(7.W)
    val OP = "h33".U(7.W)
    val LUI = "h37".U(7.W)
    val BRANCH = "h63".U(7.W)
    val JALR = "h67".U(7.W)
    val JAL = "h6f".U(7.W)
    val SYSTEM = "h73".U(7.W)

    val encoding: Map[String, Int] = Map(
      "OPCODE_LOAD" -> 0x03,
      "OPCODE_MISC_MEM" -> 0x0f,
      "OPCODE_OP_IMM" -> 0x13,
      "OPCODE_AUIPC" -> 0x17,
      "OPCODE_STORE" -> 0x23,
      "OPCODE_OP" -> 0x33,
      "OPCODE_LUI" -> 0x37,
      "OPCODE_BRANCH" -> 0x63,
      "OPCODE_JALR" -> 0x67,
      "OPCODE_JAL" -> 0x6f,
      "OPCODE_SYSTEM" -> 0x73
    )
  }

  object AluOp extends ChiselEnum {
    val ADD, SUB = Value
    val XOR, OR, AND = Value
    val XNOR, ORN, ANDN = Value
    val SRA, SRL, SLL = Value
    val SRO, SLO, ROR, ROL, GREV, GORC, SHFL, UNSHFL, XPERM_N, XPERM_B, XPERM_H = Value
    val SH1ADD, SH2ADD, SH3ADD = Value
    val LT, LTU, GE, GEU, EQ, NE = Value
    val MIN, MINU, MAX, MAXU = Value
    val PACK, PACKU, PACKH = Value
    val SEXTB, SEXTH = Value
    val CLZ, CTZ, CPOP = Value
    val SLT, SLTU = Value
    val CMOV, CMIX, FSL, FSR = Value
    val BSET, BCLR, BINV, BEXT = Value
    val BCOMPRESS, BDECOMPRESS = Value
    val BFP = Value
    val CLMUL, CLMULR, CLMULH = Value
    val CRC32_B, CRC32C_B, CRC32_H, CRC32C_H, CRC32_W, CRC32C_W = Value

    val encoding: Map[String, Int] = Seq(
      "ALU_ADD", "ALU_SUB", "ALU_XOR", "ALU_OR", "ALU_AND", "ALU_XNOR", "ALU_ORN", "ALU_ANDN",
      "ALU_SRA", "ALU_SRL", "ALU_SLL", "ALU_SRO", "ALU_SLO", "ALU_ROR", "ALU_ROL", "ALU_GREV",
      "ALU_GORC", "ALU_SHFL", "ALU_UNSHFL", "ALU_XPERM_N", "ALU_XPERM_B", "ALU_XPERM_H",
      "ALU_SH1ADD", "ALU_SH2ADD", "ALU_SH3ADD", "ALU_LT", "ALU_LTU", "ALU_GE", "ALU_GEU",
      "ALU_EQ", "ALU_NE", "ALU_MIN", "ALU_MINU", "ALU_MAX", "ALU_MAXU", "ALU_PACK",
      "ALU_PACKU", "ALU_PACKH", "ALU_SEXTB", "ALU_SEXTH", "ALU_CLZ", "ALU_CTZ", "ALU_CPOP",
      "ALU_SLT", "ALU_SLTU", "ALU_CMOV", "ALU_CMIX", "ALU_FSL", "ALU_FSR", "ALU_BSET",
      "ALU_BCLR", "ALU_BINV", "ALU_BEXT", "ALU_BCOMPRESS", "ALU_BDECOMPRESS", "ALU_BFP",
      "ALU_CLMUL", "ALU_CLMULR", "ALU_CLMULH", "ALU_CRC32_B", "ALU_CRC32C_B", "ALU_CRC32_H",
      "ALU_CRC32C_H", "ALU_CRC32_W", "ALU_CRC32C_W"
    ).zipWithIndex.toMap
  }

  object MdOp extends ChiselEnum {
    val MULL, MULH, DIV, REM = Value
  }

  object CsrOp extends ChiselEnum {
    val READ, WRITE, SET, CLEAR = Value
  }

  object PrivLvl {
    val M = "b11".U(2.W)
    val H = "b10".U(2.W)
    val S = "b01".U(2.W)
    val U = "b00".U(2.W)
  }

  object XDebugVer {
    val No = 0.U(4.W)
    val Std = 4.U(4.W)
    val NonStd = 15.U(4.W)
  }

  object WbInstrType extends ChiselEnum {
    val Load, Store, Other = Value
  }

  object OpASel extends ChiselEnum {
    val RegA, Fwd, CurrPc, Imm = Value
  }

  object ImmASel extends ChiselEnum {
    val Z, Zero = Value
  }

  object OpBSel extends ChiselEnum {
    val RegB, Imm = Value
  }

  object ImmBSel extends ChiselEnum {
    val I, S, B, U, J, IncrPc, IncrAddr = Value
  }

  object RfWdSel extends ChiselEnum {
    val Ex, Csr = Value
  }

  object CtrlFsm extends ChiselEnum {
    val Reset, BootSet, WaitSleep, Sleep, FirstFetch, Decode, Flush, IrqTaken, DbgTakenIf, DbgTakenId = Value
  }

  object PcSel extends ChiselEnum {
    val Boot, Jump, Exc, Eret, Dret, Bp = Value
  }

  object InstrExp extends ChiselEnum {
    val NotExpanded, Expanded, ExpandedLast = Value
  }

  object ExcPcSel extends ChiselEnum {
    val Exc, Irq, Dbd, DbgExc = Value
  }

  final class Irqs extends Bundle {
    val irq_software = Bool()
    val irq_timer = Bool()
    val irq_external = Bool()
    val irq_fast = UInt(15.W)
  }

  final class ExcCause extends Bundle {
    val irq_int = Bool()
    val irq_ext = Bool()
    val lower_cause = UInt(5.W)
  }

  object ExcCause {
    def apply(irqExt: Boolean, irqInt: Boolean, lowerCause: Int): UInt =
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

  object NmiIntCause {
    val Ecc = "b00000".U(5.W)
  }

  object DbgCause {
    val None_ = "h0".U(3.W)
    val Ebreak = "h1".U(3.W)
    val Trigger = "h2".U(3.W)
    val HaltReq = "h3".U(3.W)
    val Step = "h4".U(3.W)
  }

  val ADDR_W = 32
  val BUS_SIZE = 32
  val BUS_BYTES = BUS_SIZE / 8
  val BUS_W = log2Ceil(BUS_BYTES)
  val IC_SIZE_BYTES = 4096
  val IC_NUM_WAYS = 2
  val IC_LINE_SIZE = 64
  val IC_LINE_BYTES = IC_LINE_SIZE / 8
  val IC_LINE_W = log2Ceil(IC_LINE_BYTES)
  val IC_NUM_LINES = IC_SIZE_BYTES / IC_NUM_WAYS / IC_LINE_BYTES
  val IC_LINE_BEATS = IC_LINE_BYTES / BUS_BYTES
  val IC_LINE_BEATS_W = log2Ceil(IC_LINE_BEATS)
  val IC_INDEX_W = log2Ceil(IC_NUM_LINES)
  val IC_INDEX_HI = IC_INDEX_W + IC_LINE_W - 1
  val IC_TAG_SIZE = ADDR_W - IC_INDEX_W - IC_LINE_W + 1
  val IC_OUTPUT_BEATS = BUS_BYTES / 2
  val IC_DATA_ECC_SIZE = 7
  val IC_TAG_ECC_SIZE = 6
  val SCRAMBLE_KEY_W = 128
  val SCRAMBLE_NONCE_W = 64

  val PMP_MAX_REGIONS = 16
  val PMP_CFG_W = 8
  val PMP_ADDR_MSB = 33
  val PMP_ADDR_LSB = 2
  val PMP_I = 0
  val PMP_I2 = 1
  val PMP_D = 2

  object PmpReq {
    val Exec = "b00".U(2.W)
    val Write = "b01".U(2.W)
    val Read = "b10".U(2.W)
  }

  object PmpCfgMode {
    val Off = "b00".U(2.W)
    val Tor = "b01".U(2.W)
    val Na4 = "b10".U(2.W)
    val Napot = "b11".U(2.W)
  }

  final class PmpCfg extends Bundle {
    val lock = Bool()
    val mode = UInt(2.W)
    val exec = Bool()
    val write = Bool()
    val read = Bool()
  }

  object PmpCfg {
    def resetValue: UInt = 0.U(6.W)
  }

  final class PmpMseccfg extends Bundle {
    val rlb = Bool()
    val mmwp = Bool()
    val mml = Bool()
  }

  object CsrNum {
    val MVENDORID = 0xf11
    val MARCHID = 0xf12
    val MIMPID = 0xf13
    val MHARTID = 0xf14
    val MCONFIGPTR = 0xf15
    val MSTATUS = 0x300
    val MISA = 0x301
    val MIE = 0x304
    val MTVEC = 0x305
    val MCOUNTEREN = 0x306
    val MSTATUSH = 0x310
    val MENVCFG = 0x30a
    val MENVCFGH = 0x31a
    val MSCRATCH = 0x340
    val MEPC = 0x341
    val MCAUSE = 0x342
    val MTVAL = 0x343
    val MIP = 0x344
    val PMPCFG0 = 0x3a0
    val PMPCFG1 = 0x3a1
    val PMPCFG2 = 0x3a2
    val PMPCFG3 = 0x3a3
    val PMPADDR0 = 0x3b0
    val PMPADDR15 = 0x3bf
    val SCONTEXT = 0x5a8
    val MSECCFG = 0x747
    val MSECCFGH = 0x757
    val TSELECT = 0x7a0
    val TDATA1 = 0x7a1
    val TDATA2 = 0x7a2
    val TDATA3 = 0x7a3
    val MCONTEXT = 0x7a8
    val MSCONTEXT = 0x7aa
    val DCSR = 0x7b0
    val DPC = 0x7b1
    val DSCRATCH0 = 0x7b2
    val DSCRATCH1 = 0x7b3
    val MCOUNTINHIBIT = 0x320
    val MCYCLE = 0xb00
    val MINSTRET = 0xb02
    val MHPMCOUNTER3 = 0xb03
    val MHPMCOUNTER31 = 0xb1f
    val MCYCLEH = 0xb80
    val MINSTRETH = 0xb82
    val MHPMCOUNTER3H = 0xb83
    val MHPMCOUNTER31H = 0xb9f
    val CPUCTRLSTS = 0x7c0
    val SECURESEED = 0x7c1

    val pmpCfg: IndexedSeq[Int] = 0 until 4 map (PMPCFG0 + _)
    val pmpAddr: IndexedSeq[Int] = 0 until 16 map (PMPADDR0 + _)
    val mhpmEvent: IndexedSeq[Int] = 3 to 31 map (0x320 + _)
    val mhpmCounter: IndexedSeq[Int] = 3 to 31 map (0xb00 + _)
    val mhpmCounterH: IndexedSeq[Int] = 3 to 31 map (0xb80 + _)
  }

  val CSR_OFF_PMP_CFG = "h3a0".U(12.W)
  val CSR_OFF_PMP_ADDR = "h3b0".U(12.W)
  val CSR_MSTATUS_MIE_BIT = 3
  val CSR_MSTATUS_MPIE_BIT = 7
  val CSR_MSTATUS_MPP_BIT_LOW = 11
  val CSR_MSTATUS_MPP_BIT_HIGH = 12
  val CSR_MSTATUS_MPRV_BIT = 17
  val CSR_MSTATUS_TW_BIT = 21
  val CSR_MISA_MXL = 1.U(2.W)
  val CSR_MSIX_BIT = 3
  val CSR_MTIX_BIT = 7
  val CSR_MEIX_BIT = 11
  val CSR_MFIX_BIT_LOW = 16
  val CSR_MFIX_BIT_HIGH = 30
  val CSR_MSECCFG_MML_BIT = 0
  val CSR_MSECCFG_MMWP_BIT = 1
  val CSR_MSECCFG_RLB_BIT = 2
  val CSR_MARCHID_VALUE = 22.U(32.W)
  val CSR_MCONFIGPTR_VALUE = 0.U(32.W)

  val LfsrWidth = 32
  val RndCnstLfsrSeedDefault = "hac533bf4".U(32.W)
  val RndCnstLfsrPermDefault = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16)
  val RndCnstIbexKeyDefault = BigInt("14e8cecae3040d5e12286bb3cc113298", 16)
  val RndCnstIbexNonceDefault = BigInt("f79780bc735f3843", 16)
  val IbexMuBiWidth = 4
  val IbexMuBiOn = "b0101".U(IbexMuBiWidth.W)
  val IbexMuBiOff = "b1010".U(IbexMuBiWidth.W)
  val PmpCfgRst: Seq[BigInt] = Seq.fill(PMP_MAX_REGIONS)(BigInt(0))
  val PmpAddrRst: Seq[BigInt] = Seq.fill(PMP_MAX_REGIONS)(BigInt(0))
  val PmpMseccfgRst: BigInt = 0
}
