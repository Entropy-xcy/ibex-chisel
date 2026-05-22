package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexFetchFifoSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val clear_i = Input(Bool())
      val busy_o = Output(UInt(2.W))
      val in_valid_i = Input(Bool())
      val in_addr_i = Input(UInt(32.W))
      val in_rdata_i = Input(UInt(32.W))
      val in_err_i = Input(Bool())
      val out_valid_o = Output(Bool())
      val out_ready_i = Input(Bool())
      val out_addr_o = Output(UInt(32.W))
      val out_rdata_o = Output(UInt(32.W))
      val out_err_o = Output(Bool())
      val out_err_plus2_o = Output(Bool())
    })

    val dut = Module(new IbexFetchFifo(numReqs = 2, resetAll = true))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.clear_i := io.clear_i
    io.busy_o := dut.busy_o
    dut.in_valid_i := io.in_valid_i
    dut.in_addr_i := io.in_addr_i
    dut.in_rdata_i := io.in_rdata_i
    dut.in_err_i := io.in_err_i
    io.out_valid_o := dut.out_valid_o
    dut.out_ready_i := io.out_ready_i
    io.out_addr_o := dut.out_addr_o
    io.out_rdata_o := dut.out_rdata_o
    io.out_err_o := dut.out_err_o
    io.out_err_plus2_o := dut.out_err_plus2_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.clear_i.poke(false.B)
    dut.io.in_valid_i.poke(false.B)
    dut.io.in_addr_i.poke(0.U)
    dut.io.in_rdata_i.poke(0.U)
    dut.io.in_err_i.poke(false.B)
    dut.io.out_ready_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "IbexFetchFifo" - {
    "bypasses incoming aligned data when the FIFO is empty" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000100".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h00000013".U)
        dut.io.out_valid_o.expect(true.B)
        dut.io.out_addr_o.expect("h00000100".U)
        dut.io.out_rdata_o.expect("h00000013".U)
        dut.io.out_err_o.expect(false.B)
      }
    }

    "increments the instruction address by 4 for uncompressed aligned instructions" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000200".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h00000013".U)
        dut.io.out_ready_i.poke(true.B)
        dut.clock.step()
        dut.io.out_addr_o.expect("h00000204".U)
      }
    }

    "increments by 4 for uncompressed aligned instructions when address bit 2 is set" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000204".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h00100113".U)
        dut.io.out_ready_i.poke(true.B)
        dut.clock.step()
        dut.io.out_addr_o.expect("h00000208".U)
      }
    }

    "increments the instruction address by 2 for compressed aligned instructions" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000300".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h00000001".U)
        dut.io.out_ready_i.poke(true.B)
        dut.clock.step()
        dut.io.out_addr_o.expect("h00000302".U)
      }
    }

    "assembles an unaligned 32-bit instruction from stored and incoming halfwords" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000302".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h12370000".U)
        dut.io.in_err_i.poke(false.B)
        dut.io.out_ready_i.poke(false.B)
        dut.io.out_valid_o.expect(false.B)
        dut.clock.step()

        dut.io.in_rdata_i.poke("h0000abcd".U)
        dut.io.out_valid_o.expect(true.B)
        dut.io.out_addr_o.expect("h00000302".U)
        dut.io.out_rdata_o.expect("habcd1237".U)
        dut.io.out_err_o.expect(false.B)
        dut.io.out_err_plus2_o.expect(false.B)
      }
    }

    "marks plus2 when the second half of an unaligned 32-bit instruction has an error" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000302".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h12370000".U)
        dut.io.in_err_i.poke(false.B)
        dut.io.out_ready_i.poke(false.B)
        dut.clock.step()

        dut.io.in_rdata_i.poke("h0000abcd".U)
        dut.io.in_err_i.poke(true.B)
        dut.io.out_valid_o.expect(true.B)
        dut.io.out_err_o.expect(true.B)
        dut.io.out_err_plus2_o.expect(true.B)
      }
    }

    "does not attach an incoming second-half error to a compressed unaligned instruction" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000302".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h12340000".U)
        dut.io.in_err_i.poke(false.B)
        dut.io.out_ready_i.poke(false.B)
        dut.io.out_valid_o.expect(true.B)
        dut.io.out_err_o.expect(false.B)
        dut.clock.step()

        dut.io.in_rdata_i.poke("h0000abcd".U)
        dut.io.in_err_i.poke(true.B)
        dut.io.out_valid_o.expect(true.B)
        dut.io.out_addr_o.expect("h00000302".U)
        dut.io.out_err_o.expect(false.B)
      }
    }

    "clear removes queued data and suppresses same-cycle incoming data" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.clear_i.poke(true.B)
        dut.io.in_addr_i.poke("h00000400".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)

        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("h00000013".U)
        dut.io.out_ready_i.poke(false.B)
        dut.clock.step()
        dut.io.busy_o.expect(0.U)

        dut.io.clear_i.poke(true.B)
        dut.io.in_valid_i.poke(true.B)
        dut.io.in_rdata_i.poke("hffffffff".U)
        dut.clock.step()
        dut.io.clear_i.poke(false.B)
        dut.io.in_valid_i.poke(false.B)
        dut.io.out_valid_o.expect(false.B)
        dut.io.busy_o.expect(0.U)
      }
    }
  }
}
