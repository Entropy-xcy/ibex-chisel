package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexRegisterFileFPGASpec extends AnyFreeSpec with Matchers with ChiselSim {
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

    val dut = Module(new IbexRegisterFileFPGA(rv32e, dataWidth, dummyInstructions, wordZeroVal))
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

  private def initInputs(dut: Harness): Unit = {
    dut.io.rst_ni.poke(true.B)
    dut.io.test_en_i.poke(false.B)
    dut.io.dummy_instr_id_i.poke(false.B)
    dut.io.dummy_instr_wb_i.poke(false.B)
    dut.io.raddr_a_i.poke(0.U)
    dut.io.raddr_b_i.poke(0.U)
    dut.io.waddr_a_i.poke(0.U)
    dut.io.wdata_a_i.poke(0.U)
    dut.io.we_a_i.poke(false.B)
  }

  "IbexRegisterFileFPGA" - {
    "initializes memory to WordZeroVal and masks reads and writes to x0" in {
      simulate(new Harness(wordZeroVal = BigInt("11111111", 16))) { dut =>
        initInputs(dut)
        dut.io.raddr_a_i.poke(1.U)
        dut.io.rdata_a_o.expect("h11111111".U)

        dut.io.we_a_i.poke(true.B)
        dut.io.waddr_a_i.poke(0.U)
        dut.io.wdata_a_i.poke("hffffffff".U)
        dut.clock.step()
        dut.io.raddr_a_i.poke(0.U)
        dut.io.rdata_a_o.expect("h11111111".U)
      }
    }

    "writes nonzero registers and ignores dummy instruction controls" in {
      simulate(new Harness(dummyInstructions = true)) { dut =>
        initInputs(dut)
        dut.io.dummy_instr_wb_i.poke(true.B)
        dut.io.we_a_i.poke(true.B)
        dut.io.waddr_a_i.poke(2.U)
        dut.io.wdata_a_i.poke("h89abcdef".U)
        dut.clock.step()

        dut.io.we_a_i.poke(false.B)
        dut.io.dummy_instr_id_i.poke(true.B)
        dut.io.raddr_a_i.poke(2.U)
        dut.io.rdata_a_o.expect("h89abcdef".U)
        dut.io.raddr_b_i.poke(0.U)
        dut.io.rdata_b_o.expect(0.U)
      }
    }

    "supports the RV32E-sized valid register set while preserving the architectural x0 mask" in {
      simulate(new Harness(rv32e = true, wordZeroVal = BigInt("a5a5a5a5", 16))) { dut =>
        initInputs(dut)

        dut.io.raddr_a_i.poke(15.U)
        dut.io.rdata_a_o.expect("ha5a5a5a5".U)

        dut.io.we_a_i.poke(true.B)
        dut.io.waddr_a_i.poke(0.U)
        dut.io.wdata_a_i.poke("h11111111".U)
        dut.clock.step()

        dut.io.raddr_a_i.poke(0.U)
        dut.io.raddr_b_i.poke(15.U)
        dut.io.rdata_a_o.expect("ha5a5a5a5".U)
        dut.io.rdata_b_o.expect("ha5a5a5a5".U)

        dut.io.waddr_a_i.poke(15.U)
        dut.io.wdata_a_i.poke("h22222222".U)
        dut.clock.step()

        dut.io.we_a_i.poke(false.B)
        dut.io.raddr_a_i.poke(15.U)
        dut.io.raddr_b_i.poke(0.U)
        dut.io.rdata_a_o.expect("h22222222".U)
        dut.io.rdata_b_o.expect("ha5a5a5a5".U)
      }
    }
  }
}
