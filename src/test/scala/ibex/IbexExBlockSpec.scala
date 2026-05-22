package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexExBlockSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(rv32m: Int = 2, rv32b: Int = 0, branchTargetALU: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val alu_operator_i = Input(UInt(7.W))
      val alu_operand_a_i = Input(UInt(32.W))
      val alu_operand_b_i = Input(UInt(32.W))
      val alu_instr_first_cycle_i = Input(Bool())
      val bt_a_operand_i = Input(UInt(32.W))
      val bt_b_operand_i = Input(UInt(32.W))
      val multdiv_operator_i = Input(UInt(2.W))
      val mult_en_i = Input(Bool())
      val div_en_i = Input(Bool())
      val mult_sel_i = Input(Bool())
      val div_sel_i = Input(Bool())
      val multdiv_signed_mode_i = Input(UInt(2.W))
      val multdiv_operand_a_i = Input(UInt(32.W))
      val multdiv_operand_b_i = Input(UInt(32.W))
      val multdiv_ready_id_i = Input(Bool())
      val data_ind_timing_i = Input(Bool())
      val imd_val_0_o = Output(UInt(34.W))
      val imd_val_1_o = Output(UInt(34.W))
      val imd_val_we_o = Output(UInt(2.W))
      val alu_adder_result_ex_o = Output(UInt(32.W))
      val result_ex_o = Output(UInt(32.W))
      val branch_target_o = Output(UInt(32.W))
      val branch_decision_o = Output(Bool())
      val ex_valid_o = Output(Bool())
    })

    val dut = Module(new IbexExBlock(rv32m = rv32m, rv32b = rv32b, branchTargetALU = branchTargetALU))
    val imdVal = RegInit(VecInit(Seq.fill(2)(0.U(34.W))))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.alu_operator_i := io.alu_operator_i
    dut.alu_operand_a_i := io.alu_operand_a_i
    dut.alu_operand_b_i := io.alu_operand_b_i
    dut.alu_instr_first_cycle_i := io.alu_instr_first_cycle_i
    dut.bt_a_operand_i := io.bt_a_operand_i
    dut.bt_b_operand_i := io.bt_b_operand_i
    dut.multdiv_operator_i := io.multdiv_operator_i
    dut.mult_en_i := io.mult_en_i
    dut.div_en_i := io.div_en_i
    dut.mult_sel_i := io.mult_sel_i
    dut.div_sel_i := io.div_sel_i
    dut.multdiv_signed_mode_i := io.multdiv_signed_mode_i
    dut.multdiv_operand_a_i := io.multdiv_operand_a_i
    dut.multdiv_operand_b_i := io.multdiv_operand_b_i
    dut.multdiv_ready_id_i := io.multdiv_ready_id_i
    dut.data_ind_timing_i := io.data_ind_timing_i
    dut.imd_val_q_i := imdVal

    for (i <- 0 until 2) {
      when(dut.imd_val_we_o(i)) {
        imdVal(i) := dut.imd_val_d_o(i)
      }
    }

    io.imd_val_0_o := imdVal(0)
    io.imd_val_1_o := imdVal(1)
    io.imd_val_we_o := dut.imd_val_we_o
    io.alu_adder_result_ex_o := dut.alu_adder_result_ex_o
    io.result_ex_o := dut.result_ex_o
    io.branch_target_o := dut.branch_target_o
    io.branch_decision_o := dut.branch_decision_o
    io.ex_valid_o := dut.ex_valid_o
  }

  private object MdOp {
    val MULL = 0
    val DIV = 2
  }

  private def op(name: String): UInt = IbexPkg.AluOp.encoding(name).U

  private def init(dut: Harness): Unit = {
    dut.io.alu_operator_i.poke(op("ALU_ADD"))
    dut.io.alu_operand_a_i.poke(0.U)
    dut.io.alu_operand_b_i.poke(0.U)
    dut.io.alu_instr_first_cycle_i.poke(true.B)
    dut.io.bt_a_operand_i.poke(0.U)
    dut.io.bt_b_operand_i.poke(0.U)
    dut.io.multdiv_operator_i.poke(MdOp.MULL.U)
    dut.io.mult_en_i.poke(false.B)
    dut.io.div_en_i.poke(false.B)
    dut.io.mult_sel_i.poke(false.B)
    dut.io.div_sel_i.poke(false.B)
    dut.io.multdiv_signed_mode_i.poke(0.U)
    dut.io.multdiv_operand_a_i.poke(0.U)
    dut.io.multdiv_operand_b_i.poke(0.U)
    dut.io.multdiv_ready_id_i.poke(true.B)
    dut.io.data_ind_timing_i.poke(false.B)
  }

  private def runMultDiv(
      dut: Harness,
      operator: Int,
      signedMode: Int,
      a: BigInt,
      b: BigInt,
      isDiv: Boolean,
      maxCycles: Int = 50
  ): BigInt = {
    dut.io.alu_operator_i.poke(op("ALU_ADD"))
    dut.io.multdiv_operator_i.poke(operator.U)
    dut.io.multdiv_signed_mode_i.poke(signedMode.U)
    dut.io.multdiv_operand_a_i.poke((a & BigInt("ffffffff", 16)).U)
    dut.io.multdiv_operand_b_i.poke((b & BigInt("ffffffff", 16)).U)
    dut.io.alu_operand_a_i.poke((a & BigInt("ffffffff", 16)).U)
    dut.io.alu_operand_b_i.poke((BigInt(0) - (b & BigInt("ffffffff", 16)) & BigInt("ffffffff", 16)).U)
    dut.io.mult_sel_i.poke((!isDiv).B)
    dut.io.div_sel_i.poke(isDiv.B)
    dut.io.mult_en_i.poke((!isDiv).B)
    dut.io.div_en_i.poke(isDiv.B)

    var cycles = 0
    while (!dut.io.ex_valid_o.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"operation did not become valid within $maxCycles cycles") {
      dut.io.ex_valid_o.peek().litToBoolean mustBe true
    }
    val result = dut.io.result_ex_o.peek().litValue
    dut.clock.step()
    dut.io.mult_en_i.poke(false.B)
    dut.io.div_en_i.poke(false.B)
    dut.io.mult_sel_i.poke(false.B)
    dut.io.div_sel_i.poke(false.B)
    dut.clock.step()
    result
  }

  "IbexExBlock" - {
    "routes ALU results, branch decisions, and default branch target" in {
      simulate(new Harness(rv32m = 0)) { dut =>
        init(dut)

        dut.io.alu_operator_i.poke(op("ALU_ADD"))
        dut.io.alu_operand_a_i.poke("h00001000".U)
        dut.io.alu_operand_b_i.poke("h00000024".U)
        dut.io.result_ex_o.expect("h00001024".U)
        dut.io.alu_adder_result_ex_o.expect("h00001024".U)
        dut.io.branch_target_o.expect("h00001024".U)
        dut.io.ex_valid_o.expect(true.B)

        dut.io.alu_operator_i.poke(op("ALU_EQ"))
        dut.io.alu_operand_a_i.poke("h00000055".U)
        dut.io.alu_operand_b_i.poke("h00000055".U)
        dut.io.branch_decision_o.expect(true.B)
        dut.io.result_ex_o.expect(1.U)
      }
    }

    "uses the branch-target ALU generator when enabled" in {
      simulate(new Harness(rv32m = 0, branchTargetALU = true)) { dut =>
        init(dut)

        dut.io.alu_operator_i.poke(op("ALU_ADD"))
        dut.io.alu_operand_a_i.poke("h00001000".U)
        dut.io.alu_operand_b_i.poke("h00000024".U)
        dut.io.bt_a_operand_i.poke("h80000000".U)
        dut.io.bt_b_operand_i.poke("h00000010".U)
        dut.io.alu_adder_result_ex_o.expect("h00001024".U)
        dut.io.branch_target_o.expect("h80000010".U)
      }
    }

    "ignores multdiv selects when RV32M is disabled" in {
      simulate(new Harness(rv32m = 0)) { dut =>
        init(dut)

        dut.io.alu_operator_i.poke(op("ALU_ADD"))
        dut.io.alu_operand_a_i.poke(2.U)
        dut.io.alu_operand_b_i.poke(3.U)
        dut.io.mult_sel_i.poke(true.B)
        dut.io.mult_en_i.poke(true.B)
        dut.io.multdiv_operand_a_i.poke(7.U)
        dut.io.multdiv_operand_b_i.poke(9.U)
        dut.io.result_ex_o.expect(5.U)
        dut.io.ex_valid_o.expect(true.B)
      }
    }

    "routes fast multdiv results and IMD writes" in {
      simulate(new Harness(rv32m = 2)) { dut =>
        init(dut)

        runMultDiv(dut, MdOp.MULL, signedMode = 0, a = 7, b = 9, isDiv = false) mustBe 63
        runMultDiv(dut, MdOp.DIV, signedMode = 3, a = BigInt("fffffff1", 16), b = 4, isDiv = true) mustBe BigInt("fffffffd", 16)
      }
    }
  }
}
