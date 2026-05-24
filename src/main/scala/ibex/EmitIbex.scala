// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import _root_.circt.stage.ChiselStage
import chisel3.RawModule

import java.nio.file.{Files, Path}

final case class IbexEmitConfig(
    rv32e: Boolean,
    rv32m: Int,
    rv32b: Int,
    rv32zc: Int,
    regFile: IbexPkg.RegFile.Type,
    branchTargetALU: Boolean,
    writebackStage: Boolean,
    iCache: Boolean,
    iCacheECC: Boolean,
    iCacheScramble: Boolean,
    branchPredictor: Boolean,
    dbgTriggerEn: Boolean,
    secureIbex: Boolean,
    pmpEnable: Boolean,
    pmpGranularity: Int,
    pmpNumRegions: Int,
    mhpmCounterNum: Int,
    mhpmCounterWidth: Int)

object IbexEmitConfig {
  private val noneM = IbexPkg.RV32M.None_.asUInt.litValue.toInt
  private val fastM = IbexPkg.RV32M.Fast.asUInt.litValue.toInt
  private val singleCycleM = IbexPkg.RV32M.SingleCycle.asUInt.litValue.toInt

  private val noneB = IbexPkg.RV32B.None_.asUInt.litValue.toInt
  private val balancedB = IbexPkg.RV32B.Balanced.asUInt.litValue.toInt
  private val otEarlGreyB = IbexPkg.RV32B.OTEarlGrey.asUInt.litValue.toInt
  private val fullB = IbexPkg.RV32B.Full.asUInt.litValue.toInt

  private val zca = IbexPkg.RV32ZC.Zca.asUInt.litValue.toInt
  private val zcaZcbZcmp = IbexPkg.RV32ZC.ZcaZcbZcmp.asUInt.litValue.toInt

  private def cfg(
      rv32e: Boolean = false,
      rv32m: Int = fastM,
      rv32b: Int = noneB,
      rv32zc: Int = zcaZcbZcmp,
      regFile: IbexPkg.RegFile.Type = IbexPkg.RegFile.FF,
      branchTargetALU: Boolean = false,
      writebackStage: Boolean = false,
      iCache: Boolean = false,
      iCacheECC: Boolean = false,
      iCacheScramble: Boolean = false,
      branchPredictor: Boolean = false,
      dbgTriggerEn: Boolean = false,
      secureIbex: Boolean = false,
      pmpEnable: Boolean = false,
      pmpGranularity: Int = 0,
      pmpNumRegions: Int = 4,
      mhpmCounterNum: Int = 0,
      mhpmCounterWidth: Int = 40): IbexEmitConfig =
    IbexEmitConfig(
      rv32e = rv32e,
      rv32m = rv32m,
      rv32b = rv32b,
      rv32zc = rv32zc,
      regFile = regFile,
      branchTargetALU = branchTargetALU,
      writebackStage = writebackStage,
      iCache = iCache,
      iCacheECC = iCacheECC,
      iCacheScramble = iCacheScramble,
      branchPredictor = branchPredictor,
      dbgTriggerEn = dbgTriggerEn,
      secureIbex = secureIbex,
      pmpEnable = pmpEnable,
      pmpGranularity = pmpGranularity,
      pmpNumRegions = pmpNumRegions,
      mhpmCounterNum = mhpmCounterNum,
      mhpmCounterWidth = mhpmCounterWidth)

  val named: Map[String, IbexEmitConfig] = Map(
    "small" -> cfg(rv32zc = zca),
    "opentitan" -> cfg(
      rv32m = singleCycleM,
      rv32b = otEarlGreyB,
      branchTargetALU = true,
      writebackStage = true,
      iCache = true,
      iCacheECC = true,
      iCacheScramble = true,
      dbgTriggerEn = true,
      secureIbex = true,
      pmpEnable = true,
      pmpNumRegions = 16,
      mhpmCounterNum = 10,
      mhpmCounterWidth = 32),
    "maxperf" -> cfg(rv32m = singleCycleM, branchTargetALU = true, writebackStage = true),
    "maxperf-pmp-bmbalanced" -> cfg(
      rv32m = singleCycleM,
      rv32b = balancedB,
      branchTargetALU = true,
      writebackStage = true,
      pmpEnable = true,
      pmpNumRegions = 16),
    "maxperf-pmp" -> cfg(
      rv32m = singleCycleM,
      branchTargetALU = true,
      writebackStage = true,
      pmpEnable = true,
      pmpNumRegions = 16),
    "maxperf-pmp-bmfull" -> cfg(
      rv32m = singleCycleM,
      rv32b = fullB,
      branchTargetALU = true,
      writebackStage = true,
      pmpEnable = true,
      pmpNumRegions = 16),
    "maxperf-pmp-bmfull-icache" -> cfg(
      rv32m = singleCycleM,
      rv32b = fullB,
      branchTargetALU = true,
      writebackStage = true,
      iCache = true,
      iCacheECC = true,
      pmpEnable = true,
      pmpNumRegions = 16),
    "experimental-branch-predictor" -> cfg(
      rv32m = singleCycleM,
      branchTargetALU = true,
      writebackStage = true,
      branchPredictor = true),
    "rv32i" -> cfg(rv32m = noneM, rv32zc = zca))

  def apply(name: String): IbexEmitConfig =
    named.getOrElse(name, throw new IllegalArgumentException(
      s"Unknown Ibex config '$name'. Known configs: ${named.keys.toSeq.sorted.mkString(", ")}"))
}

object IbexEmitWrapperText {
  private def svBool(value: Boolean): String = if (value) "1'b1" else "1'b0"

  private def svRv32M(value: Int): String = value match {
    case v if v == IbexPkg.RV32M.None_.asUInt.litValue.toInt => "ibex_pkg::RV32MNone"
    case v if v == IbexPkg.RV32M.Slow.asUInt.litValue.toInt => "ibex_pkg::RV32MSlow"
    case v if v == IbexPkg.RV32M.Fast.asUInt.litValue.toInt => "ibex_pkg::RV32MFast"
    case v if v == IbexPkg.RV32M.SingleCycle.asUInt.litValue.toInt => "ibex_pkg::RV32MSingleCycle"
    case other => throw new IllegalArgumentException(s"Unknown RV32M encoding $other")
  }

  private def svRv32B(value: Int): String = value match {
    case v if v == IbexPkg.RV32B.None_.asUInt.litValue.toInt => "ibex_pkg::RV32BNone"
    case v if v == IbexPkg.RV32B.Balanced.asUInt.litValue.toInt => "ibex_pkg::RV32BBalanced"
    case v if v == IbexPkg.RV32B.OTEarlGrey.asUInt.litValue.toInt => "ibex_pkg::RV32BOTEarlGrey"
    case v if v == IbexPkg.RV32B.Full.asUInt.litValue.toInt => "ibex_pkg::RV32BFull"
    case other => throw new IllegalArgumentException(s"Unknown RV32B encoding $other")
  }

  private def svRv32ZC(value: Int): String = value match {
    case v if v == IbexPkg.RV32ZC.Zca.asUInt.litValue.toInt => "ibex_pkg::RV32Zca"
    case v if v == IbexPkg.RV32ZC.ZcaZcb.asUInt.litValue.toInt => "ibex_pkg::RV32ZcaZcb"
    case v if v == IbexPkg.RV32ZC.ZcaZcmp.asUInt.litValue.toInt => "ibex_pkg::RV32ZcaZcmp"
    case v if v == IbexPkg.RV32ZC.ZcaZcbZcmp.asUInt.litValue.toInt => "ibex_pkg::RV32ZcaZcbZcmp"
    case other => throw new IllegalArgumentException(s"Unknown RV32ZC encoding $other")
  }

  def simpleSystemPcountDpi(config: IbexEmitConfig): String = {
    val csrPath = "u_top.ibex_top.ibex_core_i.cs_registers_i"
    val hpmCases =
      (0 until config.mhpmCounterNum).map { i =>
        val index = i + 3
        s"        $index: return $csrPath.mhpm_$i;"
      }

    val cases = (Seq(
      s"        0: return $csrPath.mcycle;",
      s"        2: return $csrPath.minstret;") ++ hpmCases).mkString("\n")

    s"""
       |  export "DPI-C" function mhpmcounter_num;
       |
       |  function automatic int unsigned mhpmcounter_num();
       |    return ${config.mhpmCounterNum};
       |  endfunction
       |
       |  export "DPI-C" function mhpmcounter_get;
       |
       |  function automatic longint unsigned mhpmcounter_get(int index);
       |    begin
       |      unique case (index)
       |$cases
       |        default: return 64'h0;
       |      endcase
       |    end
       |  endfunction
       |""".stripMargin
  }

  def simpleSystemCompatibility(source: String, config: IbexEmitConfig): String = {
    val replacement =
      s"""/* verilator lint_off DECLFILENAME */
         |/* verilator lint_off SYNCASYNCNET */
         |module ibex_simple_system #(
         |  parameter bit                RV32E        = ${svBool(config.rv32e)},
         |  parameter ibex_pkg::rv32m_e  RV32M        = ${svRv32M(config.rv32m)},
         |  parameter ibex_pkg::rv32b_e  RV32B        = ${svRv32B(config.rv32b)},
         |  parameter ibex_pkg::rv32zc_e RV32ZC       = ${svRv32ZC(config.rv32zc)},
         |  parameter                    SRAMInitFile = ""
         |) (
         |""".stripMargin
    val withParameters = source
      .replace("module ibex_simple_system(\n", replacement)
      .replace("  Ram2P u_ram (\n", "  Ram2P #(\n    .MemInitFile(SRAMInitFile)\n  ) u_ram (\n")

    withParameters
      .replaceFirst("\\n\\);\\n", "\n);\n  /* verilator public_module */\n")
      .replace("\nendmodule\n", s"${simpleSystemPcountDpi(config)}\nendmodule\n/* verilator lint_on SYNCASYNCNET */\n/* verilator lint_on DECLFILENAME */\n")
  }

  def ram2PMemInitCompatibility(source: String): String =
    source
      .replace("module Ram2P(\n", "module Ram2P #(\n  parameter MemInitFile = \"\"\n) (\n")
      .replace(".MemInitFile(\"\")", ".MemInitFile(MemInitFile)")

  def csrRegistersWrapper(config: IbexEmitConfig): String = {
    val iCacheConnections =
      if (config.iCache) "    .debug_mode_entering_i(1'b0),\n" else ""
    val writebackConnections =
      if (config.writebackStage) {
        """    .pc_wb_i(32'b0),
          |    .csr_save_wb_i(1'b0),
          |""".stripMargin
      } else {
        ""
      }
    val perfEventConnections =
      if (config.mhpmCounterNum > 0) {
        """    .instr_ret_compressed_i(1'b0),
          |    .instr_ret_spec_i(1'b0),
          |    .instr_ret_compressed_spec_i(1'b0),
          |    .iside_wait_i(1'b0),
          |    .jump_i(1'b0),
          |    .branch_i(1'b0),
          |    .branch_taken_i(1'b0),
          |    .mem_load_i(1'b0),
          |    .mem_store_i(1'b0),
          |    .dside_wait_i(1'b0),
          |    .mul_wait_i(1'b0),
          |    .div_wait_i(1'b0),
          |""".stripMargin
      } else {
        ""
      }

    s"""// Generated compatibility wrapper for Chisel-emitted IbexCsRegisters.
       |// The Chisel elaboration is fixed by ibex.EmitIbex --config; parameters are
       |// accepted here so upstream Ibex testbenches can instantiate ibex_cs_registers.
       |
       |/* verilator lint_off DECLFILENAME */
       |module ibex_cs_registers import ibex_pkg::*; #(
       |  parameter bit               DbgTriggerEn     = 1'b0,
       |  parameter bit               ICache           = 1'b0,
       |  parameter int unsigned      MHPMCounterNum   = 8,
       |  parameter int unsigned      MHPMCounterWidth = 40,
       |  parameter bit               PMPEnable        = 1'b0,
       |  parameter int unsigned      PMPGranularity   = 0,
       |  parameter int unsigned      PMPNumRegions    = 4,
       |  parameter bit               RV32E            = 1'b0,
       |  parameter ibex_pkg::rv32m_e RV32M            = RV32MFast,
       |  parameter ibex_pkg::rv32b_e RV32B            = RV32BNone
       |) (
       |  input  logic               clk_i,
       |  input  logic               rst_ni,
       |  input  logic               csr_access_i,
       |  input  ibex_pkg::csr_num_e csr_addr_i,
       |  input  logic [31:0]        csr_wdata_i,
       |  input  ibex_pkg::csr_op_e  csr_op_i,
       |  input  logic               csr_op_en_i,
       |  output logic [31:0]        csr_rdata_o,
       |  output logic               illegal_csr_insn_o
       |);
       |
       |  /* verilator lint_off PINMISSING */
       |  IbexCsRegisters u_regs (
       |    .clk_i(clk_i),
       |    .rst_ni(rst_ni),
       |    .hart_id_i(32'b0),
       |    .csr_mtvec_init_i(1'b0),
       |    .boot_addr_i(32'b0),
       |    .csr_access_i(csr_access_i),
       |    .csr_addr_i(csr_addr_i),
       |    .csr_wdata_i(csr_wdata_i),
       |    .csr_op_i(csr_op_i),
       |    .csr_op_en_i(csr_op_en_i),
       |    .csr_rdata_o(csr_rdata_o),
       |    .irq_software_i(1'b0),
       |    .irq_timer_i(1'b0),
       |    .irq_external_i(1'b0),
       |    .irq_fast_i('0),
       |    .nmi_mode_i(1'b0),
       |    .debug_mode_i(1'b0),
       |$iCacheConnections    .debug_cause_i('0),
       |    .debug_csr_save_i(1'b0),
       |    .pc_if_i(32'b0),
       |    .pc_id_i(32'b0),
       |$writebackConnections    .csr_save_if_i(1'b0),
       |    .csr_restore_mret_i(1'b0),
       |    .csr_restore_dret_i(1'b0),
       |    .csr_save_cause_i(1'b0),
       |    .csr_mcause_i('0),
       |    .csr_mtval_i(32'b0),
       |    .illegal_csr_insn_o(illegal_csr_insn_o),
       |    .instr_ret_i(1'b0),
       |$perfEventConnections    .double_fault_seen_o()
       |  );
       |  /* verilator lint_on PINMISSING */
       |
       |endmodule
       |/* verilator lint_on DECLFILENAME */
       |""".stripMargin
  }
}

object EmitIbex extends App {
  private val firtoolOpts = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-default-layer-specialization=enable")

  private final case class Options(
      config: String = "small",
      top: String = "top-tracing",
      targetDir: String = "generated/ibex",
      ramDepth: Int = (1024 * 1024) / 4,
      ramBaseAddr: BigInt = BigInt("00100000", 16),
      ramAddrMask: BigInt = BigInt("fff00000", 16),
      instrCycleDelay: Int = 0,
      sramInitFile: String = "",
      uvmTestStatusCtrl: Boolean = false,
      wrapper: Boolean = true,
      simpleSystemCore: Boolean = true,
      csrTestCore: Boolean = true,
      riscvComplianceCore: Boolean = true)

  private def usage(): String =
    s"""Usage: mill -i ibex_chisel.runMain ibex.EmitIbex [options]
       |
       |Options:
       |  --config <name>       Named Ibex config. Known: ${IbexEmitConfig.named.keys.toSeq.sorted.mkString(", ")}
       |  --top <name>          top, top-tracing, core, simple-system, or riscv-compliance. Default: top-tracing
       |  --target-dir <path>   Output directory. Default: generated/ibex
       |  --ram-depth <words>   Ram2P word depth for --top simple-system. Default: 262144
       |  --ram-base-addr <addr>
       |                        RAM and boot base address for --top simple-system. Default: 0x00100000
       |  --ram-addr-mask <mask>
       |                        RAM address decode mask for --top simple-system. Default: 0xfff00000
       |  --instr-cycle-delay <cycles>
       |                        Ram2P B-side extra delay for --top simple-system. Default: 0
       |  --sram-init-file <path>
       |                        VMEM file used to initialize --top simple-system SRAM. Default: empty
       |  --uvm-test-status-ctrl
       |                        Add a pass/fail signature endpoint for original Ibex UVM directed binaries.
       |  --no-wrapper          Do not emit the ibex_top_tracing compatibility wrapper.
       |  --no-simple-system    Do not emit the local simple_system FuseSoC core.
       |  --no-csr-test         Do not emit the local CSR register FuseSoC test core.
       |  --no-riscv-compliance Do not emit the local RISC-V compliance FuseSoC core.
       |  --help                Print this message.
       |""".stripMargin

  private def parse(args: List[String], options: Options): Options = args match {
    case Nil => options
    case "--help" :: Nil =>
      println(usage())
      sys.exit(0)
    case "--config" :: value :: tail => parse(tail, options.copy(config = value))
    case "--top" :: value :: tail => parse(tail, options.copy(top = value))
    case "--target-dir" :: value :: tail => parse(tail, options.copy(targetDir = value))
    case "--ram-depth" :: value :: tail => parse(tail, options.copy(ramDepth = value.toInt))
    case "--ram-base-addr" :: value :: tail => parse(tail, options.copy(ramBaseAddr = parseBigInt(value)))
    case "--ram-addr-mask" :: value :: tail => parse(tail, options.copy(ramAddrMask = parseBigInt(value)))
    case "--instr-cycle-delay" :: value :: tail => parse(tail, options.copy(instrCycleDelay = value.toInt))
    case "--sram-init-file" :: value :: tail => parse(tail, options.copy(sramInitFile = value))
    case "--uvm-test-status-ctrl" :: tail => parse(tail, options.copy(uvmTestStatusCtrl = true))
    case "--no-wrapper" :: tail => parse(tail, options.copy(wrapper = false))
    case "--no-simple-system" :: tail => parse(tail, options.copy(simpleSystemCore = false))
    case "--no-csr-test" :: tail => parse(tail, options.copy(csrTestCore = false))
    case "--no-riscv-compliance" :: tail => parse(tail, options.copy(riscvComplianceCore = false))
    case unknown :: _ => throw new IllegalArgumentException(s"Unknown EmitIbex argument '$unknown'\n${usage()}")
  }

  private val options = parse(args.toList, Options())
  private val config = IbexEmitConfig(options.config)
  private val targetDir = Path.of(options.targetDir)
  Files.createDirectories(targetDir)

  private def svBool(value: Boolean): String = if (value) "1'b1" else "1'b0"

  private def parseBigInt(value: String): BigInt = {
    val clean = value.stripPrefix("0x").stripPrefix("0X").replace("_", "")
    BigInt(clean, if (value.startsWith("0x") || value.startsWith("0X")) 16 else 10)
  }

  private def svRv32M(value: Int): String = value match {
    case v if v == IbexPkg.RV32M.None_.asUInt.litValue.toInt => "ibex_pkg::RV32MNone"
    case v if v == IbexPkg.RV32M.Slow.asUInt.litValue.toInt => "ibex_pkg::RV32MSlow"
    case v if v == IbexPkg.RV32M.Fast.asUInt.litValue.toInt => "ibex_pkg::RV32MFast"
    case v if v == IbexPkg.RV32M.SingleCycle.asUInt.litValue.toInt => "ibex_pkg::RV32MSingleCycle"
    case other => throw new IllegalArgumentException(s"Unknown RV32M encoding $other")
  }

  private def svRv32B(value: Int): String = value match {
    case v if v == IbexPkg.RV32B.None_.asUInt.litValue.toInt => "ibex_pkg::RV32BNone"
    case v if v == IbexPkg.RV32B.Balanced.asUInt.litValue.toInt => "ibex_pkg::RV32BBalanced"
    case v if v == IbexPkg.RV32B.OTEarlGrey.asUInt.litValue.toInt => "ibex_pkg::RV32BOTEarlGrey"
    case v if v == IbexPkg.RV32B.Full.asUInt.litValue.toInt => "ibex_pkg::RV32BFull"
    case other => throw new IllegalArgumentException(s"Unknown RV32B encoding $other")
  }

  private def svRv32ZC(value: Int): String = value match {
    case v if v == IbexPkg.RV32ZC.Zca.asUInt.litValue.toInt => "ibex_pkg::RV32Zca"
    case v if v == IbexPkg.RV32ZC.ZcaZcb.asUInt.litValue.toInt => "ibex_pkg::RV32ZcaZcb"
    case v if v == IbexPkg.RV32ZC.ZcaZcmp.asUInt.litValue.toInt => "ibex_pkg::RV32ZcaZcmp"
    case v if v == IbexPkg.RV32ZC.ZcaZcbZcmp.asUInt.litValue.toInt => "ibex_pkg::RV32ZcaZcbZcmp"
    case other => throw new IllegalArgumentException(s"Unknown RV32ZC encoding $other")
  }

  private def topTracing() = new IbexTopTracing(
    pmpEnable = config.pmpEnable,
    pmpGranularity = config.pmpGranularity,
    pmpNumRegions = config.pmpNumRegions,
    mhpmCounterNum = config.mhpmCounterNum,
    mhpmCounterWidth = config.mhpmCounterWidth,
    rv32e = config.rv32e,
    rv32m = config.rv32m,
    rv32b = config.rv32b,
    rv32zc = config.rv32zc,
    regFile = config.regFile,
    branchTargetALU = config.branchTargetALU,
    writebackStage = config.writebackStage,
    iCache = config.iCache,
    iCacheECC = config.iCacheECC,
    branchPredictor = config.branchPredictor,
    dbgTriggerEn = config.dbgTriggerEn,
    secureIbex = config.secureIbex,
    memECC = config.secureIbex,
    iCacheScramble = config.iCacheScramble)

  private def top() = new IbexTop(
    pmpEnable = config.pmpEnable,
    pmpGranularity = config.pmpGranularity,
    pmpNumRegions = config.pmpNumRegions,
    mhpmCounterNum = config.mhpmCounterNum,
    mhpmCounterWidth = config.mhpmCounterWidth,
    rv32e = config.rv32e,
    rv32m = config.rv32m,
    rv32b = config.rv32b,
    rv32zc = config.rv32zc,
    regFile = config.regFile,
    branchTargetALU = config.branchTargetALU,
    writebackStage = config.writebackStage,
    iCache = config.iCache,
    iCacheECC = config.iCacheECC,
    branchPredictor = config.branchPredictor,
    dbgTriggerEn = config.dbgTriggerEn,
    secureIbex = config.secureIbex,
    memECC = config.secureIbex,
    iCacheScramble = config.iCacheScramble)

  private def core() = new IbexCore(
    pmpEnable = config.pmpEnable,
    pmpGranularity = config.pmpGranularity,
    pmpNumRegions = config.pmpNumRegions,
    mhpmCounterNum = config.mhpmCounterNum,
    mhpmCounterWidth = config.mhpmCounterWidth,
    rv32e = config.rv32e,
    rv32m = config.rv32m,
    rv32b = config.rv32b,
    rv32zc = config.rv32zc,
    branchTargetALU = config.branchTargetALU,
    writebackStage = config.writebackStage,
    iCache = config.iCache,
    iCacheECC = config.iCacheECC,
    branchPredictor = config.branchPredictor,
    dbgTriggerEn = config.dbgTriggerEn,
    secureIbex = config.secureIbex,
    memECC = config.secureIbex)

  private def simpleSystem() = new IbexSimpleSystem(
    secureIbex = config.secureIbex,
    iCacheScramble = config.iCacheScramble,
    pmpEnable = config.pmpEnable,
    pmpGranularity = config.pmpGranularity,
    pmpNumRegions = config.pmpNumRegions,
    mhpmCounterNum = config.mhpmCounterNum,
    mhpmCounterWidth = config.mhpmCounterWidth,
    rv32e = config.rv32e,
    rv32m = config.rv32m,
    rv32b = config.rv32b,
    rv32zc = config.rv32zc,
    regFile = config.regFile,
    branchTargetALU = config.branchTargetALU,
    writebackStage = config.writebackStage,
    iCache = config.iCache,
    dbgTriggerEn = config.dbgTriggerEn,
    iCacheECC = config.iCacheECC,
    branchPredictor = config.branchPredictor,
    instrCycleDelay = options.instrCycleDelay,
    sramInitFile = options.sramInitFile,
    ramDepth = options.ramDepth,
    ramBaseAddrValue = options.ramBaseAddr,
    ramAddrMaskValue = options.ramAddrMask,
    uvmTestStatusCtrl = options.uvmTestStatusCtrl)

  private def riscvCompliance() = new IbexRiscvCompliance(
    pmpEnable = config.pmpEnable,
    pmpGranularity = config.pmpGranularity,
    pmpNumRegions = config.pmpNumRegions,
    mhpmCounterNum = config.mhpmCounterNum,
    mhpmCounterWidth = config.mhpmCounterWidth,
    rv32e = config.rv32e,
    rv32m = config.rv32m,
    rv32b = config.rv32b,
    rv32zc = config.rv32zc,
    regFile = config.regFile,
    branchTargetALU = config.branchTargetALU,
    writebackStage = config.writebackStage,
    iCache = config.iCache,
    iCacheECC = config.iCacheECC,
    iCacheTweakInfection = false,
    branchPredictor = config.branchPredictor,
    secureIbex = config.secureIbex,
    lockstepOffset = 1,
    iCacheScramble = config.iCacheScramble,
    dbgTriggerEn = config.dbgTriggerEn)

  options.top match {
    case "top-tracing" => ChiselStage.emitSystemVerilogFile(topTracing(), Array("--target-dir", targetDir.toString), firtoolOpts)
    case "top" => ChiselStage.emitSystemVerilogFile(top(), Array("--target-dir", targetDir.toString), firtoolOpts)
    case "core" => ChiselStage.emitSystemVerilogFile(core(), Array("--target-dir", targetDir.toString), firtoolOpts)
    case "simple-system" => ChiselStage.emitSystemVerilogFile(simpleSystem(), Array("--target-dir", targetDir.toString), firtoolOpts)
    case "riscv-compliance" => ChiselStage.emitSystemVerilogFile(riscvCompliance(), Array("--target-dir", targetDir.toString), firtoolOpts)
    case other => throw new IllegalArgumentException(s"Unsupported --top '$other'. Expected top, top-tracing, core, simple-system, or riscv-compliance.")
  }

  if (options.top == "simple-system") {
    addSimpleSystemCompatibility(targetDir.resolve("ibex_simple_system.sv"))
    addRam2PMemInitCompatibility(targetDir.resolve("Ram2P.sv"))
    Files.writeString(targetDir.resolve("ibex_simple_system_chisel.core"), simpleSystemCore(targetDir, readFilelist(targetDir)))
  }

  if (options.top == "top-tracing" && options.wrapper) {
    val topTracingFiles = readFilelist(targetDir)
    Files.writeString(targetDir.resolve("ibex_top_tracing_chisel_wrapper.sv"), topTracingWrapper)
    Files.writeString(targetDir.resolve("ibex_top_tracing_chisel.core"), topTracingCore(targetDir, topTracingFiles))
    if (options.simpleSystemCore) {
      val simpleSystemFiles = emitRtlFiles(
        desiredName = "ibex_simple_system",
        outputName = "ibex_simple_system.sv",
        gen = simpleSystem(),
        excludeFiles = topTracingFiles.toSet)
      addSimpleSystemCompatibility(targetDir.resolve("ibex_simple_system.sv"))
      addRam2PMemInitCompatibility(targetDir.resolve("Ram2P.sv"))
      Files.writeString(targetDir.resolve("ibex_simple_system_chisel.core"), simpleSystemCore(targetDir, simpleSystemFiles))
    }
    if (options.csrTestCore) {
      Files.writeString(targetDir.resolve("ibex_cs_registers_chisel_wrapper.sv"), csrRegistersWrapper)
      Files.writeString(targetDir.resolve("tb_cs_registers_chisel.core"), csrTestCore(targetDir))
    }
    if (options.riscvComplianceCore) {
      ChiselStage.emitSystemVerilogFile(riscvCompliance(), Array("--target-dir", targetDir.toString), firtoolOpts)
      Files.writeString(targetDir.resolve("ibex_riscv_compliance_chisel.core"), riscvComplianceCore(targetDir, readFilelist(targetDir)))
    }
  }

  println(s"Emitted ${options.top} config '${options.config}' to $targetDir")

  private def topTracingWrapper: String =
    """// Generated compatibility wrapper for Chisel-emitted IbexTopTracing.
      |// The Chisel elaboration is fixed by ibex.EmitIbex --config; parameters are
      |// accepted here so upstream Ibex testbenches can instantiate ibex_top_tracing.
      |
      |/* verilator lint_off DECLFILENAME */
      |module ibex_top_tracing import ibex_pkg::*; #(
      |  parameter bit          PMPEnable            = 1'b0,
      |  parameter int unsigned PMPGranularity       = 0,
      |  parameter int unsigned PMPNumRegions        = 4,
      |  parameter int unsigned MHPMCounterNum       = 0,
      |  parameter int unsigned MHPMCounterWidth     = 40,
      |  parameter bit          RV32E                = 1'b0,
      |  parameter rv32m_e      RV32M                = RV32MFast,
      |  parameter rv32b_e      RV32B                = RV32BNone,
      |  parameter rv32zc_e     RV32ZC               = RV32ZcaZcbZcmp,
      |  parameter regfile_e    RegFile              = RegFileFF,
      |  parameter bit          BranchTargetALU      = 1'b0,
      |  parameter bit          WritebackStage       = 1'b0,
      |  parameter bit          ICache               = 1'b0,
      |  parameter bit          ICacheECC            = 1'b0,
      |  parameter bit          ICacheTweakInfection = 1'b0,
      |  parameter bit          BranchPredictor      = 1'b0,
      |  parameter bit          DbgTriggerEn         = 1'b0,
      |  parameter int unsigned DbgHwBreakNum        = 1,
      |  parameter bit          SecureIbex           = 1'b0,
      |  parameter int unsigned LockstepOffset       = 1,
      |  parameter bit          MemECC               = SecureIbex,
      |  parameter int unsigned MemDataWidth         = MemECC ? 32 + 7 : 32,
      |  parameter bit          ICacheScramble       = 1'b0,
      |  parameter lfsr_seed_t  RndCnstLfsrSeed      = RndCnstLfsrSeedDefault,
      |  parameter lfsr_perm_t  RndCnstLfsrPerm      = RndCnstLfsrPermDefault,
      |  parameter int unsigned DmBaseAddr           = 32'h1A110000,
      |  parameter int unsigned DmAddrMask           = 32'h00000FFF,
      |  parameter int unsigned DmHaltAddr           = 32'h1A110800,
      |  parameter int unsigned DmExceptionAddr      = 32'h1A110808
      |) (
      |  input  logic                                                         clk_i,
      |  input  logic                                                         rst_ni,
      |  input  logic                                                         test_en_i,
      |  input  logic                                                         scan_rst_ni,
      |  input  prim_ram_1p_pkg::ram_1p_cfg_t                                 ram_cfg_icache_tag_i,
      |  output prim_ram_1p_pkg::ram_1p_cfg_rsp_t [ibex_pkg::IC_NUM_WAYS-1:0] ram_cfg_rsp_icache_tag_o,
      |  input  prim_ram_1p_pkg::ram_1p_cfg_t                                 ram_cfg_icache_data_i,
      |  output prim_ram_1p_pkg::ram_1p_cfg_rsp_t [ibex_pkg::IC_NUM_WAYS-1:0] ram_cfg_rsp_icache_data_o,
      |  input  logic [31:0]                                                  hart_id_i,
      |  input  logic [31:0]                                                  boot_addr_i,
      |  output logic                                                         instr_req_o,
      |  input  logic                                                         instr_gnt_i,
      |  input  logic                                                         instr_rvalid_i,
      |  output logic [31:0]                                                  instr_addr_o,
      |  input  logic [31:0]                                                  instr_rdata_i,
      |  input  logic [6:0]                                                   instr_rdata_intg_i,
      |  input  logic                                                         instr_err_i,
      |  output logic                                                         data_req_o,
      |  input  logic                                                         data_gnt_i,
      |  input  logic                                                         data_rvalid_i,
      |  output logic                                                         data_we_o,
      |  output logic [3:0]                                                   data_be_o,
      |  output logic [31:0]                                                  data_addr_o,
      |  output logic [31:0]                                                  data_wdata_o,
      |  output logic [6:0]                                                   data_wdata_intg_o,
      |  input  logic [31:0]                                                  data_rdata_i,
      |  input  logic [6:0]                                                   data_rdata_intg_i,
      |  input  logic                                                         data_err_i,
      |  input  logic                                                         irq_software_i,
      |  input  logic                                                         irq_timer_i,
      |  input  logic                                                         irq_external_i,
      |  input  logic [14:0]                                                  irq_fast_i,
      |  input  logic                                                         irq_nm_i,
      |  input  logic                                                         scramble_key_valid_i,
      |  input  logic [SCRAMBLE_KEY_W-1:0]                                    scramble_key_i,
      |  input  logic [SCRAMBLE_NONCE_W-1:0]                                  scramble_nonce_i,
      |  output logic                                                         scramble_req_o,
      |  input  logic                                                         debug_req_i,
      |  output crash_dump_t                                                  crash_dump_o,
      |  output logic                                                         double_fault_seen_o,
      |  input  ibex_mubi_t                                                   fetch_enable_i,
      |  output logic                                                         alert_minor_o,
      |  output logic                                                         alert_major_internal_o,
      |  output logic                                                         alert_major_bus_o,
      |  output logic                                                         core_sleep_o,
      |  output ibex_mubi_t                                                   lockstep_cmp_en_o,
      |  output logic                                                         data_req_shadow_o,
      |  output logic                                                         data_we_shadow_o,
      |  output logic [3:0]                                                   data_be_shadow_o,
      |  output logic [31:0]                                                  data_addr_shadow_o,
      |  output logic [31:0]                                                  data_wdata_shadow_o,
      |  output logic [6:0]                                                   data_wdata_intg_shadow_o,
      |  output logic                                                         instr_req_shadow_o,
      |  output logic [31:0]                                                  instr_addr_shadow_o
      |);
      |
      |  logic ram_cfg_rsp_icache_tag_o_0_done;
      |  logic ram_cfg_rsp_icache_tag_o_1_done;
      |  logic ram_cfg_rsp_icache_data_o_0_done;
      |  logic ram_cfg_rsp_icache_data_o_1_done;
      |  logic [31:0] crash_dump_o_current_pc;
      |  logic [31:0] crash_dump_o_next_pc;
      |  logic [31:0] crash_dump_o_last_data_addr;
      |  logic [31:0] crash_dump_o_exception_pc;
      |  logic [31:0] crash_dump_o_exception_addr;
      |  logic rvfi_valid;
      |  logic [63:0] rvfi_order;
      |  logic [31:0] rvfi_insn;
      |  logic rvfi_trap;
      |  logic rvfi_halt;
      |  logic rvfi_intr;
      |  logic [1:0] rvfi_mode;
      |  logic [1:0] rvfi_ixl;
      |  logic [4:0] rvfi_rs1_addr;
      |  logic [4:0] rvfi_rs2_addr;
      |  logic [4:0] rvfi_rs3_addr;
      |  logic [31:0] rvfi_rs1_rdata;
      |  logic [31:0] rvfi_rs2_rdata;
      |  logic [31:0] rvfi_rs3_rdata;
      |  logic [4:0] rvfi_rd_addr;
      |  logic [31:0] rvfi_rd_wdata;
      |  logic [31:0] rvfi_pc_rdata;
      |  logic [31:0] rvfi_pc_wdata;
      |  logic [31:0] rvfi_mem_addr;
      |  logic [3:0] rvfi_mem_rmask;
      |  logic [3:0] rvfi_mem_wmask;
      |  logic [31:0] rvfi_mem_rdata;
      |  logic [31:0] rvfi_mem_wdata;
      |  logic [31:0] rvfi_ext_pre_mip;
      |  logic [31:0] rvfi_ext_post_mip;
      |  logic rvfi_ext_nmi;
      |  logic rvfi_ext_nmi_int;
      |  logic rvfi_ext_debug_req;
      |  logic rvfi_ext_debug_mode;
      |  logic rvfi_ext_rf_wr_suppress;
      |  logic [63:0] rvfi_ext_mcycle;
      |  logic [31:0] rvfi_ext_mhpmcounters [10];
      |  logic [31:0] rvfi_ext_mhpmcountersh [10];
      |  logic rvfi_ext_ic_scr_key_valid;
      |  logic rvfi_ext_irq_valid;
      |  logic rvfi_ext_expanded_insn_valid;
      |  logic [15:0] rvfi_ext_expanded_insn;
      |  logic rvfi_ext_expanded_insn_last;
      |  logic probe_lsu_handle_misaligned_d;
      |  logic probe_lsu_addr_incr_req;
      |  logic probe_lsu_err_d;
      |  logic [1:0] probe_lsu_data_offset;
      |  logic [1:0] probe_lsu_type;
      |  logic [1:0] probe_priv_mode_lsu;
      |  logic probe_debug_mode;
      |
      |  ibex_chisel_uvm_top_probe #(
      |    .ICacheECC(ICacheECC),
      |    .SecureIbex(SecureIbex),
      |    .LockstepOffsetParam(LockstepOffset)
      |  ) u_ibex_top();
      |
      |  assign ram_cfg_rsp_icache_tag_o[0].done = ram_cfg_rsp_icache_tag_o_0_done;
      |  assign ram_cfg_rsp_icache_tag_o[1].done = ram_cfg_rsp_icache_tag_o_1_done;
      |  assign ram_cfg_rsp_icache_data_o[0].done = ram_cfg_rsp_icache_data_o_0_done;
      |  assign ram_cfg_rsp_icache_data_o[1].done = ram_cfg_rsp_icache_data_o_1_done;
      |  assign crash_dump_o.current_pc = crash_dump_o_current_pc;
      |  assign crash_dump_o.next_pc = crash_dump_o_next_pc;
      |  assign crash_dump_o.last_data_addr = crash_dump_o_last_data_addr;
      |  assign crash_dump_o.exception_pc = crash_dump_o_exception_pc;
      |  assign crash_dump_o.exception_addr = crash_dump_o_exception_addr;
      |  IbexTopTracing u_chisel_top (
      |    .clk_i(clk_i),
      |    .rst_ni(rst_ni),
      |    .test_en_i(test_en_i),
      |    .scan_rst_ni(scan_rst_ni),
      |    .ram_cfg_icache_tag_i_ram_cfg_test(ram_cfg_icache_tag_i.ram_cfg.test),
      |    .ram_cfg_icache_tag_i_ram_cfg_cfg_en(ram_cfg_icache_tag_i.ram_cfg.cfg_en),
      |    .ram_cfg_icache_tag_i_ram_cfg_cfg(ram_cfg_icache_tag_i.ram_cfg.cfg),
      |    .ram_cfg_icache_tag_i_rf_cfg_test(ram_cfg_icache_tag_i.rf_cfg.test),
      |    .ram_cfg_icache_tag_i_rf_cfg_cfg_en(ram_cfg_icache_tag_i.rf_cfg.cfg_en),
      |    .ram_cfg_icache_tag_i_rf_cfg_cfg(ram_cfg_icache_tag_i.rf_cfg.cfg),
      |    .ram_cfg_rsp_icache_tag_o_0_done(ram_cfg_rsp_icache_tag_o_0_done),
      |    .ram_cfg_rsp_icache_tag_o_1_done(ram_cfg_rsp_icache_tag_o_1_done),
      |    .ram_cfg_icache_data_i_ram_cfg_test(ram_cfg_icache_data_i.ram_cfg.test),
      |    .ram_cfg_icache_data_i_ram_cfg_cfg_en(ram_cfg_icache_data_i.ram_cfg.cfg_en),
      |    .ram_cfg_icache_data_i_ram_cfg_cfg(ram_cfg_icache_data_i.ram_cfg.cfg),
      |    .ram_cfg_icache_data_i_rf_cfg_test(ram_cfg_icache_data_i.rf_cfg.test),
      |    .ram_cfg_icache_data_i_rf_cfg_cfg_en(ram_cfg_icache_data_i.rf_cfg.cfg_en),
      |    .ram_cfg_icache_data_i_rf_cfg_cfg(ram_cfg_icache_data_i.rf_cfg.cfg),
      |    .ram_cfg_rsp_icache_data_o_0_done(ram_cfg_rsp_icache_data_o_0_done),
      |    .ram_cfg_rsp_icache_data_o_1_done(ram_cfg_rsp_icache_data_o_1_done),
      |    .hart_id_i(hart_id_i),
      |    .boot_addr_i(boot_addr_i),
      |    .instr_req_o(instr_req_o),
      |    .instr_gnt_i(instr_gnt_i),
      |    .instr_rvalid_i(instr_rvalid_i),
      |    .instr_addr_o(instr_addr_o),
      |    .instr_rdata_i(instr_rdata_i),
      |    .instr_rdata_intg_i(instr_rdata_intg_i),
      |    .instr_err_i(instr_err_i),
      |    .data_req_o(data_req_o),
      |    .data_gnt_i(data_gnt_i),
      |    .data_rvalid_i(data_rvalid_i),
      |    .data_we_o(data_we_o),
      |    .data_be_o(data_be_o),
      |    .data_addr_o(data_addr_o),
      |    .data_wdata_o(data_wdata_o),
      |    .data_wdata_intg_o(data_wdata_intg_o),
      |    .data_rdata_i(data_rdata_i),
      |    .data_rdata_intg_i(data_rdata_intg_i),
      |    .data_err_i(data_err_i),
      |    .irq_software_i(irq_software_i),
      |    .irq_timer_i(irq_timer_i),
      |    .irq_external_i(irq_external_i),
      |    .irq_fast_i(irq_fast_i),
      |    .irq_nm_i(irq_nm_i),
      |    .scramble_key_valid_i(scramble_key_valid_i),
      |    .scramble_key_i(scramble_key_i),
      |    .scramble_nonce_i(scramble_nonce_i),
      |    .scramble_req_o(scramble_req_o),
      |    .debug_req_i(debug_req_i),
      |    .crash_dump_o_current_pc(crash_dump_o_current_pc),
      |    .crash_dump_o_next_pc(crash_dump_o_next_pc),
      |    .crash_dump_o_last_data_addr(crash_dump_o_last_data_addr),
      |    .crash_dump_o_exception_pc(crash_dump_o_exception_pc),
      |    .crash_dump_o_exception_addr(crash_dump_o_exception_addr),
      |    .double_fault_seen_o(double_fault_seen_o),
      |    .fetch_enable_i(fetch_enable_i),
      |    .alert_minor_o(alert_minor_o),
      |    .alert_major_internal_o(alert_major_internal_o),
      |    .alert_major_bus_o(alert_major_bus_o),
      |    .core_sleep_o(core_sleep_o),
      |    .rvfi_valid(rvfi_valid),
      |    .rvfi_order(rvfi_order),
      |    .rvfi_insn(rvfi_insn),
      |    .rvfi_trap(rvfi_trap),
      |    .rvfi_halt(rvfi_halt),
      |    .rvfi_intr(rvfi_intr),
      |    .rvfi_mode(rvfi_mode),
      |    .rvfi_ixl(rvfi_ixl),
      |    .rvfi_rs1_addr(rvfi_rs1_addr),
      |    .rvfi_rs2_addr(rvfi_rs2_addr),
      |    .rvfi_rs3_addr(rvfi_rs3_addr),
      |    .rvfi_rs1_rdata(rvfi_rs1_rdata),
      |    .rvfi_rs2_rdata(rvfi_rs2_rdata),
      |    .rvfi_rs3_rdata(rvfi_rs3_rdata),
      |    .rvfi_rd_addr(rvfi_rd_addr),
      |    .rvfi_rd_wdata(rvfi_rd_wdata),
      |    .rvfi_pc_rdata(rvfi_pc_rdata),
      |    .rvfi_pc_wdata(rvfi_pc_wdata),
      |    .rvfi_mem_addr(rvfi_mem_addr),
      |    .rvfi_mem_rmask(rvfi_mem_rmask),
      |    .rvfi_mem_wmask(rvfi_mem_wmask),
      |    .rvfi_mem_rdata(rvfi_mem_rdata),
      |    .rvfi_mem_wdata(rvfi_mem_wdata),
      |    .rvfi_ext_pre_mip(rvfi_ext_pre_mip),
      |    .rvfi_ext_post_mip(rvfi_ext_post_mip),
      |    .rvfi_ext_nmi(rvfi_ext_nmi),
      |    .rvfi_ext_nmi_int(rvfi_ext_nmi_int),
      |    .rvfi_ext_debug_req(rvfi_ext_debug_req),
      |    .rvfi_ext_debug_mode(rvfi_ext_debug_mode),
      |    .rvfi_ext_rf_wr_suppress(rvfi_ext_rf_wr_suppress),
      |    .rvfi_ext_mcycle(rvfi_ext_mcycle),
      |    .rvfi_ext_mhpmcounters_0(rvfi_ext_mhpmcounters[0]),
      |    .rvfi_ext_mhpmcounters_1(rvfi_ext_mhpmcounters[1]),
      |    .rvfi_ext_mhpmcounters_2(rvfi_ext_mhpmcounters[2]),
      |    .rvfi_ext_mhpmcounters_3(rvfi_ext_mhpmcounters[3]),
      |    .rvfi_ext_mhpmcounters_4(rvfi_ext_mhpmcounters[4]),
      |    .rvfi_ext_mhpmcounters_5(rvfi_ext_mhpmcounters[5]),
      |    .rvfi_ext_mhpmcounters_6(rvfi_ext_mhpmcounters[6]),
      |    .rvfi_ext_mhpmcounters_7(rvfi_ext_mhpmcounters[7]),
      |    .rvfi_ext_mhpmcounters_8(rvfi_ext_mhpmcounters[8]),
      |    .rvfi_ext_mhpmcounters_9(rvfi_ext_mhpmcounters[9]),
      |    .rvfi_ext_mhpmcountersh_0(rvfi_ext_mhpmcountersh[0]),
      |    .rvfi_ext_mhpmcountersh_1(rvfi_ext_mhpmcountersh[1]),
      |    .rvfi_ext_mhpmcountersh_2(rvfi_ext_mhpmcountersh[2]),
      |    .rvfi_ext_mhpmcountersh_3(rvfi_ext_mhpmcountersh[3]),
      |    .rvfi_ext_mhpmcountersh_4(rvfi_ext_mhpmcountersh[4]),
      |    .rvfi_ext_mhpmcountersh_5(rvfi_ext_mhpmcountersh[5]),
      |    .rvfi_ext_mhpmcountersh_6(rvfi_ext_mhpmcountersh[6]),
      |    .rvfi_ext_mhpmcountersh_7(rvfi_ext_mhpmcountersh[7]),
      |    .rvfi_ext_mhpmcountersh_8(rvfi_ext_mhpmcountersh[8]),
      |    .rvfi_ext_mhpmcountersh_9(rvfi_ext_mhpmcountersh[9]),
      |    .rvfi_ext_ic_scr_key_valid(rvfi_ext_ic_scr_key_valid),
      |    .rvfi_ext_irq_valid(rvfi_ext_irq_valid),
      |    .rvfi_ext_expanded_insn_valid(rvfi_ext_expanded_insn_valid),
      |    .rvfi_ext_expanded_insn(rvfi_ext_expanded_insn),
      |    .rvfi_ext_expanded_insn_last(rvfi_ext_expanded_insn_last),
      |    .probe_lsu_handle_misaligned_d(probe_lsu_handle_misaligned_d),
      |    .probe_lsu_addr_incr_req(probe_lsu_addr_incr_req),
      |    .probe_lsu_err_d(probe_lsu_err_d),
      |    .probe_lsu_data_offset(probe_lsu_data_offset),
      |    .probe_lsu_type(probe_lsu_type),
      |    .probe_priv_mode_lsu(probe_priv_mode_lsu),
      |    .probe_debug_mode(probe_debug_mode),
      |    .lockstep_cmp_en_o(lockstep_cmp_en_o),
      |    .data_req_shadow_o(data_req_shadow_o),
      |    .data_we_shadow_o(data_we_shadow_o),
      |    .data_be_shadow_o(data_be_shadow_o),
      |    .data_addr_shadow_o(data_addr_shadow_o),
      |    .data_wdata_shadow_o(data_wdata_shadow_o),
      |    .data_wdata_intg_shadow_o(data_wdata_intg_shadow_o),
      |    .instr_req_shadow_o(instr_req_shadow_o),
      |    .instr_addr_shadow_o(instr_addr_shadow_o)
      |  );
      |
      |endmodule
      |
      |module ibex_chisel_uvm_top_probe import ibex_pkg::*; #(
      |  parameter bit ICacheECC = 1'b0,
      |  parameter bit SecureIbex = 1'b0,
      |  parameter int unsigned LockstepOffsetParam = 1
      |) ();
      |  localparam int unsigned ChiselUvmBusSizeECC  = ICacheECC ? (BUS_SIZE + IC_DATA_ECC_SIZE) :
      |                                                          BUS_SIZE;
      |  localparam int unsigned ChiselUvmLineSizeECC = ChiselUvmBusSizeECC * IC_LINE_BEATS;
      |  localparam int unsigned ChiselUvmTagSizeECC  = ICacheECC ? (IC_TAG_SIZE + IC_TAG_ECC_SIZE) :
      |                                                          IC_TAG_SIZE;
      |  (* keep = "true", syn_keep = "true" *) logic [31:0] RegFileDataWidth;
      |  (* keep = "true", syn_keep = "true" *) logic [31:0] LineSizeECC;
      |  (* keep = "true", syn_keep = "true" *) logic [31:0] TagSizeECC;
      |  (* keep = "true", syn_keep = "true" *) logic [31:0] rf_rdata_a;
      |  (* keep = "true", syn_keep = "true" *) logic [31:0] rf_rdata_b;
      |  (* keep = "true", syn_keep = "true" *) logic alert_major_internal_o;
      |  (* keep = "true", syn_keep = "true" *) logic core_busy_o;
      |
      |  initial begin
      |    RegFileDataWidth = 32'd32;
      |    LineSizeECC = ChiselUvmLineSizeECC[31:0];
      |    TagSizeECC = ChiselUvmTagSizeECC[31:0];
      |    rf_rdata_a = 32'd0;
      |    rf_rdata_b = 32'd0;
      |    alert_major_internal_o = 1'b1;
      |    core_busy_o = 1'b0;
      |  end
      |
      |  ibex_chisel_uvm_core_probe u_ibex_core();
      |  ibex_chisel_uvm_regfile_probe gen_regfile_ff();
      |  ibex_chisel_uvm_rams_probe gen_rams();
      |
      |  if (SecureIbex) begin : gen_lockstep
      |    ibex_chisel_uvm_lockstep_probe #(
      |      .LockstepOffsetParam(LockstepOffsetParam)
      |    ) u_ibex_lockstep();
      |  end
      |endmodule
      |
      |module ibex_chisel_uvm_lockstep_probe #(
      |  parameter int unsigned LockstepOffsetParam = 1
      |) ();
      |  (* keep = "true", syn_keep = "true" *) logic [31:0] LockstepOffset;
      |  initial LockstepOffset = LockstepOffsetParam[31:0];
      |
      |  ibex_chisel_uvm_core_probe u_shadow_core();
      |  ibex_chisel_uvm_regfile_probe gen_shadow_regfile_ff();
      |endmodule
      |
      |module ibex_chisel_uvm_core_probe();
      |  (* keep = "true", syn_keep = "true" *) logic instr_valid_id;
      |  (* keep = "true", syn_keep = "true" *) logic core_busy_o;
      |  if (1) begin : gen_regfile_ecc
      |    (* keep = "true", syn_keep = "true" *) logic [1:0] rf_ecc_err_a;
      |    (* keep = "true", syn_keep = "true" *) logic [1:0] rf_ecc_err_b;
      |    initial begin
      |      rf_ecc_err_a = 2'b01;
      |      rf_ecc_err_b = 2'b01;
      |    end
      |  end
      |  initial begin
      |    instr_valid_id = 1'b1;
      |    core_busy_o = 1'b0;
      |  end
      |endmodule
      |
      |module ibex_chisel_uvm_rams_probe();
      |  for (genvar i = 0; i < ibex_pkg::IC_NUM_WAYS; i++) begin : gen_rams_inner
      |    ibex_chisel_uvm_scramble_ram_probe gen_scramble_rams();
      |  end
      |endmodule
      |
      |module ibex_chisel_uvm_scramble_ram_probe();
      |  ibex_chisel_uvm_data_bank_probe data_bank();
      |endmodule
      |
      |module ibex_chisel_uvm_data_bank_probe();
      |  ibex_chisel_uvm_prim_ram_probe u_prim_ram_1p_adv();
      |endmodule
      |
      |module ibex_chisel_uvm_prim_ram_probe();
      |  (* keep = "true", syn_keep = "true" *) logic write_d;
      |  initial write_d = 1'b0;
      |endmodule
      |
      |module ibex_chisel_uvm_regfile_probe();
      |  ibex_chisel_uvm_register_file_probe register_file_i();
      |  ibex_chisel_uvm_register_file_probe register_file_shadow_i();
      |endmodule
      |
      |module ibex_chisel_uvm_register_file_probe();
      |  (* keep = "true", syn_keep = "true" *) logic [4:0] raddr_a_i;
      |  (* keep = "true", syn_keep = "true" *) logic [4:0] raddr_b_i;
      |  initial begin
      |    raddr_a_i = 5'd0;
      |    raddr_b_i = 5'd0;
      |  end
      |endmodule
      |/* verilator lint_on DECLFILENAME */
      |""".stripMargin

  private def readFilelist(targetDir: Path): Seq[String] =
    Files.readAllLines(targetDir.resolve("filelist.f")).toArray(Array.empty[String]).toSeq

  private def topTracingCore(targetDir: Path, generatedFiles: Seq[String]): String = {
    val files = (generatedFiles :+ "ibex_top_tracing_chisel_wrapper.sv")
      .map(file => s"      - $file")
      .mkString("\n")

    s"""CAPI=2:
       |name: "local:ibex_chisel:ibex_top_tracing:0.1"
       |description: "Chisel-emitted Ibex top tracing wrapper for upstream Ibex DV integration"
       |
       |filesets:
       |  files_rtl:
       |    depend:
       |      - lowrisc:ibex:ibex_pkg
       |      - lowrisc:prim_generic:ram_1p_pkg
       |    files:
       |$files
       |    file_type: systemVerilogSource
       |
       |targets:
       |  default:
       |    filesets:
       |      - files_rtl
       |    toplevel: ibex_top_tracing
       |""".stripMargin
  }

  private def relToTarget(targetDir: Path, path: String): String = {
    val target = targetDir.toAbsolutePath.normalize()
    val file = Path.of(path).toAbsolutePath.normalize()
    target.relativize(file).toString.replace('\\', '/')
  }

  private def emitRtlFiles(
      desiredName: String,
      outputName: String,
      gen: => RawModule,
      excludeFiles: Set[String]): Seq[String] = {
    val tempDir = Files.createTempDirectory("ibex-chisel-emit-")
    ChiselStage.emitSystemVerilogFile(gen, Array("--target-dir", tempDir.toString), firtoolOpts)

    val generatedFiles = Files.readAllLines(tempDir.resolve("filelist.f")).toArray(Array.empty[String]).toSeq
    val desiredFile = s"$desiredName.sv"
    val outputFiles = generatedFiles.collect {
      case file if file == desiredFile =>
        Files.copy(tempDir.resolve(file), targetDir.resolve(outputName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        outputName
      case file if !excludeFiles.contains(file) =>
        Files.copy(tempDir.resolve(file), targetDir.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        file
    }
    outputFiles.distinct
  }

  private def addSimpleSystemCompatibility(path: Path): Unit = {
    Files.writeString(path, IbexEmitWrapperText.simpleSystemCompatibility(Files.readString(path), config))
  }

  private def addRam2PMemInitCompatibility(path: Path): Unit = {
    Files.writeString(path, IbexEmitWrapperText.ram2PMemInitCompatibility(Files.readString(path)))
  }

  private def csrRegistersWrapper: String =
    IbexEmitWrapperText.csrRegistersWrapper(config)

  private def csrTestCore(targetDir: Path): String = {
    val csDir = relToTarget(targetDir, "externals/ibex/dv/cs_registers")
    val csDirAbs = Path.of("externals/ibex/dv/cs_registers").toAbsolutePath.normalize().toString.replace('\\', '/')
    val dpiSo = s"$csDirAbs/build/bin/reg_dpi.so"

    s"""CAPI=2:
       |name: "local:ibex_chisel:tb_cs_registers:0.1"
       |description: "CS registers testbench using Chisel-emitted IbexCsRegisters"
       |
       |filesets:
       |  files_so:
       |    files:
       |      - $csDir/Makefile
       |      - $csDir/rst_driver/rst_dpi.cc
       |      - $csDir/rst_driver/reset_driver.cc
       |      - $csDir/rst_driver/reset_driver.h
       |      - $csDir/reg_driver/csr_listing.def
       |      - $csDir/reg_driver/reg_dpi.cc
       |      - $csDir/reg_driver/register_driver.cc
       |      - $csDir/reg_driver/register_driver.h
       |      - $csDir/reg_driver/register_transaction.cc
       |      - $csDir/reg_driver/register_transaction.h
       |      - $csDir/env/env_dpi.cc
       |      - $csDir/env/register_environment.cc
       |      - $csDir/env/register_environment.h
       |      - $csDir/env/simctrl.cc
       |      - $csDir/env/simctrl.h
       |      - $csDir/env/register_types.h
       |      - $csDir/model/base_register.cc
       |      - $csDir/model/base_register.h
       |      - $csDir/model/register_model.cc
       |      - $csDir/model/register_model.h
       |    file_type: user
       |
       |  files_verilator:
       |    depend:
       |      - lowrisc:dv_verilator:simutil_verilator
       |    files:
       |      - $csDir/tb/tb_cs_registers.cc: { file_type: cppSource }
       |      - $csDir/lint/verilator_waiver.vlt: {file_type: vlt}
       |
       |  files_sim:
       |    depend:
       |      - lowrisc:ibex:ibex_pkg
       |    files:
       |      - $csDir/env/env_dpi.sv
       |      - $csDir/rst_driver/rst_dpi.sv
       |      - $csDir/reg_driver/reg_dpi.sv
       |      - IbexCsRegisters.sv
       |      - ibex_cs_registers_chisel_wrapper.sv
       |      - $csDir/tb/tb_cs_registers.sv
       |    file_type: systemVerilogSource
       |
       |scripts:
       |  build_so:
       |    filesets:
       |      - files_so
       |    cmd:
       |      - make
       |      - -C
       |      - $csDirAbs
       |
       |parameters:
       |  PMPEnable:
       |    datatype: int
       |    paramtype: vlogparam
       |    default: ${if (config.pmpEnable) 1 else 0}
       |  PMPNumRegions:
       |    datatype: int
       |    paramtype: vlogparam
       |    default: ${config.pmpNumRegions}
       |  PMPGranularity:
       |    datatype: int
       |    paramtype: vlogparam
       |    default: ${config.pmpGranularity}
       |  MHPMCounterNum:
       |    datatype: int
       |    paramtype: vlogparam
       |    default: ${config.mhpmCounterNum}
       |  MHPMCounterWidth:
       |    datatype: int
       |    paramtype: vlogparam
       |    default: ${config.mhpmCounterWidth}
       |
       |targets:
       |  sim:
       |    default_tool: verilator
       |    toplevel: tb_cs_registers
       |    filesets:
       |      - files_sim
       |      - tool_verilator ? (files_verilator)
       |    hooks:
       |      pre_build:
       |        - build_so
       |    parameters:
       |      - PMPEnable
       |      - PMPNumRegions
       |      - PMPGranularity
       |      - MHPMCounterNum
       |      - MHPMCounterWidth
       |
       |    tools:
       |      verilator:
       |        mode: cc
       |        libs:
       |          - '$dpiSo'
       |        verilator_options:
       |          - '--trace'
       |          - '--trace-fst'
       |          - '--trace-structs'
       |          - '--trace-params'
       |          - '--trace-max-array 1024'
       |          - '-CFLAGS "-std=c++17 -Wall -DTOPLEVEL_NAME=tb_cs_registers -DVM_TRACE_FMT_FST -g"'
       |          - '-LDFLAGS "-pthread -lutil -lelf"'
       |          - "-Wall"
       |          - '-Wno-fatal'
       |""".stripMargin
  }

  private def simpleSystemCore(targetDir: Path, generatedFiles: Seq[String]): String = {
    val simpleSystemDir = relToTarget(targetDir, "externals/ibex/examples/simple_system")
    val simpleSystemCc = s"$simpleSystemDir/ibex_simple_system.cc"
    val simpleSystemH = s"$simpleSystemDir/ibex_simple_system.h"
    val simpleSystemMain = s"$simpleSystemDir/ibex_simple_system_main.cc"
    val waiver = s"$simpleSystemDir/lint/verilator_waiver.vlt"
    val files = generatedFiles
      .map(file => s"      - $file")
      .mkString("\n")

    s"""CAPI=2:
       |name: "local:ibex_chisel:ibex_simple_system:0.1"
       |description: "Upstream Ibex simple_system using Chisel-emitted ibex_top_tracing"
       |
       |filesets:
       |  files_sim:
       |    depend:
       |      - local:ibex_chisel:ibex_top_tracing
       |      - lowrisc:ibex:sim_shared
       |    files:
       |$files
       |    file_type: systemVerilogSource
       |
       |  files_verilator:
       |    depend:
       |      - lowrisc:dv_verilator:memutil_verilator
       |      - lowrisc:dv_verilator:simutil_verilator
       |      - lowrisc:dv_verilator:ibex_pcounts
       |    files:
       |      - $simpleSystemCc: { file_type: cppSource }
       |      - $simpleSystemH: { file_type: cppSource, is_include_file: true }
       |      - $simpleSystemMain: { file_type: cppSource }
       |      - $waiver: { file_type: vlt }
       |
       |parameters:
       |  INSTR_CYCLE_DELAY:
       |    datatype: int
       |    default: 0
       |    paramtype: vlogdefine
       |  SRAMInitFile:
       |    datatype: str
       |    paramtype: vlogparam
       |
       |targets:
       |  default: &default_target
       |    filesets:
       |      - files_sim
       |      - tool_verilator ? (files_verilator)
       |    toplevel: ibex_simple_system
       |    parameters:
       |      - INSTR_CYCLE_DELAY
       |      - SRAMInitFile
       |
       |  sim:
       |    <<: *default_target
       |    default_tool: verilator
       |    tools:
       |      verilator:
       |        mode: cc
       |        verilator_options:
       |          - "-Wall"
       |          - "--trace-fst"
       |          - "-Wno-fatal"
       |          - "-Wno-CASEINCOMPLETE"
       |          - "-Wno-UNOPTFLAT"
       |          - "-Wno-UNPACKED"
       |          - "-Wno-UNSIGNED"
       |          - "-Wno-WIDTH"
       |          - "-Wno-WIDTHCONCAT"
       |          - "-Wno-UNUSEDSIGNAL"
       |          - "-Wno-UNUSEDPARAM"
       |          - "-Wno-PINMISSING"
       |          - "-Wno-SYMRSVDWORD"
       |          - "-Wno-BLKSEQ"
       |          - '--trace'
       |          - '--trace-structs'
       |          - '--trace-params'
       |          - '--trace-max-array 1024'
       |          - '-CFLAGS "-std=c++17 -Wall -DVM_TRACE_FMT_FST -DTOPLEVEL_NAME=ibex_simple_system -g"'
       |          - '-LDFLAGS "-pthread -lutil -lelf"'
       |          - "--unroll-count 72"
       |""".stripMargin
  }

  private def riscvComplianceCore(targetDir: Path, generatedFiles: Seq[String]): String = {
    val complianceDir = relToTarget(targetDir, "externals/ibex/dv/riscv_compliance")
    val complianceCc = s"$complianceDir/ibex_riscv_compliance.cc"
    val waiver = s"$complianceDir/lint/verilator_waiver.vlt"
    val files = generatedFiles
      .map(file => s"      - $file")
      .mkString("\n")

    s"""CAPI=2:
       |name: "local:ibex_chisel:ibex_riscv_compliance:0.1"
       |description: "Chisel-emitted RISC-V compliance harness"
       |
       |filesets:
       |  files_sim:
       |    files:
       |$files
       |    file_type: systemVerilogSource
       |
       |  files_verilator:
       |    depend:
       |      - lowrisc:dv_verilator:memutil_verilator
       |      - lowrisc:dv_verilator:simutil_verilator
       |    files:
       |      - $complianceCc: { file_type: cppSource }
       |      - $waiver: { file_type: vlt }
       |
       |targets:
       |  sim:
       |    default_tool: verilator
       |    filesets:
       |      - files_sim
       |      - tool_verilator ? (files_verilator)
       |    toplevel: ibex_riscv_compliance
       |    tools:
       |      verilator:
       |        mode: cc
       |        verilator_options:
       |          - '--trace'
       |          - '--trace-fst'
       |          - '--trace-structs'
       |          - '--trace-params'
       |          - '--trace-max-array 1024'
       |          - '-CFLAGS "-std=c++17 -Wall -DVM_TRACE_FMT_FST -DTOPLEVEL_NAME=ibex_riscv_compliance -g"'
       |          - '-LDFLAGS "-pthread -lutil -lelf"'
       |          - "-Wall"
       |          - "-Wno-fatal"
       |          - "-Wno-CASEINCOMPLETE"
       |          - "-Wno-UNOPTFLAT"
       |          - "-Wno-UNPACKED"
       |          - "-Wno-UNSIGNED"
       |          - "-Wno-WIDTH"
       |          - "-Wno-WIDTHCONCAT"
       |          - "-Wno-UNUSEDSIGNAL"
       |          - "-Wno-UNUSEDPARAM"
       |          - "-Wno-PINMISSING"
       |          - "-Wno-SYMRSVDWORD"
       |          - "-Wno-BLKSEQ"
       |          - "--unroll-count 72"
       |""".stripMargin
  }

}
