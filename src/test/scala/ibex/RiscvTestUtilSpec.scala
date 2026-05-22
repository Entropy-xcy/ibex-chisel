package ibex

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RiscvTestUtilSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val dev_req_i = Input(Bool())
      val dev_we_i = Input(Bool())
      val dev_addr_i = Input(UInt(32.W))
      val dev_wdata_i = Input(UInt(32.W))
      val dev_be_i = Input(UInt(4.W))
      val dev_rvalid_o = Output(Bool())
      val dev_rdata_o = Output(UInt(32.W))
      val dev_err_o = Output(Bool())
      val host_req_o = Output(Bool())
      val host_gnt_i = Input(Bool())
      val host_rvalid_i = Input(Bool())
      val host_addr_o = Output(UInt(32.W))
      val host_rdata_i = Input(UInt(32.W))
    })

    val dut = Module(new RiscvTestUtil(finishOnTerminate = false))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.dev_req_i := io.dev_req_i
    dut.dev_we_i := io.dev_we_i
    dut.dev_addr_i := io.dev_addr_i
    dut.dev_wdata_i := io.dev_wdata_i
    dut.dev_be_i := io.dev_be_i
    io.dev_rvalid_o := dut.dev_rvalid_o
    io.dev_rdata_o := dut.dev_rdata_o
    io.dev_err_o := dut.dev_err_o
    io.host_req_o := dut.host_req_o
    dut.host_gnt_i := io.host_gnt_i
    dut.host_rvalid_i := io.host_rvalid_i
    io.host_addr_o := dut.host_addr_o
    dut.host_rdata_i := io.host_rdata_i
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.dev_req_i.poke(false.B)
    dut.io.dev_we_i.poke(false.B)
    dut.io.dev_addr_i.poke(0.U)
    dut.io.dev_wdata_i.poke(0.U)
    dut.io.dev_be_i.poke(0.U)
    dut.io.host_gnt_i.poke(false.B)
    dut.io.host_rvalid_i.poke(false.B)
    dut.io.host_rdata_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def writeReg(dut: Harness, addr: Int, data: BigInt, be: Int = 0xf): Unit = {
    dut.io.dev_req_i.poke(true.B)
    dut.io.dev_we_i.poke(true.B)
    dut.io.dev_addr_i.poke(addr.U)
    dut.io.dev_wdata_i.poke(data.U)
    dut.io.dev_be_i.poke(be.U)
    dut.clock.step()
    dut.io.dev_req_i.poke(false.B)
    dut.io.dev_we_i.poke(false.B)
    dut.io.dev_addr_i.poke(0.U)
    dut.io.dev_wdata_i.poke(0.U)
    dut.io.dev_be_i.poke(0.U)
  }

  "RiscvTestUtil" - {
    "responds one cycle after device accesses and rejects reads or narrow writes" in {
      simulate(new Harness) { dut =>
        reset(dut)

        dut.io.dev_req_i.poke(true.B)
        dut.io.dev_we_i.poke(true.B)
        dut.io.dev_addr_i.poke(4.U)
        dut.io.dev_be_i.poke("hf".U)
        dut.clock.step()
        dut.io.dev_req_i.poke(false.B)
        dut.io.dev_rvalid_o.expect(true.B)
        dut.io.dev_err_o.expect(false.B)
        dut.io.dev_rdata_o.expect(0.U)

        dut.io.dev_req_i.poke(true.B)
        dut.io.dev_we_i.poke(false.B)
        dut.io.dev_addr_i.poke(4.U)
        dut.io.dev_be_i.poke("hf".U)
        dut.clock.step()
        dut.io.dev_req_i.poke(false.B)
        dut.io.dev_rvalid_o.expect(true.B)
        dut.io.dev_err_o.expect(true.B)

        dut.io.dev_req_i.poke(true.B)
        dut.io.dev_we_i.poke(true.B)
        dut.io.dev_addr_i.poke(4.U)
        dut.io.dev_be_i.poke("h3".U)
        dut.clock.step()
        dut.io.dev_req_i.poke(false.B)
        dut.io.dev_rvalid_o.expect(true.B)
        dut.io.dev_err_o.expect(true.B)
      }
    }

    "reads the programmed signature range through the host port before halting" in {
      simulate(new Harness) { dut =>
        reset(dut)

        writeReg(dut, 4, BigInt("00000100", 16))
        writeReg(dut, 8, BigInt("00000108", 16))
        writeReg(dut, 0, 1)

        dut.io.host_req_o.expect(true.B)
        dut.io.host_addr_o.expect("h00000100".U)

        dut.io.host_gnt_i.poke(true.B)
        dut.clock.step()
        dut.io.host_addr_o.expect("h00000104".U)
        dut.io.host_req_o.expect(true.B)

        dut.clock.step()
        dut.io.host_gnt_i.poke(false.B)
        dut.io.host_req_o.expect(false.B)
        dut.io.host_addr_o.expect("h00000108".U)

        dut.io.host_rvalid_i.poke(true.B)
        dut.io.host_rdata_i.poke("hdeadbeef".U)
        dut.clock.step()
        dut.io.host_rvalid_i.poke(false.B)
      }
    }

    "emits the termination message before finishing" in {
      val verilog = ChiselStage.emitSystemVerilog(new RiscvTestUtil)
      val displayIndex = verilog.indexOf("$display(\"Terminating simulation by software request.\");")
      val finishIndex = verilog.indexOf("$finish;")

      verilog must include("module RiscvTestUtilTerminator")
      displayIndex must be >= 0
      finishIndex must be >= 0
      displayIndex must be < finishIndex
      verilog must not include "$fwrite(32'h80000002, \"Terminating simulation by software request.\\n\")"
    }
  }
}
