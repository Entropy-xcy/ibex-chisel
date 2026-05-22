package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class TimerSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val timer_req_i = Input(Bool())
      val timer_addr_i = Input(UInt(32.W))
      val timer_we_i = Input(Bool())
      val timer_be_i = Input(UInt(4.W))
      val timer_wdata_i = Input(UInt(32.W))
      val timer_rvalid_o = Output(Bool())
      val timer_rdata_o = Output(UInt(32.W))
      val timer_err_o = Output(Bool())
      val timer_intr_o = Output(Bool())
    })

    val dut = Module(new Timer)
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.timer_req_i := io.timer_req_i
    dut.timer_addr_i := io.timer_addr_i
    dut.timer_we_i := io.timer_we_i
    dut.timer_be_i := io.timer_be_i
    dut.timer_wdata_i := io.timer_wdata_i
    io.timer_rvalid_o := dut.timer_rvalid_o
    io.timer_rdata_o := dut.timer_rdata_o
    io.timer_err_o := dut.timer_err_o
    io.timer_intr_o := dut.timer_intr_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.timer_req_i.poke(false.B)
    dut.io.timer_addr_i.poke(0.U)
    dut.io.timer_we_i.poke(false.B)
    dut.io.timer_be_i.poke(0.U)
    dut.io.timer_wdata_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def write(dut: Harness, addr: Int, data: BigInt, be: Int = 0xf): Unit = {
    dut.io.timer_req_i.poke(true.B)
    dut.io.timer_we_i.poke(true.B)
    dut.io.timer_addr_i.poke(addr.U)
    dut.io.timer_be_i.poke(be.U)
    dut.io.timer_wdata_i.poke(data.U)
    dut.clock.step()
    dut.io.timer_req_i.poke(false.B)
    dut.io.timer_we_i.poke(false.B)
    dut.io.timer_be_i.poke(0.U)
    dut.io.timer_wdata_i.poke(0.U)
  }

  private def readReq(dut: Harness, addr: Int): Unit = {
    dut.io.timer_req_i.poke(true.B)
    dut.io.timer_we_i.poke(false.B)
    dut.io.timer_addr_i.poke(addr.U)
    dut.io.timer_be_i.poke(0.U)
    dut.clock.step()
    dut.io.timer_req_i.poke(false.B)
  }

  "Timer" - {
    "increments mtime and returns read data one cycle after request" in {
      simulate(new Harness) { dut =>
        reset(dut)
        readReq(dut, 0)
        dut.io.timer_rvalid_o.expect(true.B)
        dut.io.timer_rdata_o.expect(1.U)
        dut.io.timer_err_o.expect(false.B)
      }
    }

    "supports byte-enable writes to mtimecmp registers" in {
      simulate(new Harness) { dut =>
        reset(dut)
        write(dut, 8, BigInt("11223344", 16), be = 0xf)
        write(dut, 8, BigInt("aa55aa55", 16), be = 0x5)
        readReq(dut, 8)
        dut.io.timer_rvalid_o.expect(true.B)
        dut.io.timer_rdata_o.expect("h11553355".U)
      }
    }

    "latches timer interrupt until mtimecmp is written" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.timer_intr_o.expect(true.B)

        write(dut, 8, 100)
        dut.io.timer_intr_o.expect(false.B)
        dut.clock.step()
        dut.io.timer_intr_o.expect(false.B)
      }
    }

    "reports an error for invalid register offsets" in {
      simulate(new Harness) { dut =>
        reset(dut)
        readReq(dut, 16)
        dut.io.timer_rvalid_o.expect(true.B)
        dut.io.timer_err_o.expect(true.B)
        dut.io.timer_rdata_o.expect(0.U)
      }
    }
  }
}
