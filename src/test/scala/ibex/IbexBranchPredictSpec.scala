package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexBranchPredictSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val fetch_rdata_i = Input(UInt(32.W))
      val fetch_pc_i = Input(UInt(32.W))
      val fetch_valid_i = Input(Bool())
      val predict_branch_taken_o = Output(Bool())
      val predict_branch_pc_o = Output(UInt(32.W))
    })

    val dut = Module(new IbexBranchPredict)
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.fetch_rdata_i := io.fetch_rdata_i
    dut.fetch_pc_i := io.fetch_pc_i
    dut.fetch_valid_i := io.fetch_valid_i
    io.predict_branch_taken_o := dut.predict_branch_taken_o
    io.predict_branch_pc_o := dut.predict_branch_pc_o
  }

  private def jal(rd: Int, imm: Int): BigInt = {
    val u = imm & 0x001fffff
    BigInt(
      (((u >> 20) & 0x1).toLong << 31) |
      (((u >> 1) & 0x3ff).toLong << 21) |
      (((u >> 11) & 0x1).toLong << 20) |
      (((u >> 12) & 0xff).toLong << 12) |
      ((rd & 0x1f).toLong << 7) |
      0x6fL
    )
  }

  private def branch(funct3: Int, rs1: Int, rs2: Int, imm: Int): BigInt = {
    val u = imm & 0x1fff
    BigInt(
      (((u >> 12) & 0x1).toLong << 31) |
      (((u >> 5) & 0x3f).toLong << 25) |
      ((rs2 & 0x1f).toLong << 20) |
      ((rs1 & 0x1f).toLong << 15) |
      ((funct3 & 0x7).toLong << 12) |
      (((u >> 1) & 0xf).toLong << 8) |
      (((u >> 11) & 0x1).toLong << 7) |
      0x63L
    )
  }

  private def cJump(funct3: Int, imm: Int): BigInt = {
    val u = imm & 0xfff
    BigInt(
      ((funct3 & 0x7) << 13) |
      (((u >> 11) & 0x1) << 12) |
      (((u >> 4) & 0x1) << 11) |
      (((u >> 8) & 0x3) << 9) |
      (((u >> 10) & 0x1) << 8) |
      (((u >> 6) & 0x1) << 7) |
      (((u >> 7) & 0x1) << 6) |
      (((u >> 1) & 0x7) << 3) |
      (((u >> 5) & 0x1) << 2) |
      0x1)
  }

  private def cBranch(funct3: Int, rs1p: Int, imm: Int): BigInt = {
    val u = imm & 0x1ff
    BigInt(
      ((funct3 & 0x7) << 13) |
      (((u >> 8) & 0x1) << 12) |
      (((u >> 3) & 0x3) << 10) |
      ((rs1p & 0x7) << 7) |
      (((u >> 6) & 0x3) << 5) |
      (((u >> 1) & 0x3) << 3) |
      (((u >> 5) & 0x1) << 2) |
      0x1)
  }

  "IbexBranchPredict" - {
    "always predicts JAL taken and computes the target" in {
      simulate(new Harness) { dut =>
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.fetch_pc_i.poke("h00001000".U)
        dut.io.fetch_rdata_i.poke(jal(rd = 1, imm = 16).U)
        dut.io.predict_branch_taken_o.expect(true.B)
        dut.io.predict_branch_pc_o.expect("h00001010".U)
      }
    }

    "predicts only negative-offset branches as taken" in {
      simulate(new Harness) { dut =>
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.fetch_pc_i.poke("h00001000".U)

        dut.io.fetch_rdata_i.poke(branch(funct3 = 0, rs1 = 1, rs2 = 2, imm = -4).U)
        dut.io.predict_branch_taken_o.expect(true.B)
        dut.io.predict_branch_pc_o.expect("h00000ffc".U)

        dut.io.fetch_rdata_i.poke(branch(funct3 = 0, rs1 = 1, rs2 = 2, imm = 8).U)
        dut.io.predict_branch_taken_o.expect(false.B)
        dut.io.predict_branch_pc_o.expect("h00001008".U)
      }
    }

    "masks taken prediction when fetch_valid_i is low" in {
      simulate(new Harness) { dut =>
        dut.io.fetch_valid_i.poke(false.B)
        dut.io.fetch_pc_i.poke("h00001000".U)
        dut.io.fetch_rdata_i.poke(jal(rd = 0, imm = 4).U)
        dut.io.predict_branch_taken_o.expect(false.B)
        dut.io.predict_branch_pc_o.expect("h00001004".U)
      }
    }

    "predicts compressed jumps and computes their target" in {
      simulate(new Harness) { dut =>
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.fetch_pc_i.poke("h00002000".U)
        dut.io.fetch_rdata_i.poke(cJump(funct3 = 5, imm = 12).U)
        dut.io.predict_branch_taken_o.expect(true.B)
        dut.io.predict_branch_pc_o.expect("h0000200c".U)

        dut.io.fetch_rdata_i.poke(cJump(funct3 = 1, imm = -2).U)
        dut.io.predict_branch_taken_o.expect(true.B)
        dut.io.predict_branch_pc_o.expect("h00001ffe".U)
      }
    }

    "predicts compressed branches only for negative offsets" in {
      simulate(new Harness) { dut =>
        dut.io.fetch_valid_i.poke(true.B)
        dut.io.fetch_pc_i.poke("h00002000".U)

        dut.io.fetch_rdata_i.poke(cBranch(funct3 = 6, rs1p = 1, imm = -4).U)
        dut.io.predict_branch_taken_o.expect(true.B)
        dut.io.predict_branch_pc_o.expect("h00001ffc".U)

        dut.io.fetch_rdata_i.poke(cBranch(funct3 = 7, rs1p = 2, imm = 6).U)
        dut.io.predict_branch_taken_o.expect(false.B)
        dut.io.predict_branch_pc_o.expect("h00002006".U)
      }
    }
  }
}
