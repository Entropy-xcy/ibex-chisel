package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PrimSecdedInv3932Spec extends AnyFreeSpec with Matchers with ChiselSim {
  class EncHarness extends Module {
    val io = IO(new Bundle {
      val data_i = Input(UInt(32.W))
      val data_o = Output(UInt(39.W))
    })
    val dut = Module(new PrimSecdedInv3932Enc)
    dut.data_i := io.data_i
    io.data_o := dut.data_o
  }

  class DecHarness extends Module {
    val io = IO(new Bundle {
      val data_i = Input(UInt(39.W))
      val data_o = Output(UInt(32.W))
      val syndrome_o = Output(UInt(7.W))
      val err_o = Output(UInt(2.W))
    })
    val dut = Module(new PrimSecdedInv3932Dec)
    dut.data_i := io.data_i
    io.data_o := dut.data_o
    io.syndrome_o := dut.syndrome_o
    io.err_o := dut.err_o
  }

  private def parity(value: BigInt, mask: BigInt): BigInt = (value & mask).bitCount % 2

  private def encode(data: BigInt): BigInt = {
    val masks = Seq(
      BigInt("002606BD25", 16),
      BigInt("00DEBA8050", 16),
      BigInt("00413D89AA", 16),
      BigInt("0031234ED1", 16),
      BigInt("00C2C1323B", 16),
      BigInt("002DCC624C", 16),
      BigInt("0098505586", 16))
    val withParity = masks.zipWithIndex.foldLeft(data) { case (acc, (mask, bit)) =>
      acc | (parity(acc, mask) << (32 + bit))
    }
    withParity ^ BigInt("2A00000000", 16)
  }

  "PrimSecdedInv3932" - {
    "matches the generated inverse SECDED 39/32 encoder constants" in {
      simulate(new EncHarness) { dut =>
        val data = BigInt("89abcdef", 16)
        dut.io.data_i.poke(data.U)
        dut.io.data_o.expect(encode(data).U)
      }
    }

    "corrects a single data-bit error and reports double-bit errors" in {
      simulate(new DecHarness) { dut =>
        val data = BigInt("12345678", 16)
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
