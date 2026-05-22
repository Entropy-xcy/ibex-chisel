package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class PrimPrinceSpec extends AnyFreeSpec with ChiselSim {
  class Harness(
      useOldKeySched: Boolean = false,
      halfwayDataReg: Boolean = false,
      halfwayKeyReg: Boolean = false)
      extends Module {
    val io = IO(new Bundle {
      val valid_i = Input(Bool())
      val data_i = Input(UInt(64.W))
      val key_i = Input(UInt(128.W))
      val dec_i = Input(Bool())
      val valid_o = Output(Bool())
      val data_o = Output(UInt(64.W))
    })

    val dut = Module(new PrimPrince(
      dataWidth = 64,
      keyWidth = 128,
      numRoundsHalf = 5,
      useOldKeySched = useOldKeySched,
      halfwayDataReg = halfwayDataReg,
      halfwayKeyReg = halfwayKeyReg))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.valid_i := io.valid_i
    dut.data_i := io.data_i
    dut.key_i := io.key_i
    dut.dec_i := io.dec_i
    io.valid_o := dut.valid_o
    io.data_o := dut.data_o
  }

  private val goldenVectors = Seq(
    (BigInt(0), BigInt("00000000000000000000000000000001", 16), BigInt("992538535f1c8880", 16)),
    (BigInt(0), BigInt("00000000000000000000000000000000", 16), BigInt("818665aa0d02dfda", 16)),
    (BigInt(1), BigInt("00000000000000000000000000000000", 16), BigInt("92b4151443700edb", 16)),
    (BigInt(0), BigInt("00000000000000010000000000000000", 16), BigInt("352b90fadeb3e269", 16)),
    (BigInt("0123456789abcdef", 16), BigInt("fedcba98765432100000000000000000", 16), BigInt("59b009fc2ce58380", 16))
  )
  private val goldenVectorsOldSched = Seq(
    (BigInt(0), BigInt("00000000000000000000000000000001", 16), BigInt("6dca6f616f123590", 16)),
    (BigInt(0), BigInt("00000000000000000000000000000000", 16), BigInt("818665aa0d02dfda", 16)),
    (BigInt(1), BigInt("00000000000000000000000000000000", 16), BigInt("92b4151443700edb", 16)),
    (BigInt(0), BigInt("00000000000000010000000000000000", 16), BigInt("12b4151443700edb", 16)),
    (BigInt("0123456789abcdef", 16), BigInt("fedcba98765432100000000000000000", 16), BigInt("1f24bb8638e813d3", 16))
  )

  "PrimPrince" - {
    "matches the standard zero-vector encryption" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.valid_i.poke(true.B)
        dut.io.dec_i.poke(false.B)
        dut.io.data_i.poke(0.U)
        dut.io.key_i.poke(0.U)
        dut.clock.step()
        dut.io.valid_o.expect(true.B)
        dut.io.data_o.expect("h818665aa0d02dfda".U)
      }
    }

    "round-trips through decryption" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.valid_i.poke(true.B)
        dut.io.dec_i.poke(false.B)
        dut.io.data_i.poke("h0123456789abcdef".U)
        dut.io.key_i.poke("h00112233445566778899aabbccddeeff".U)
        dut.clock.step()
        val cipher = dut.io.data_o.peek().litValue
        dut.io.dec_i.poke(true.B)
        dut.io.data_i.poke(cipher.U)
        dut.clock.step()
        dut.io.data_o.expect("h0123456789abcdef".U)
      }
    }

    "supports the registered halfway data and key variant" in {
      simulate(new Harness(halfwayDataReg = true, halfwayKeyReg = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.valid_i.poke(false.B)
        dut.io.dec_i.poke(false.B)
        dut.io.data_i.poke(0.U)
        dut.io.key_i.poke(0.U)
        dut.io.valid_o.expect(false.B)

        dut.io.valid_i.poke(true.B)
        dut.clock.step()
        dut.io.valid_o.expect(true.B)
        dut.io.data_o.expect("h818665aa0d02dfda".U)

        dut.io.valid_i.poke(false.B)
        dut.clock.step()
        dut.io.valid_o.expect(false.B)
      }
    }

    "matches the SV golden vectors for the new key schedule" in {
      simulate(new Harness(useOldKeySched = false)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.valid_i.poke(true.B)
        dut.io.dec_i.poke(false.B)
        for ((plaintext, key, expected) <- goldenVectors) {
          dut.io.data_i.poke(plaintext.U)
          dut.io.key_i.poke(key.U)
          dut.io.data_o.expect(expected.U)
        }
      }
    }

    "matches the SV golden vectors for the old key schedule" in {
      simulate(new Harness(useOldKeySched = true)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.valid_i.poke(true.B)
        dut.io.dec_i.poke(false.B)
        for ((plaintext, key, expected) <- goldenVectorsOldSched) {
          dut.io.data_i.poke(plaintext.U)
          dut.io.key_i.poke(key.U)
          dut.io.data_o.expect(expected.U)
        }
      }
    }
  }
}
