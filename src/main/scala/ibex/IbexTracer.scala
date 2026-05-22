// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

private class IbexTracerLogger extends ExtModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val hart_id_i = IO(Input(UInt(32.W)))
  val rvfi_valid_i = IO(Input(Bool()))
  val cycle_i = IO(Input(UInt(32.W)))
  val rvfi_insn_i = IO(Input(UInt(32.W)))
  val insn_is_compressed_i = IO(Input(Bool()))
  val rvfi_pc_rdata_i = IO(Input(UInt(32.W)))
  val rvfi_pc_wdata_i = IO(Input(UInt(32.W)))
  val data_accessed_i = IO(Input(UInt(5.W)))
  val rvfi_rs1_addr_i = IO(Input(UInt(5.W)))
  val rvfi_rs2_addr_i = IO(Input(UInt(5.W)))
  val rvfi_rs3_addr_i = IO(Input(UInt(5.W)))
  val rvfi_rs1_rdata_i = IO(Input(UInt(32.W)))
  val rvfi_rs2_rdata_i = IO(Input(UInt(32.W)))
  val rvfi_rs3_rdata_i = IO(Input(UInt(32.W)))
  val rvfi_rd_addr_i = IO(Input(UInt(5.W)))
  val rvfi_rd_wdata_i = IO(Input(UInt(32.W)))
  val rvfi_mem_addr_i = IO(Input(UInt(32.W)))
  val rvfi_mem_rmask_i = IO(Input(UInt(4.W)))
  val rvfi_mem_wmask_i = IO(Input(UInt(4.W)))
  val rvfi_mem_rdata_i = IO(Input(UInt(32.W)))
  val rvfi_mem_wdata_i = IO(Input(UInt(32.W)))
  val rvfi_ext_expanded_insn_valid_i = IO(Input(Bool()))
  val rvfi_ext_expanded_insn_i = IO(Input(UInt(16.W)))

  override def desiredName: String = "IbexTracerLogger"

  setInline("IbexTracerLogger.sv",
    """// Simulator-only file writer for IbexTracer.
      |module IbexTracerLogger (
      |  input logic        clk_i,
      |  input logic        rst_ni,
      |  input logic [31:0] hart_id_i,
      |  input logic        rvfi_valid_i,
      |  input logic [31:0] cycle_i,
      |  input logic [31:0] rvfi_insn_i,
      |  input logic        insn_is_compressed_i,
      |  input logic [31:0] rvfi_pc_rdata_i,
      |  input logic [31:0] rvfi_pc_wdata_i,
      |  input logic [ 4:0] data_accessed_i,
      |  input logic [ 4:0] rvfi_rs1_addr_i,
      |  input logic [ 4:0] rvfi_rs2_addr_i,
      |  input logic [ 4:0] rvfi_rs3_addr_i,
      |  input logic [31:0] rvfi_rs1_rdata_i,
      |  input logic [31:0] rvfi_rs2_rdata_i,
      |  input logic [31:0] rvfi_rs3_rdata_i,
      |  input logic [ 4:0] rvfi_rd_addr_i,
      |  input logic [31:0] rvfi_rd_wdata_i,
      |  input logic [31:0] rvfi_mem_addr_i,
      |  input logic [ 3:0] rvfi_mem_rmask_i,
      |  input logic [ 3:0] rvfi_mem_wmask_i,
      |  input logic [31:0] rvfi_mem_rdata_i,
      |  input logic [31:0] rvfi_mem_wdata_i,
      |  input logic        rvfi_ext_expanded_insn_valid_i,
      |  input logic [15:0] rvfi_ext_expanded_insn_i
      |);
      |`ifndef SYNTHESIS
      |  localparam logic [4:0] RS1 = (1 << 0);
      |  localparam logic [4:0] RS2 = (1 << 1);
      |  localparam logic [4:0] RS3 = (1 << 2);
      |  localparam logic [4:0] RD  = (1 << 3);
      |  localparam logic [4:0] MEM = (1 << 4);
      |
      |  int    file_handle;
      |  string file_name;
      |  logic  trace_log_enable;
      |
      |  initial begin
      |    if ($value$plusargs("ibex_tracer_enable=%b", trace_log_enable)) begin
      |      if (trace_log_enable == 1'b0) begin
      |        $display("%m: Instruction trace disabled.");
      |      end
      |    end else begin
      |      trace_log_enable = 1'b1;
      |    end
      |  end
      |
      |  final begin
      |    if (file_handle != 32'h0) begin
      |      static int fh = file_handle;
      |      $fclose(fh);
      |    end
      |  end
      |
      |  function automatic string reg_addr_to_str(input logic [4:0] addr);
      |    if (addr < 10) begin
      |      return $sformatf(" x%0d", addr);
      |    end else begin
      |      return $sformatf("x%0d", addr);
      |    end
      |  endfunction
      |
      |  function automatic int signed sext12(input logic [11:0] imm);
      |    return int'($signed(imm));
      |  endfunction
      |
      |  function automatic int signed branch_target();
      |    logic [12:0] imm;
      |    imm = {rvfi_insn_i[31], rvfi_insn_i[7], rvfi_insn_i[30:25], rvfi_insn_i[11:8], 1'b0};
      |    return int'(rvfi_pc_rdata_i) + int'($signed(imm));
      |  endfunction
      |
      |  function automatic int signed store_imm();
      |    return int'($signed({rvfi_insn_i[31:25], rvfi_insn_i[11:7]}));
      |  endfunction
      |
      |  function automatic int signed c_imm6();
      |    return int'($signed({rvfi_insn_i[12], rvfi_insn_i[6:2]}));
      |  endfunction
      |
      |  function automatic int signed c_addi16sp_imm();
      |    return int'($signed({rvfi_insn_i[12], rvfi_insn_i[4:3], rvfi_insn_i[5], rvfi_insn_i[2], rvfi_insn_i[6], 4'b0}));
      |  endfunction
      |
      |  function automatic logic [4:0] c_reg(input logic [2:0] addr);
      |    return {2'b01, addr};
      |  endfunction
      |
      |  function automatic int unsigned c_lw_imm();
      |    return {1'b0, rvfi_insn_i[5], rvfi_insn_i[12:10], rvfi_insn_i[6], 2'b00};
      |  endfunction
      |
      |  function automatic int unsigned c_lwsp_imm();
      |    return {rvfi_insn_i[3:2], rvfi_insn_i[12], rvfi_insn_i[6:4], 2'b00};
      |  endfunction
      |
      |  function automatic int unsigned c_swsp_imm();
      |    return {rvfi_insn_i[8:7], rvfi_insn_i[12:9], 2'b00};
      |  endfunction
      |
      |  function automatic int signed c_branch_target();
      |    logic [8:0] imm;
      |    imm = {rvfi_insn_i[12], rvfi_insn_i[6:5], rvfi_insn_i[2], rvfi_insn_i[11:10], rvfi_insn_i[4:3], 1'b0};
      |    return int'(rvfi_pc_rdata_i) + int'($signed(imm));
      |  endfunction
      |
      |  function automatic string csr_name(input logic [11:0] csr_addr);
      |    unique case (csr_addr)
      |      12'd768:  return "mstatus";
      |      12'd769:  return "misa";
      |      12'd772:  return "mie";
      |      12'd773:  return "mtvec";
      |      12'd800:  return "mcountinhibit";
      |      12'd832:  return "mscratch";
      |      12'd833:  return "mepc";
      |      12'd834:  return "mcause";
      |      12'd835:  return "mtval";
      |      12'd836:  return "mip";
      |      12'd1968: return "dcsr";
      |      12'd1969: return "dpc";
      |      12'd1970: return "dscratch";
      |      12'd2816: return "mcycle";
      |      12'd2818: return "minstret";
      |      12'd2944: return "mcycleh";
      |      12'd2946: return "minstreth";
      |      12'd3860: return "mhartid";
      |      default:  return $sformatf("0x%x", csr_addr);
      |    endcase
      |  endfunction
      |
      |  function automatic string fence_desc(input logic [3:0] bits);
      |    string desc = "";
      |    if (bits[3]) desc = {desc, "i"};
      |    if (bits[2]) desc = {desc, "o"};
      |    if (bits[1]) desc = {desc, "r"};
      |    if (bits[0]) desc = {desc, "w"};
      |    return desc;
      |  endfunction
      |
      |  function automatic string decoded_instruction();
      |    logic [6:0]  opcode = rvfi_insn_i[6:0];
      |    logic [2:0]  funct3 = rvfi_insn_i[14:12];
      |    logic [6:0]  funct7 = rvfi_insn_i[31:25];
      |    logic [4:0]  rd = rvfi_insn_i[11:7];
      |    logic [4:0]  rs1 = rvfi_insn_i[19:15];
      |    logic [4:0]  rs2 = rvfi_insn_i[24:20];
      |    logic [11:0] csr = rvfi_insn_i[31:20];
      |
      |    if (insn_is_compressed_i) begin
      |      unique case (rvfi_insn_i[1:0])
      |        2'b00: begin
      |          unique case (rvfi_insn_i[15:13])
      |            3'b000: return $sformatf("c.addi4spn\tx%0d,x2,%0d", c_reg(rvfi_insn_i[4:2]), {rvfi_insn_i[10:7], rvfi_insn_i[12:11], rvfi_insn_i[5], rvfi_insn_i[6], 2'b00});
      |            3'b010: return $sformatf("c.lw\tx%0d,%0d(x%0d)", c_reg(rvfi_insn_i[4:2]), c_lw_imm(), c_reg(rvfi_insn_i[9:7]));
      |            3'b110: return $sformatf("c.sw\tx%0d,%0d(x%0d)", c_reg(rvfi_insn_i[4:2]), c_lw_imm(), c_reg(rvfi_insn_i[9:7]));
      |            default: ;
      |          endcase
      |        end
      |        2'b01: begin
      |          unique case (rvfi_insn_i[15:13])
      |            3'b000: return $sformatf("c.addi\tx%0d,%0d", rvfi_insn_i[11:7], c_imm6());
      |            3'b001: return $sformatf("c.jal\t%0x", rvfi_pc_wdata_i);
      |            3'b010: return $sformatf("c.li\tx%0d,%0d", rvfi_insn_i[11:7], c_imm6());
      |            3'b011: begin
      |              if (rvfi_insn_i[11:7] == 5'd2) return $sformatf("c.addi16sp\tx2,%0d", c_addi16sp_imm());
      |              else return $sformatf("c.lui\tx%0d,0x%0x", rvfi_insn_i[11:7], 20'($signed({rvfi_insn_i[12], rvfi_insn_i[6:2]})));
      |            end
      |            3'b100: begin
      |              unique case (rvfi_insn_i[11:10])
      |                2'b00: return $sformatf("c.srli\tx%0d,0x%0x", c_reg(rvfi_insn_i[9:7]), {rvfi_insn_i[12], rvfi_insn_i[6:2]});
      |                2'b01: return $sformatf("c.srai\tx%0d,0x%0x", c_reg(rvfi_insn_i[9:7]), {rvfi_insn_i[12], rvfi_insn_i[6:2]});
      |                2'b10: return $sformatf("c.andi\tx%0d,%0d", c_reg(rvfi_insn_i[9:7]), c_imm6());
      |                2'b11: begin
      |                  unique case ({rvfi_insn_i[12], rvfi_insn_i[6:5]})
      |                    3'b000: return $sformatf("c.sub\tx%0d,x%0d", c_reg(rvfi_insn_i[9:7]), c_reg(rvfi_insn_i[4:2]));
      |                    3'b001: return $sformatf("c.xor\tx%0d,x%0d", c_reg(rvfi_insn_i[9:7]), c_reg(rvfi_insn_i[4:2]));
      |                    3'b010: return $sformatf("c.or\tx%0d,x%0d", c_reg(rvfi_insn_i[9:7]), c_reg(rvfi_insn_i[4:2]));
      |                    3'b011: return $sformatf("c.and\tx%0d,x%0d", c_reg(rvfi_insn_i[9:7]), c_reg(rvfi_insn_i[4:2]));
      |                    3'b110: return $sformatf("c.mul\tx%0d,x%0d", c_reg(rvfi_insn_i[9:7]), c_reg(rvfi_insn_i[4:2]));
      |                    default: ;
      |                  endcase
      |                end
      |                default: ;
      |              endcase
      |            end
      |            3'b101: return $sformatf("c.j\t%0x", rvfi_pc_wdata_i);
      |            3'b110: return $sformatf("c.beqz\tx%0d,%0x", c_reg(rvfi_insn_i[9:7]), c_branch_target());
      |            3'b111: return $sformatf("c.bnez\tx%0d,%0x", c_reg(rvfi_insn_i[9:7]), c_branch_target());
      |            default: ;
      |          endcase
      |        end
      |        2'b10: begin
      |          unique case (rvfi_insn_i[15:13])
      |            3'b000: return $sformatf("c.slli\tx%0d,0x%0x", rvfi_insn_i[11:7], {rvfi_insn_i[12], rvfi_insn_i[6:2]});
      |            3'b010: return $sformatf("c.lwsp\tx%0d,%0d(x%0d)", rvfi_insn_i[11:7], c_lwsp_imm(), rvfi_rs1_addr_i);
      |            3'b100: begin
      |              if (rvfi_insn_i[12] && rvfi_insn_i[11:7] == 5'b0 && rvfi_insn_i[6:2] == 5'b0) return "c.ebreak";
      |              if (rvfi_insn_i[6:2] == 5'b0) begin
      |                if (rvfi_insn_i[12]) return $sformatf("c.jalr\tx%0d", rvfi_rs1_addr_i);
      |                else return $sformatf("c.jr\tx%0d", rvfi_rs1_addr_i);
      |              end else begin
      |                if (rvfi_insn_i[12]) return $sformatf("c.add\tx%0d,x%0d", rvfi_rd_addr_i, rvfi_rs2_addr_i);
      |                else return $sformatf("c.mv\tx%0d,x%0d", rvfi_rd_addr_i, rvfi_rs2_addr_i);
      |              end
      |            end
      |            3'b110: return $sformatf("c.swsp\tx%0d,%0d(x%0d)", rvfi_rs2_addr_i, c_swsp_imm(), rvfi_rs1_addr_i);
      |            default: ;
      |          endcase
      |        end
      |        default: ;
      |      endcase
      |      return "INVALID";
      |    end
      |
      |    unique case (opcode)
      |      7'b0110111: return $sformatf("lui\tx%0d,0x%0x", rd, rvfi_insn_i[31:12]);
      |      7'b0010111: return $sformatf("auipc\tx%0d,0x%0x", rd, rvfi_insn_i[31:12]);
      |      7'b1101111: return $sformatf("jal\tx%0d,%0x", rd, rvfi_pc_wdata_i);
      |      7'b1100111: begin
      |        if (funct3 == 3'b000) return $sformatf("jalr\tx%0d,%0d(x%0d)", rd, sext12(rvfi_insn_i[31:20]), rs1);
      |      end
      |      7'b1100011: begin
      |        unique case (funct3)
      |          3'b000: return $sformatf("beq\tx%0d,x%0d,%0x", rs1, rs2, branch_target());
      |          3'b001: return $sformatf("bne\tx%0d,x%0d,%0x", rs1, rs2, branch_target());
      |          3'b100: return $sformatf("blt\tx%0d,x%0d,%0x", rs1, rs2, branch_target());
      |          3'b101: return $sformatf("bge\tx%0d,x%0d,%0x", rs1, rs2, branch_target());
      |          3'b110: return $sformatf("bltu\tx%0d,x%0d,%0x", rs1, rs2, branch_target());
      |          3'b111: return $sformatf("bgeu\tx%0d,x%0d,%0x", rs1, rs2, branch_target());
      |          default: ;
      |        endcase
      |      end
      |      7'b0010011: begin
      |        unique case (funct3)
      |          3'b000: return $sformatf("addi\tx%0d,x%0d,%0d", rd, rs1, sext12(rvfi_insn_i[31:20]));
      |          3'b010: return $sformatf("slti\tx%0d,x%0d,%0d", rd, rs1, sext12(rvfi_insn_i[31:20]));
      |          3'b011: return $sformatf("sltiu\tx%0d,x%0d,%0d", rd, rs1, sext12(rvfi_insn_i[31:20]));
      |          3'b100: return $sformatf("xori\tx%0d,x%0d,%0d", rd, rs1, sext12(rvfi_insn_i[31:20]));
      |          3'b110: return $sformatf("ori\tx%0d,x%0d,%0d", rd, rs1, sext12(rvfi_insn_i[31:20]));
      |          3'b111: return $sformatf("andi\tx%0d,x%0d,%0d", rd, rs1, sext12(rvfi_insn_i[31:20]));
      |          3'b001: if (funct7 == 7'b0000000) return $sformatf("slli\tx%0d,x%0d,0x%0x", rd, rs1, rvfi_insn_i[24:20]);
      |          3'b101: begin
      |            if (funct7 == 7'b0000000) return $sformatf("srli\tx%0d,x%0d,0x%0x", rd, rs1, rvfi_insn_i[24:20]);
      |            if (funct7 == 7'b0100000) return $sformatf("srai\tx%0d,x%0d,0x%0x", rd, rs1, rvfi_insn_i[24:20]);
      |            if (rvfi_insn_i[31:26] == 6'b011000) return $sformatf("rori\tx%0d,x%0d,0x%0x", rd, rs1, rvfi_insn_i[24:20]);
      |          end
      |          default: ;
      |        endcase
      |        if (rvfi_insn_i[31:20] == 12'h600 && funct3 == 3'b001) return $sformatf("clz\tx%0d,x%0d", rd, rs1);
      |        if (rvfi_insn_i[31:20] == 12'h601 && funct3 == 3'b001) return $sformatf("ctz\tx%0d,x%0d", rd, rs1);
      |        if (rvfi_insn_i[31:20] == 12'h602 && funct3 == 3'b001) return $sformatf("cpop\tx%0d,x%0d", rd, rs1);
      |        if (rvfi_insn_i[31:20] == 12'h604 && funct3 == 3'b001) return $sformatf("sext.b\tx%0d,x%0d", rd, rs1);
      |        if (rvfi_insn_i[31:20] == 12'h605 && funct3 == 3'b001) return $sformatf("sext.h\tx%0d,x%0d", rd, rs1);
      |      end
      |      7'b0110011: begin
      |        if (funct7 == 7'b0000001) begin
      |          unique case (funct3)
      |            3'b000: return $sformatf("mul\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b001: return $sformatf("mulh\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b010: return $sformatf("mulhsu\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b011: return $sformatf("mulhu\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b100: return $sformatf("div\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b101: return $sformatf("divu\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b110: return $sformatf("rem\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            3'b111: return $sformatf("remu\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |            default: ;
      |          endcase
      |        end
      |        unique case ({funct7, funct3})
      |          10'b0000000_000: return $sformatf("add\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0100000_000: return $sformatf("sub\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_001: return $sformatf("sll\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_010: return $sformatf("slt\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_011: return $sformatf("sltu\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_100: return $sformatf("xor\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_101: return $sformatf("srl\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0100000_101: return $sformatf("sra\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_110: return $sformatf("or\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0000000_111: return $sformatf("and\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0100000_100: return $sformatf("xnor\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0100000_110: return $sformatf("orn\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          10'b0100000_111: return $sformatf("andn\tx%0d,x%0d,x%0d", rd, rs1, rs2);
      |          default: ;
      |        endcase
      |      end
      |      7'b0000011: begin
      |        unique case (funct3)
      |          3'b000: return $sformatf("lb\tx%0d,%0d(x%0d)", rd, sext12(rvfi_insn_i[31:20]), rs1);
      |          3'b001: return $sformatf("lh\tx%0d,%0d(x%0d)", rd, sext12(rvfi_insn_i[31:20]), rs1);
      |          3'b010: return $sformatf("lw\tx%0d,%0d(x%0d)", rd, sext12(rvfi_insn_i[31:20]), rs1);
      |          3'b100: return $sformatf("lbu\tx%0d,%0d(x%0d)", rd, sext12(rvfi_insn_i[31:20]), rs1);
      |          3'b101: return $sformatf("lhu\tx%0d,%0d(x%0d)", rd, sext12(rvfi_insn_i[31:20]), rs1);
      |          default: ;
      |        endcase
      |      end
      |      7'b0100011: begin
      |        if (rvfi_insn_i[14] == 1'b0) begin
      |          unique case (rvfi_insn_i[13:12])
      |            2'b00: return $sformatf("sb\tx%0d,%0d(x%0d)", rs2, store_imm(), rs1);
      |            2'b01: return $sformatf("sh\tx%0d,%0d(x%0d)", rs2, store_imm(), rs1);
      |            2'b10: return $sformatf("sw\tx%0d,%0d(x%0d)", rs2, store_imm(), rs1);
      |            default: ;
      |          endcase
      |        end
      |      end
      |      7'b0001111: begin
      |        if (funct3 == 3'b000) return $sformatf("fence\t%s,%s", fence_desc(rvfi_insn_i[27:24]), fence_desc(rvfi_insn_i[23:20]));
      |        if (rvfi_insn_i[31:7] == 25'b0 && funct3 == 3'b001) return "fence.i";
      |      end
      |      7'b1110011: begin
      |        if (funct3 == 3'b000) begin
      |          unique case (rvfi_insn_i[31:20])
      |            12'h000: return "ecall";
      |            12'h001: return "ebreak";
      |            12'h302: return "mret";
      |            12'h7B2: return "dret";
      |            12'h105: return "wfi";
      |            default: ;
      |          endcase
      |        end else if (funct3[2]) begin
      |          return $sformatf("%s\tx%0d,%s,%0d",
      |                          funct3 == 3'b101 ? "csrrwi" : funct3 == 3'b110 ? "csrrsi" : "csrrci",
      |                          rd, csr_name(csr), rs1);
      |        end else begin
      |          return $sformatf("%s\tx%0d,%s,x%0d",
      |                          funct3 == 3'b001 ? "csrrw" : funct3 == 3'b010 ? "csrrs" : "csrrc",
      |                          rd, csr_name(csr), rs1);
      |        end
      |      end
      |      default: ;
      |    endcase
      |    return "INVALID";
      |  endfunction
      |
      |  function automatic logic [4:0] trace_data_access();
      |    if (!insn_is_compressed_i) begin
      |      return data_accessed_i;
      |    end
      |
      |    unique case (rvfi_insn_i[1:0])
      |      2'b00: begin
      |        unique case (rvfi_insn_i[15:13])
      |          3'b000: return RD;
      |          3'b010: return RS1 | RD | MEM;
      |          3'b110: return RS1 | RS2 | MEM;
      |          default: return 5'h0;
      |        endcase
      |      end
      |      2'b01: begin
      |        unique case (rvfi_insn_i[15:13])
      |          3'b000: return RS1 | RD;
      |          3'b001: return RD;
      |          3'b010: return RD;
      |          3'b011: return rvfi_insn_i[11:7] == 5'd2 ? RS1 | RD : RD;
      |          3'b100: return RS1 | RS2 | RD;
      |          3'b110, 3'b111: return RS1;
      |          default: return 5'h0;
      |        endcase
      |      end
      |      2'b10: begin
      |        unique case (rvfi_insn_i[15:13])
      |          3'b000: return RS1 | RD;
      |          3'b010: return RS1 | RD | MEM;
      |          3'b100: return rvfi_insn_i[6:2] == 5'b0 ? RS1 | MuxBit(rvfi_insn_i[12], RD) : RS1 | RS2 | RD;
      |          3'b110: return RS1 | RS2 | MEM;
      |          default: return 5'h0;
      |        endcase
      |      end
      |      default: return 5'h0;
      |    endcase
      |  endfunction
      |
      |  function automatic logic [4:0] MuxBit(input logic sel, input logic [4:0] value);
      |    return sel ? value : 5'h0;
      |  endfunction
      |
      |  always @(posedge clk_i) begin
      |    if (rst_ni && rvfi_valid_i && trace_log_enable) begin
      |      static int fh = file_handle;
      |      string rvfi_insn_str;
      |      logic [4:0] data_accessed;
      |
      |      if (fh == 32'h0) begin
      |        static string file_name_base = "trace_core";
      |        void'($value$plusargs("ibex_tracer_file_base=%s", file_name_base));
      |        $sformat(file_name, "%s_%h.log", file_name_base, hart_id_i);
      |
      |        $display("%m: Writing execution trace to %s", file_name);
      |        fh = $fopen(file_name, "w");
      |        file_handle <= fh;
      |        $fwrite(fh, "Time\tCycle\tPC\tInsn\tDecoded instruction\tRegister and memory contents\n");
      |      end
      |
      |      if (insn_is_compressed_i) begin
      |        rvfi_insn_str = $sformatf("%h", rvfi_insn_i[15:0]);
      |      end else begin
      |        rvfi_insn_str = $sformatf("%h", rvfi_insn_i);
      |      end
      |      data_accessed = trace_data_access();
      |
      |      $fwrite(fh, "%15t\t%d\t%h\t%s\t%s\t",
      |              $time, cycle_i, rvfi_pc_rdata_i, rvfi_insn_str, decoded_instruction());
      |      if ((data_accessed & RS1) != 0) begin
      |        $fwrite(fh, " %s:0x%08x", reg_addr_to_str(rvfi_rs1_addr_i), rvfi_rs1_rdata_i);
      |      end
      |      if ((data_accessed & RS2) != 0) begin
      |        $fwrite(fh, " %s:0x%08x", reg_addr_to_str(rvfi_rs2_addr_i), rvfi_rs2_rdata_i);
      |      end
      |      if ((data_accessed & RS3) != 0) begin
      |        $fwrite(fh, " %s:0x%08x", reg_addr_to_str(rvfi_rs3_addr_i), rvfi_rs3_rdata_i);
      |      end
      |      if ((data_accessed & RD) != 0) begin
      |        $fwrite(fh, " %s=0x%08x", reg_addr_to_str(rvfi_rd_addr_i), rvfi_rd_wdata_i);
      |      end
      |      if ((data_accessed & MEM) != 0) begin
      |        $fwrite(fh, " PA:0x%08x", rvfi_mem_addr_i);
      |        if (rvfi_mem_wmask_i != 4'b0000) begin
      |          $fwrite(fh, " store:0x%08x", rvfi_mem_wdata_i);
      |        end
      |        if (rvfi_mem_rmask_i != 4'b0000) begin
      |          $fwrite(fh, " load:0x%08x", rvfi_mem_rdata_i);
      |        end
      |      end
      |      if (rvfi_ext_expanded_insn_valid_i) begin
      |        $fwrite(fh, " expand_insn:0x%04x", rvfi_ext_expanded_insn_i);
      |      end
      |      $fwrite(fh, "\n");
      |    end
      |  end
      |`endif
      |endmodule
      |""".stripMargin)
}

class IbexTracer extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val hart_id_i = IO(Input(UInt(32.W)))

  val rvfi_valid = IO(Input(Bool()))
  val rvfi_order = IO(Input(UInt(64.W)))
  val rvfi_insn = IO(Input(UInt(32.W)))
  val rvfi_trap = IO(Input(Bool()))
  val rvfi_halt = IO(Input(Bool()))
  val rvfi_intr = IO(Input(Bool()))
  val rvfi_mode = IO(Input(UInt(2.W)))
  val rvfi_ixl = IO(Input(UInt(2.W)))
  val rvfi_rs1_addr = IO(Input(UInt(5.W)))
  val rvfi_rs2_addr = IO(Input(UInt(5.W)))
  val rvfi_rs3_addr = IO(Input(UInt(5.W)))
  val rvfi_rs1_rdata = IO(Input(UInt(32.W)))
  val rvfi_rs2_rdata = IO(Input(UInt(32.W)))
  val rvfi_rs3_rdata = IO(Input(UInt(32.W)))
  val rvfi_rd_addr = IO(Input(UInt(5.W)))
  val rvfi_rd_wdata = IO(Input(UInt(32.W)))
  val rvfi_pc_rdata = IO(Input(UInt(32.W)))
  val rvfi_pc_wdata = IO(Input(UInt(32.W)))
  val rvfi_mem_addr = IO(Input(UInt(32.W)))
  val rvfi_mem_rmask = IO(Input(UInt(4.W)))
  val rvfi_mem_wmask = IO(Input(UInt(4.W)))
  val rvfi_mem_rdata = IO(Input(UInt(32.W)))
  val rvfi_mem_wdata = IO(Input(UInt(32.W)))
  val rvfi_ext_expanded_insn_valid = IO(Input(Bool()))
  val rvfi_ext_expanded_insn = IO(Input(UInt(16.W)))

  private val RS1 = 1.U(5.W) << 0
  private val RS2 = 1.U(5.W) << 1
  private val RS3 = 1.U(5.W) << 2
  private val RD = 1.U(5.W) << 3
  private val MEM = 1.U(5.W) << 4

  private val cycle = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val cycle = RegInit(0.U(32.W))
    cycle := cycle + 1.U
    cycle
  }

  private val insn_is_compressed = rvfi_insn(1, 0) =/= "b11".U
  private val data_accessed = WireDefault(0.U(5.W))

  private val opcode = rvfi_insn(6, 0)
  private val funct3 = rvfi_insn(14, 12)
  private val funct7 = rvfi_insn(31, 25)

  when(insn_is_compressed) {
    data_accessed := RS1 | RS2 | RD | MEM
  }.otherwise {
    switch(opcode) {
      is(IbexPkg.Opcode.LOAD) {
        data_accessed := RS1 | RD | MEM
      }
      is(IbexPkg.Opcode.STORE) {
        data_accessed := RS1 | RS2 | MEM
      }
      is(IbexPkg.Opcode.BRANCH) {
        data_accessed := RS1 | RS2
      }
      is(IbexPkg.Opcode.JAL) {
        data_accessed := RD
      }
      is(IbexPkg.Opcode.JALR) {
        data_accessed := RS1 | RD
      }
      is(IbexPkg.Opcode.LUI, IbexPkg.Opcode.AUIPC) {
        data_accessed := RD
      }
      is(IbexPkg.Opcode.OP_IMM) {
        data_accessed := RS1 | RD
      }
      is(IbexPkg.Opcode.OP) {
        when(funct7 === 1.U && (funct3 === 0.U || funct3 === 1.U || funct3 === 2.U || funct3 === 3.U)) {
          data_accessed := RS1 | RS2 | RD
        }.otherwise {
          data_accessed := RS1 | RS2 | RD
        }
      }
      is(IbexPkg.Opcode.SYSTEM) {
        data_accessed := Mux(funct3 === 0.U, 0.U, Mux(rvfi_insn(14), RD, RS1 | RD))
      }
    }
  }

  private val logger = Module(new IbexTracerLogger)
  logger.clk_i := clk_i
  logger.rst_ni := rst_ni
  logger.hart_id_i := hart_id_i
  logger.rvfi_valid_i := rvfi_valid
  logger.cycle_i := cycle
  logger.rvfi_insn_i := rvfi_insn
  logger.insn_is_compressed_i := insn_is_compressed
  logger.rvfi_pc_rdata_i := rvfi_pc_rdata
  logger.rvfi_pc_wdata_i := rvfi_pc_wdata
  logger.data_accessed_i := data_accessed
  logger.rvfi_rs1_addr_i := rvfi_rs1_addr
  logger.rvfi_rs2_addr_i := rvfi_rs2_addr
  logger.rvfi_rs3_addr_i := rvfi_rs3_addr
  logger.rvfi_rs1_rdata_i := rvfi_rs1_rdata
  logger.rvfi_rs2_rdata_i := rvfi_rs2_rdata
  logger.rvfi_rs3_rdata_i := rvfi_rs3_rdata
  logger.rvfi_rd_addr_i := rvfi_rd_addr
  logger.rvfi_rd_wdata_i := rvfi_rd_wdata
  logger.rvfi_mem_addr_i := rvfi_mem_addr
  logger.rvfi_mem_rmask_i := rvfi_mem_rmask
  logger.rvfi_mem_wmask_i := rvfi_mem_wmask
  logger.rvfi_mem_rdata_i := rvfi_mem_rdata
  logger.rvfi_mem_wdata_i := rvfi_mem_wdata
  logger.rvfi_ext_expanded_insn_valid_i := rvfi_ext_expanded_insn_valid
  logger.rvfi_ext_expanded_insn_i := rvfi_ext_expanded_insn

  dontTouch(hart_id_i)
  dontTouch(rvfi_order)
  dontTouch(rvfi_trap)
  dontTouch(rvfi_halt)
  dontTouch(rvfi_intr)
  dontTouch(rvfi_mode)
  dontTouch(rvfi_ixl)
}
