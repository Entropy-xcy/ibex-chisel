package ibex

import chisel3._
import chisel3.util.MuxLookup
import circt.stage.ChiselStage
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexTopSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(
      regFile: IbexPkg.RegFile.Type = IbexPkg.RegFile.FF,
      iCache: Boolean = false,
      iCacheECC: Boolean = false,
      iCacheScramble: Boolean = false,
      iCacheTweakInfection: Boolean = false,
      secureIbex: Boolean = false,
      memECC: Boolean = false,
      lockstepOffset: Int = 1,
      rndCnstLfsrSeed: BigInt = 0xac533bf4L,
      rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16),
      rndCnstIbexKey: BigInt = IbexPkg.RndCnstIbexKeyDefault,
      rndCnstIbexNonce: BigInt = IbexPkg.RndCnstIbexNonceDefault)
      extends Module {
    val io = IO(new Bundle {
      val instr_req_o = Output(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val data_req_o = Output(Bool())
      val data_we_o = Output(Bool())
      val data_wdata_intg_o = Output(UInt(7.W))
      val scramble_req_o = Output(Bool())
      val lockstep_cmp_en_o = Output(UInt(IbexPkg.IbexMuBiWidth.W))
      val instr_req_shadow_o = Output(Bool())
      val instr_addr_shadow_o = Output(UInt(32.W))
      val data_req_shadow_o = Output(Bool())
      val core_sleep_o = Output(Bool())
      val alert_major_internal_o = Output(Bool())
      val alert_major_bus_o = Output(Bool())
    })

    val dut = Module(new IbexTop(regFile = regFile, iCache = iCache, iCacheECC = iCacheECC, iCacheScramble = iCacheScramble, iCacheTweakInfection = iCacheTweakInfection, secureIbex = secureIbex, memECC = memECC, lockstepOffset = lockstepOffset, rndCnstLfsrSeed = rndCnstLfsrSeed, rndCnstLfsrPerm = rndCnstLfsrPerm, rndCnstIbexKey = rndCnstIbexKey, rndCnstIbexNonce = rndCnstIbexNonce))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.test_en_i := false.B
    dut.ram_cfg_icache_tag_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
    dut.ram_cfg_icache_data_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
    dut.hart_id_i := 0.U
    dut.boot_addr_i := "h00001000".U

    dut.instr_gnt_i := false.B
    dut.instr_rvalid_i := false.B
    dut.instr_rdata_i := "h00000013".U
    dut.instr_rdata_intg_i := 0.U
    dut.instr_err_i := false.B

    dut.data_gnt_i := false.B
    dut.data_rvalid_i := false.B
    dut.data_rdata_i := 0.U
    dut.data_rdata_intg_i := 0.U
    dut.data_err_i := false.B

    dut.irq_software_i := false.B
    dut.irq_timer_i := false.B
    dut.irq_external_i := false.B
    dut.irq_fast_i := 0.U
    dut.irq_nm_i := false.B

    dut.scramble_key_valid_i := iCacheScramble.B
    dut.scramble_key_i := 0.U
    dut.scramble_nonce_i := 0.U

    dut.debug_req_i := false.B
    dut.fetch_enable_i := IbexPkg.IbexMuBiOn
    dut.scan_rst_ni := true.B

    io.instr_req_o := dut.instr_req_o
    io.instr_addr_o := dut.instr_addr_o
    io.data_req_o := dut.data_req_o
    io.data_we_o := dut.data_we_o
    io.data_wdata_intg_o := dut.data_wdata_intg_o
    io.scramble_req_o := dut.scramble_req_o
    io.lockstep_cmp_en_o := dut.lockstep_cmp_en_o
    io.instr_req_shadow_o := dut.instr_req_shadow_o
    io.instr_addr_shadow_o := dut.instr_addr_shadow_o
    io.data_req_shadow_o := dut.data_req_shadow_o
    io.core_sleep_o := dut.core_sleep_o
    io.alert_major_internal_o := dut.alert_major_internal_o
    io.alert_major_bus_o := dut.alert_major_bus_o
  }

  private def checks(dut: Harness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)

    dut.clock.step()

    dut.io.instr_req_o.expect(true.B)
    dut.io.instr_addr_o.expect("h00001080".U)
    dut.io.data_req_o.expect(false.B)
    dut.io.data_we_o.expect(false.B)
    dut.io.data_wdata_intg_o.expect(0.U)
    dut.io.scramble_req_o.expect(false.B)
    dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
    dut.io.instr_req_shadow_o.expect(false.B)
    dut.io.instr_addr_shadow_o.expect(0.U)
    dut.io.data_req_shadow_o.expect(false.B)
    dut.io.core_sleep_o.expect(false.B)
    dut.io.alert_major_internal_o.expect(false.B)
    dut.io.alert_major_bus_o.expect(false.B)
  }

  private def finishIcacheInvalidation(dut: Harness): Unit = {
    dut.clock.step()
    dut.clock.step()
    for (_ <- 0 until IbexPkg.IC_NUM_LINES) {
      dut.clock.step()
    }
    var cycles = 0
    while (!dut.io.instr_req_o.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
  }

  private def encode3932(data: BigInt): BigInt = {
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

  private def intg3932(data: BigInt): BigInt = encode3932(data) >> 32

  class ProgramHarness(
      iCache: Boolean = false,
      iCacheECC: Boolean = false,
      iCacheScramble: Boolean = false,
      secureIbex: Boolean = false,
      memECC: Boolean = false,
      pmpEnable: Boolean = false,
      rv32m: Int = IbexPkg.RV32M.Fast.asUInt.litValue.toInt,
      rv32b: Int = IbexPkg.RV32B.None_.asUInt.litValue.toInt)
      extends Module {
    val io = IO(new Bundle {
      val rvfi_valid = Output(Bool())
      val rvfi_insn = Output(UInt(32.W))
      val rvfi_pc_rdata = Output(UInt(32.W))
      val rvfi_pc_wdata = Output(UInt(32.W))
      val rvfi_trap = Output(Bool())
      val alert_major_internal_o = Output(Bool())
      val alert_major_bus_o = Output(Bool())
    })

    val dut = Module(new IbexTop(
      iCache = iCache,
      iCacheECC = iCacheECC,
      iCacheScramble = iCacheScramble,
      secureIbex = secureIbex,
      memECC = memECC,
      pmpEnable = pmpEnable,
      pmpNumRegions = if (pmpEnable) 16 else 4,
      rv32m = rv32m,
      rv32b = rv32b,
      rv32zc = 3,
      branchTargetALU = true,
      writebackStage = true,
      dbgTriggerEn = secureIbex,
      mhpmCounterNum = if (secureIbex) 10 else 0,
      mhpmCounterWidth = if (secureIbex) 32 else 40))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.test_en_i := false.B
    dut.ram_cfg_icache_tag_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
    dut.ram_cfg_icache_data_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
    dut.hart_id_i := 0.U
    dut.boot_addr_i := "h00000000".U
    dut.instr_gnt_i := dut.instr_req_o
    dut.instr_rvalid_i := RegNext(dut.instr_req_o, false.B)
    dut.instr_err_i := false.B

    val lastInstrAddr = RegNext(dut.instr_addr_o, 0.U)
    val instrRdata = MuxLookup(lastInstrAddr, "h00000013".U)(Seq(
      "h00000080".U -> "h7c007073".U, // csrci cpuctrlsts, 1
      "h00000084".U -> "h4fa0006f".U, // jal x0, 0x57e
      "h00000578".U -> "h10734501".U,
      "h0000057c".U -> "h70733205".U,
      "h00000580".U -> "h45157c02".U,
      "h00000584".U -> "hc5193f9d".U))
    dut.instr_rdata_i := instrRdata
    dut.instr_rdata_intg_i := MuxLookup(instrRdata, intg3932(BigInt("00000013", 16)).U)(Seq(
      "h7c007073".U -> intg3932(BigInt("7c007073", 16)).U,
      "h4fa0006f".U -> intg3932(BigInt("4fa0006f", 16)).U,
      "h10734501".U -> intg3932(BigInt("10734501", 16)).U,
      "h70733205".U -> intg3932(BigInt("70733205", 16)).U,
      "h45157c02".U -> intg3932(BigInt("45157c02", 16)).U,
      "hc5193f9d".U -> intg3932(BigInt("c5193f9d", 16)).U))

    dut.data_gnt_i := dut.data_req_o
    dut.data_rvalid_i := RegNext(dut.data_req_o, false.B)
    dut.data_rdata_i := 0.U
    dut.data_rdata_intg_i := intg3932(0).U
    dut.data_err_i := false.B

    dut.irq_software_i := false.B
    dut.irq_timer_i := false.B
    dut.irq_external_i := false.B
    dut.irq_fast_i := 0.U
    dut.irq_nm_i := false.B
    dut.scramble_key_valid_i := iCacheScramble.B
    dut.scramble_key_i := 0.U
    dut.scramble_nonce_i := 0.U
    dut.debug_req_i := false.B
    dut.fetch_enable_i := IbexPkg.IbexMuBiOn
    dut.scan_rst_ni := true.B

    io.rvfi_valid := dut.rvfi_valid
    io.rvfi_insn := dut.rvfi_insn
    io.rvfi_pc_rdata := dut.rvfi_pc_rdata
    io.rvfi_pc_wdata := dut.rvfi_pc_wdata
    io.rvfi_trap := dut.rvfi_trap
    io.alert_major_internal_o := dut.alert_major_internal_o
    io.alert_major_bus_o := dut.alert_major_bus_o
  }

  private def collectRvfi(dut: ProgramHarness, cycles: Int): Seq[(BigInt, BigInt, BigInt, Boolean)] = {
    val seen = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt, Boolean)]
    for (_ <- 0 until cycles) {
      if (dut.io.rvfi_valid.peek().litToBoolean) {
        seen += ((dut.io.rvfi_pc_rdata.peek().litValue, dut.io.rvfi_insn.peek().litValue,
          dut.io.rvfi_pc_wdata.peek().litValue, dut.io.rvfi_trap.peek().litToBoolean))
      }
      dut.clock.step()
    }
    seen.toSeq
  }

  "IbexTop" - {
    "elaborates to SystemVerilog with the expected top-level modules" in {
      val verilog = ChiselStage.emitSystemVerilog(new IbexTop())
      verilog must include("module IbexTop")
      verilog must include("module IbexCore")
      verilog must include("module IbexCsRegisters")
      verilog must include("module IbexIfStage")
      verilog must include("CrashDumpCurrentPCConn")
      verilog must include("CrashDumpExceptionAddrConn")
    }

    "elaborates the SecureIbex path with the dummy writeback assertion" in {
      val verilog = ChiselStage.emitSystemVerilog(new IbexTop(secureIbex = true))
      verilog must include("WaddrAZeroForDummyInstr")
    }

    "elaborates the base top with the FF register file" in {
      simulate(new Harness(IbexPkg.RegFile.FF))(checks)
    }

    "elaborates the base top with the FPGA register file" in {
      simulate(new Harness(IbexPkg.RegFile.FPGA))(checks)
    }

    "elaborates the base top with the latch register file generator" in {
      simulate(new Harness(IbexPkg.RegFile.Latch))(checks)
    }

    "elaborates the base top with ICache RAM banks" in {
      simulate(new Harness(iCache = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.scramble_req_o.expect(false.B)
        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the base top with ICacheECC RAM banks" in {
      simulate(new Harness(iCache = true, iCacheECC = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.scramble_req_o.expect(false.B)
        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the base top with ICacheScramble RAM banks" in {
      simulate(new Harness(iCache = true, iCacheScramble = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.scramble_req_o.expect(false.B)
        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the base top with ICacheTweakInfection RAM banks" in {
      simulate(new Harness(iCache = true, iCacheTweakInfection = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.scramble_req_o.expect(false.B)
        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "executes a cached unaligned 32-bit instruction before a compressed instruction" in {
      simulate(new ProgramHarness(iCache = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        val retired = collectRvfi(dut, 300)
        retired must contain ((BigInt("57e", 16), BigInt("7c027073", 16), BigInt("582", 16), false))
        retired must contain ((BigInt("582", 16), BigInt("00004515", 16), BigInt("584", 16), false))
        retired.exists(_._2 == BigInt("3f9d7073", 16)) mustBe false
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "executes the cached unaligned sequence in an OpenTitan-like configuration" in {
      simulate(new ProgramHarness(
        iCache = true,
        iCacheECC = true,
        iCacheScramble = true,
        secureIbex = true,
        memECC = true,
        pmpEnable = true,
        rv32m = IbexPkg.RV32M.SingleCycle.asUInt.litValue.toInt,
        rv32b = IbexPkg.RV32B.OTEarlGrey.asUInt.litValue.toInt)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        val retired = collectRvfi(dut, 360)
        retired must contain ((BigInt("57e", 16), BigInt("7c027073", 16), BigInt("582", 16), false))
        retired must contain ((BigInt("582", 16), BigInt("00004515", 16), BigInt("584", 16), false))
        retired.exists(_._2 == BigInt("3f9d7073", 16)) mustBe false
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the SecureIbex lockstep path" in {
      simulate(new Harness(secureIbex = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.clock.step(3)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.instr_req_shadow_o.expect(true.B)
        dut.io.instr_addr_shadow_o.expect("h00001080".U)
        dut.io.data_req_shadow_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the SecureIbex lockstep path with LockstepOffset=2" in {
      simulate(new Harness(secureIbex = true, lockstepOffset = 2)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.clock.step(5)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.instr_req_shadow_o.expect(true.B)
        dut.io.instr_addr_shadow_o.expect("h00001080".U)
        dut.io.data_req_shadow_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the MemECC data path" in {
      simulate(new Harness(memECC = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.data_req_o.expect(false.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the SecureIbex lockstep path with MemECC enabled" in {
      simulate(new Harness(secureIbex = true, memECC = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.clock.step(3)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.instr_req_shadow_o.expect(true.B)
        dut.io.instr_addr_shadow_o.expect("h00001080".U)
        dut.io.data_req_shadow_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates the SecureIbex path with custom dummy-instruction LFSR constants" in {
      simulate(new Harness(
        secureIbex = true,
        rndCnstLfsrSeed = 0x12345678L,
        rndCnstLfsrPerm = BigInt("00112233445566778899aabbccddeeff00112233", 16))) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.clock.step(3)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }

    "elaborates ICacheScramble with custom default scramble key and nonce" in {
      simulate(new Harness(
        iCache = true,
        iCacheScramble = true,
        rndCnstIbexKey = BigInt("00112233445566778899aabbccddeeff", 16),
        rndCnstIbexNonce = BigInt("0123456789abcdef", 16))) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        finishIcacheInvalidation(dut)

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001080".U)
        dut.io.scramble_req_o.expect(false.B)
        dut.io.alert_major_bus_o.expect(false.B)
      }
    }
  }
}
