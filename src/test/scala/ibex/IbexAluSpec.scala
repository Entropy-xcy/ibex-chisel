package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexAluSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(rv32b: Int = 0) extends Module {
    val io = IO(new Bundle {
      val operator_i = Input(UInt(7.W))
      val operand_a_i = Input(UInt(32.W))
      val operand_b_i = Input(UInt(32.W))
      val instr_first_cycle_i = Input(Bool())
      val multdiv_operand_a_i = Input(UInt(33.W))
      val multdiv_operand_b_i = Input(UInt(33.W))
      val multdiv_sel_i = Input(Bool())
      val imd_val_q_i = Input(Vec(2, UInt(32.W)))
      val imd_val_d_o = Output(Vec(2, UInt(32.W)))
      val imd_val_we_o = Output(UInt(2.W))
      val adder_result_o = Output(UInt(32.W))
      val adder_result_ext_o = Output(UInt(34.W))
      val result_o = Output(UInt(32.W))
      val comparison_result_o = Output(Bool())
      val is_equal_result_o = Output(Bool())
    })

    val dut = Module(new IbexAlu(rv32b = rv32b))
    dut.operator_i := io.operator_i
    dut.operand_a_i := io.operand_a_i
    dut.operand_b_i := io.operand_b_i
    dut.instr_first_cycle_i := io.instr_first_cycle_i
    dut.multdiv_operand_a_i := io.multdiv_operand_a_i
    dut.multdiv_operand_b_i := io.multdiv_operand_b_i
    dut.multdiv_sel_i := io.multdiv_sel_i
    dut.imd_val_q_i := io.imd_val_q_i
    io.imd_val_d_o := dut.imd_val_d_o
    io.imd_val_we_o := dut.imd_val_we_o
    io.adder_result_o := dut.adder_result_o
    io.adder_result_ext_o := dut.adder_result_ext_o
    io.result_o := dut.result_o
    io.comparison_result_o := dut.comparison_result_o
    io.is_equal_result_o := dut.is_equal_result_o
  }

  private def op(name: String): UInt = IbexPkg.AluOp.encoding(name).U

  private def mask32(x: BigInt): BigInt = x & BigInt("ffffffff", 16)

  private def reverse32(x: BigInt): BigInt =
    (0 until 32).foldLeft(BigInt(0)) { (acc, i) => acc | (((x >> i) & 1) << (31 - i)) }

  private def clmulRaw(a: BigInt, b: BigInt): BigInt =
    mask32((0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
      if (((b >> i) & 1) == 1) acc ^ mask32(a << i) else acc
    })

  private def crcInterm(rs1: BigInt, crc32c: Boolean, bytes: Int): BigInt = {
    val muRev = if (crc32c) BigInt("dea713f1", 16) else BigInt("f7011641", 16)
    val crcOperand = bytes match {
      case 1 => mask32((rs1 & 0xff) << 24)
      case 2 => mask32((rs1 & 0xffff) << 16)
      case _ => mask32(rs1)
    }
    reverse32(clmulRaw(crcOperand, muRev))
  }

  private def crcFinal(rs1: BigInt, interm: BigInt, crc32c: Boolean, bytes: Int): BigInt = {
    val poly = if (crc32c) BigInt("1edc6f41", 16) else BigInt("04c11db7", 16)
    val folded = reverse32(clmulRaw(interm, poly))
    bytes match {
      case 1 => mask32(folded ^ (rs1 >> 8))
      case 2 => mask32(folded ^ (rs1 >> 16))
      case _ => folded
    }
  }

  private def bit(value: BigInt, idx: Int): Int = ((value >> idx) & 1).toInt

  private def popCount(value: BigInt): Int = value.bitCount

  private def bitcntPacked(mask: BigInt): (BigInt, BigInt) = {
    val partial = (0 until 32).map(i => popCount(mask & ((BigInt(1) << (i + 1)) - 1)))
    val lsb = (0 until 32).foldLeft(BigInt(0)) { (acc, i) => acc | (BigInt(partial(i) & 1) << i) }
    var msb = BigInt(0)
    for (i <- 0 until 16) {
      msb |= BigInt((partial(2 * i + 1) >> 1) & 1) << i
    }
    for (i <- 0 until 8) {
      msb |= BigInt((partial(4 * i + 3) >> 2) & 1) << (16 + i)
    }
    for (i <- 0 until 4) {
      msb |= BigInt((partial(8 * i + 7) >> 3) & 1) << (24 + i)
    }
    for (i <- 0 until 2) {
      msb |= BigInt((partial(16 * i + 15) >> 4) & 1) << (28 + i)
    }
    msb |= BigInt((partial(31) >> 5) & 1) << 30
    (lsb, msb)
  }

  private def bcompress(value: BigInt, mask: BigInt): BigInt = {
    var out = BigInt(0)
    var pos = 0
    for (i <- 0 until 32) {
      if (bit(mask, i) == 1) {
        out |= BigInt(bit(value, i)) << pos
        pos += 1
      }
    }
    out
  }

  private def bdecompress(value: BigInt, mask: BigInt): BigInt = {
    var out = BigInt(0)
    var pos = 0
    for (i <- 0 until 32) {
      if (bit(mask, i) == 1) {
        out |= BigInt(bit(value, pos)) << i
        pos += 1
      }
    }
    out
  }

  private def init(dut: Harness): Unit = {
    dut.io.operator_i.poke(op("ALU_ADD"))
    dut.io.operand_a_i.poke(0.U)
    dut.io.operand_b_i.poke(0.U)
    dut.io.instr_first_cycle_i.poke(true.B)
    dut.io.multdiv_operand_a_i.poke(0.U)
    dut.io.multdiv_operand_b_i.poke(0.U)
    dut.io.multdiv_sel_i.poke(false.B)
    dut.io.imd_val_q_i(0).poke(0.U)
    dut.io.imd_val_q_i(1).poke(0.U)
  }

  "IbexAlu" - {
    "implements add, subtract, and bitwise RV32I operations" in {
      simulate(new Harness()) { dut =>
        init(dut)

        dut.io.operator_i.poke(op("ALU_ADD"))
        dut.io.operand_a_i.poke("h00000012".U)
        dut.io.operand_b_i.poke("h00000034".U)
        dut.io.result_o.expect("h00000046".U)
        dut.io.adder_result_o.expect("h00000046".U)

        dut.io.operator_i.poke(op("ALU_SUB"))
        dut.io.operand_a_i.poke("h00000012".U)
        dut.io.operand_b_i.poke("h00000034".U)
        dut.io.result_o.expect("hffffffde".U)

        dut.io.operator_i.poke(op("ALU_XOR"))
        dut.io.operand_a_i.poke("hff00aa55".U)
        dut.io.operand_b_i.poke("h0ff055aa".U)
        dut.io.result_o.expect("hf0f0ffff".U)

        dut.io.operator_i.poke(op("ALU_AND"))
        dut.io.result_o.expect("h0f000000".U)

        dut.io.operator_i.poke(op("ALU_OR"))
        dut.io.result_o.expect("hfff0ffff".U)
      }
    }

    "implements signed and unsigned comparisons" in {
      simulate(new Harness()) { dut =>
        init(dut)
        dut.io.operand_a_i.poke("hffffffff".U)
        dut.io.operand_b_i.poke(1.U)

        dut.io.operator_i.poke(op("ALU_LT"))
        dut.io.result_o.expect(1.U)
        dut.io.comparison_result_o.expect(true.B)

        dut.io.operator_i.poke(op("ALU_LTU"))
        dut.io.result_o.expect(0.U)
        dut.io.comparison_result_o.expect(false.B)

        dut.io.operator_i.poke(op("ALU_EQ"))
        dut.io.operand_b_i.poke("hffffffff".U)
        dut.io.result_o.expect(1.U)
        dut.io.is_equal_result_o.expect(true.B)
      }
    }

    "implements logical and arithmetic shifts" in {
      simulate(new Harness()) { dut =>
        init(dut)

        dut.io.operator_i.poke(op("ALU_SLL"))
        dut.io.operand_a_i.poke(1.U)
        dut.io.operand_b_i.poke(8.U)
        dut.io.result_o.expect("h00000100".U)

        dut.io.operator_i.poke(op("ALU_SRL"))
        dut.io.operand_a_i.poke("h80000000".U)
        dut.io.operand_b_i.poke(4.U)
        dut.io.result_o.expect("h08000000".U)

        dut.io.operator_i.poke(op("ALU_SRA"))
        dut.io.result_o.expect("hf8000000".U)
      }
    }

    "uses multdiv operands for the shared adder when selected" in {
      simulate(new Harness()) { dut =>
        init(dut)
        dut.io.multdiv_sel_i.poke(true.B)
        dut.io.multdiv_operand_a_i.poke("h000000010".U)
        dut.io.multdiv_operand_b_i.poke("h000000020".U)
        dut.io.adder_result_ext_o.expect("h0000000030".U)
        dut.io.adder_result_o.expect("h00000018".U)
      }
    }

    "implements representative RV32B operations" in {
      simulate(new Harness(rv32b = 3)) { dut =>
        init(dut)

        dut.io.operator_i.poke(op("ALU_MIN"))
        dut.io.operand_a_i.poke("hffffffff".U)
        dut.io.operand_b_i.poke(1.U)
        dut.io.result_o.expect("hffffffff".U)

        dut.io.operator_i.poke(op("ALU_CLZ"))
        dut.io.operand_a_i.poke("h00f00000".U)
        dut.io.result_o.expect(8.U)

        dut.io.operator_i.poke(op("ALU_CPOP"))
        dut.io.operand_a_i.poke("hf0f0000f".U)
        dut.io.result_o.expect(12.U)

        dut.io.operator_i.poke(op("ALU_PACK"))
        dut.io.operand_a_i.poke("h11223344".U)
        dut.io.operand_b_i.poke("haabbccdd".U)
        dut.io.result_o.expect("hccdd3344".U)

        dut.io.operator_i.poke(op("ALU_BSET"))
        dut.io.operand_a_i.poke(0.U)
        dut.io.operand_b_i.poke(5.U)
        dut.io.result_o.expect("h00000020".U)
      }
    }

    "implements two-cycle ternary operations and zero-shift rotate results" in {
      simulate(new Harness(rv32b = 3)) { dut =>
        init(dut)

        dut.io.operator_i.poke(op("ALU_CMOV"))
        dut.io.operand_a_i.poke("h11112222".U)
        dut.io.operand_b_i.poke("h00000001".U)
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect("h11112222".U)
        dut.io.imd_val_we_o.expect("b01".U)

        dut.io.operand_a_i.poke("h33334444".U)
        dut.io.imd_val_q_i(0).poke("h11112222".U)
        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.result_o.expect("h11112222".U)
        dut.io.imd_val_we_o.expect(0.U)

        dut.io.operand_b_i.poke(0.U)
        dut.io.result_o.expect("h33334444".U)

        dut.io.operator_i.poke(op("ALU_CMIX"))
        dut.io.operand_a_i.poke("haaaa5555".U)
        dut.io.operand_b_i.poke("h0f0ff0f0".U)
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect("h0a0a5050".U)
        dut.io.imd_val_we_o.expect("b01".U)

        dut.io.operand_a_i.poke("h12345678".U)
        dut.io.imd_val_q_i(0).poke("h0a0a5050".U)
        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.result_o.expect("h1a3a5658".U)
        dut.io.imd_val_we_o.expect(0.U)

        dut.io.operator_i.poke(op("ALU_ROL"))
        dut.io.operand_a_i.poke("h89abcdef".U)
        dut.io.operand_b_i.poke(0.U)
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect("h89abcdef".U)
        dut.io.imd_val_we_o.expect("b01".U)

        dut.io.operand_a_i.poke("h01234567".U)
        dut.io.imd_val_q_i(0).poke("h89abcdef".U)
        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.result_o.expect("h89abcdef".U)
        dut.io.imd_val_we_o.expect(0.U)
      }
    }

    "implements CRC32 and CRC32C two-cycle CLMUL folding" in {
      simulate(new Harness(rv32b = 3)) { dut =>
        init(dut)
        val rs1 = BigInt("12345678", 16)
        val crc32bInterm = crcInterm(rs1, crc32c = false, bytes = 1)
        val crc32bFinal = crcFinal(rs1, crc32bInterm, crc32c = false, bytes = 1)

        dut.io.operator_i.poke(op("ALU_CRC32_B"))
        dut.io.operand_a_i.poke(rs1.U)
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect(crc32bInterm.U)
        dut.io.imd_val_we_o.expect("b01".U)

        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.imd_val_q_i(0).poke(crc32bInterm.U)
        dut.io.result_o.expect(crc32bFinal.U)
        dut.io.imd_val_we_o.expect(0.U)

        val crc32cWInterm = crcInterm(rs1, crc32c = true, bytes = 4)
        val crc32cWFinal = crcFinal(rs1, crc32cWInterm, crc32c = true, bytes = 4)
        dut.io.operator_i.poke(op("ALU_CRC32C_W"))
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect(crc32cWInterm.U)
        dut.io.imd_val_we_o.expect("b01".U)

        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.imd_val_q_i(0).poke(crc32cWInterm.U)
        dut.io.result_o.expect(crc32cWFinal.U)
      }
    }

    "writes BCOMPRESS/BDECOMPRESS bitcount intermediates and returns packed results" in {
      simulate(new Harness(rv32b = 3)) { dut =>
        init(dut)
        val value = BigInt("deadbeef", 16)
        val mask = BigInt("0f0ff00f", 16)
        val (lsb, msb) = bitcntPacked(mask)

        dut.io.operator_i.poke(op("ALU_BCOMPRESS"))
        dut.io.operand_a_i.poke(value.U)
        dut.io.operand_b_i.poke(mask.U)
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect(lsb.U)
        dut.io.imd_val_d_o(1).expect(msb.U)
        dut.io.imd_val_we_o.expect("b11".U)

        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.imd_val_q_i(0).poke(lsb.U)
        dut.io.imd_val_q_i(1).poke(msb.U)
        dut.io.imd_val_we_o.expect(0.U)
        dut.io.result_o.expect(bcompress(value, mask).U)

        dut.io.operator_i.poke(op("ALU_BDECOMPRESS"))
        dut.io.operand_a_i.poke(bcompress(value, mask).U)
        dut.io.operand_b_i.poke(mask.U)
        dut.io.instr_first_cycle_i.poke(true.B)
        dut.io.imd_val_d_o(0).expect(lsb.U)
        dut.io.imd_val_d_o(1).expect(msb.U)
        dut.io.imd_val_we_o.expect("b11".U)

        dut.io.instr_first_cycle_i.poke(false.B)
        dut.io.imd_val_q_i(0).poke(lsb.U)
        dut.io.imd_val_q_i(1).poke(msb.U)
        dut.io.imd_val_we_o.expect(0.U)
        dut.io.result_o.expect(bdecompress(bcompress(value, mask), mask).U)
      }
    }
  }
}
