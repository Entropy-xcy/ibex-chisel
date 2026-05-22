package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class PrimSubstPermSpec extends AnyFreeSpec with ChiselSim {
  class Harness(decrypt: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val data_i = Input(UInt(8.W))
      val key_i = Input(UInt(8.W))
      val data_o = Output(UInt(8.W))
    })

    val dut = Module(new PrimSubstPerm(dataWidth = 8, numRounds = 1, decrypt = decrypt))
    dut.data_i := io.data_i
    dut.key_i := io.key_i
    io.data_o := dut.data_o
  }

  "PrimSubstPerm" - {
    "matches the PRESENT substitution/permutation round" in {
      simulate(new Harness) { dut =>
        dut.io.data_i.poke("h3a".U)
        dut.io.key_i.poke("h5c".U)
        dut.io.data_o.expect("h53".U)
      }
    }

    "inverts the substitution/permutation round in decrypt mode" in {
      simulate(new Harness(decrypt = true)) { dut =>
        dut.io.data_i.poke("h53".U)
        dut.io.key_i.poke("h5c".U)
        dut.io.data_o.expect("h3a".U)
      }
    }
  }
}
