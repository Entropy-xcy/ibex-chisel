package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexRegisterFileFFSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(
      rv32e: Boolean = false,
      dataWidth: Int = 32,
      dummyInstructions: Boolean = false,
      wordZeroVal: BigInt = 0)
      extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val test_en_i = Input(Bool())
      val dummy_instr_id_i = Input(Bool())
      val dummy_instr_wb_i = Input(Bool())
      val raddr_a_i = Input(UInt(5.W))
      val rdata_a_o = Output(UInt(dataWidth.W))
      val raddr_b_i = Input(UInt(5.W))
      val rdata_b_o = Output(UInt(dataWidth.W))
      val waddr_a_i = Input(UInt(5.W))
      val wdata_a_i = Input(UInt(dataWidth.W))
      val we_a_i = Input(Bool())
    })

    val dut = Module(new IbexRegisterFileFF(rv32e, dataWidth, dummyInstructions, wordZeroVal))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.test_en_i := io.test_en_i
    dut.dummy_instr_id_i := io.dummy_instr_id_i
    dut.dummy_instr_wb_i := io.dummy_instr_wb_i
    dut.raddr_a_i := io.raddr_a_i
    io.rdata_a_o := dut.rdata_a_o
    dut.raddr_b_i := io.raddr_b_i
    io.rdata_b_o := dut.rdata_b_o
    dut.waddr_a_i := io.waddr_a_i
    dut.wdata_a_i := io.wdata_a_i
    dut.we_a_i := io.we_a_i
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.test_en_i.poke(false.B)
    dut.io.dummy_instr_id_i.poke(false.B)
    dut.io.dummy_instr_wb_i.poke(false.B)
    dut.io.raddr_a_i.poke(0.U)
    dut.io.raddr_b_i.poke(0.U)
    dut.io.waddr_a_i.poke(0.U)
    dut.io.wdata_a_i.poke(0.U)
    dut.io.we_a_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "IbexRegisterFileFF" - {
    "keeps x0 fixed and supports two asynchronous read ports" in {
      simulate(new Harness()) { dut =>
        reset(dut)
        dut.io.raddr_a_i.poke(0.U)
        dut.io.rdata_a_o.expect(0.U)

        dut.io.we_a_i.poke(true.B)
        dut.io.waddr_a_i.poke(1.U)
        dut.io.wdata_a_i.poke("h12345678".U)
        dut.clock.step()

        dut.io.raddr_a_i.poke(1.U)
        dut.io.raddr_b_i.poke(0.U)
        dut.io.rdata_a_o.expect("h12345678".U)
        dut.io.rdata_b_o.expect(0.U)

        dut.io.waddr_a_i.poke(0.U)
        dut.io.wdata_a_i.poke("hffffffff".U)
        dut.clock.step()
        dut.io.raddr_a_i.poke(0.U)
        dut.io.rdata_a_o.expect(0.U)
      }
    }

    "implements dummy x0 storage only for dummy instruction reads" in {
      simulate(new Harness(dummyInstructions = true, wordZeroVal = 0)) { dut =>
        reset(dut)

        dut.io.we_a_i.poke(true.B)
        dut.io.dummy_instr_wb_i.poke(true.B)
        dut.io.waddr_a_i.poke(0.U)
        dut.io.wdata_a_i.poke("hcafebabe".U)
        dut.clock.step()

        dut.io.we_a_i.poke(false.B)
        dut.io.dummy_instr_wb_i.poke(false.B)
        dut.io.raddr_a_i.poke(0.U)
        dut.io.dummy_instr_id_i.poke(false.B)
        dut.io.rdata_a_o.expect(0.U)

        dut.io.dummy_instr_id_i.poke(true.B)
        dut.io.rdata_a_o.expect("hcafebabe".U)
      }
    }

    "resets every stored word and x0 to WordZeroVal" in {
      simulate(new Harness(dataWidth = 16, wordZeroVal = 0x5a5a)) { dut =>
        reset(dut)

        dut.io.raddr_a_i.poke(0.U)
        dut.io.raddr_b_i.poke(7.U)
        dut.io.rdata_a_o.expect("h5a5a".U)
        dut.io.rdata_b_o.expect("h5a5a".U)

        dut.io.we_a_i.poke(true.B)
        dut.io.waddr_a_i.poke(7.U)
        dut.io.wdata_a_i.poke("h1234".U)
        dut.clock.step()
        dut.io.rdata_b_o.expect("h1234".U)

        dut.io.waddr_a_i.poke(0.U)
        dut.io.wdata_a_i.poke("hffff".U)
        dut.clock.step()
        dut.io.rdata_a_o.expect("h5a5a".U)
      }
    }

    "writes the dummy x0 register whenever a dummy writeback is enabled" in {
      simulate(new Harness(dummyInstructions = true, wordZeroVal = 0)) { dut =>
        reset(dut)

        dut.io.we_a_i.poke(true.B)
        dut.io.dummy_instr_wb_i.poke(true.B)
        dut.io.waddr_a_i.poke(5.U)
        dut.io.wdata_a_i.poke("h13579bdf".U)
        dut.clock.step()

        dut.io.we_a_i.poke(false.B)
        dut.io.dummy_instr_wb_i.poke(false.B)
        dut.io.raddr_a_i.poke(0.U)
        dut.io.dummy_instr_id_i.poke(false.B)
        dut.io.rdata_a_o.expect(0.U)

        dut.io.dummy_instr_id_i.poke(true.B)
        dut.io.rdata_a_o.expect("h13579bdf".U)
      }
    }
  }
}
