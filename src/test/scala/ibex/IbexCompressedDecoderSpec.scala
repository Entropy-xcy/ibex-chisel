package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexCompressedDecoderSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(rv32zc: Int = 3) extends Module {
    val io = IO(new Bundle {
      val valid_i = Input(Bool())
      val id_in_ready_i = Input(Bool())
      val instr_i = Input(UInt(32.W))
      val instr_o = Output(UInt(32.W))
      val is_compressed_o = Output(Bool())
      val gets_expanded_o = Output(UInt(2.W))
      val illegal_instr_o = Output(Bool())
    })

    val dut = Module(new IbexCompressedDecoder(rv32zc = rv32zc))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.valid_i := io.valid_i
    dut.id_in_ready_i := io.id_in_ready_i
    dut.instr_i := io.instr_i
    io.instr_o := dut.instr_o
    io.is_compressed_o := dut.is_compressed_o
    io.gets_expanded_o := dut.gets_expanded_o
    io.illegal_instr_o := dut.illegal_instr_o
  }

  private def addi(rd: Int, rs1: Int, imm: Int): BigInt = {
    val u = imm & 0xfff
    BigInt((u.toLong << 20) | ((rs1 & 0x1f).toLong << 15) | ((rd & 0x1f).toLong << 7) | 0x13L)
  }

  private def jalr(rd: Int, rs1: Int, imm: Int): BigInt = {
    val u = imm & 0xfff
    BigInt((u.toLong << 20) | ((rs1 & 0x1f).toLong << 15) | ((rd & 0x1f).toLong << 7) | 0x67L)
  }

  private def lw(rd: Int, rs1: Int, imm: Int): BigInt = {
    BigInt(((imm & 0xfff).toLong << 20) | ((rs1 & 0x1f).toLong << 15) | (2L << 12) | ((rd & 0x1f).toLong << 7) | 0x03L)
  }

  private def sw(rs2: Int, rs1: Int, imm: Int): BigInt = {
    val u = imm & 0xfff
    BigInt((((u >> 5) & 0x7f).toLong << 25) | ((rs2 & 0x1f).toLong << 20) | ((rs1 & 0x1f).toLong << 15) |
      (2L << 12) | ((u & 0x1f).toLong << 7) | 0x23L)
  }

  private def cm(funct5: Int, rlistOrRs1: Int, mid: Int, low: Int): BigInt =
    BigInt((5 << 13) | ((funct5 & 0x1f) << 8) | ((rlistOrRs1 & 0xf) << 4) | ((mid & 0x3) << 2) | (low & 0x3))

  private def expectExpanded(
      dut: Harness,
      instr: BigInt,
      expectedInstr: BigInt,
      expectedGetsExpanded: Int,
      stepAfter: Boolean = true
  ): Unit = {
    dut.io.valid_i.poke(true.B)
    dut.io.id_in_ready_i.poke(true.B)
    dut.io.instr_i.poke(instr.U)
    dut.io.instr_o.expect(expectedInstr.U)
    dut.io.gets_expanded_o.expect(expectedGetsExpanded.U)
    dut.io.illegal_instr_o.expect(false.B)
    if (stepAfter) {
      dut.clock.step()
    }
  }

  "IbexCompressedDecoder" - {
    "passes through non-compressed instructions" in {
      simulate(new Harness()) { dut =>
        dut.io.valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke("h00000013".U)

        dut.io.instr_o.expect("h00000013".U)
        dut.io.is_compressed_o.expect(false.B)
        dut.io.gets_expanded_o.expect(0.U)
        dut.io.illegal_instr_o.expect(false.B)
      }
    }

    "expands c.addi" in {
      simulate(new Harness()) { dut =>
        dut.io.valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke("h00000081".U)

        dut.io.instr_o.expect(addi(rd = 1, rs1 = 1, imm = 0).U)
        dut.io.is_compressed_o.expect(true.B)
        dut.io.illegal_instr_o.expect(false.B)
      }
    }

    "expands c.lwsp and rejects rd x0" in {
      simulate(new Harness()) { dut =>
        dut.io.valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke("h00004082".U)
        dut.io.instr_o.expect(lw(rd = 1, rs1 = 2, imm = 0).U)
        dut.io.illegal_instr_o.expect(false.B)

        dut.io.instr_i.poke("h00004002".U)
        dut.io.illegal_instr_o.expect(true.B)
      }
    }

    "rejects c.addi4spn with a zero immediate" in {
      simulate(new Harness()) { dut =>
        dut.io.valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke(0.U)

        dut.io.is_compressed_o.expect(true.B)
        dut.io.illegal_instr_o.expect(true.B)
      }
    }

    "gates Zcb-only encodings by RV32ZC parameter" in {
      val cLbu = BigInt((4 << 13) | (0 << 10) | (1 << 7) | (1 << 2))

      simulate(new Harness(rv32zc = 0)) { dut =>
        dut.io.valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke(cLbu.U)
        dut.io.illegal_instr_o.expect(true.B)
      }

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.io.valid_i.poke(true.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke(cLbu.U)
        dut.io.illegal_instr_o.expect(false.B)
      }
    }

    "expands cm.push through store and stack decrement" in {
      val cmPush = cm(funct5 = 0x18, rlistOrRs1 = 4, mid = 0, low = 2)

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        expectExpanded(dut, cmPush, sw(rs2 = 1, rs1 = 2, imm = -4), expectedGetsExpanded = 1)
        expectExpanded(dut, cmPush, addi(rd = 2, rs1 = 2, imm = -16), expectedGetsExpanded = 2, stepAfter = false)
      }
    }

    "gates Zcmp expansion and holds the expansion FSM while input is invalid" in {
      val cmPush = cm(funct5 = 0x18, rlistOrRs1 = 4, mid = 0, low = 2)

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.valid_i.poke(false.B)
        dut.io.id_in_ready_i.poke(true.B)
        dut.io.instr_i.poke(cmPush.U)
        dut.io.instr_o.expect(sw(rs2 = 1, rs1 = 2, imm = -4).U)
        dut.io.gets_expanded_o.expect(0.U)
        dut.clock.step()

        expectExpanded(dut, cmPush, sw(rs2 = 1, rs1 = 2, imm = -4), expectedGetsExpanded = 1)
        expectExpanded(dut, cmPush, addi(rd = 2, rs1 = 2, imm = -16), expectedGetsExpanded = 2, stepAfter = false)
      }
    }

    "expands cm.pop through load and stack increment" in {
      val cmPop = cm(funct5 = 0x1a, rlistOrRs1 = 4, mid = 0, low = 2)

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        expectExpanded(dut, cmPop, lw(rd = 1, rs1 = 2, imm = 12), expectedGetsExpanded = 1)
        expectExpanded(dut, cmPop, addi(rd = 2, rs1 = 2, imm = 16), expectedGetsExpanded = 2, stepAfter = false)
      }
    }

    "expands cm.popretz through load, stack increment, zero a0, and return" in {
      val cmPopretz = cm(funct5 = 0x1c, rlistOrRs1 = 4, mid = 0, low = 2)

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        expectExpanded(dut, cmPopretz, lw(rd = 1, rs1 = 2, imm = 12), expectedGetsExpanded = 1)
        expectExpanded(dut, cmPopretz, addi(rd = 2, rs1 = 2, imm = 16), expectedGetsExpanded = 1)
        expectExpanded(dut, cmPopretz, addi(rd = 10, rs1 = 0, imm = 0), expectedGetsExpanded = 1)
        expectExpanded(dut, cmPopretz, jalr(rd = 0, rs1 = 1, imm = 0), expectedGetsExpanded = 2, stepAfter = false)
      }
    }

    "expands cm.mvsa01 as two register moves" in {
      val cmMvsa01 = BigInt((5 << 13) | (0x0c << 8) | (1 << 7) | (1 << 5) | (2 << 2) | 2)

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        expectExpanded(dut, cmMvsa01, addi(rd = 9, rs1 = 10, imm = 0), expectedGetsExpanded = 1)
        expectExpanded(dut, cmMvsa01, addi(rd = 18, rs1 = 11, imm = 0), expectedGetsExpanded = 2, stepAfter = false)
      }
    }

    "expands cm.mva01s as two register moves" in {
      val cmMva01s = BigInt((5 << 13) | (0x0c << 8) | (1 << 7) | (3 << 5) | (2 << 2) | 2)

      simulate(new Harness(rv32zc = 3)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        expectExpanded(dut, cmMva01s, addi(rd = 10, rs1 = 9, imm = 0), expectedGetsExpanded = 1)
        expectExpanded(dut, cmMva01s, addi(rd = 11, rs1 = 18, imm = 0), expectedGetsExpanded = 2, stepAfter = false)
      }
    }
  }
}
