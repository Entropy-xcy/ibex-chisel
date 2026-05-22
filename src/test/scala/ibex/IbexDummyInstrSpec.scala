package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexDummyInstrSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(
      rndCnstLfsrSeed: BigInt = 0xac533bf4L,
      rndCnstLfsrPerm: BigInt = BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16)) extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val dummy_instr_en_i = Input(Bool())
      val dummy_instr_mask_i = Input(UInt(3.W))
      val dummy_instr_seed_en_i = Input(Bool())
      val dummy_instr_seed_i = Input(UInt(32.W))
      val fetch_valid_i = Input(Bool())
      val id_in_ready_i = Input(Bool())
      val insert_dummy_instr_o = Output(Bool())
      val dummy_instr_data_o = Output(UInt(32.W))
    })

    val dut = Module(new IbexDummyInstr(
      rndCnstLfsrSeed = rndCnstLfsrSeed,
      rndCnstLfsrPerm = rndCnstLfsrPerm))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.dummy_instr_en_i := io.dummy_instr_en_i
    dut.dummy_instr_mask_i := io.dummy_instr_mask_i
    dut.dummy_instr_seed_en_i := io.dummy_instr_seed_en_i
    dut.dummy_instr_seed_i := io.dummy_instr_seed_i
    dut.fetch_valid_i := io.fetch_valid_i
    dut.id_in_ready_i := io.id_in_ready_i
    io.insert_dummy_instr_o := dut.insert_dummy_instr_o
    io.dummy_instr_data_o := dut.dummy_instr_data_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.dummy_instr_en_i.poke(false.B)
    dut.io.dummy_instr_mask_i.poke(0.U)
    dut.io.dummy_instr_seed_en_i.poke(false.B)
    dut.io.dummy_instr_seed_i.poke(0.U)
    dut.io.fetch_valid_i.poke(false.B)
    dut.io.id_in_ready_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private val identityPerm: BigInt =
    (0 until 32).foldLeft(BigInt(0)) { (acc, bit) => acc | (BigInt(bit) << (bit * 5)) }

  private def lfsrState(instrType: Int, opB: Int, opA: Int, cnt: Int = 0): BigInt =
    BigInt((instrType & 0x3) << 15) | BigInt((opB & 0x1f) << 10) |
      BigInt((opA & 0x1f) << 5) | BigInt(cnt & 0x1f)

  private def rType(set: Int, rs2: Int, rs1: Int, funct3: Int): BigInt =
    BigInt((set & 0x7f) << 25) | BigInt((rs2 & 0x1f) << 20) |
      BigInt((rs1 & 0x1f) << 15) | BigInt((funct3 & 0x7) << 12) | BigInt(0x33)

  "IbexDummyInstr" - {
    "does not request insertion while disabled" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.dummy_instr_en_i.poke(false.B)
        dut.io.insert_dummy_instr_o.expect(false.B)
      }
    }

    "encodes a deterministic ADD dummy instruction from a zero-seeded LFSR state" in {
      simulate(new Harness) { dut =>
        reset(dut)

        dut.io.dummy_instr_seed_en_i.poke(true.B)
        dut.io.dummy_instr_seed_i.poke(0.U)
        dut.clock.step()
        dut.io.dummy_instr_seed_en_i.poke(false.B)

        dut.io.dummy_instr_en_i.poke(true.B)
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.dummy_instr_mask_i.poke(0.U)

        dut.io.insert_dummy_instr_o.expect(true.B)
        dut.io.dummy_instr_data_o.expect("h00000033".U)
      }
    }

    "holds the zero-seeded LFSR state while id_in_ready_i is low" in {
      simulate(new Harness) { dut =>
        reset(dut)

        dut.io.dummy_instr_seed_en_i.poke(true.B)
        dut.io.dummy_instr_seed_i.poke(0.U)
        dut.clock.step()
        dut.io.dummy_instr_seed_en_i.poke(false.B)

        dut.io.dummy_instr_en_i.poke(true.B)
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(false.B)
        dut.io.dummy_instr_mask_i.poke(0.U)

        dut.io.insert_dummy_instr_o.expect(true.B)
        dut.io.dummy_instr_data_o.expect("h00000033".U)
        dut.clock.step()
        dut.io.insert_dummy_instr_o.expect(true.B)
        dut.io.dummy_instr_data_o.expect("h00000033".U)
      }
    }

    "encodes each SystemVerilog dummy instruction type" in {
      val opA = 3
      val opB = 4
      val cases = Seq(
        0 -> rType(set = 0, rs2 = opB, rs1 = opA, funct3 = 0),
        1 -> rType(set = 1, rs2 = opB, rs1 = opA, funct3 = 0),
        2 -> rType(set = 1, rs2 = opB, rs1 = opA, funct3 = 4),
        3 -> rType(set = 0, rs2 = opB, rs1 = opA, funct3 = 7))

      for ((instrType, expected) <- cases) {
        simulate(new Harness(
          rndCnstLfsrSeed = lfsrState(instrType, opB, opA),
          rndCnstLfsrPerm = identityPerm)) { dut =>
          reset(dut)

          dut.io.dummy_instr_en_i.poke(true.B)
          dut.io.fetch_valid_i.poke(true.B)
          dut.io.id_in_ready_i.poke(true.B)
          dut.io.dummy_instr_mask_i.poke(0.U)

          dut.io.insert_dummy_instr_o.expect(true.B)
          dut.io.dummy_instr_data_o.expect(expected.U)
        }
      }
    }
  }
}
