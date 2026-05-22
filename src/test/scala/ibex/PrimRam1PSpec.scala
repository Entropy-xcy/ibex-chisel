package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PrimRam1PSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(width: Int = 32, depth: Int = 8, dataBitsPerMask: Int = 8) extends Module {
    private val aw = chisel3.util.log2Ceil(depth)
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val req_i = Input(Bool())
      val write_i = Input(Bool())
      val addr_i = Input(UInt(aw.W))
      val wdata_i = Input(UInt(width.W))
      val wmask_i = Input(UInt(width.W))
      val rdata_o = Output(UInt(width.W))
      val ram_cfg_en_i = Input(Bool())
      val rf_cfg_en_i = Input(Bool())
      val cfg_done_o = Output(Bool())
    })

    val dut = Module(new PrimRam1P(width = width, depth = depth, dataBitsPerMask = dataBitsPerMask))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.req_i := io.req_i
    dut.write_i := io.write_i
    dut.addr_i := io.addr_i
    dut.wdata_i := io.wdata_i
    dut.wmask_i := io.wmask_i
    io.rdata_o := dut.rdata_o

    dut.cfg_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
    dut.cfg_i.ram_cfg.cfg_en := io.ram_cfg_en_i
    dut.cfg_i.rf_cfg.cfg_en := io.rf_cfg_en_i
    io.cfg_done_o := dut.cfg_rsp_o.done
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.req_i.poke(false.B)
    dut.io.write_i.poke(false.B)
    dut.io.addr_i.poke(0.U)
    dut.io.wdata_i.poke(0.U)
    dut.io.wmask_i.poke(0.U)
    dut.io.ram_cfg_en_i.poke(false.B)
    dut.io.rf_cfg_en_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "PrimRam1P" - {
    "returns read data one cycle after a read request" in {
      simulate(new Harness()) { dut =>
        reset(dut)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(3.U)
        dut.io.wdata_i.poke("h11223344".U)
        dut.io.wmask_i.poke("hffffffff".U)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.io.rdata_o.expect(0.U)
        dut.clock.step()
        dut.io.rdata_o.expect("h11223344".U)
      }
    }

    "merges writes according to the full-bit mask and reports config completion" in {
      simulate(new Harness(dataBitsPerMask = 8)) { dut =>
        reset(dut)
        dut.io.cfg_done_o.expect(false.B)
        dut.io.ram_cfg_en_i.poke(true.B)
        dut.io.cfg_done_o.expect(true.B)
        dut.io.ram_cfg_en_i.poke(false.B)
        dut.io.rf_cfg_en_i.poke(true.B)
        dut.io.cfg_done_o.expect(true.B)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(4.U)
        dut.io.wdata_i.poke("h11223344".U)
        dut.io.wmask_i.poke("hffffffff".U)
        dut.clock.step()

        dut.io.wdata_i.poke("haa55aa55".U)
        dut.io.wmask_i.poke("h00ff00ff".U)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.clock.step()
        dut.io.rdata_o.expect("h11553355".U)
      }
    }
  }
}
