package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Cat
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexMultDivFastSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(rv32m: Int) extends Module {
    val io = IO(new Bundle {
      val mult_en_i = Input(Bool())
      val div_en_i = Input(Bool())
      val mult_sel_i = Input(Bool())
      val div_sel_i = Input(Bool())
      val operator_i = Input(UInt(2.W))
      val signed_mode_i = Input(UInt(2.W))
      val op_a_i = Input(UInt(32.W))
      val op_b_i = Input(UInt(32.W))
      val equal_to_zero_i = Input(Bool())
      val data_ind_timing_i = Input(Bool())
      val multdiv_ready_id_i = Input(Bool())
      val imd_val_0_o = Output(UInt(34.W))
      val imd_val_1_o = Output(UInt(34.W))
      val multdiv_result_o = Output(UInt(32.W))
      val valid_o = Output(Bool())
    })

    val dut = Module(new IbexMultDivFast(rv32m))
    val imdVal = RegInit(VecInit(Seq.fill(2)(0.U(34.W))))
    val aluAdderExt = Wire(UInt(34.W))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.mult_en_i := io.mult_en_i
    dut.div_en_i := io.div_en_i
    dut.mult_sel_i := io.mult_sel_i
    dut.div_sel_i := io.div_sel_i
    dut.operator_i := io.operator_i
    dut.signed_mode_i := io.signed_mode_i
    dut.op_a_i := io.op_a_i
    dut.op_b_i := io.op_b_i
    dut.equal_to_zero_i := io.equal_to_zero_i
    dut.data_ind_timing_i := io.data_ind_timing_i
    dut.multdiv_ready_id_i := io.multdiv_ready_id_i
    dut.imd_val_q_i := imdVal

    aluAdderExt := Cat(0.U(1.W), dut.alu_operand_a_o) + Cat(0.U(1.W), dut.alu_operand_b_o)
    dut.alu_adder_ext_i := aluAdderExt
    dut.alu_adder_i := aluAdderExt(32, 1)

    for (i <- 0 until 2) {
      when(dut.imd_val_we_o(i)) {
        imdVal(i) := dut.imd_val_d_o(i)
      }
    }

    io.imd_val_0_o := imdVal(0)
    io.imd_val_1_o := imdVal(1)
    io.multdiv_result_o := dut.multdiv_result_o
    io.valid_o := dut.valid_o
  }

  private object MdOp {
    val MULL = 0
    val MULH = 1
    val DIV = 2
    val REM = 3
  }

  private def init(dut: Harness): Unit = {
    dut.io.mult_en_i.poke(false.B)
    dut.io.div_en_i.poke(false.B)
    dut.io.mult_sel_i.poke(false.B)
    dut.io.div_sel_i.poke(false.B)
    dut.io.operator_i.poke(MdOp.MULL.U)
    dut.io.signed_mode_i.poke(0.U)
    dut.io.op_a_i.poke(0.U)
    dut.io.op_b_i.poke(0.U)
    dut.io.equal_to_zero_i.poke(false.B)
    dut.io.data_ind_timing_i.poke(false.B)
    dut.io.multdiv_ready_id_i.poke(true.B)
  }

  private def runOp(
      dut: Harness,
      operator: Int,
      signedMode: Int,
      a: BigInt,
      b: BigInt,
      isDiv: Boolean,
      maxCycles: Int = 50
  ): BigInt = {
    dut.io.operator_i.poke(operator.U)
    dut.io.signed_mode_i.poke(signedMode.U)
    dut.io.op_a_i.poke(u32(a).U)
    dut.io.op_b_i.poke(u32(b).U)
    dut.io.equal_to_zero_i.poke(u32(b) == 0)
    dut.io.mult_sel_i.poke((!isDiv).B)
    dut.io.div_sel_i.poke(isDiv.B)
    dut.io.mult_en_i.poke((!isDiv).B)
    dut.io.div_en_i.poke(isDiv.B)

    var cycles = 0
    while (!dut.io.valid_o.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step()
      cycles += 1
    }
    withClue(s"operation did not become valid within $maxCycles cycles") {
      dut.io.valid_o.peek().litToBoolean mustBe true
    }
    val result = dut.io.multdiv_result_o.peek().litValue
    dut.clock.step()
    dut.io.mult_en_i.poke(false.B)
    dut.io.div_en_i.poke(false.B)
    dut.io.mult_sel_i.poke(false.B)
    dut.io.div_sel_i.poke(false.B)
    dut.clock.step()
    result
  }

  private def s32(x: BigInt): BigInt = {
    val y = u32(x)
    if ((y & BigInt("80000000", 16)) != 0) y - (BigInt(1) << 32) else y
  }

  private def u32(x: BigInt): BigInt = x & BigInt("ffffffff", 16)

  private def mulhExpected(signedMode: Int, a: BigInt, b: BigInt): BigInt = {
    val lhs = if ((signedMode & 1) != 0) s32(a) else u32(a)
    val rhs = if ((signedMode & 2) != 0) s32(b) else u32(b)
    u32((lhs * rhs) >> 32)
  }

  private def exercise(rv32m: Int): Unit = {
    simulate(new Harness(rv32m)) { dut =>
      init(dut)

      runOp(dut, MdOp.MULL, signedMode = 0, a = BigInt("12345678", 16), b = BigInt("11111111", 16), isDiv = false) mustBe
        u32(BigInt("12345678", 16) * BigInt("11111111", 16))
      runOp(dut, MdOp.MULH, signedMode = 0, a = BigInt("f2345678", 16), b = BigInt("80000000", 16), isDiv = false) mustBe
        mulhExpected(0, BigInt("f2345678", 16), BigInt("80000000", 16))
      runOp(dut, MdOp.MULH, signedMode = 3, a = BigInt("fffffffe", 16), b = 3, isDiv = false) mustBe
        mulhExpected(3, BigInt("fffffffe", 16), 3)
      runOp(dut, MdOp.MULH, signedMode = 1, a = BigInt("fffffffe", 16), b = 3, isDiv = false) mustBe
        mulhExpected(1, BigInt("fffffffe", 16), 3)

      runOp(dut, MdOp.DIV, signedMode = 3, a = BigInt("fffffff1", 16), b = 4, isDiv = true) mustBe BigInt("fffffffd", 16)
      runOp(dut, MdOp.REM, signedMode = 3, a = BigInt("fffffff1", 16), b = 4, isDiv = true) mustBe BigInt("fffffffd", 16)
      runOp(dut, MdOp.DIV, signedMode = 0, a = 123, b = 0, isDiv = true) mustBe BigInt("ffffffff", 16)
      runOp(dut, MdOp.REM, signedMode = 0, a = 123, b = 0, isDiv = true) mustBe 123
    }
  }

  "IbexMultDivFast" - {
    "computes multiply and divide operations with the fast multiplier generator" in {
      exercise(rv32m = 2)
    }

    "computes multiply and divide operations with the single-cycle multiplier generator" in {
      exercise(rv32m = 3)
    }
  }
}
