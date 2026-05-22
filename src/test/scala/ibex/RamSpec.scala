package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RamSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Ram1PHarness(depth: Int = 8, memInitFile: String = "") extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val req_i = Input(Bool())
      val we_i = Input(Bool())
      val be_i = Input(UInt(4.W))
      val addr_i = Input(UInt(32.W))
      val wdata_i = Input(UInt(32.W))
      val rvalid_o = Output(Bool())
      val rdata_o = Output(UInt(32.W))
    })

    val dut = Module(new Ram1P(depth, memInitFile))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.req_i := io.req_i
    dut.we_i := io.we_i
    dut.be_i := io.be_i
    dut.addr_i := io.addr_i
    dut.wdata_i := io.wdata_i
    io.rvalid_o := dut.rvalid_o
    io.rdata_o := dut.rdata_o
  }

  class Ram2PHarness(depth: Int = 8, bExtraDelay: Int = 0) extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val a_req_i = Input(Bool())
      val a_we_i = Input(Bool())
      val a_be_i = Input(UInt(4.W))
      val a_addr_i = Input(UInt(32.W))
      val a_wdata_i = Input(UInt(32.W))
      val a_rvalid_o = Output(Bool())
      val a_rdata_o = Output(UInt(32.W))
      val b_req_i = Input(Bool())
      val b_we_i = Input(Bool())
      val b_be_i = Input(UInt(4.W))
      val b_addr_i = Input(UInt(32.W))
      val b_wdata_i = Input(UInt(32.W))
      val b_rvalid_o = Output(Bool())
      val b_rdata_o = Output(UInt(32.W))
    })

    val dut = Module(new Ram2P(depth, bExtraDelay))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.a_req_i := io.a_req_i
    dut.a_we_i := io.a_we_i
    dut.a_be_i := io.a_be_i
    dut.a_addr_i := io.a_addr_i
    dut.a_wdata_i := io.a_wdata_i
    io.a_rvalid_o := dut.a_rvalid_o
    io.a_rdata_o := dut.a_rdata_o
    dut.b_req_i := io.b_req_i
    dut.b_we_i := io.b_we_i
    dut.b_be_i := io.b_be_i
    dut.b_addr_i := io.b_addr_i
    dut.b_wdata_i := io.b_wdata_i
    io.b_rvalid_o := dut.b_rvalid_o
    io.b_rdata_o := dut.b_rdata_o
  }

  private def reset1p(dut: Ram1PHarness): Unit = {
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

  private def reset2p(dut: Ram2PHarness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.a_req_i.poke(false.B)
    dut.io.a_we_i.poke(false.B)
    dut.io.a_be_i.poke(0.U)
    dut.io.a_addr_i.poke(0.U)
    dut.io.a_wdata_i.poke(0.U)
    dut.io.b_req_i.poke(false.B)
    dut.io.b_we_i.poke(false.B)
    dut.io.b_be_i.poke(0.U)
    dut.io.b_addr_i.poke(0.U)
    dut.io.b_wdata_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def writeTempVmem(lines: Seq[String]): String = {
    val file = Files.createTempFile("ram-init", ".vmem")
    Files.writeString(file, lines.mkString("", "\n", "\n"), StandardCharsets.US_ASCII)
    file.toAbsolutePath.toString
  }

  "Ram1P" - {
    "returns rvalid one cycle after request and honors byte enables" in {
      simulate(new Ram1PHarness()) { dut =>
        reset1p(dut)
        dut.io.req_i.poke(true.B)
        dut.io.we_i.poke(true.B)
        dut.io.be_i.poke("b1111".U)
        dut.io.addr_i.poke(4.U)
        dut.io.wdata_i.poke("h11223344".U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)

        dut.io.we_i.poke(false.B)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("h11223344".U)

        dut.io.we_i.poke(true.B)
        dut.io.be_i.poke("b0101".U)
        dut.io.wdata_i.poke("haa55aa55".U)
        dut.clock.step()
        dut.io.we_i.poke(false.B)
        dut.clock.step()
        dut.io.rdata_o.expect("h11553355".U)
      }
    }

    "holds the previous read data across write requests" in {
      simulate(new Ram1PHarness()) { dut =>
        reset1p(dut)
        dut.io.req_i.poke(true.B)
        dut.io.we_i.poke(true.B)
        dut.io.be_i.poke("b1111".U)
        dut.io.addr_i.poke(4.U)
        dut.io.wdata_i.poke("h11223344".U)
        dut.clock.step()

        dut.io.we_i.poke(false.B)
        dut.clock.step()
        dut.io.rdata_o.expect("h11223344".U)

        dut.io.we_i.poke(true.B)
        dut.io.addr_i.poke(8.U)
        dut.io.wdata_i.poke("haabbccdd".U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("h11223344".U)

        dut.io.we_i.poke(false.B)
        dut.clock.step()
        dut.io.rdata_o.expect("haabbccdd".U)
      }
    }

    "loads initial contents from MemInitFile" in {
      val initFile = writeTempVmem(Seq("11223344", "aabbccdd"))
      simulate(new Ram1PHarness(depth = 4, memInitFile = initFile)) { dut =>
        dut.io.rst_ni.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.we_i.poke(false.B)
        dut.io.be_i.poke(0.U)
        dut.io.addr_i.poke(0.U)
        dut.io.wdata_i.poke(0.U)
        dut.clock.step()
        dut.io.rdata_o.expect("h11223344".U)
      }
    }

    "honors MemInitFile address markers" in {
      val initFile = writeTempVmem(Seq("@00000002", "11223344"))
      simulate(new Ram1PHarness(depth = 4, memInitFile = initFile)) { dut =>
        dut.io.rst_ni.poke(true.B)
        dut.io.req_i.poke(true.B)
        dut.io.we_i.poke(false.B)
        dut.io.be_i.poke(0.U)
        dut.io.addr_i.poke(0.U)
        dut.io.wdata_i.poke(0.U)
        dut.clock.step()
        dut.io.rdata_o.expect(0.U)

        dut.io.addr_i.poke(8.U)
        dut.clock.step()
        dut.io.rdata_o.expect("h11223344".U)
      }
    }
  }

  "Ram2P" - {
    "supports independent A/B requests with one-cycle valid for zero extra delay" in {
      simulate(new Ram2PHarness()) { dut =>
        reset2p(dut)
        dut.io.a_req_i.poke(true.B)
        dut.io.a_we_i.poke(true.B)
        dut.io.a_be_i.poke("b1111".U)
        dut.io.a_addr_i.poke(8.U)
        dut.io.a_wdata_i.poke("hfeedc0de".U)
        dut.clock.step()
        dut.io.a_rvalid_o.expect(true.B)

        dut.io.a_we_i.poke(false.B)
        dut.io.b_req_i.poke(true.B)
        dut.io.b_we_i.poke(false.B)
        dut.io.b_addr_i.poke(8.U)
        dut.clock.step()
        dut.io.b_rvalid_o.expect(true.B)
        dut.io.b_rdata_o.expect("hfeedc0de".U)
      }
    }

    "delays B-side responses by BExtraDelay cycles" in {
      simulate(new Ram2PHarness(bExtraDelay = 2)) { dut =>
        reset2p(dut)
        dut.io.b_req_i.poke(true.B)
        dut.io.b_we_i.poke(false.B)
        dut.io.b_addr_i.poke(0.U)
        dut.clock.step()
        dut.io.b_rvalid_o.expect(false.B)
        dut.io.b_req_i.poke(false.B)
        dut.clock.step()
        dut.io.b_rvalid_o.expect(true.B)
      }
    }

    "holds each port's previous read data across write requests" in {
      simulate(new Ram2PHarness()) { dut =>
        reset2p(dut)
        dut.io.a_req_i.poke(true.B)
        dut.io.a_we_i.poke(true.B)
        dut.io.a_be_i.poke("b1111".U)
        dut.io.a_addr_i.poke(4.U)
        dut.io.a_wdata_i.poke("h11223344".U)
        dut.clock.step()

        dut.io.a_we_i.poke(false.B)
        dut.clock.step()
        dut.io.a_rdata_o.expect("h11223344".U)

        dut.io.a_we_i.poke(true.B)
        dut.io.a_addr_i.poke(8.U)
        dut.io.a_wdata_i.poke("haabbccdd".U)
        dut.clock.step()
        dut.io.a_rvalid_o.expect(true.B)
        dut.io.a_rdata_o.expect("h11223344".U)

        dut.io.a_req_i.poke(false.B)
        dut.io.a_we_i.poke(false.B)
        dut.io.b_req_i.poke(true.B)
        dut.io.b_we_i.poke(false.B)
        dut.io.b_addr_i.poke(8.U)
        dut.clock.step()
        dut.io.b_rdata_o.expect("haabbccdd".U)

        dut.io.b_we_i.poke(true.B)
        dut.io.b_addr_i.poke(12.U)
        dut.io.b_be_i.poke("b1111".U)
        dut.io.b_wdata_i.poke("hcafef00d".U)
        dut.clock.step()
        dut.io.b_rvalid_o.expect(true.B)
        dut.io.b_rdata_o.expect("haabbccdd".U)

        dut.io.b_we_i.poke(false.B)
        dut.clock.step()
        dut.io.b_rdata_o.expect("hcafef00d".U)
      }
    }
  }
}
