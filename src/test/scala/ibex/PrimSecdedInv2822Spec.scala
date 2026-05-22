package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PrimSecdedInv2822Spec extends AnyFreeSpec with Matchers with ChiselSim {
  class EncHarness extends Module {
    val io = IO(new Bundle {
      val data_i = Input(UInt(22.W))
      val data_o = Output(UInt(28.W))
    })
    val dut = Module(new PrimSecdedInv2822Enc)
    dut.data_i := io.data_i
    io.data_o := dut.data_o
  }

  class DecHarness extends Module {
    val io = IO(new Bundle {
      val data_i = Input(UInt(28.W))
      val data_o = Output(UInt(22.W))
      val syndrome_o = Output(UInt(6.W))
      val err_o = Output(UInt(2.W))
    })
    val dut = Module(new PrimSecdedInv2822Dec)
    dut.data_i := io.data_i
    io.data_o := dut.data_o
    io.syndrome_o := dut.syndrome_o
    io.err_o := dut.err_o
  }

  private def parity(value: BigInt, mask: BigInt): BigInt = (value & mask).bitCount % 2

  private def encode(data: BigInt): BigInt = {
    val masks = Seq(
      BigInt("03003FF", 16),
      BigInt("010FC0F", 16),
      BigInt("0271C71", 16),
      BigInt("03B6592", 16),
      BigInt("03DAAA4", 16),
      BigInt("03ED348", 16))
    val withParity = masks.zipWithIndex.foldLeft(data) { case (acc, (mask, bit)) =>
      acc | (parity(acc, mask) << (22 + bit))
    }
    withParity ^ BigInt("A800000", 16)
  }

  "PrimSecdedInv2822" - {
    "matches the generated inverse SECDED 28/22 encoder constants" in {
      simulate(new EncHarness) { dut =>
        val data = BigInt("2aa55", 16)
        dut.io.data_i.poke(data.U)
        dut.io.data_o.expect(encode(data).U)
      }
    }

    "corrects a single data-bit error and reports double-bit errors" in {
      simulate(new DecHarness) { dut =>
        val data = BigInt("2a55aa", 16) & ((BigInt(1) << 22) - 1)
        val encoded = encode(data)

        dut.io.data_i.poke((encoded ^ 1).U)
        dut.io.data_o.expect(data.U)
        dut.io.err_o.expect("b01".U)

        dut.io.data_i.poke((encoded ^ 3).U)
        dut.io.err_o.expect("b10".U)
      }
    }
  }
}
