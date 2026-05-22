package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexCsrSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(width: Int, shadowCopy: Boolean, resetValue: BigInt) extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val wr_data_i = Input(UInt(width.W))
      val wr_en_i = Input(Bool())
      val rd_data_o = Output(UInt(width.W))
      val rd_error_o = Output(Bool())
    })

    val dut = Module(new IbexCsr(width, shadowCopy, resetValue))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.wr_data_i := io.wr_data_i
    dut.wr_en_i := io.wr_en_i
    io.rd_data_o := dut.rd_data_o
    io.rd_error_o := dut.rd_error_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.wr_en_i.poke(false.B)
    dut.io.wr_data_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "IbexCsr" - {
    "resets to ResetValue, writes when enabled, and holds otherwise" in {
      simulate(new Harness(width = 32, shadowCopy = false, resetValue = BigInt("12345678", 16))) { dut =>
        reset(dut)
        dut.io.rd_data_o.expect("h12345678".U)
        dut.io.rd_error_o.expect(false.B)

        dut.io.wr_en_i.poke(true.B)
        dut.io.wr_data_i.poke("hdeadbeef".U)
        dut.clock.step()
        dut.io.rd_data_o.expect("hdeadbeef".U)

        dut.io.wr_en_i.poke(false.B)
        dut.io.wr_data_i.poke("h00000000".U)
        dut.clock.step()
        dut.io.rd_data_o.expect("hdeadbeef".U)
      }
    }

    "keeps a complemented shadow copy and reports no error during normal operation" in {
      simulate(new Harness(width = 8, shadowCopy = true, resetValue = 0x5a)) { dut =>
        reset(dut)
        dut.io.rd_data_o.expect("h5a".U)
        dut.io.rd_error_o.expect(false.B)

        dut.io.wr_en_i.poke(true.B)
        dut.io.wr_data_i.poke("ha5".U)
        dut.clock.step()
        dut.io.rd_data_o.expect("ha5".U)
        dut.io.rd_error_o.expect(false.B)
      }
    }
  }
}
