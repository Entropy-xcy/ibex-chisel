package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(
      iCache: Boolean = false,
      iCacheECC: Boolean = false,
      iCacheTweakInfection: Boolean = false,
      pmpEnable: Boolean = false,
      secureIbex: Boolean = false,
      fetchEnable: UInt = IbexPkg.IbexMuBiOn,
      writebackStage: Boolean = false,
      rndCnstLfsrSeed: BigInt = 0xac533bf4L,
      rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16)) extends Module {
    val io = IO(new Bundle {
      val instr_req_o = Output(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val data_req_o = Output(Bool())
      val data_we_o = Output(Bool())
      val core_busy_o = Output(UInt(IbexPkg.IbexMuBiWidth.W))
      val alert_major_internal_o = Output(Bool())
      val alert_major_bus_o = Output(Bool())
    })

    val dut = Module(new IbexCore(resetAll = true, iCache = iCache, iCacheECC = iCacheECC, iCacheTweakInfection = iCacheTweakInfection, pmpEnable = pmpEnable, secureIbex = secureIbex, writebackStage = writebackStage, rndCnstLfsrSeed = rndCnstLfsrSeed, rndCnstLfsrPerm = rndCnstLfsrPerm))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.hart_id_i := 0.U
    dut.boot_addr_i := "h00001000".U

    dut.instr_gnt_i := false.B
    dut.instr_rvalid_i := false.B
    dut.instr_rdata_i := "h00000013".U
    dut.instr_err_i := false.B

    dut.data_gnt_i := false.B
    dut.data_rvalid_i := false.B
    dut.data_rdata_i := 0.U
    dut.data_err_i := false.B

    dut.rf_rdata_a_ecc_i := 0.U
    dut.rf_rdata_b_ecc_i := 0.U

    dut.ic_tag_rdata_i.foreach(_ := 0.U)
    dut.ic_data_rdata_i.foreach(_ := 0.U)
    dut.ic_scr_key_valid_i := true.B

    dut.irq_software_i := false.B
    dut.irq_timer_i := false.B
    dut.irq_external_i := false.B
    dut.irq_fast_i := 0.U
    dut.irq_nm_i := false.B

    dut.debug_req_i := false.B
    dut.fetch_enable_i := fetchEnable

    io.instr_req_o := dut.instr_req_o
    io.instr_addr_o := dut.instr_addr_o
    io.data_req_o := dut.data_req_o
    io.data_we_o := dut.data_we_o
    io.core_busy_o := dut.core_busy_o
    io.alert_major_internal_o := dut.alert_major_internal_o
    io.alert_major_bus_o := dut.alert_major_bus_o
  }

  class RegFileEcCHarness extends Module {
    val io = IO(new Bundle {
      val instr_req_o = Output(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val instr_gnt_i = Input(Bool())
      val instr_rvalid_i = Input(Bool())
      val instr_rdata_i = Input(UInt(32.W))
      val rf_rdata_a_ecc_i = Input(UInt(39.W))
      val rf_rdata_b_ecc_i = Input(UInt(39.W))
      val rf_wdata_wb_ecc_o = Output(UInt(39.W))
      val alert_major_internal_o = Output(Bool())
    })

    val dut = Module(new IbexCore(resetAll = true, regFileECC = true, regFileDataWidth = 39))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.hart_id_i := 0.U
    dut.boot_addr_i := "h00001000".U

    io.instr_req_o := dut.instr_req_o
    io.instr_addr_o := dut.instr_addr_o
    dut.instr_gnt_i := io.instr_gnt_i
    dut.instr_rvalid_i := io.instr_rvalid_i
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_err_i := false.B

    dut.data_gnt_i := false.B
    dut.data_rvalid_i := false.B
    dut.data_rdata_i := 0.U
    dut.data_err_i := false.B

    dut.rf_rdata_a_ecc_i := io.rf_rdata_a_ecc_i
    dut.rf_rdata_b_ecc_i := io.rf_rdata_b_ecc_i
    io.rf_wdata_wb_ecc_o := dut.rf_wdata_wb_ecc_o

    dut.ic_tag_rdata_i.foreach(_ := 0.U)
    dut.ic_data_rdata_i.foreach(_ := 0.U)
    dut.ic_scr_key_valid_i := true.B

    dut.irq_software_i := false.B
    dut.irq_timer_i := false.B
    dut.irq_external_i := false.B
    dut.irq_fast_i := 0.U
    dut.irq_nm_i := false.B

    dut.debug_req_i := false.B
    dut.fetch_enable_i := IbexPkg.IbexMuBiOn
    io.alert_major_internal_o := dut.alert_major_internal_o
  }

  class RvfiIrqHarness(writebackStage: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val irq_software_i = Input(Bool())
      val irq_nm_i = Input(Bool())
      val rvfi_ext_irq_valid = Output(Bool())
      val rvfi_ext_pre_mip = Output(UInt(32.W))
      val rvfi_ext_post_mip = Output(UInt(32.W))
      val rvfi_ext_nmi = Output(Bool())
      val instr_req_o = Output(Bool())
    })

    val dut = Module(new IbexCore(resetAll = true, writebackStage = writebackStage))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.hart_id_i := 0.U
    dut.boot_addr_i := "h00001000".U

    dut.instr_gnt_i := false.B
    dut.instr_rvalid_i := false.B
    dut.instr_rdata_i := "h00000013".U
    dut.instr_err_i := false.B

    dut.data_gnt_i := false.B
    dut.data_rvalid_i := false.B
    dut.data_rdata_i := 0.U
    dut.data_err_i := false.B

    dut.rf_rdata_a_ecc_i := 0.U
    dut.rf_rdata_b_ecc_i := 0.U

    dut.ic_tag_rdata_i.foreach(_ := 0.U)
    dut.ic_data_rdata_i.foreach(_ := 0.U)
    dut.ic_scr_key_valid_i := true.B

    dut.irq_software_i := io.irq_software_i
    dut.irq_timer_i := false.B
    dut.irq_external_i := false.B
    dut.irq_fast_i := 0.U
    dut.irq_nm_i := io.irq_nm_i

    dut.debug_req_i := false.B
    dut.fetch_enable_i := IbexPkg.IbexMuBiOn

    io.rvfi_ext_irq_valid := dut.rvfi_ext_irq_valid
    io.rvfi_ext_pre_mip := dut.rvfi_ext_pre_mip
    io.rvfi_ext_post_mip := dut.rvfi_ext_post_mip
    io.rvfi_ext_nmi := dut.rvfi_ext_nmi
    io.instr_req_o := dut.instr_req_o
  }

  private def expectPulseWithin(dut: RvfiIrqHarness, cycles: Int): Unit = {
    var seen = false
    for (_ <- 0 until cycles) {
      if (dut.io.rvfi_ext_irq_valid.peek().litToBoolean) {
        seen = true
      }
      dut.clock.step()
    }
    seen mustBe true
  }

  private def expectIrqExtWithin(dut: RvfiIrqHarness, cycles: Int): Unit = {
    var seen = false
    for (_ <- 0 until cycles) {
      if (dut.io.rvfi_ext_irq_valid.peek().litToBoolean) {
        seen = true
        dut.io.rvfi_ext_nmi.expect(true.B)
        dut.io.rvfi_ext_pre_mip.expect("h00000008".U)
        dut.io.rvfi_ext_post_mip.expect("h00000008".U)
      }
      dut.clock.step()
    }
    seen mustBe true
  }

  private def resetInit(dut: Harness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def finishIcacheInvalidation(dut: Harness): Unit = {
    dut.clock.step()
    dut.clock.step()
    for (_ <- 0 until IbexPkg.IC_NUM_LINES) {
      dut.clock.step()
    }
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

  "IbexCore" - {
    "elaborates and requests from the boot vector in the base configuration" in {
      simulate(new Harness) { dut =>
        resetInit(dut)

        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.core_busy_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates and requests from the boot vector with the ICache generator path" in {
      simulate(new Harness(iCache = true)) { dut =>
        resetInit(dut)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates and requests from the boot vector with ICacheECC enabled" in {
      simulate(new Harness(iCache = true, iCacheECC = true)) { dut =>
        resetInit(dut)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates and requests from the boot vector with ICacheTweakInfection enabled" in {
      simulate(new Harness(iCache = true, iCacheTweakInfection = true)) { dut =>
        resetInit(dut)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates and requests from the boot vector with PMP enabled" in {
      simulate(new Harness(pmpEnable = true)) { dut =>
        resetInit(dut)

        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "checks register-file integrity when RegFileECC is enabled" in {
      simulate(new RegFileEcCHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_rdata_i.poke(0.U)
        dut.io.rf_rdata_a_ecc_i.poke(encode(1).U)
        dut.io.rf_rdata_b_ecc_i.poke(encode(2).U)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h002081b3".U) // add x3, x1, x2
        dut.clock.step()

        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.rf_rdata_a_ecc_i.poke((encode(1) ^ 3).U)
        dut.clock.step()

        dut.io.alert_major_internal_o.expect(true.B)
      }
    }

    "gates instruction fetch when SecureIbex is enabled and fetch is off" in {
      simulate(new Harness(secureIbex = true, fetchEnable = IbexPkg.IbexMuBiOff)) { dut =>
        resetInit(dut)

        dut.clock.step()

        dut.io.instr_req_o.expect(false.B)
        dut.io.data_req_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates with custom dummy-instruction LFSR constants" in {
      simulate(new Harness(
        rndCnstLfsrSeed = 0x12345678L,
        rndCnstLfsrPerm = BigInt("00112233445566778899aabbccddeeff00112233", 16))) { dut =>
        resetInit(dut)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
      }
    }

    "elaborates the RVFI two-stage tracking path when WritebackStage is enabled" in {
      simulate(new Harness(writebackStage = true)) { dut =>
        resetInit(dut)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "raises RVFI irq notification when NMI arrives with an empty pipe" in {
      simulate(new RvfiIrqHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.irq_software_i.poke(true.B)
        dut.io.irq_nm_i.poke(true.B)

        expectIrqExtWithin(dut, 6)
      }
    }

    "raises RVFI irq notification through the WritebackStage RVFI pipe" in {
      simulate(new RvfiIrqHarness(writebackStage = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.irq_software_i.poke(true.B)
        dut.io.irq_nm_i.poke(true.B)

        expectIrqExtWithin(dut, 8)
      }
    }
  }
}
