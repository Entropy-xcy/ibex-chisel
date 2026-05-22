package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexIfStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(branchPredictor: Boolean = false, pcIncrCheck: Boolean = false, iCache: Boolean = false) extends Module {
    private val tagSizeECC = IbexPkg.IC_TAG_SIZE
    private val lineSizeECC = IbexPkg.IC_LINE_SIZE

    val io = IO(new Bundle {
      val boot_addr_i = Input(UInt(32.W))
      val req_i = Input(Bool())
      val instr_gnt_i = Input(Bool())
      val instr_rvalid_i = Input(Bool())
      val instr_rdata_i = Input(UInt(32.W))
      val instr_bus_err_i = Input(Bool())
      val pmp_err_if_i = Input(Bool())
      val pmp_err_if_plus2_i = Input(Bool())
      val instr_valid_clear_i = Input(Bool())
      val pc_set_i = Input(Bool())
      val pc_mux_i = Input(UInt(3.W))
      val nt_branch_mispredict_i = Input(Bool())
      val nt_branch_addr_i = Input(UInt(32.W))
      val exc_pc_mux_i = Input(UInt(2.W))
      val exc_cause = Input(UInt(7.W))
      val icache_enable_i = Input(Bool())
      val branch_target_ex_i = Input(UInt(32.W))
      val csr_mepc_i = Input(UInt(32.W))
      val csr_depc_i = Input(UInt(32.W))
      val csr_mtvec_i = Input(UInt(32.W))
      val id_in_ready_i = Input(Bool())

      val instr_req_o = Output(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val instr_valid_id_d_o = Output(Bool())
      val instr_new_id_d_o = Output(Bool())
      val instr_valid_id_o = Output(Bool())
      val instr_new_id_o = Output(Bool())
      val instr_rdata_id_o = Output(UInt(32.W))
      val instr_rdata_alu_id_o = Output(UInt(32.W))
      val instr_rdata_c_id_o = Output(UInt(16.W))
      val instr_is_compressed_id_o = Output(Bool())
      val instr_gets_expanded_id_o = Output(UInt(2.W))
      val instr_bp_taken_o = Output(Bool())
      val instr_fetch_err_o = Output(Bool())
      val instr_fetch_err_plus2_o = Output(Bool())
      val illegal_c_insn_id_o = Output(Bool())
      val pc_if_o = Output(UInt(32.W))
      val pc_id_o = Output(UInt(32.W))
      val csr_mtvec_init_o = Output(Bool())
      val if_busy_o = Output(Bool())
      val pc_mismatch_alert_o = Output(Bool())
      val ic_tag_req_o = Output(UInt(2.W))
      val ic_data_req_o = Output(UInt(2.W))
      val ic_scr_key_req_o = Output(Bool())
      val icache_ecc_error_o = Output(Bool())
    })

    val dut = Module(new IbexIfStage(
      iCache = iCache,
      branchPredictor = branchPredictor,
      pcIncrCheck = pcIncrCheck,
      resetAll = true))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.boot_addr_i := io.boot_addr_i
    dut.req_i := io.req_i
    dut.instr_gnt_i := io.instr_gnt_i
    dut.instr_rvalid_i := io.instr_rvalid_i
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_bus_err_i := io.instr_bus_err_i
    dut.pmp_err_if_i := io.pmp_err_if_i
    dut.pmp_err_if_plus2_i := io.pmp_err_if_plus2_i
    dut.instr_valid_clear_i := io.instr_valid_clear_i
    dut.pc_set_i := io.pc_set_i
    dut.pc_mux_i := io.pc_mux_i
    dut.nt_branch_mispredict_i := io.nt_branch_mispredict_i
    dut.nt_branch_addr_i := io.nt_branch_addr_i
    dut.exc_pc_mux_i := io.exc_pc_mux_i
    dut.exc_cause := io.exc_cause
    dut.dummy_instr_en_i := false.B
    dut.dummy_instr_mask_i := 0.U
    dut.dummy_instr_seed_en_i := false.B
    dut.dummy_instr_seed_i := 0.U
    dut.icache_enable_i := io.icache_enable_i
    dut.icache_inval_i := false.B
    dut.branch_target_ex_i := io.branch_target_ex_i
    dut.csr_mepc_i := io.csr_mepc_i
    dut.csr_depc_i := io.csr_depc_i
    dut.csr_mtvec_i := io.csr_mtvec_i
    dut.id_in_ready_i := io.id_in_ready_i

    dut.ic_scr_key_valid_i := true.B

    if (iCache) {
      val tagRam = RegInit(VecInit(Seq.fill(IbexPkg.IC_NUM_WAYS)(
        VecInit(Seq.fill(IbexPkg.IC_NUM_LINES)(0.U(tagSizeECC.W))))))
      val dataRam = RegInit(VecInit(Seq.fill(IbexPkg.IC_NUM_WAYS)(
        VecInit(Seq.fill(IbexPkg.IC_NUM_LINES)(0.U(lineSizeECC.W))))))

      for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
        dut.ic_tag_rdata_i(way) := tagRam(way)(dut.ic_tag_addr_o)
        dut.ic_data_rdata_i(way) := dataRam(way)(dut.ic_data_addr_o)

        when(dut.ic_tag_write_o && dut.ic_tag_req_o(way)) {
          tagRam(way)(dut.ic_tag_addr_o) := dut.ic_tag_wdata_o
        }
        when(dut.ic_data_write_o && dut.ic_data_req_o(way)) {
          dataRam(way)(dut.ic_data_addr_o) := dut.ic_data_wdata_o
        }
      }
    } else {
      dut.ic_tag_rdata_i.foreach(_ := 0.U)
      dut.ic_data_rdata_i.foreach(_ := 0.U)
    }

    io.instr_req_o := dut.instr_req_o
    io.instr_addr_o := dut.instr_addr_o
    io.instr_valid_id_d_o := dut.instr_valid_id_d_o
    io.instr_new_id_d_o := dut.instr_new_id_d_o
    io.instr_valid_id_o := dut.instr_valid_id_o
    io.instr_new_id_o := dut.instr_new_id_o
    io.instr_rdata_id_o := dut.instr_rdata_id_o
    io.instr_rdata_alu_id_o := dut.instr_rdata_alu_id_o
    io.instr_rdata_c_id_o := dut.instr_rdata_c_id_o
    io.instr_is_compressed_id_o := dut.instr_is_compressed_id_o
    io.instr_gets_expanded_id_o := dut.instr_gets_expanded_id_o
    io.instr_bp_taken_o := dut.instr_bp_taken_o
    io.instr_fetch_err_o := dut.instr_fetch_err_o
    io.instr_fetch_err_plus2_o := dut.instr_fetch_err_plus2_o
    io.illegal_c_insn_id_o := dut.illegal_c_insn_id_o
    io.pc_if_o := dut.pc_if_o
    io.pc_id_o := dut.pc_id_o
    io.csr_mtvec_init_o := dut.csr_mtvec_init_o
    io.if_busy_o := dut.if_busy_o
    io.pc_mismatch_alert_o := dut.pc_mismatch_alert_o
    io.ic_tag_req_o := dut.ic_tag_req_o
    io.ic_data_req_o := dut.ic_data_req_o
    io.ic_scr_key_req_o := dut.ic_scr_key_req_o
    io.icache_ecc_error_o := dut.icache_ecc_error_o
  }

  class MemEcCHarness extends Module {
    val io = IO(new Bundle {
      val boot_addr_i = Input(UInt(32.W))
      val req_i = Input(Bool())
      val instr_gnt_i = Input(Bool())
      val instr_rvalid_i = Input(Bool())
      val instr_rdata_i = Input(UInt(39.W))
      val instr_bus_err_i = Input(Bool())
      val instr_fetch_err_o = Output(Bool())
      val instr_req_o = Output(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val instr_valid_id_d_o = Output(Bool())
      val instr_valid_id_o = Output(Bool())
      val pc_set_i = Input(Bool())
      val id_in_ready_i = Input(Bool())
    })

    val dut = Module(new IbexIfStage(memECC = true, resetAll = true))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.boot_addr_i := io.boot_addr_i
    dut.req_i := io.req_i
    dut.instr_gnt_i := io.instr_gnt_i
    dut.instr_rvalid_i := io.instr_rvalid_i
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_bus_err_i := io.instr_bus_err_i
    dut.pmp_err_if_i := false.B
    dut.pmp_err_if_plus2_i := false.B
    dut.instr_valid_clear_i := false.B
    dut.pc_set_i := io.pc_set_i
    dut.pc_mux_i := 0.U
    dut.nt_branch_mispredict_i := false.B
    dut.nt_branch_addr_i := 0.U
    dut.exc_pc_mux_i := 0.U
    dut.exc_cause := 0.U
    dut.dummy_instr_en_i := false.B
    dut.dummy_instr_mask_i := 0.U
    dut.dummy_instr_seed_en_i := false.B
    dut.dummy_instr_seed_i := 0.U
    dut.icache_enable_i := false.B
    dut.icache_inval_i := false.B
    dut.branch_target_ex_i := 0.U
    dut.csr_mepc_i := 0.U
    dut.csr_depc_i := 0.U
    dut.csr_mtvec_i := 0.U
    dut.id_in_ready_i := io.id_in_ready_i
    dut.ic_tag_rdata_i.foreach(_ := 0.U)
    dut.ic_data_rdata_i.foreach(_ := 0.U)
    dut.ic_scr_key_valid_i := true.B

    io.instr_fetch_err_o := dut.instr_fetch_err_o
    io.instr_req_o := dut.instr_req_o
    io.instr_addr_o := dut.instr_addr_o
    io.instr_valid_id_d_o := dut.instr_valid_id_d_o
    io.instr_valid_id_o := dut.instr_valid_id_o
  }

  private object PcSel {
    val Boot = 0
    val Jump = 1
    val Exc = 2
    val Eret = 3
    val Dret = 4
  }

  private object ExcPcSel {
    val Exc = 0
    val Irq = 1
    val Dbd = 2
    val DbgExc = 3
  }

  private object InstrExp {
    val NotExpanded = 0
  }

  private def init(dut: Harness): Unit = {
    dut.io.boot_addr_i.poke("h00001000".U)
    dut.io.req_i.poke(false.B)
    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(false.B)
    dut.io.instr_rdata_i.poke(0.U)
    dut.io.instr_bus_err_i.poke(false.B)
    dut.io.pmp_err_if_i.poke(false.B)
    dut.io.pmp_err_if_plus2_i.poke(false.B)
    dut.io.instr_valid_clear_i.poke(false.B)
    dut.io.pc_set_i.poke(false.B)
    dut.io.pc_mux_i.poke(PcSel.Boot.U)
    dut.io.nt_branch_mispredict_i.poke(false.B)
    dut.io.nt_branch_addr_i.poke(0.U)
    dut.io.exc_pc_mux_i.poke(ExcPcSel.Exc.U)
    dut.io.exc_cause.poke(0.U)
    dut.io.icache_enable_i.poke(false.B)
    dut.io.branch_target_ex_i.poke(0.U)
    dut.io.csr_mepc_i.poke("h00002000".U)
    dut.io.csr_depc_i.poke("h00003000".U)
    dut.io.csr_mtvec_i.poke("h00004000".U)
    dut.io.id_in_ready_i.poke(true.B)
  }

  private def resetInit(dut: Harness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    init(dut)
  }

  private def requestAndReturn(dut: Harness, pcSet: Boolean, pcMux: Int, addr: BigInt, instr: BigInt): Unit = {
    dut.io.req_i.poke(true.B)
    dut.io.pc_set_i.poke(pcSet.B)
    dut.io.pc_mux_i.poke(pcMux.U)
    dut.io.instr_gnt_i.poke(true.B)
    dut.io.instr_req_o.expect(true.B)
    dut.io.instr_addr_o.expect(addr.U)
    dut.clock.step()

    dut.io.pc_set_i.poke(false.B)
    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(true.B)
    dut.io.instr_rdata_i.poke(instr.U)
    dut.clock.step()

    dut.io.instr_rvalid_i.poke(false.B)
  }

  private def finishIcacheInvalidation(dut: Harness): Unit = {
    dut.clock.step()
    dut.clock.step()
    for (_ <- 0 until IbexPkg.IC_NUM_LINES) {
      dut.clock.step()
    }
  }

  private def fillIcacheLine(
      dut: Harness,
      addr: BigInt,
      word0: BigInt,
      word1: BigInt): Unit = {
    dut.io.icache_enable_i.poke(true.B)
    dut.io.id_in_ready_i.poke(false.B)
    dut.io.req_i.poke(true.B)
    dut.io.pc_set_i.poke(true.B)
    dut.io.pc_mux_i.poke(PcSel.Jump.U)
    dut.io.branch_target_ex_i.poke(addr.U)
    dut.clock.step()

    dut.io.req_i.poke(false.B)
    dut.io.pc_set_i.poke(false.B)
    dut.clock.step()

    dut.io.instr_gnt_i.poke(true.B)
    dut.io.instr_req_o.expect(true.B)
    dut.io.instr_addr_o.expect(addr.U)
    dut.clock.step()

    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(true.B)
    dut.io.instr_rdata_i.poke(word0.U)
    dut.clock.step()

    dut.io.instr_rvalid_i.poke(false.B)
    dut.clock.step()

    dut.io.instr_gnt_i.poke(true.B)
    dut.io.instr_req_o.expect(true.B)
    dut.io.instr_addr_o.expect((addr + 4).U)
    dut.clock.step()

    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(true.B)
    dut.io.instr_rdata_i.poke(word1.U)
    dut.clock.step()

    dut.io.instr_rvalid_i.poke(false.B)
    dut.io.ic_tag_req_o.peek().litValue must not be BigInt(0)
    dut.io.ic_data_req_o.peek().litValue must not be BigInt(0)
    dut.clock.step()
  }

  private def stepUntilInstrNew(dut: Harness, maxCycles: Int = 8): Unit = {
    var cycles = 0
    while (!dut.io.instr_new_id_o.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.instr_new_id_o.peek().litToBoolean mustBe true
  }

  private def stepUntilNextInstrNew(dut: Harness, maxCycles: Int = 8): Unit = {
    dut.clock.step()
    stepUntilInstrNew(dut, maxCycles)
  }

  private def encode(data: BigInt): BigInt = {
    def parity(value: BigInt, mask: BigInt): BigInt = (value & mask).bitCount % 2
    val masks = Seq(
      BigInt("002606BD25", 16), BigInt("00DEBA8050", 16), BigInt("00413D89AA", 16),
      BigInt("0031234ED1", 16), BigInt("00C2C1323B", 16), BigInt("002DCC624C", 16),
      BigInt("0098505586", 16))
    val withParity = masks.zipWithIndex.foldLeft(data) { case (acc, (mask, bit)) =>
      acc | (parity(acc, mask) << (32 + bit))
    }
    withParity ^ BigInt("2A00000000", 16)
  }

  "IbexIfStage" - {
    "requests from boot PC and registers an aligned instruction into ID" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        requestAndReturn(dut, pcSet = true, pcMux = PcSel.Boot, addr = BigInt("1080", 16), instr = BigInt("00000013", 16))
        dut.io.instr_valid_id_d_o.expect(true.B)
        dut.io.instr_valid_id_o.expect(true.B)
        dut.io.instr_new_id_o.expect(true.B)
        dut.io.instr_rdata_id_o.expect("h00000013".U)
        dut.io.instr_rdata_alu_id_o.expect("h00000013".U)
        dut.io.instr_rdata_c_id_o.expect("h0013".U)
        dut.io.instr_is_compressed_id_o.expect(false.B)
        dut.io.instr_gets_expanded_id_o.expect(InstrExp.NotExpanded.U)
        dut.io.pc_id_o.expect("h00001080".U)
        dut.io.csr_mtvec_init_o.expect(false.B)
      }
    }

    "holds instr_valid_id_d while instr_new_id_d drops after the transfer" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        requestAndReturn(dut, pcSet = true, pcMux = PcSel.Boot, addr = BigInt("1080", 16), instr = BigInt("00000013", 16))
        dut.clock.step()

        dut.io.instr_valid_id_d_o.expect(true.B)
        dut.io.instr_new_id_d_o.expect(false.B)
        dut.io.instr_valid_id_o.expect(true.B)
        dut.io.instr_new_id_o.expect(false.B)
      }
    }

    "redirects fetches for jump and exception PC selections" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        dut.io.req_i.poke(true.B)
        dut.io.pc_set_i.poke(true.B)
        dut.io.pc_mux_i.poke(PcSel.Jump.U)
        dut.io.branch_target_ex_i.poke("h00002004".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00002004".U)
        dut.clock.step()

        dut.io.pc_mux_i.poke(PcSel.Exc.U)
        dut.io.exc_pc_mux_i.poke(ExcPcSel.Irq.U)
        dut.io.exc_cause.poke("b1001011".U) // external IRQ cause 11
        dut.io.csr_mtvec_i.poke("h00004000".U)
        dut.io.instr_addr_o.expect("h0000402c".U)
      }
    }

    "expands compressed instructions" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        requestAndReturn(dut, pcSet = true, pcMux = PcSel.Boot, addr = BigInt("1080", 16), instr = BigInt("00000001", 16))
        dut.io.instr_valid_id_o.expect(true.B)
        dut.io.instr_is_compressed_id_o.expect(true.B)
        dut.io.instr_rdata_id_o.expect("h00000013".U)
      }
    }

    "reports fetch errors" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        dut.io.req_i.poke(true.B)
        dut.io.pc_set_i.poke(true.B)
        dut.io.pc_mux_i.poke(PcSel.Boot.U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.clock.step()

        dut.io.pc_set_i.poke(false.B)
        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.instr_bus_err_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_fetch_err_o.expect(true.B)
      }
    }

    "ties off ICache RAM outputs when ICache is disabled" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        dut.io.ic_tag_req_o.expect(0.U)
        dut.io.ic_data_req_o.expect(0.U)
        dut.io.ic_scr_key_req_o.expect(false.B)
        dut.io.icache_ecc_error_o.expect(false.B)
      }
    }

    "uses the ICache generator path for cache-disabled instruction fetches" in {
      simulate(new Harness(iCache = true)) { dut =>
        resetInit(dut)
        finishIcacheInvalidation(dut)

        requestAndReturn(dut, pcSet = true, pcMux = PcSel.Boot, addr = BigInt("1080", 16), instr = BigInt("00000013", 16))
        dut.io.instr_valid_id_o.expect(true.B)
        dut.io.instr_rdata_id_o.expect("h00000013".U)
        dut.io.pc_id_o.expect("h00001080".U)
        dut.io.icache_ecc_error_o.expect(false.B)
      }
    }

    "realigns cached unaligned 32-bit instructions before a compressed instruction" in {
      simulate(new Harness(iCache = true)) { dut =>
        resetInit(dut)
        finishIcacheInvalidation(dut)

        fillIcacheLine(
          dut,
          addr = BigInt("578", 16),
          word0 = BigInt("10734501", 16),
          word1 = BigInt("70733205", 16))
        fillIcacheLine(
          dut,
          addr = BigInt("580", 16),
          word0 = BigInt("45157c02", 16),
          word1 = BigInt("c5193f9d", 16))

        dut.io.id_in_ready_i.poke(true.B)
        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.pc_set_i.poke(true.B)
        dut.io.pc_mux_i.poke(PcSel.Jump.U)
        dut.io.branch_target_ex_i.poke("h0000057e".U)
        dut.clock.step()

        dut.io.pc_set_i.poke(false.B)
        stepUntilInstrNew(dut)
        dut.io.pc_id_o.expect("h0000057e".U)
        dut.io.instr_rdata_id_o.expect("h7c027073".U)
        dut.io.instr_is_compressed_id_o.expect(false.B)

        stepUntilNextInstrNew(dut)
        dut.io.pc_id_o.expect("h00000582".U)
        dut.io.instr_rdata_c_id_o.expect("h4515".U)
        dut.io.instr_rdata_id_o.expect("h00500513".U)
        dut.io.instr_is_compressed_id_o.expect(true.B)
      }
    }

    "continues fetching after two sequential uncompressed words from an enabled ICache" in {
      simulate(new Harness(iCache = true)) { dut =>
        resetInit(dut)
        finishIcacheInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.pc_set_i.poke(true.B)
        dut.io.pc_mux_i.poke(PcSel.Jump.U)
        dut.io.branch_target_ex_i.poke("h00000000".U)
        dut.clock.step()

        dut.io.pc_set_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00000000".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h01c00293".U) // addi x5, x0, 28
        stepUntilInstrNew(dut)
        dut.io.pc_id_o.expect("h00000000".U)
        dut.io.instr_rdata_id_o.expect("h01c00293".U)

        dut.io.instr_valid_clear_i.poke(true.B)
        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00000004".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00502e23".U) // sw x5, 28(x0)
        stepUntilInstrNew(dut)
        dut.io.pc_id_o.expect("h00000004".U)
        dut.io.instr_rdata_id_o.expect("h00502e23".U)

        dut.io.instr_valid_clear_i.poke(true.B)
        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_gnt_i.poke(false.B)

        var sawNextRequest = false
        for (_ <- 0 until 12) {
          if (dut.io.instr_req_o.peek().litToBoolean) {
            dut.io.instr_addr_o.expect("h00000008".U)
            sawNextRequest = true
          }
          dut.clock.step()
        }
        sawNextRequest mustBe true
      }
    }

    "reports instruction integrity errors when MemECC is enabled" in {
      simulate(new MemEcCHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.boot_addr_i.poke("h00001000".U)
        dut.io.req_i.poke(true.B)
        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_bus_err_i.poke(false.B)
        dut.io.pc_set_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.pc_set_i.poke(false.B)
        dut.io.instr_rdata_i.poke((encode(BigInt("00000013", 16)) ^ 3).U)
        dut.clock.step()

        dut.io.instr_valid_id_o.expect(true.B)
        dut.io.instr_fetch_err_o.expect(true.B)
      }
    }
  }
}
