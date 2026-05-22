package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexIcacheSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val EccInvalidTag = BigInt("0a800000", 16)

  class Harness(iCacheECC: Boolean = false, branchCache: Boolean = false, tweakInfection: Boolean = false) extends Module {
    private val busSizeECC = if (iCacheECC) IbexPkg.BUS_SIZE + IbexPkg.IC_DATA_ECC_SIZE else IbexPkg.BUS_SIZE
    private val tagSizeECC = if (iCacheECC) IbexPkg.IC_TAG_SIZE + IbexPkg.IC_TAG_ECC_SIZE else IbexPkg.IC_TAG_SIZE
    private val lineSizeECC = busSizeECC * IbexPkg.IC_LINE_BEATS

    val io = IO(new Bundle {
      val req_i = Input(Bool())
      val branch_i = Input(Bool())
      val addr_i = Input(UInt(32.W))
      val ready_i = Input(Bool())
      val instr_gnt_i = Input(Bool())
      val instr_rdata_i = Input(UInt(32.W))
      val instr_err_i = Input(Bool())
      val instr_rvalid_i = Input(Bool())
      val ic_scr_key_valid_i = Input(Bool())
      val icache_enable_i = Input(Bool())
      val icache_inval_i = Input(Bool())

      val valid_o = Output(Bool())
      val rdata_o = Output(UInt(32.W))
      val addr_o = Output(UInt(32.W))
      val err_o = Output(Bool())
      val err_plus2_o = Output(Bool())
      val instr_req_o = Output(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val ic_tag_req_o = Output(UInt(IbexPkg.IC_NUM_WAYS.W))
      val ic_tag_write_o = Output(Bool())
      val ic_tag_addr_o = Output(UInt(IbexPkg.IC_INDEX_W.W))
      val ic_tag_wdata_o = Output(UInt(tagSizeECC.W))
      val corrupt_tag_i = Input(Bool())
      val ic_data_req_o = Output(UInt(IbexPkg.IC_NUM_WAYS.W))
      val ic_data_write_o = Output(Bool())
      val ic_data_addr_o = Output(UInt(IbexPkg.IC_INDEX_W.W))
      val ic_data_wdata_o = Output(UInt(lineSizeECC.W))
      val corrupt_data_i = Input(Bool())
      val ic_scr_key_req_o = Output(Bool())
      val busy_o = Output(Bool())
      val ecc_error_o = Output(Bool())
    })

    val dut = Module(new IbexIcache(
      iCacheECC = iCacheECC,
      resetAll = true,
      busSizeECC = busSizeECC,
      tagSizeECC = tagSizeECC,
      lineSizeECC = lineSizeECC,
      branchCache = branchCache,
      tweakInfection = tweakInfection))
    val tagRam = RegInit(VecInit(Seq.fill(IbexPkg.IC_NUM_WAYS)(
      VecInit(Seq.fill(IbexPkg.IC_NUM_LINES)(0.U(tagSizeECC.W))))))
    val dataRam = RegInit(VecInit(Seq.fill(IbexPkg.IC_NUM_WAYS)(
      VecInit(Seq.fill(IbexPkg.IC_NUM_LINES)(0.U(lineSizeECC.W))))))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.req_i := io.req_i
    dut.branch_i := io.branch_i
    dut.addr_i := io.addr_i
    dut.ready_i := io.ready_i
    dut.instr_gnt_i := io.instr_gnt_i
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_err_i := io.instr_err_i
    dut.instr_rvalid_i := io.instr_rvalid_i
    for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
      dut.ic_tag_rdata_i(way) := tagRam(way)(dut.ic_tag_addr_o)
      dut.ic_data_rdata_i(way) := dataRam(way)(dut.ic_data_addr_o)
    }
    dut.ic_scr_key_valid_i := io.ic_scr_key_valid_i
    dut.icache_enable_i := io.icache_enable_i
    dut.icache_inval_i := io.icache_inval_i

    for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
      when(dut.ic_tag_write_o && dut.ic_tag_req_o(way)) {
        tagRam(way)(dut.ic_tag_addr_o) := dut.ic_tag_wdata_o
      }
    }
    when(io.corrupt_tag_i) {
      tagRam(0)(0) := tagRam(0)(0) ^ 3.U
    }
    for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
      when(dut.ic_data_write_o && dut.ic_data_req_o(way)) {
        dataRam(way)(dut.ic_data_addr_o) := dut.ic_data_wdata_o
      }
    }
    when(io.corrupt_data_i) {
      dataRam(0)(0) := dataRam(0)(0) ^ 3.U
    }

    io.valid_o := dut.valid_o
    io.rdata_o := dut.rdata_o
    io.addr_o := dut.addr_o
    io.err_o := dut.err_o
    io.err_plus2_o := dut.err_plus2_o
    io.instr_req_o := dut.instr_req_o
    io.instr_addr_o := dut.instr_addr_o
    io.ic_tag_req_o := dut.ic_tag_req_o
    io.ic_tag_write_o := dut.ic_tag_write_o
    io.ic_tag_addr_o := dut.ic_tag_addr_o
    io.ic_tag_wdata_o := dut.ic_tag_wdata_o
    io.ic_data_req_o := dut.ic_data_req_o
    io.ic_data_write_o := dut.ic_data_write_o
    io.ic_data_addr_o := dut.ic_data_addr_o
    io.ic_data_wdata_o := dut.ic_data_wdata_o
    io.ic_scr_key_req_o := dut.ic_scr_key_req_o
    io.busy_o := dut.busy_o
    io.ecc_error_o := dut.ecc_error_o
  }

  private def init(dut: Harness): Unit = {
    dut.io.req_i.poke(false.B)
    dut.io.branch_i.poke(false.B)
    dut.io.addr_i.poke(0.U)
    dut.io.ready_i.poke(true.B)
    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rdata_i.poke(0.U)
    dut.io.instr_err_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(false.B)
    dut.io.ic_scr_key_valid_i.poke(true.B)
    dut.io.icache_enable_i.poke(false.B)
    dut.io.icache_inval_i.poke(false.B)
    dut.io.corrupt_tag_i.poke(false.B)
    dut.io.corrupt_data_i.poke(false.B)
  }

  private def resetInit(dut: Harness): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    init(dut)
  }

  private def finishResetInvalidation(dut: Harness, expectedTagWdata: Int => BigInt = _ => BigInt(0)): Unit = {
    resetInit(dut)
    dut.clock.step()
    dut.clock.step()
    for (idx <- 0 until IbexPkg.IC_NUM_LINES) {
      dut.io.ic_tag_req_o.expect(((1 << IbexPkg.IC_NUM_WAYS) - 1).U)
      dut.io.ic_tag_write_o.expect(true.B)
      dut.io.ic_tag_addr_o.expect(idx.U)
      dut.io.ic_tag_wdata_o.expect(expectedTagWdata(idx).U)
      dut.clock.step()
    }
    dut.io.busy_o.expect(false.B)
  }

  private def fillCacheLine(
      dut: Harness,
      addr: BigInt,
      word0: BigInt,
      word1: BigInt,
      expectedWay: Int): Unit = {
    dut.io.icache_enable_i.poke(true.B)
    dut.io.req_i.poke(true.B)
    dut.io.branch_i.poke(true.B)
    dut.io.addr_i.poke(addr.U)
    dut.clock.step()

    dut.io.req_i.poke(false.B)
    dut.io.branch_i.poke(false.B)
    dut.clock.step()

    dut.io.instr_gnt_i.poke(true.B)
    dut.io.instr_req_o.expect(true.B)
    dut.io.instr_addr_o.expect(addr.U)
    dut.clock.step()

    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(true.B)
    dut.io.instr_rdata_i.poke(word0.U)
    dut.io.valid_o.expect(true.B)
    dut.io.rdata_o.expect(word0.U)
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
    dut.io.valid_o.expect(true.B)
    dut.io.rdata_o.expect(word1.U)
    dut.clock.step()

    dut.io.instr_rvalid_i.poke(false.B)
    dut.io.ic_tag_write_o.expect(true.B)
    dut.io.ic_tag_req_o.expect(expectedWay.U)
    dut.io.ic_data_write_o.expect(true.B)
    dut.io.ic_data_req_o.expect(expectedWay.U)
    dut.clock.step()
  }

  private def fillCacheLineWithoutOutputChecks(
      dut: Harness,
      addr: BigInt,
      word0: BigInt,
      word1: BigInt): Unit = {
    dut.io.ready_i.poke(false.B)
    dut.io.icache_enable_i.poke(true.B)
    dut.io.req_i.poke(true.B)
    dut.io.branch_i.poke(true.B)
    dut.io.addr_i.poke(addr.U)
    dut.clock.step()

    dut.io.req_i.poke(false.B)
    dut.io.branch_i.poke(false.B)
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
    dut.io.ic_tag_write_o.expect(true.B)
    dut.io.ic_tag_req_o.peek().litValue must not be BigInt(0)
    dut.io.ic_data_write_o.expect(true.B)
    dut.io.ic_data_req_o.peek().litValue must not be BigInt(0)
    dut.clock.step()

    dut.io.branch_i.poke(true.B)
    dut.io.addr_i.poke((addr + 8).U)
    dut.clock.step()
    dut.io.branch_i.poke(false.B)
    dut.io.ready_i.poke(true.B)
  }

  private def stepUntilValid(dut: Harness, maxCycles: Int = 8): Unit = {
    var cycles = 0
    while (!dut.io.valid_o.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    dut.io.valid_o.peek().litToBoolean mustBe true
  }

  private def appendValid(
      dut: Harness,
      seen: collection.mutable.ArrayBuffer[(BigInt, BigInt)]): Unit = {
    if (dut.io.valid_o.peek().litToBoolean) {
      seen += ((dut.io.addr_o.peek().litValue, dut.io.rdata_o.peek().litValue))
    }
  }

  private def driveIcacheMemoryCycle(
      dut: Harness,
      mem: Map[BigInt, BigInt],
      seen: collection.mutable.ArrayBuffer[(BigInt, BigInt)]): Unit = {
    appendValid(dut, seen)
    if (dut.io.instr_req_o.peek().litToBoolean) {
      val addr = dut.io.instr_addr_o.peek().litValue
      dut.io.instr_gnt_i.poke(true.B)
      dut.clock.step()

      dut.io.instr_gnt_i.poke(false.B)
      dut.io.instr_rvalid_i.poke(true.B)
      dut.io.instr_rdata_i.poke(mem.getOrElse(addr, BigInt("00000013", 16)).U)
      appendValid(dut, seen)
      dut.clock.step()

      dut.io.instr_rvalid_i.poke(false.B)
      dut.io.instr_rdata_i.poke(0.U)
    } else {
      dut.clock.step()
    }
  }

  "IbexIcache" - {
    "invalidates all tag entries after reset" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)
        dut.io.ic_tag_write_o.expect(false.B)
        dut.io.ic_data_req_o.expect(0.U)
        dut.io.ecc_error_o.expect(false.B)
      }
    }

    "requests an icache scramble key before invalidation starts when the key is missing" in {
      simulate(new Harness(tweakInfection = false)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.ic_scr_key_valid_i.poke(false.B)
        dut.io.ic_scr_key_req_o.expect(true.B)
        dut.io.busy_o.expect(true.B)

        dut.clock.step()
        dut.io.ic_scr_key_valid_i.poke(true.B)
        dut.io.ic_scr_key_req_o.expect(false.B)
        dut.clock.step()
        dut.io.ic_tag_write_o.expect(true.B)
      }
    }

    "restarts invalidation from index zero when invalidated during tag writes" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.clock.step()
        dut.clock.step()
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(0.U)
        dut.clock.step()
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(1.U)

        dut.io.ic_scr_key_valid_i.poke(false.B)
        dut.io.icache_inval_i.poke(true.B)
        dut.io.ic_scr_key_req_o.expect(true.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.clock.step()

        dut.io.icache_inval_i.poke(false.B)
        dut.io.ic_scr_key_req_o.expect(false.B)
        dut.io.ic_tag_write_o.expect(false.B)
        dut.io.busy_o.expect(true.B)
        dut.clock.step()

        dut.io.ic_scr_key_valid_i.poke(true.B)
        dut.clock.step()
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(0.U)
      }
    }

    "forwards cache-disabled branch fetches to the instruction bus" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001002".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00010013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00001002".U)
        dut.io.rdata_o.expect("h00130001".U)
        dut.io.err_o.expect(false.B)
        dut.io.err_plus2_o.expect(false.B)
      }
    }

    "reports err_plus2 for the second half of an unaligned uncompressed fetch" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001002".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00b70013".U)
        dut.io.valid_o.expect(false.B)
        dut.io.err_o.expect(false.B)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001004".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_err_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00008067".U)
        dut.io.valid_o.expect(true.B)
        dut.io.err_o.expect(true.B)
        dut.io.err_plus2_o.expect(true.B)
        dut.io.rdata_o.expect("h806700b7".U)
      }
    }

    "replays a cache-disabled response when the core is not ready" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001800".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001800".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00b707b3".U)
        dut.io.ready_i.poke(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00001800".U)
        dut.io.err_o.expect(false.B)
        dut.io.err_plus2_o.expect(false.B)
        dut.io.rdata_o.expect("h00b707b3".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_rdata_i.poke("hdeadbeef".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00001800".U)
        dut.io.rdata_o.expect("h00b707b3".U)

        dut.io.ready_i.poke(true.B)
        dut.clock.step()
        dut.io.valid_o.expect(false.B)
      }
    }

    "squashes a cache-disabled response on branch redirect" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001800".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00b707b3".U)
        dut.io.ready_i.poke(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00001800".U)
        dut.io.rdata_o.expect("h00b707b3".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001a00".U)
        dut.io.valid_o.expect(false.B)
        dut.io.addr_o.expect("h00001800".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.ready_i.poke(true.B)
        dut.io.valid_o.expect(false.B)
        dut.io.addr_o.expect("h00001a00".U)
      }
    }

    "discards a cache-disabled response that arrives after a branch redirect" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001800".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001800".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00001a00".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00b707b3".U)
        dut.io.valid_o.expect(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00001a00".U)
        dut.clock.step()

        dut.clock.step()
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00c707b3".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00001a00".U)
        dut.io.rdata_o.expect("h00c707b3".U)
      }
    }

    "uses the skid halfword for an unaligned 32-bit instruction" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00002002".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00018013".U)
        dut.io.ready_i.poke(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h80130001".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ready_i.poke(true.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00002002".U)
        dut.io.rdata_o.expect("h80130001".U)
      }
    }

    "does not skip cache-disabled bypass words while streaming compressed instructions" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003004".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h82068186".U)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003004".U)
        dut.io.rdata_o.expect("h82068186".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003008".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003006".U)
        dut.io.rdata_o.expect("h00008206".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h83068286".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003008".U)
        dut.io.rdata_o.expect("h83068286".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h0000300a".U)
        dut.io.rdata_o.expect("h00008306".U)

        dut.clock.step()
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h84068386".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h0000300c".U)
        dut.io.rdata_o.expect("h84068386".U)
      }
    }

    "continues cache-disabled fetches after an aligned 32-bit instruction" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00005000".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00005000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h32051073".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00005000".U)
        dut.io.rdata_o.expect("h32051073".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.valid_o.expect(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00005004".U)
      }
    }

    "continues cache-disabled fetches through consecutive unaligned 32-bit instructions" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h000004de".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h000004dc".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h28238f3d".U)
        dut.io.valid_o.expect(false.B)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h000004e0".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h179318e6".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h000004de".U)
        dut.io.rdata_o.expect("h18e62823".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.valid_o.expect(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h000004e4".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h830501f7".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h000004e2".U)
        dut.io.rdata_o.expect("h01f71793".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h000004e6".U)
        dut.io.rdata_o.expect("h00008305".U)
      }
    }

    "fills way zero on a cache miss and serves a later hit from RAM" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00100093".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(0.U)
        dut.io.ic_tag_wdata_o.expect("h200006".U)
        dut.io.ic_data_write_o.expect(true.B)
        dut.io.ic_data_addr_o.expect(0.U)
        dut.io.ic_data_wdata_o.expect("h0010009300000013".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)

        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
      }
    }

    "serves the upper halfword on a direct cache hit to a compressed instruction" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        fillCacheLine(
          dut,
          addr = BigInt("3000", 16),
          word0 = BigInt("45157c02", 16),
          word1 = BigInt("c5193f9d", 16),
          expectedWay = 1)

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003002".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003002".U)
        dut.io.rdata_o.expect("h7c024515".U)
      }
    }

    "realigns after a cached unaligned 32-bit instruction before a compressed instruction" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        fillCacheLineWithoutOutputChecks(
          dut,
          addr = BigInt("578", 16),
          word0 = BigInt("10734501", 16),
          word1 = BigInt("70733205", 16))
        fillCacheLineWithoutOutputChecks(
          dut,
          addr = BigInt("580", 16),
          word0 = BigInt("45157c02", 16),
          word1 = BigInt("c5193f9d", 16))

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h0000057e".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        stepUntilValid(dut)
        dut.io.addr_o.expect("h0000057e".U)
        dut.io.rdata_o.expect("h7c027073".U)
        dut.clock.step()

        stepUntilValid(dut)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00000582".U)
        dut.io.rdata_o.expect("h3f9d4515".U)
      }
    }

    "realigns a miss-fill stream after an unaligned 32-bit instruction before a compressed instruction" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        val mem = Map(
          BigInt("570", 16) -> BigInt("00014501", 16),
          BigInt("574", 16) -> BigInt("32051073", 16),
          BigInt("578", 16) -> BigInt("10734501", 16),
          BigInt("57c", 16) -> BigInt("70733205", 16),
          BigInt("580", 16) -> BigInt("45157c02", 16),
          BigInt("584", 16) -> BigInt("c5193f9d", 16))
        val seen = collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00000572".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        for (_ <- 0 until 40) {
          driveIcacheMemoryCycle(dut, mem, seen)
        }

        seen must contain ((BigInt("57e", 16), BigInt("7c027073", 16)))
        seen.exists { case (addr, data) =>
          addr == BigInt("582", 16) && (data & BigInt("ffff", 16)) == BigInt("4515", 16)
        } mustBe true
        seen.exists { case (addr, data) =>
          addr == BigInt("582", 16) && (data & BigInt("ffff", 16)) == BigInt("7073", 16)
        } mustBe false
        seen.exists(_._2 == BigInt("3f9d7073", 16)) mustBe false
      }
    }

    "continues a miss-fill stream through consecutive unaligned 32-bit instructions" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        val mem = Map(
          BigInt("4d8", 16) -> BigInt("8fed87fd", 16),
          BigInt("4dc", 16) -> BigInt("28238f3d", 16),
          BigInt("4e0", 16) -> BigInt("179318e6", 16),
          BigInt("4e4", 16) -> BigInt("830501f7", 16),
          BigInt("4e8", 16) -> BigInt("8fed87fd", 16))
        val seen = collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h000004de".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        for (_ <- 0 until 64) {
          driveIcacheMemoryCycle(dut, mem, seen)
        }

        seen must contain ((BigInt("4de", 16), BigInt("18e62823", 16)))
        seen must contain ((BigInt("4e2", 16), BigInt("01f71793", 16)))
        seen must contain ((BigInt("4e6", 16), BigInt("87fd8305", 16)))
      }
    }

    "realigns a cache miss after a flushed aligned CSR instruction" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        val mem = Map(
          BigInt("570", 16) -> BigInt("00014501", 16),
          BigInt("574", 16) -> BigInt("32051073", 16),
          BigInt("578", 16) -> BigInt("10734501", 16),
          BigInt("57c", 16) -> BigInt("70733205", 16),
          BigInt("580", 16) -> BigInt("45157c02", 16),
          BigInt("584", 16) -> BigInt("c5193f9d", 16))
        val seen = collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00000572".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        while (!seen.exists(_._1 == BigInt("57a", 16)) && seen.length < 16) {
          driveIcacheMemoryCycle(dut, mem, seen)
        }

        seen must contain ((BigInt("57a", 16), BigInt("32051073", 16)))

        dut.io.ready_i.poke(false.B)
        for (_ <- 0 until 2) {
          driveIcacheMemoryCycle(dut, mem, seen)
        }

        dut.io.ready_i.poke(true.B)
        for (_ <- 0 until 24) {
          driveIcacheMemoryCycle(dut, mem, seen)
        }

        seen must contain ((BigInt("57e", 16), BigInt("7c027073", 16)))
        seen.exists { case (addr, data) =>
          addr == BigInt("582", 16) && (data & BigInt("ffff", 16)) == BigInt("4515", 16)
        } mustBe true
        seen.exists(_._2 == BigInt("3f9d7073", 16)) mustBe false
      }
    }

    "allocates different ways for same-index lines and hits both tags" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        fillCacheLine(dut, BigInt("3000", 16), BigInt("00000013", 16), BigInt("00100093", 16), expectedWay = 1)
        fillCacheLine(dut, BigInt("4000", 16), BigInt("00200113", 16), BigInt("00300193", 16), expectedWay = 2)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003000".U)
        dut.io.rdata_o.expect("h00000013".U)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00004000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00004000".U)
        dut.io.rdata_o.expect("h00200113".U)
      }
    }

    "keeps serving compressed words across a cached line" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h81064081".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h81064081".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h82068186".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h82068186".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_data_write_o.expect(true.B)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003000".U)
        dut.io.rdata_o.expect("h81064081".U)

        dut.clock.step()
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003002".U)
        dut.io.rdata_o.expect("h81868106".U)

        dut.clock.step()
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003004".U)
        dut.io.rdata_o.expect("h82068186".U)
      }
    }

    "keeps serving compressed words across cached line boundaries" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h81064081".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h81064081".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h82068186".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h82068186".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_data_write_o.expect(true.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003008".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003008".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h83068286".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h83068286".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h0000300c".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h84068386".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h84068386".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_data_write_o.expect(true.B)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003004".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003004".U)
        dut.io.rdata_o.expect("h82068186".U)

        dut.clock.step()
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003006".U)
        dut.io.rdata_o.expect("h81868206".U)

        dut.clock.step()
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003008".U)
        dut.io.rdata_o.expect("h83068286".U)
      }
    }

    "keeps serving compressed words across cached line boundaries with ECC and tweaks" in {
      simulate(new Harness(iCacheECC = true, tweakInfection = true)) { dut =>
        finishResetInvalidation(dut, idx =>
          EccInvalidTag ^ BigInt(idx | (idx << (IbexPkg.IC_INDEX_W + IbexPkg.IC_TAG_ECC_SIZE))))

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h81064081".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h81064081".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h82068186".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h82068186".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_data_write_o.expect(true.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003008".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003008".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h83068286".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h83068286".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h0000300c".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h84068386".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h84068386".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_data_write_o.expect(true.B)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003004".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003004".U)
        dut.io.rdata_o.expect("h82068186".U)

        dut.clock.step()
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003006".U)
        dut.io.rdata_o.expect("h81868206".U)

        dut.clock.step()
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003008".U)
        dut.io.rdata_o.expect("h83068286".U)
      }
    }

    "does not overlap cache misses while request remains asserted" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003000".U)
        dut.io.rdata_o.expect("h00000013".U)
        dut.io.instr_req_o.expect(false.B)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003004".U)
        dut.io.rdata_o.expect("h00100093".U)
      }
    }

    "requests the next cache line after streaming two uncompressed words" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003000".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.instr_gnt_i.poke(false.B)

        var sawNextLineReq = false
        for (_ <- 0 until 8) {
          if (dut.io.instr_req_o.peek().litToBoolean) {
            dut.io.instr_addr_o.expect("h00003008".U)
            sawNextLineReq = true
          }
          dut.clock.step()
        }
        sawNextLineReq mustBe true
      }
    }

    "holds the instruction memory request and address stable until grant" in {
      simulate(new Harness) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00006000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        for (_ <- 0 until 4) {
          dut.io.instr_gnt_i.poke(false.B)
          dut.io.instr_req_o.expect(true.B)
          dut.io.instr_addr_o.expect("h00006000".U)
          dut.clock.step()
        }

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00006000".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00006000".U)
        dut.io.rdata_o.expect("h00000013".U)
      }
    }

    "encodes and checks tag/data RAM contents when ICacheECC is enabled" in {
      simulate(new Harness(iCacheECC = true)) { dut =>
        finishResetInvalidation(dut, _ => EccInvalidTag)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003000".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003004".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00100093".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(0.U)
        dut.io.ic_tag_wdata_o.expect("h03e00006".U)
        dut.io.ic_data_write_o.expect(true.B)
        dut.io.ic_data_addr_o.expect(0.U)
        dut.io.ic_data_wdata_o.expect("h3b80080049fd00000013".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.req_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.ecc_error_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)

        dut.io.corrupt_data_i.poke(true.B)
        dut.clock.step()
        dut.io.corrupt_data_i.poke(false.B)

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.req_i.poke(false.B)
        dut.io.ecc_error_o.expect(true.B)
        dut.io.valid_o.expect(false.B)
      }
    }

    "does not allocate sequential misses when BranchCache is enabled" in {
      simulate(new Harness(branchCache = true)) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00004000".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.icache_enable_i.poke(false.B)
        dut.clock.step()
        dut.clock.step()

        dut.io.icache_enable_i.poke(true.B)
        dut.clock.step()
        dut.io.req_i.poke(false.B)

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00004018".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h0000401c".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00100093".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(false.B)
        dut.io.ic_data_write_o.expect(false.B)
      }
    }

    "allocates branch target misses when BranchCache is enabled" in {
      simulate(new Harness(branchCache = true)) { dut =>
        finishResetInvalidation(dut)

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00005000".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00005000".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00005004".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00100093".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(0.U)
        dut.io.ic_tag_wdata_o.expect("h20000a".U)
        dut.io.ic_data_write_o.expect(true.B)
        dut.io.ic_data_addr_o.expect(0.U)
        dut.io.ic_data_wdata_o.expect("h0010009300000013".U)
      }
    }

    "applies and removes tag/data tweaks when TweakInfection is enabled" in {
      simulate(new Harness(tweakInfection = true)) { dut =>
        finishResetInvalidation(dut, idx => BigInt(idx | (idx << IbexPkg.IC_INDEX_W)))

        dut.io.icache_enable_i.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003080".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003080".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00003084".U)
        dut.clock.step()

        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00100093".U)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00100093".U)
        dut.clock.step()

        dut.io.instr_rvalid_i.poke(false.B)
        dut.io.ic_tag_write_o.expect(true.B)
        dut.io.ic_tag_addr_o.expect(16.U)
        dut.io.ic_tag_wdata_o.expect("h201016".U)
        dut.io.ic_data_write_o.expect(true.B)
        dut.io.ic_data_addr_o.expect(16.U)
        dut.io.ic_data_wdata_o.expect("h0010301300003093".U)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00003080".U)
        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(false.B)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.req_i.poke(false.B)
        dut.io.instr_req_o.expect(false.B)
        dut.io.valid_o.expect(true.B)
        dut.io.rdata_o.expect("h00000013".U)
      }
    }
  }
}
