package ibex

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SimulatorCtrlSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val req_i = Input(Bool())
      val we_i = Input(Bool())
      val be_i = Input(UInt(4.W))
      val addr_i = Input(UInt(32.W))
      val wdata_i = Input(UInt(32.W))
      val rvalid_o = Output(Bool())
      val rdata_o = Output(UInt(32.W))
      val halt_o = Output(Bool())
    })

    val dut = Module(new SimulatorCtrl(finishOnHalt = false))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.req_i := io.req_i
    dut.we_i := io.we_i
    dut.be_i := io.be_i
    dut.addr_i := io.addr_i
    dut.wdata_i := io.wdata_i
    io.rvalid_o := dut.rvalid_o
    io.rdata_o := dut.rdata_o
    io.halt_o := dut.halt_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.req_i.poke(false.B)
    dut.io.we_i.poke(false.B)
    dut.io.be_i.poke(0.U)
    dut.io.addr_i.poke(0.U)
    dut.io.wdata_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def write(dut: Harness, addr: Int, data: BigInt, be: Int = 0xf): Unit = {
    dut.io.req_i.poke(true.B)
    dut.io.we_i.poke(true.B)
    dut.io.be_i.poke(be.U)
    dut.io.addr_i.poke(addr.U)
    dut.io.wdata_i.poke(data.U)
    dut.clock.step()
    dut.io.req_i.poke(false.B)
    dut.io.we_i.poke(false.B)
    dut.io.be_i.poke(0.U)
    dut.io.wdata_i.poke(0.U)
  }

  "SimulatorCtrl" - {
    "responds one cycle after any request and always returns zero data" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.req_i.poke(true.B)
        dut.io.we_i.poke(false.B)
        dut.io.addr_i.poke(0.U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect(0.U)
        dut.io.halt_o.expect(false.B)
      }
    }

    "raises halt two cycles after a software halt write" in {
      simulate(new Harness) { dut =>
        reset(dut)
        write(dut, 8, 1)
        dut.io.rvalid_o.expect(true.B)
        dut.io.halt_o.expect(false.B)

        dut.clock.step()
        dut.io.halt_o.expect(true.B)
      }
    }

    "ignores halt writes when byte enable zero or bit zero is clear" in {
      simulate(new Harness) { dut =>
        reset(dut)
        write(dut, 8, 1, be = 0)
        dut.clock.step(3)
        dut.io.halt_o.expect(false.B)

        write(dut, 8, 2)
        dut.clock.step(3)
        dut.io.halt_o.expect(false.B)
      }
    }

    "emits the original simulator_ctrl visible response port and finish behavior" in {
      val verilog = ChiselStage.emitSystemVerilog(new RawModule {
        val dut = Module(new SimulatorCtrl)
        dut.clk_i := false.B.asClock
        dut.rst_ni := false.B
        dut.req_i := false.B
        dut.we_i := false.B
        dut.be_i := 0.U
        dut.addr_i := 0.U
        dut.wdata_i := 0.U
        dontTouch(dut.rvalid_o)
        dontTouch(dut.rdata_o)
        dontTouch(dut.halt_o)
      })
      verilog must include("module SimulatorCtrl")
      verilog must include("rdata_o")
      verilog must include("Terminating simulation by software request.")
      verilog must include("$finish")
    }
  }
}
