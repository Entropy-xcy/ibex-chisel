// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexDecoder(rv32e: Boolean = false, rv32m: Int = 2, rv32b: Int = 0, branchTargetALU: Boolean = false)
    extends RawModule {
  require(0 <= rv32m && rv32m <= 3)
  require(0 <= rv32b && rv32b <= 3)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val illegal_insn_o = IO(Output(Bool()))
  val ebrk_insn_o = IO(Output(Bool()))
  val mret_insn_o = IO(Output(Bool()))
  val dret_insn_o = IO(Output(Bool()))
  val ecall_insn_o = IO(Output(Bool()))
  val wfi_insn_o = IO(Output(Bool()))
  val jump_set_o = IO(Output(Bool()))
  val branch_taken_i = IO(Input(Bool()))
  val icache_inval_o = IO(Output(Bool()))

  val instr_first_cycle_i = IO(Input(Bool()))
  val instr_rdata_i = IO(Input(UInt(32.W)))
  val instr_rdata_alu_i = IO(Input(UInt(32.W)))
  val illegal_c_insn_i = IO(Input(Bool()))

  val imm_a_mux_sel_o = IO(Output(UInt(1.W)))
  val imm_b_mux_sel_o = IO(Output(UInt(3.W)))
  val bt_a_mux_sel_o = IO(Output(UInt(2.W)))
  val bt_b_mux_sel_o = IO(Output(UInt(3.W)))
  val imm_i_type_o = IO(Output(UInt(32.W)))
  val imm_s_type_o = IO(Output(UInt(32.W)))
  val imm_b_type_o = IO(Output(UInt(32.W)))
  val imm_u_type_o = IO(Output(UInt(32.W)))
  val imm_j_type_o = IO(Output(UInt(32.W)))
  val zimm_rs1_type_o = IO(Output(UInt(32.W)))

  val rf_wdata_sel_o = IO(Output(UInt(1.W)))
  val rf_we_o = IO(Output(Bool()))
  val rf_raddr_a_o = IO(Output(UInt(5.W)))
  val rf_raddr_b_o = IO(Output(UInt(5.W)))
  val rf_waddr_o = IO(Output(UInt(5.W)))
  val rf_ren_a_o = IO(Output(Bool()))
  val rf_ren_b_o = IO(Output(Bool()))

  val alu_operator_o = IO(Output(UInt(7.W)))
  val alu_op_a_mux_sel_o = IO(Output(UInt(2.W)))
  val alu_op_b_mux_sel_o = IO(Output(UInt(1.W)))
  val alu_multicycle_o = IO(Output(Bool()))

  val mult_en_o = IO(Output(Bool()))
  val div_en_o = IO(Output(Bool()))
  val mult_sel_o = IO(Output(Bool()))
  val div_sel_o = IO(Output(Bool()))
  val multdiv_operator_o = IO(Output(UInt(2.W)))
  val multdiv_signed_mode_o = IO(Output(UInt(2.W)))

  val csr_access_o = IO(Output(Bool()))
  val csr_op_o = IO(Output(UInt(2.W)))
  val csr_addr_o = IO(Output(UInt(12.W)))

  val data_req_o = IO(Output(Bool()))
  val data_we_o = IO(Output(Bool()))
  val data_type_o = IO(Output(UInt(2.W)))
  val data_sign_extension_o = IO(Output(Bool()))

  val jump_in_dec_o = IO(Output(Bool()))
  val branch_in_dec_o = IO(Output(Bool()))

  private object Op {
    val LOAD = "h03".U(7.W)
    val MISC_MEM = "h0f".U(7.W)
    val OP_IMM = "h13".U(7.W)
    val AUIPC = "h17".U(7.W)
    val STORE = "h23".U(7.W)
    val OP = "h33".U(7.W)
    val LUI = "h37".U(7.W)
    val BRANCH = "h63".U(7.W)
    val JALR = "h67".U(7.W)
    val JAL = "h6f".U(7.W)
    val SYSTEM = "h73".U(7.W)
  }

  private object OpA {
    val REG_A = 0.U(2.W)
    val FWD = 1.U(2.W)
    val CURRPC = 2.U(2.W)
    val IMM = 3.U(2.W)
  }
  private object ImmA {
    val Z = 0.U(1.W)
    val ZERO = 1.U(1.W)
  }
  private object OpB {
    val REG_B = 0.U(1.W)
    val IMM = 1.U(1.W)
  }
  private object ImmB {
    val I = 0.U(3.W)
    val S = 1.U(3.W)
    val B = 2.U(3.W)
    val U = 3.U(3.W)
    val J = 4.U(3.W)
    val INCR_PC = 5.U(3.W)
    val INCR_ADDR = 6.U(3.W)
  }
  private object RfWd {
    val EX = 0.U(1.W)
    val CSR = 1.U(1.W)
  }
  private object CsrOp {
    val READ = 0.U(2.W)
    val WRITE = 1.U(2.W)
    val SET = 2.U(2.W)
    val CLEAR = 3.U(2.W)
  }
  private object MdOp {
    val MULL = 0.U(2.W)
    val MULH = 1.U(2.W)
    val DIV = 2.U(2.W)
    val REM = 3.U(2.W)
  }

  private val alu = IbexPkg.AluOp.encoding.map { case (name, value) => name -> value.U(7.W) }
  private def rvbAny: Boolean = rv32b != 0
  private def rvbOtOrFull: Boolean = rv32b == 2 || rv32b == 3
  private def rvbFull: Boolean = rv32b == 3
  private def rv32mEnabled: Boolean = rv32m != 0
  private def bitPat(x: UInt): UInt = Cat(x(31, 25), x(14, 12))

  val instr = instr_rdata_i
  val instr_alu = instr_rdata_alu_i
  val instr_rs1 = instr(19, 15)
  val instr_rs2 = instr(24, 20)
  val instr_rs3 = instr(31, 27)
  val instr_rd = instr(11, 7)

  imm_i_type_o := Cat(Fill(20, instr(31)), instr(31, 20))
  imm_s_type_o := Cat(Fill(20, instr(31)), instr(31, 25), instr(11, 7))
  imm_b_type_o := Cat(Fill(19, instr(31)), instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
  imm_u_type_o := Cat(instr(31, 12), 0.U(12.W))
  imm_j_type_o := Cat(Fill(12, instr(31)), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))
  csr_addr_o := instr(31, 20)
  zimm_rs1_type_o := Cat(0.U(27.W), instr_rs1)

  val use_rs3_d = WireDefault(false.B)
  val use_rs3_q =
    if (rvbAny) withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegNext(use_rs3_d, false.B) }
    else use_rs3_d

  rf_raddr_a_o := Mux(use_rs3_q && !instr_first_cycle_i, instr_rs3, instr_rs1)
  rf_raddr_b_o := instr_rs2
  rf_waddr_o := instr_rd

  val illegal_insn = WireDefault(false.B)
  val csr_illegal = WireDefault(false.B)
  val rf_we = WireDefault(false.B)
  val csr_op = WireDefault(CsrOp.READ)

  jump_in_dec_o := false.B
  jump_set_o := false.B
  branch_in_dec_o := false.B
  icache_inval_o := false.B
  multdiv_operator_o := MdOp.MULL
  multdiv_signed_mode_o := 0.U
  rf_wdata_sel_o := RfWd.EX
  rf_ren_a_o := false.B
  rf_ren_b_o := false.B
  csr_access_o := false.B
  data_we_o := false.B
  data_type_o := 0.U
  data_sign_extension_o := false.B
  data_req_o := false.B
  ebrk_insn_o := false.B
  mret_insn_o := false.B
  dret_insn_o := false.B
  ecall_insn_o := false.B
  wfi_insn_o := false.B

  switch(instr(6, 0)) {
    is(Op.JAL) {
      jump_in_dec_o := true.B
      when(instr_first_cycle_i) {
        rf_we := branchTargetALU.B
        jump_set_o := true.B
      }.otherwise {
        rf_we := true.B
      }
    }
    is(Op.JALR) {
      jump_in_dec_o := true.B
      when(instr_first_cycle_i) {
        rf_we := branchTargetALU.B
        jump_set_o := true.B
      }.otherwise {
        rf_we := true.B
      }
      when(instr(14, 12) =/= 0.U) { illegal_insn := true.B }
      rf_ren_a_o := true.B
    }
    is(Op.BRANCH) {
      branch_in_dec_o := true.B
      when(!Seq(0, 1, 4, 5, 6, 7).map(_.U === instr(14, 12)).reduce(_ || _)) {
        illegal_insn := true.B
      }
      rf_ren_a_o := true.B
      rf_ren_b_o := true.B
    }
    is(Op.STORE) {
      rf_ren_a_o := true.B
      rf_ren_b_o := true.B
      data_req_o := true.B
      data_we_o := true.B
      when(instr(14)) { illegal_insn := true.B }
      switch(instr(13, 12)) {
        is(0.U) { data_type_o := "b10".U }
        is(1.U) { data_type_o := "b01".U }
        is(2.U) { data_type_o := "b00".U }
        is(3.U) { illegal_insn := true.B }
      }
    }
    is(Op.LOAD) {
      rf_ren_a_o := true.B
      data_req_o := true.B
      data_type_o := 0.U
      data_sign_extension_o := !instr(14)
      switch(instr(13, 12)) {
        is(0.U) { data_type_o := "b10".U }
        is(1.U) { data_type_o := "b01".U }
        is(2.U) {
          data_type_o := "b00".U
          when(instr(14)) { illegal_insn := true.B }
        }
        is(3.U) { illegal_insn := true.B }
      }
    }
    is(Op.LUI, Op.AUIPC) {
      rf_we := true.B
    }
    is(Op.OP_IMM) {
      rf_ren_a_o := true.B
      rf_we := true.B
      switch(instr(14, 12)) {
        is(0.U, 2.U, 3.U, 4.U, 6.U, 7.U) { illegal_insn := false.B }
        is(1.U) {
          switch(instr(31, 27)) {
            is("b00000".U) { illegal_insn := instr(26, 25) =/= 0.U }
            is("b00100".U) { illegal_insn := (!rvbOtOrFull).B }
            is("b01001".U, "b00101".U, "b01101".U) { illegal_insn := (!rvbAny).B }
            is("b00001".U) { illegal_insn := (!rvbOtOrFull).B || instr(26) }
            is("b01100".U) {
              val legalBasic = Seq(0, 1, 2, 4, 5).map(_.U === instr(26, 20)).reduce(_ || _) && rvbAny.B
              val legalCrc = Seq(0x10, 0x11, 0x12, 0x18, 0x19, 0x1a).map(_.U === instr(26, 20)).reduce(_ || _) && rvbOtOrFull.B
              illegal_insn := !(legalBasic || legalCrc)
            }
            is("b01010".U) { illegal_insn := true.B }
            is("b01011".U) { illegal_insn := true.B }
            is("b01110".U) { illegal_insn := true.B }
            is("b01111".U) { illegal_insn := true.B }
            is("b10000".U) { illegal_insn := true.B }
            is("b10001".U) { illegal_insn := true.B }
            is("b10010".U) { illegal_insn := true.B }
            is("b10011".U) { illegal_insn := true.B }
            is("b10100".U) { illegal_insn := true.B }
            is("b10101".U) { illegal_insn := true.B }
            is("b10110".U) { illegal_insn := true.B }
            is("b10111".U) { illegal_insn := true.B }
            is("b11000".U) { illegal_insn := true.B }
            is("b11001".U) { illegal_insn := true.B }
            is("b11010".U) { illegal_insn := true.B }
            is("b11011".U) { illegal_insn := true.B }
            is("b11100".U) { illegal_insn := true.B }
            is("b11101".U) { illegal_insn := true.B }
            is("b11110".U) { illegal_insn := true.B }
            is("b11111".U) { illegal_insn := true.B }
          }
        }
        is(5.U) {
          when(instr(26)) {
            illegal_insn := (!rvbAny).B
          }.otherwise {
            switch(instr(31, 27)) {
              is("b00000".U, "b01000".U) { illegal_insn := instr(26, 25) =/= 0.U }
              is("b00100".U) { illegal_insn := (!rvbOtOrFull).B }
              is("b01100".U, "b01001".U) { illegal_insn := (!rvbAny).B }
              is("b01101".U) { illegal_insn := Mux(rvbOtOrFull.B, false.B, Mux(rvbAny.B, instr(24, 20) =/= "b11000".U, true.B)) }
              is("b00101".U) { illegal_insn := Mux(rvbOtOrFull.B, false.B, Mux(instr(24, 20) === "b00111".U, (!rvbAny).B, true.B)) }
              is("b00001".U) { illegal_insn := (!rvbOtOrFull).B }
            }
          }
        }
      }
    }
    is(Op.OP) {
      rf_ren_a_o := true.B
      rf_ren_b_o := true.B
      rf_we := true.B
      when(instr(26) && instr(13, 12) === 1.U) {
        illegal_insn := (!rvbAny).B
      }.otherwise {
        val pat = bitPat(instr)
        val legalRv32i = Seq("b0000000000", "b0100000000", "b0000000010", "b0000000011", "b0000000100",
          "b0000000110", "b0000000111", "b0000000001", "b0000000101", "b0100000101").map(_.U(10.W) === pat).reduce(_ || _)
        val legalBAny = Seq("b0010000010", "b0010000100", "b0010000110", "b0100000111", "b0100000110",
          "b0100000100", "b0110000001", "b0110000101", "b0000101100", "b0000101110", "b0000101101",
          "b0000101111", "b0000100100", "b0100100100", "b0000100111", "b0100100001", "b0010100001",
          "b0110100001", "b0100100101", "b0100100111").map(_.U(10.W) === pat).reduce(_ || _) && rvbAny.B
        val legalBOt = Seq("b0110100101", "b0010100101", "b0000100001", "b0000100101", "b0010100010",
          "b0010100100", "b0010100110", "b0010000001", "b0010000101", "b0000101001", "b0000101010",
          "b0000101011").map(_.U(10.W) === pat).reduce(_ || _) && rvbOtOrFull.B
        val legalBFull = Seq("b0100100110", "b0000100110").map(_.U(10.W) === pat).reduce(_ || _) && rvbFull.B
        val legalM = instr(31, 25) === "b0000001".U && rv32mEnabled.B
        illegal_insn := !(legalRv32i || legalBAny || legalBOt || legalBFull || legalM)
        when(instr(31, 25) === "b0000001".U) {
          switch(instr(14, 12)) {
            is(0.U) { multdiv_operator_o := MdOp.MULL; multdiv_signed_mode_o := 0.U }
            is(1.U) { multdiv_operator_o := MdOp.MULH; multdiv_signed_mode_o := 3.U }
            is(2.U) { multdiv_operator_o := MdOp.MULH; multdiv_signed_mode_o := 1.U }
            is(3.U) { multdiv_operator_o := MdOp.MULH; multdiv_signed_mode_o := 0.U }
            is(4.U) { multdiv_operator_o := MdOp.DIV; multdiv_signed_mode_o := 3.U }
            is(5.U) { multdiv_operator_o := MdOp.DIV; multdiv_signed_mode_o := 0.U }
            is(6.U) { multdiv_operator_o := MdOp.REM; multdiv_signed_mode_o := 3.U }
            is(7.U) { multdiv_operator_o := MdOp.REM; multdiv_signed_mode_o := 0.U }
          }
        }
      }
    }
    is(Op.MISC_MEM) {
      switch(instr(14, 12)) {
        is(0.U) { rf_we := false.B }
        is(1.U) {
          jump_in_dec_o := true.B
          when(instr_first_cycle_i) {
            jump_set_o := true.B
            icache_inval_o := true.B
          }
        }
        is(2.U, 3.U, 4.U, 5.U, 6.U, 7.U) { illegal_insn := true.B }
      }
    }
    is(Op.SYSTEM) {
      when(instr(14, 12) === 0.U) {
        switch(instr(31, 20)) {
          is("h000".U) { ecall_insn_o := true.B }
          is("h001".U) { ebrk_insn_o := true.B }
          is("h302".U) { mret_insn_o := true.B }
          is("h7b2".U) { dret_insn_o := true.B }
          is("h105".U) { wfi_insn_o := true.B }
        }
        when(!Seq("h000".U, "h001".U, "h302".U, "h7b2".U, "h105".U).map(_ === instr(31, 20)).reduce(_ || _)) {
          illegal_insn := true.B
        }
        when(instr_rs1 =/= 0.U || instr_rd =/= 0.U) { illegal_insn := true.B }
      }.otherwise {
        csr_access_o := true.B
        rf_wdata_sel_o := RfWd.CSR
        rf_we := true.B
        when(!instr(14)) { rf_ren_a_o := true.B }
        switch(instr(13, 12)) {
          is(1.U) { csr_op := CsrOp.WRITE }
          is(2.U) { csr_op := CsrOp.SET }
          is(3.U) { csr_op := CsrOp.CLEAR }
          is(0.U) { csr_illegal := true.B }
        }
        illegal_insn := csr_illegal
      }
    }
  }

  when(illegal_c_insn_i) { illegal_insn := true.B }
  when(illegal_insn) {
    rf_we := false.B
    data_req_o := false.B
    data_we_o := false.B
    jump_in_dec_o := false.B
    jump_set_o := false.B
    branch_in_dec_o := false.B
    csr_access_o := false.B
  }

  csr_op_o := Mux((csr_op === CsrOp.SET || csr_op === CsrOp.CLEAR) && instr_rs1 === 0.U, CsrOp.READ, csr_op)

  alu_operator_o := alu("ALU_SLTU")
  alu_op_a_mux_sel_o := OpA.IMM
  alu_op_b_mux_sel_o := OpB.IMM
  imm_a_mux_sel_o := ImmA.ZERO
  imm_b_mux_sel_o := ImmB.I
  bt_a_mux_sel_o := OpA.CURRPC
  bt_b_mux_sel_o := ImmB.I
  use_rs3_d := false.B
  alu_multicycle_o := false.B
  mult_sel_o := false.B
  div_sel_o := false.B

  switch(instr_alu(6, 0)) {
    is(Op.JAL) {
      when(branchTargetALU.B) {
        bt_a_mux_sel_o := OpA.CURRPC
        bt_b_mux_sel_o := ImmB.J
      }
      when(instr_first_cycle_i && !branchTargetALU.B) {
        alu_op_a_mux_sel_o := OpA.CURRPC
        alu_op_b_mux_sel_o := OpB.IMM
        imm_b_mux_sel_o := ImmB.J
        alu_operator_o := alu("ALU_ADD")
      }.otherwise {
        alu_op_a_mux_sel_o := OpA.CURRPC
        alu_op_b_mux_sel_o := OpB.IMM
        imm_b_mux_sel_o := ImmB.INCR_PC
        alu_operator_o := alu("ALU_ADD")
      }
    }
    is(Op.JALR) {
      when(branchTargetALU.B) {
        bt_a_mux_sel_o := OpA.REG_A
        bt_b_mux_sel_o := ImmB.I
      }
      when(instr_first_cycle_i && !branchTargetALU.B) {
        alu_op_a_mux_sel_o := OpA.REG_A
        alu_op_b_mux_sel_o := OpB.IMM
        imm_b_mux_sel_o := ImmB.I
      }.otherwise {
        alu_op_a_mux_sel_o := OpA.CURRPC
        alu_op_b_mux_sel_o := OpB.IMM
        imm_b_mux_sel_o := ImmB.INCR_PC
      }
      alu_operator_o := alu("ALU_ADD")
    }
    is(Op.BRANCH) {
      switch(instr_alu(14, 12)) {
        is(0.U) { alu_operator_o := alu("ALU_EQ") }
        is(1.U) { alu_operator_o := alu("ALU_NE") }
        is(4.U) { alu_operator_o := alu("ALU_LT") }
        is(5.U) { alu_operator_o := alu("ALU_GE") }
        is(6.U) { alu_operator_o := alu("ALU_LTU") }
        is(7.U) { alu_operator_o := alu("ALU_GEU") }
      }
      when(branchTargetALU.B) {
        bt_a_mux_sel_o := OpA.CURRPC
        bt_b_mux_sel_o := Mux(branch_taken_i, ImmB.B, ImmB.INCR_PC)
      }
      when(instr_first_cycle_i) {
        alu_op_a_mux_sel_o := OpA.REG_A
        alu_op_b_mux_sel_o := OpB.REG_B
      }.elsewhen(!branchTargetALU.B) {
        alu_op_a_mux_sel_o := OpA.CURRPC
        alu_op_b_mux_sel_o := OpB.IMM
        imm_b_mux_sel_o := Mux(branch_taken_i, ImmB.B, ImmB.INCR_PC)
        alu_operator_o := alu("ALU_ADD")
      }
    }
    is(Op.STORE) {
      alu_op_a_mux_sel_o := OpA.REG_A
      alu_op_b_mux_sel_o := OpB.REG_B
      alu_operator_o := alu("ALU_ADD")
      when(!instr_alu(14)) {
        imm_b_mux_sel_o := ImmB.S
        alu_op_b_mux_sel_o := OpB.IMM
      }
    }
    is(Op.LOAD) {
      alu_op_a_mux_sel_o := OpA.REG_A
      alu_op_b_mux_sel_o := OpB.IMM
      imm_b_mux_sel_o := ImmB.I
      alu_operator_o := alu("ALU_ADD")
    }
    is(Op.LUI) {
      alu_op_a_mux_sel_o := OpA.IMM
      alu_op_b_mux_sel_o := OpB.IMM
      imm_a_mux_sel_o := ImmA.ZERO
      imm_b_mux_sel_o := ImmB.U
      alu_operator_o := alu("ALU_ADD")
    }
    is(Op.AUIPC) {
      alu_op_a_mux_sel_o := OpA.CURRPC
      alu_op_b_mux_sel_o := OpB.IMM
      imm_b_mux_sel_o := ImmB.U
      alu_operator_o := alu("ALU_ADD")
    }
    is(Op.OP_IMM) {
      alu_op_a_mux_sel_o := OpA.REG_A
      alu_op_b_mux_sel_o := OpB.IMM
      imm_b_mux_sel_o := ImmB.I
      switch(instr_alu(14, 12)) {
        is(0.U) { alu_operator_o := alu("ALU_ADD") }
        is(2.U) { alu_operator_o := alu("ALU_SLT") }
        is(3.U) { alu_operator_o := alu("ALU_SLTU") }
        is(4.U) { alu_operator_o := alu("ALU_XOR") }
        is(6.U) { alu_operator_o := alu("ALU_OR") }
        is(7.U) { alu_operator_o := alu("ALU_AND") }
        is(1.U) {
          when(!rvbAny.B) {
            alu_operator_o := alu("ALU_SLL")
          }.otherwise {
            switch(instr_alu(31, 27)) {
              is("b00000".U) { alu_operator_o := alu("ALU_SLL") }
              is("b00100".U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_SLO") } }
              is("b01001".U) { alu_operator_o := alu("ALU_BCLR") }
              is("b00101".U) { alu_operator_o := alu("ALU_BSET") }
              is("b01101".U) { alu_operator_o := alu("ALU_BINV") }
              is("b00001".U) { when(!instr_alu(26)) { alu_operator_o := alu("ALU_SHFL") } }
              is("b01100".U) {
                switch(instr_alu(26, 20)) {
                  is(0.U) { alu_operator_o := alu("ALU_CLZ") }
                  is(1.U) { alu_operator_o := alu("ALU_CTZ") }
                  is(2.U) { alu_operator_o := alu("ALU_CPOP") }
                  is(4.U) { alu_operator_o := alu("ALU_SEXTB") }
                  is(5.U) { alu_operator_o := alu("ALU_SEXTH") }
                  is(0x10.U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_CRC32_B"); alu_multicycle_o := true.B } }
                  is(0x11.U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_CRC32_H"); alu_multicycle_o := true.B } }
                  is(0x12.U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_CRC32_W"); alu_multicycle_o := true.B } }
                  is(0x18.U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_CRC32C_B"); alu_multicycle_o := true.B } }
                  is(0x19.U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_CRC32C_H"); alu_multicycle_o := true.B } }
                  is(0x1a.U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_CRC32C_W"); alu_multicycle_o := true.B } }
                }
              }
            }
          }
        }
        is(5.U) {
          when(rvbAny.B && instr_alu(26)) {
            alu_operator_o := alu("ALU_FSR")
            alu_multicycle_o := true.B
            use_rs3_d := instr_first_cycle_i
          }.elsewhen(!rvbAny.B) {
            alu_operator_o := Mux(instr_alu(31, 27) === "b01000".U, alu("ALU_SRA"), alu("ALU_SRL"))
          }.otherwise {
            switch(instr_alu(31, 27)) {
              is("b00000".U) { alu_operator_o := alu("ALU_SRL") }
              is("b01000".U) { alu_operator_o := alu("ALU_SRA") }
              is("b00100".U) { when(rvbOtOrFull.B) { alu_operator_o := alu("ALU_SRO") } }
              is("b01001".U) { alu_operator_o := alu("ALU_BEXT") }
              is("b01100".U) { alu_operator_o := alu("ALU_ROR"); alu_multicycle_o := true.B }
              is("b01101".U) { alu_operator_o := alu("ALU_GREV") }
              is("b00101".U) { alu_operator_o := alu("ALU_GORC") }
              is("b00001".U) { when(rvbOtOrFull.B && !instr_alu(26)) { alu_operator_o := alu("ALU_UNSHFL") } }
            }
          }
        }
      }
    }
    is(Op.OP) {
      alu_op_a_mux_sel_o := OpA.REG_A
      alu_op_b_mux_sel_o := OpB.REG_B
      when(instr_alu(26) && rvbAny.B) {
        switch(Cat(instr_alu(26, 25), instr_alu(14, 12))) {
          is("b11001".U) { alu_operator_o := alu("ALU_CMIX"); alu_multicycle_o := true.B; use_rs3_d := instr_first_cycle_i }
          is("b11101".U) { alu_operator_o := alu("ALU_CMOV"); alu_multicycle_o := true.B; use_rs3_d := instr_first_cycle_i }
          is("b10001".U) { alu_operator_o := alu("ALU_FSL"); alu_multicycle_o := true.B; use_rs3_d := instr_first_cycle_i }
          is("b10101".U) { alu_operator_o := alu("ALU_FSR"); alu_multicycle_o := true.B; use_rs3_d := instr_first_cycle_i }
        }
      }.otherwise {
        switch(bitPat(instr_alu)) {
          is("b0000000000".U) { alu_operator_o := alu("ALU_ADD") }
          is("b0100000000".U) { alu_operator_o := alu("ALU_SUB") }
          is("b0000000010".U) { alu_operator_o := alu("ALU_SLT") }
          is("b0000000011".U) { alu_operator_o := alu("ALU_SLTU") }
          is("b0000000100".U) { alu_operator_o := alu("ALU_XOR") }
          is("b0000000110".U) { alu_operator_o := alu("ALU_OR") }
          is("b0000000111".U) { alu_operator_o := alu("ALU_AND") }
          is("b0000000001".U) { alu_operator_o := alu("ALU_SLL") }
          is("b0000000101".U) { alu_operator_o := alu("ALU_SRL") }
          is("b0100000101".U) { alu_operator_o := alu("ALU_SRA") }
          is("b0000001000".U, "b0000001001".U, "b0000001010".U, "b0000001011".U,
            "b0000001100".U, "b0000001101".U, "b0000001110".U, "b0000001111".U) {
            alu_operator_o := alu("ALU_ADD")
            mult_sel_o := rv32mEnabled.B && instr_alu(14, 12) <= 3.U
            div_sel_o := rv32mEnabled.B && instr_alu(14, 12) >= 4.U
          }
        }
      }
    }
    is(Op.MISC_MEM) {
      when(instr_alu(14, 12) === 1.U) {
        when(branchTargetALU.B) {
          bt_a_mux_sel_o := OpA.CURRPC
          bt_b_mux_sel_o := ImmB.INCR_PC
        }.otherwise {
          alu_op_a_mux_sel_o := OpA.CURRPC
          alu_op_b_mux_sel_o := OpB.IMM
          imm_b_mux_sel_o := ImmB.INCR_PC
          alu_operator_o := alu("ALU_ADD")
        }
      }.otherwise {
        alu_operator_o := alu("ALU_ADD")
        alu_op_a_mux_sel_o := OpA.REG_A
        alu_op_b_mux_sel_o := OpB.IMM
      }
    }
    is(Op.SYSTEM) {
      when(instr_alu(14, 12) === 0.U) {
        alu_op_a_mux_sel_o := OpA.REG_A
        alu_op_b_mux_sel_o := OpB.IMM
      }.otherwise {
        imm_a_mux_sel_o := ImmA.Z
        alu_op_a_mux_sel_o := Mux(instr_alu(14), OpA.IMM, OpA.REG_A)
      }
    }
  }

  mult_en_o := !illegal_insn && mult_sel_o
  div_en_o := !illegal_insn && div_sel_o
  val illegal_reg_rv32e =
    if (rv32e) {
      (rf_raddr_a_o(4) && alu_op_a_mux_sel_o === OpA.REG_A) ||
      (rf_raddr_b_o(4) && alu_op_b_mux_sel_o === OpB.REG_B) ||
      (rf_waddr_o(4) && rf_we)
    } else false.B
  illegal_insn_o := illegal_insn || illegal_reg_rv32e
  rf_we_o := rf_we && !illegal_reg_rv32e
}
