package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BusSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val host_req_i = Input(Vec(2, Bool()))
      val host_gnt_o = Output(Vec(2, Bool()))
      val host_addr_i = Input(Vec(2, UInt(32.W)))
      val host_we_i = Input(Vec(2, Bool()))
      val host_be_i = Input(Vec(2, UInt(4.W)))
      val host_wdata_i = Input(Vec(2, UInt(32.W)))
      val host_rvalid_o = Output(Vec(2, Bool()))
      val host_rdata_o = Output(Vec(2, UInt(32.W)))
      val host_err_o = Output(Vec(2, Bool()))
      val device_req_o = Output(Vec(2, Bool()))
      val device_addr_o = Output(Vec(2, UInt(32.W)))
      val device_we_o = Output(Vec(2, Bool()))
      val device_be_o = Output(Vec(2, UInt(4.W)))
      val device_wdata_o = Output(Vec(2, UInt(32.W)))
      val device_rvalid_i = Input(Vec(2, Bool()))
      val device_rdata_i = Input(Vec(2, UInt(32.W)))
      val device_err_i = Input(Vec(2, Bool()))
      val cfg_device_addr_base = Input(Vec(2, UInt(32.W)))
      val cfg_device_addr_mask = Input(Vec(2, UInt(32.W)))
    })

    val dut = Module(new Bus(nrDevices = 2, nrHosts = 2))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.host_req_i := io.host_req_i
    io.host_gnt_o := dut.host_gnt_o
    dut.host_addr_i := io.host_addr_i
    dut.host_we_i := io.host_we_i
    dut.host_be_i := io.host_be_i
    dut.host_wdata_i := io.host_wdata_i
    io.host_rvalid_o := dut.host_rvalid_o
    io.host_rdata_o := dut.host_rdata_o
    io.host_err_o := dut.host_err_o
    io.device_req_o := dut.device_req_o
    io.device_addr_o := dut.device_addr_o
    io.device_we_o := dut.device_we_o
    io.device_be_o := dut.device_be_o
    io.device_wdata_o := dut.device_wdata_o
    dut.device_rvalid_i := io.device_rvalid_i
    dut.device_rdata_i := io.device_rdata_i
    dut.device_err_i := io.device_err_i
    dut.cfg_device_addr_base := io.cfg_device_addr_base
    dut.cfg_device_addr_mask := io.cfg_device_addr_mask
  }

  class SingleHostHarness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val host_req_i = Input(Bool())
      val host_gnt_o = Output(Bool())
      val host_addr_i = Input(UInt(32.W))
      val host_we_i = Input(Bool())
      val host_be_i = Input(UInt(4.W))
      val host_wdata_i = Input(UInt(32.W))
      val host_rvalid_o = Output(Bool())
      val host_rdata_o = Output(UInt(32.W))
      val host_err_o = Output(Bool())
      val device_req_o = Output(Vec(3, Bool()))
      val device_addr_o = Output(Vec(3, UInt(32.W)))
      val device_we_o = Output(Vec(3, Bool()))
      val device_be_o = Output(Vec(3, UInt(4.W)))
      val device_wdata_o = Output(Vec(3, UInt(32.W)))
      val device_rvalid_i = Input(Vec(3, Bool()))
      val device_rdata_i = Input(Vec(3, UInt(32.W)))
      val device_err_i = Input(Vec(3, Bool()))
      val cfg_device_addr_base = Input(Vec(3, UInt(32.W)))
      val cfg_device_addr_mask = Input(Vec(3, UInt(32.W)))
    })

    val dut = Module(new Bus(nrDevices = 3, nrHosts = 1))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.host_req_i(0) := io.host_req_i
    io.host_gnt_o := dut.host_gnt_o(0)
    dut.host_addr_i(0) := io.host_addr_i
    dut.host_we_i(0) := io.host_we_i
    dut.host_be_i(0) := io.host_be_i
    dut.host_wdata_i(0) := io.host_wdata_i
    io.host_rvalid_o := dut.host_rvalid_o(0)
    io.host_rdata_o := dut.host_rdata_o(0)
    io.host_err_o := dut.host_err_o(0)
    io.device_req_o := dut.device_req_o
    io.device_addr_o := dut.device_addr_o
    io.device_we_o := dut.device_we_o
    io.device_be_o := dut.device_be_o
    io.device_wdata_o := dut.device_wdata_o
    dut.device_rvalid_i := io.device_rvalid_i
    dut.device_rdata_i := io.device_rdata_i
    dut.device_err_i := io.device_err_i
    dut.cfg_device_addr_base := io.cfg_device_addr_base
    dut.cfg_device_addr_mask := io.cfg_device_addr_mask
  }

  private def init(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    for (i <- 0 until 2) {
      dut.io.host_req_i(i).poke(false.B)
      dut.io.host_addr_i(i).poke(0.U)
      dut.io.host_we_i(i).poke(false.B)
      dut.io.host_be_i(i).poke(0.U)
      dut.io.host_wdata_i(i).poke(0.U)
      dut.io.device_rvalid_i(i).poke(false.B)
      dut.io.device_rdata_i(i).poke(0.U)
      dut.io.device_err_i(i).poke(false.B)
    }
    dut.io.cfg_device_addr_base(0).poke("h00000000".U)
    dut.io.cfg_device_addr_mask(0).poke("hfffff000".U)
    dut.io.cfg_device_addr_base(1).poke("h00001000".U)
    dut.io.cfg_device_addr_mask(1).poke("hfffff000".U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def initSingleHost(dut: SingleHostHarness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.host_req_i.poke(false.B)
    dut.io.host_addr_i.poke(0.U)
    dut.io.host_we_i.poke(false.B)
    dut.io.host_be_i.poke(0.U)
    dut.io.host_wdata_i.poke(0.U)
    for (i <- 0 until 3) {
      dut.io.device_rvalid_i(i).poke(false.B)
      dut.io.device_rdata_i(i).poke(0.U)
      dut.io.device_err_i(i).poke(false.B)
    }
    dut.io.cfg_device_addr_base(0).poke("h00100000".U)
    dut.io.cfg_device_addr_mask(0).poke("hfff00000".U)
    dut.io.cfg_device_addr_base(1).poke("h00020000".U)
    dut.io.cfg_device_addr_mask(1).poke("hfffffc00".U)
    dut.io.cfg_device_addr_base(2).poke("h00030000".U)
    dut.io.cfg_device_addr_mask(2).poke("hfffffc00".U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "Bus" - {
    "grants host 0 over host 1 and routes the selected request to the decoded device" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.host_req_i(0).poke(true.B)
        dut.io.host_req_i(1).poke(true.B)
        dut.io.host_addr_i(0).poke("h00001020".U)
        dut.io.host_addr_i(1).poke("h00000010".U)
        dut.io.host_we_i(0).poke(true.B)
        dut.io.host_be_i(0).poke("b1111".U)
        dut.io.host_wdata_i(0).poke("h12345678".U)

        dut.io.host_gnt_o(0).expect(true.B)
        dut.io.host_gnt_o(1).expect(false.B)
        dut.io.device_req_o(0).expect(false.B)
        dut.io.device_req_o(1).expect(true.B)
        dut.io.device_addr_o(1).expect("h00001020".U)
        dut.io.device_we_o(1).expect(true.B)
        dut.io.device_wdata_o(1).expect("h12345678".U)
      }
    }

    "returns the selected device response to the selected host one cycle later" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.host_req_i(1).poke(true.B)
        dut.io.host_addr_i(1).poke("h00001004".U)
        dut.clock.step()

        dut.io.device_rvalid_i(1).poke(true.B)
        dut.io.device_rdata_i(1).poke("hdeadbeef".U)
        dut.io.device_err_i(1).poke(false.B)
        dut.io.host_rvalid_o(1).expect(true.B)
        dut.io.host_rdata_o(1).expect("hdeadbeef".U)
        dut.io.host_err_o(1).expect(false.B)
        dut.io.host_rvalid_o(0).expect(false.B)
      }
    }

    "keeps a response routed to the original host when a new request is granted" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.host_req_i(1).poke(true.B)
        dut.io.host_addr_i(1).poke("h00001004".U)
        dut.clock.step()

        dut.io.host_req_i(1).poke(false.B)
        dut.io.host_req_i(0).poke(true.B)
        dut.io.host_addr_i(0).poke("h00000008".U)
        dut.io.device_rvalid_i(1).poke(true.B)
        dut.io.device_rdata_i(1).poke("hdeadbeef".U)

        dut.io.host_rvalid_o(1).expect(true.B)
        dut.io.host_rdata_o(1).expect("hdeadbeef".U)
        dut.io.host_rvalid_o(0).expect(false.B)
        dut.io.host_gnt_o(0).expect(true.B)
      }
    }

    "raises a decode error response for unmatched addresses" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.host_req_i(0).poke(true.B)
        dut.io.host_addr_i(0).poke("h00002000".U)
        dut.clock.step()

        dut.io.host_rvalid_o(0).expect(true.B)
        dut.io.host_err_o(0).expect(true.B)
      }
    }

    "routes a single-host simple-system style request without dynamic host indexing" in {
      simulate(new SingleHostHarness) { dut =>
        initSingleHost(dut)
        dut.io.host_req_i.poke(true.B)
        dut.io.host_addr_i.poke("h00030008".U)
        dut.io.host_we_i.poke(true.B)
        dut.io.host_be_i.poke("b0011".U)
        dut.io.host_wdata_i.poke("h0000abcd".U)

        dut.io.host_gnt_o.expect(true.B)
        dut.io.device_req_o(0).expect(false.B)
        dut.io.device_req_o(1).expect(false.B)
        dut.io.device_req_o(2).expect(true.B)
        dut.io.device_addr_o(2).expect("h00030008".U)
        dut.io.device_we_o(2).expect(true.B)
        dut.io.device_be_o(2).expect("b0011".U)
        dut.io.device_wdata_o(2).expect("h0000abcd".U)

        dut.clock.step()
        dut.io.device_rvalid_i(2).poke(true.B)
        dut.io.device_rdata_i(2).poke("h1234abcd".U)
        dut.io.host_rvalid_o.expect(true.B)
        dut.io.host_rdata_o.expect("h1234abcd".U)
        dut.io.host_err_o.expect(false.B)
      }
    }
  }
}
