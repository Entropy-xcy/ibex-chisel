package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexDecoderSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(rv32e: Boolean = false, rv32m: Int = 2, rv32b: Int = 0, branchTargetALU: Boolean = false) extends Module {
    val io = IO(new Bundle {
      val branch_taken_i = Input(Bool())
      val instr_first_cycle_i = Input(Bool())
      val instr_rdata_i = Input(UInt(32.W))
      val instr_rdata_alu_i = Input(UInt(32.W))
      val illegal_c_insn_i = Input(Bool())

      val illegal_insn_o = Output(Bool())
      val ebrk_insn_o = Output(Bool())
      val mret_insn_o = Output(Bool())
      val dret_insn_o = Output(Bool())
      val ecall_insn_o = Output(Bool())
      val wfi_insn_o = Output(Bool())
      val jump_set_o = Output(Bool())
      val icache_inval_o = Output(Bool())
      val imm_a_mux_sel_o = Output(UInt(1.W))
      val imm_b_mux_sel_o = Output(UInt(3.W))
      val bt_a_mux_sel_o = Output(UInt(2.W))
      val bt_b_mux_sel_o = Output(UInt(3.W))
      val imm_i_type_o = Output(UInt(32.W))
      val imm_s_type_o = Output(UInt(32.W))
      val imm_b_type_o = Output(UInt(32.W))
      val imm_u_type_o = Output(UInt(32.W))
      val imm_j_type_o = Output(UInt(32.W))
      val zimm_rs1_type_o = Output(UInt(32.W))
      val rf_wdata_sel_o = Output(UInt(1.W))
      val rf_we_o = Output(Bool())
      val rf_raddr_a_o = Output(UInt(5.W))
      val rf_raddr_b_o = Output(UInt(5.W))
      val rf_waddr_o = Output(UInt(5.W))
      val rf_ren_a_o = Output(Bool())
      val rf_ren_b_o = Output(Bool())
      val alu_operator_o = Output(UInt(7.W))
      val alu_op_a_mux_sel_o = Output(UInt(2.W))
      val alu_op_b_mux_sel_o = Output(UInt(1.W))
      val alu_multicycle_o = Output(Bool())
      val mult_en_o = Output(Bool())
      val div_en_o = Output(Bool())
      val mult_sel_o = Output(Bool())
      val div_sel_o = Output(Bool())
      val multdiv_operator_o = Output(UInt(2.W))
      val multdiv_signed_mode_o = Output(UInt(2.W))
      val csr_access_o = Output(Bool())
      val csr_op_o = Output(UInt(2.W))
      val csr_addr_o = Output(UInt(12.W))
      val data_req_o = Output(Bool())
      val data_we_o = Output(Bool())
      val data_type_o = Output(UInt(2.W))
      val data_sign_extension_o = Output(Bool())
      val jump_in_dec_o = Output(Bool())
      val branch_in_dec_o = Output(Bool())
    })

    val dut = Module(new IbexDecoder(rv32e = rv32e, rv32m = rv32m, rv32b = rv32b, branchTargetALU = branchTargetALU))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.branch_taken_i := io.branch_taken_i
    dut.instr_first_cycle_i := io.instr_first_cycle_i
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_rdata_alu_i := io.instr_rdata_alu_i
    dut.illegal_c_insn_i := io.illegal_c_insn_i

    io.illegal_insn_o := dut.illegal_insn_o
    io.ebrk_insn_o := dut.ebrk_insn_o
    io.mret_insn_o := dut.mret_insn_o
    io.dret_insn_o := dut.dret_insn_o
    io.ecall_insn_o := dut.ecall_insn_o
    io.wfi_insn_o := dut.wfi_insn_o
    io.jump_set_o := dut.jump_set_o
    io.icache_inval_o := dut.icache_inval_o
    io.imm_a_mux_sel_o := dut.imm_a_mux_sel_o
    io.imm_b_mux_sel_o := dut.imm_b_mux_sel_o
    io.bt_a_mux_sel_o := dut.bt_a_mux_sel_o
    io.bt_b_mux_sel_o := dut.bt_b_mux_sel_o
    io.imm_i_type_o := dut.imm_i_type_o
    io.imm_s_type_o := dut.imm_s_type_o
    io.imm_b_type_o := dut.imm_b_type_o
    io.imm_u_type_o := dut.imm_u_type_o
    io.imm_j_type_o := dut.imm_j_type_o
    io.zimm_rs1_type_o := dut.zimm_rs1_type_o
    io.rf_wdata_sel_o := dut.rf_wdata_sel_o
    io.rf_we_o := dut.rf_we_o
    io.rf_raddr_a_o := dut.rf_raddr_a_o
    io.rf_raddr_b_o := dut.rf_raddr_b_o
    io.rf_waddr_o := dut.rf_waddr_o
    io.rf_ren_a_o := dut.rf_ren_a_o
    io.rf_ren_b_o := dut.rf_ren_b_o
    io.alu_operator_o := dut.alu_operator_o
    io.alu_op_a_mux_sel_o := dut.alu_op_a_mux_sel_o
    io.alu_op_b_mux_sel_o := dut.alu_op_b_mux_sel_o
    io.alu_multicycle_o := dut.alu_multicycle_o
    io.mult_en_o := dut.mult_en_o
    io.div_en_o := dut.div_en_o
    io.mult_sel_o := dut.mult_sel_o
    io.div_sel_o := dut.div_sel_o
    io.multdiv_operator_o := dut.multdiv_operator_o
    io.multdiv_signed_mode_o := dut.multdiv_signed_mode_o
    io.csr_access_o := dut.csr_access_o
    io.csr_op_o := dut.csr_op_o
    io.csr_addr_o := dut.csr_addr_o
    io.data_req_o := dut.data_req_o
    io.data_we_o := dut.data_we_o
    io.data_type_o := dut.data_type_o
    io.data_sign_extension_o := dut.data_sign_extension_o
    io.jump_in_dec_o := dut.jump_in_dec_o
    io.branch_in_dec_o := dut.branch_in_dec_o
  }

  private object Sel {
    val OpARegA = 0
    val OpACurrPc = 2
    val OpAImm = 3
    val OpBRegB = 0
    val OpBImm = 1
    val ImmBZ = 0
    val ImmBS = 1
    val ImmBB = 2
    val ImmBU = 3
    val ImmBJ = 4
    val ImmBIncrPc = 5
    val RfWdCsr = 1
    val CsrRead = 0
    val CsrWrite = 1
    val CsrSet = 2
    val MdMull = 0
    val MdMulh = 1
    val MdDiv = 2
  }

  private def alu(name: String): UInt = IbexPkg.AluOp.encoding(name).U

  private def init(dut: Harness): Unit = {
    dut.io.branch_taken_i.poke(false.B)
    dut.io.instr_first_cycle_i.poke(true.B)
    dut.io.instr_rdata_i.poke(0.U)
    dut.io.instr_rdata_alu_i.poke(0.U)
    dut.io.illegal_c_insn_i.poke(false.B)
  }

  private def pokeInstr(dut: Harness, instr: BigInt): Unit = {
    dut.io.instr_rdata_i.poke(instr.U)
    dut.io.instr_rdata_alu_i.poke(instr.U)
  }

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) | (BigInt(rd) << 7) | opcode

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int = 0x33): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) | (BigInt(rd) << 7) | opcode

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(imm & 0x1f) << 7) | 0x23

  private def bType(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt =
    (BigInt((imm >> 12) & 1) << 31) | (BigInt((imm >> 5) & 0x3f) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt((imm >> 1) & 0xf) << 8) | (BigInt((imm >> 11) & 1) << 7) | 0x63

  "IbexDecoder" - {
    "decodes RV32I ALU immediate and register operations" in {
      simulate(new Harness()) { dut =>
        init(dut)

        pokeInstr(dut, iType(12, rs1 = 2, funct3 = 0, rd = 1, opcode = 0x13))
        dut.io.illegal_insn_o.expect(false.B)
        dut.io.rf_ren_a_o.expect(true.B)
        dut.io.rf_we_o.expect(true.B)
        dut.io.rf_raddr_a_o.expect(2.U)
        dut.io.rf_waddr_o.expect(1.U)
        dut.io.alu_operator_o.expect(alu("ALU_ADD"))
        dut.io.alu_op_a_mux_sel_o.expect(Sel.OpARegA.U)
        dut.io.alu_op_b_mux_sel_o.expect(Sel.OpBImm.U)
        dut.io.imm_b_mux_sel_o.expect(Sel.ImmBZ.U)
        dut.io.imm_i_type_o.expect(12.U)

        pokeInstr(dut, rType(0x20, rs2 = 4, rs1 = 3, funct3 = 0, rd = 5))
        dut.io.illegal_insn_o.expect(false.B)
        dut.io.rf_ren_a_o.expect(true.B)
        dut.io.rf_ren_b_o.expect(true.B)
        dut.io.rf_we_o.expect(true.B)
        dut.io.rf_raddr_a_o.expect(3.U)
        dut.io.rf_raddr_b_o.expect(4.U)
        dut.io.rf_waddr_o.expect(5.U)
        dut.io.alu_operator_o.expect(alu("ALU_SUB"))
        dut.io.alu_op_a_mux_sel_o.expect(Sel.OpARegA.U)
        dut.io.alu_op_b_mux_sel_o.expect(Sel.OpBRegB.U)
      }
    }

    "decodes load, store, branch, and jump controls" in {
      simulate(new Harness(branchTargetALU = true)) { dut =>
        init(dut)

        pokeInstr(dut, iType(8, rs1 = 2, funct3 = 2, rd = 1, opcode = 0x03)) // lw
        dut.io.data_req_o.expect(true.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.data_type_o.expect(0.U)
        dut.io.data_sign_extension_o.expect(true.B)
        dut.io.rf_we_o.expect(false.B)
        dut.io.alu_operator_o.expect(alu("ALU_ADD"))
        dut.io.alu_op_a_mux_sel_o.expect(Sel.OpARegA.U)

        pokeInstr(dut, iType(0, rs1 = 10, funct3 = 4, rd = 11, opcode = 0x03)) // lbu
        dut.io.data_req_o.expect(true.B)
        dut.io.data_we_o.expect(false.B)
        dut.io.data_type_o.expect(2.U)
        dut.io.data_sign_extension_o.expect(false.B)
        dut.io.rf_we_o.expect(false.B)
        dut.io.rf_ren_a_o.expect(true.B)
        dut.io.rf_waddr_o.expect(11.U)

        pokeInstr(dut, sType(12, rs2 = 5, rs1 = 3, funct3 = 1)) // sh
        dut.io.data_req_o.expect(true.B)
        dut.io.data_we_o.expect(true.B)
        dut.io.data_type_o.expect(1.U)
        dut.io.rf_ren_a_o.expect(true.B)
        dut.io.rf_ren_b_o.expect(true.B)
        dut.io.imm_b_mux_sel_o.expect(Sel.ImmBS.U)

        pokeInstr(dut, bType(16, rs2 = 2, rs1 = 1, funct3 = 0)) // beq
        dut.io.branch_in_dec_o.expect(true.B)
        dut.io.alu_operator_o.expect(alu("ALU_EQ"))
        dut.io.bt_b_mux_sel_o.expect(Sel.ImmBIncrPc.U)
        dut.io.branch_taken_i.poke(true.B)
        dut.io.bt_b_mux_sel_o.expect(Sel.ImmBB.U)

        pokeInstr(dut, BigInt("000000ef", 16)) // jal x1, 0
        dut.io.jump_in_dec_o.expect(true.B)
        dut.io.jump_set_o.expect(true.B)
        dut.io.rf_we_o.expect(true.B)
        dut.io.bt_b_mux_sel_o.expect(Sel.ImmBJ.U)
      }
    }

    "decodes system and CSR instructions" in {
      simulate(new Harness()) { dut =>
        init(dut)

        pokeInstr(dut, BigInt("00000073", 16)) // ecall
        dut.io.ecall_insn_o.expect(true.B)
        dut.io.illegal_insn_o.expect(false.B)
        dut.io.rf_we_o.expect(false.B)

        pokeInstr(dut, iType(0x300, rs1 = 2, funct3 = 1, rd = 1, opcode = 0x73)) // csrrw
        dut.io.csr_access_o.expect(true.B)
        dut.io.rf_wdata_sel_o.expect(Sel.RfWdCsr.U)
        dut.io.rf_we_o.expect(true.B)
        dut.io.rf_ren_a_o.expect(true.B)
        dut.io.csr_addr_o.expect("h300".U)
        dut.io.csr_op_o.expect(Sel.CsrWrite.U)

        pokeInstr(dut, iType(0x300, rs1 = 0, funct3 = 2, rd = 1, opcode = 0x73)) // csrrs x1, mstatus, x0
        dut.io.csr_access_o.expect(true.B)
        dut.io.csr_op_o.expect(Sel.CsrRead.U)
      }
    }

    "gates multdiv instructions by RV32M configuration" in {
      simulate(new Harness(rv32m = 2)) { dut =>
        init(dut)

        pokeInstr(dut, rType(1, rs2 = 4, rs1 = 3, funct3 = 1, rd = 5)) // mulh
        dut.io.illegal_insn_o.expect(false.B)
        dut.io.mult_sel_o.expect(true.B)
        dut.io.mult_en_o.expect(true.B)
        dut.io.div_sel_o.expect(false.B)
        dut.io.multdiv_operator_o.expect(Sel.MdMulh.U)
        dut.io.multdiv_signed_mode_o.expect(3.U)

        pokeInstr(dut, rType(1, rs2 = 4, rs1 = 3, funct3 = 4, rd = 5)) // div
        dut.io.div_sel_o.expect(true.B)
        dut.io.div_en_o.expect(true.B)
        dut.io.multdiv_operator_o.expect(Sel.MdDiv.U)
        dut.io.multdiv_signed_mode_o.expect(3.U)
      }

      simulate(new Harness(rv32m = 0)) { dut =>
        init(dut)

        pokeInstr(dut, rType(1, rs2 = 4, rs1 = 3, funct3 = 0, rd = 5)) // mul
        dut.io.illegal_insn_o.expect(true.B)
        dut.io.mult_sel_o.expect(false.B)
        dut.io.mult_en_o.expect(false.B)
        dut.io.rf_we_o.expect(false.B)
      }
    }

    "flags illegal compressed and RV32E register accesses" in {
      simulate(new Harness()) { dut =>
        init(dut)

        pokeInstr(dut, BigInt("8596c557", 16)) // unsupported vector opcode
        dut.io.illegal_insn_o.expect(true.B)
        dut.io.rf_we_o.expect(false.B)

        pokeInstr(dut, BigInt("b39e31ab", 16)) // unsupported custom opcode
        dut.io.illegal_insn_o.expect(true.B)
        dut.io.rf_we_o.expect(false.B)

        pokeInstr(dut, iType(1, rs1 = 1, funct3 = 0, rd = 1, opcode = 0x13))
        dut.io.illegal_c_insn_i.poke(true.B)
        dut.io.illegal_insn_o.expect(true.B)
        dut.io.rf_we_o.expect(false.B)
      }

      simulate(new Harness(rv32e = true)) { dut =>
        init(dut)

        pokeInstr(dut, iType(1, rs1 = 16, funct3 = 0, rd = 1, opcode = 0x13))
        dut.io.illegal_insn_o.expect(true.B)
        dut.io.rf_we_o.expect(false.B)
      }
    }
  }
}
