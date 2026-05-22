package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PrimLfsrSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(
      lfsrType: String = "GAL_XOR",
      lfsrDw: Int = 32,
      entropyDw: Int = 8,
      stateOutDw: Int = 32,
      defaultSeed: BigInt = 1,
      customCoeffs: BigInt = 0,
      statePermEn: Boolean = false,
      statePerm: Seq[Int] = Seq.empty,
      nonLinearOut: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val seed_en_i = Input(Bool())
      val seed_i = Input(UInt(lfsrDw.W))
      val lfsr_en_i = Input(Bool())
      val entropy_i = Input(UInt(entropyDw.W))
      val state_o = Output(UInt(stateOutDw.W))
    })

    val dut = Module(new PrimLfsr(
      lfsrType = lfsrType,
      lfsrDw = lfsrDw,
      entropyDw = entropyDw,
      stateOutDw = stateOutDw,
      defaultSeed = defaultSeed,
      customCoeffs = customCoeffs,
      statePermEn = statePermEn,
      statePerm = statePerm,
      nonLinearOut = nonLinearOut))

    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.seed_en_i := io.seed_en_i
    dut.seed_i := io.seed_i
    dut.lfsr_en_i := io.lfsr_en_i
    dut.entropy_i := io.entropy_i
    io.state_o := dut.state_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.seed_en_i.poke(false.B)
    dut.io.seed_i.poke(0.U)
    dut.io.lfsr_en_i.poke(false.B)
    dut.io.entropy_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  private def princeSbox4Nibble(v: Int): Int = v match {
    case 0x0 => 0x4
    case 0x1 => 0xD
    case 0x2 => 0x5
    case 0x3 => 0xE
    case 0x4 => 0x0
    case 0x5 => 0x8
    case 0x6 => 0x7
    case 0x7 => 0x6
    case 0x8 => 0x1
    case 0x9 => 0x9
    case 0xA => 0xC
    case 0xB => 0xA
    case 0xC => 0x2
    case 0xD => 0x3
    case 0xE => 0xF
    case 0xF => 0xB
  }

  private def applyPrinceSbox(state: Int, width: Int): Int = {
    val numSboxes = width / 4
    val matrixIndices = Seq.tabulate(4, numSboxes) { case (row, col) => row * numSboxes + col }
    def lrotcol(col: Seq[Int], shift: Int): Seq[Int] = {
      val out = Array.fill(col.length)(0)
      for (k <- col.indices) {
        out((k + shift) % col.length) = col(k)
      }
      out.toSeq
    }
    val matrixRotrevIndices = Seq(
      matrixIndices(0),
      lrotcol(matrixIndices(1), numSboxes / 2),
      matrixIndices(2).reverse,
      lrotcol(matrixIndices(3), 1).reverse)
    val sboxInIndices = (0 until width).map(k => matrixRotrevIndices(k % 4)(k / 4))

    (0 until numSboxes).foldLeft(0) { (acc, nib) =>
      val input = (0 until 4).foldLeft(0) { (bits, bit) =>
        bits | (((state >> sboxInIndices(nib * 4 + bit)) & 1) << bit)
      }
      acc | (princeSbox4Nibble(input) << (nib * 4))
    }
  }

  "PrimLfsr" - {
    "matches the GAL_XOR default transition" in {
      simulate(new Harness()) { dut =>
        reset(dut)
        dut.io.lfsr_en_i.poke(true.B)
        dut.io.entropy_i.poke(0.U)
        dut.clock.step()
        dut.io.state_o.expect("h80200003".U)
      }
    }

    "matches a wider GAL_XOR default coefficient transition" in {
      simulate(new Harness(lfsrDw = 64, entropyDw = 8, stateOutDw = 64, defaultSeed = 1)) { dut =>
        reset(dut)
        dut.io.lfsr_en_i.poke(true.B)
        dut.io.entropy_i.poke(0.U)
        dut.clock.step()
        dut.io.state_o.expect(BigInt("d800000000000000", 16).U)
      }
    }

    "elaborates the maximum default coefficient width" in {
      simulate(new Harness(lfsrDw = 168, entropyDw = 8, stateOutDw = 168, defaultSeed = 1)) { dut =>
        reset(dut)
        dut.io.state_o.expect(1.U)
      }
    }

    "supports a Fibonacci XNOR transition with custom coefficients" in {
      simulate(new Harness(lfsrType = "FIB_XNOR", lfsrDw = 4, entropyDw = 4, stateOutDw = 4, customCoeffs = 9)) { dut =>
        reset(dut)
        dut.io.lfsr_en_i.poke(true.B)
        dut.io.entropy_i.poke(0.U)
        dut.clock.step()
        dut.io.state_o.expect("b0010".U)
      }
    }

    "applies the configured state permutation" in {
      simulate(new Harness(lfsrDw = 4, entropyDw = 4, stateOutDw = 4, defaultSeed = 10, statePermEn = true, statePerm = Seq(3, 2, 1, 0))) { dut =>
        reset(dut)
        dut.io.seed_en_i.poke(true.B)
        dut.io.seed_i.poke("b1010".U)
        dut.clock.step()
        dut.io.seed_en_i.poke(false.B)
        dut.io.state_o.expect("b0101".U)
      }
    }

    "applies the nonlinear PRINCE S-box output transform" in {
      simulate(new Harness(lfsrDw = 16, entropyDw = 16, stateOutDw = 16, defaultSeed = 0x1234, nonLinearOut = true)) { dut =>
        reset(dut)
        dut.io.state_o.expect(applyPrinceSbox(0x1234, 16).U)
      }
    }

    "prefers external seeding over LFSR advancement" in {
      simulate(new Harness(lfsrDw = 8, entropyDw = 8, stateOutDw = 8, defaultSeed = 1)) { dut =>
        reset(dut)
        dut.io.seed_en_i.poke(true.B)
        dut.io.seed_i.poke("h5a".U)
        dut.io.lfsr_en_i.poke(true.B)
        dut.io.entropy_i.poke("h00".U)
        dut.clock.step()
        dut.io.state_o.expect("h5a".U)
      }
    }

    "recovers the default seed after lockup for both LFSR modes" in {
      simulate(new Harness(lfsrType = "GAL_XOR", lfsrDw = 4, entropyDw = 4, stateOutDw = 4, defaultSeed = 1)) { dut =>
        reset(dut)
        dut.io.seed_en_i.poke(true.B)
        dut.io.seed_i.poke(0.U)
        dut.clock.step()
        dut.io.seed_en_i.poke(false.B)
        dut.io.lfsr_en_i.poke(true.B)
        dut.io.entropy_i.poke(0.U)
        dut.clock.step()
        dut.io.state_o.expect(1.U)
      }

      simulate(new Harness(lfsrType = "FIB_XNOR", lfsrDw = 4, entropyDw = 4, stateOutDw = 4, defaultSeed = 1)) { dut =>
        reset(dut)
        dut.io.seed_en_i.poke(true.B)
        dut.io.seed_i.poke("hf".U)
        dut.clock.step()
        dut.io.seed_en_i.poke(false.B)
        dut.io.lfsr_en_i.poke(true.B)
        dut.io.entropy_i.poke(0.U)
        dut.clock.step()
        dut.io.state_o.expect(1.U)
      }
    }
  }
}
