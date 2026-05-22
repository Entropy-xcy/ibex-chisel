// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

private class Ram2PStorage(depth: Int, memInitFile: String) extends ExtModule(Map(
    "Depth" -> IntParam(depth),
    "MemInitFile" -> StringParam(memInitFile))) {
  require(depth > 0 && isPow2(depth), s"Depth must be a positive power of two, got $depth")

  private val aw = log2Ceil(depth)

  val clk_a_i = IO(Input(Clock()))
  val clk_b_i = IO(Input(Clock()))

  val a_req_i = IO(Input(Bool()))
  val a_write_i = IO(Input(Bool()))
  val a_addr_i = IO(Input(UInt(aw.W)))
  val a_wdata_i = IO(Input(UInt(32.W)))
  val a_wmask_i = IO(Input(UInt(32.W)))
  val a_rdata_o = IO(Output(UInt(32.W)))

  val b_req_i = IO(Input(Bool()))
  val b_write_i = IO(Input(Bool()))
  val b_addr_i = IO(Input(UInt(aw.W)))
  val b_wdata_i = IO(Input(UInt(32.W)))
  val b_wmask_i = IO(Input(UInt(32.W)))
  val b_rdata_o = IO(Output(UInt(32.W)))

  override def desiredName: String = "Ram2PStorage"

  setInline("Ram2PStorage.sv",
    """// Chisel-emitted equivalent of Ibex's generic prim_ram_2p storage model.
      |// The instance is intentionally named u_ram by Ram2P so upstream MemArea
      |// users can scope to TOP.ibex_simple_system.u_ram.u_ram.
      |module Ram2PStorage #(
      |  parameter int Depth = 128,
      |  parameter MemInitFile = "",
      |  localparam int Aw = $clog2(Depth)
      |) (
      |  input              clk_a_i,
      |  input              clk_b_i,
      |
      |  input              a_req_i,
      |  input              a_write_i,
      |  input  [Aw-1:0]    a_addr_i,
      |  input  [31:0]      a_wdata_i,
      |  input  [31:0]      a_wmask_i,
      |  output logic [31:0] a_rdata_o,
      |
      |  input              b_req_i,
      |  input              b_write_i,
      |  input  [Aw-1:0]    b_addr_i,
      |  input  [31:0]      b_wdata_i,
      |  input  [31:0]      b_wmask_i,
      |  output logic [31:0] b_rdata_o
      |);
      |
      |  localparam int Width = 32;
      |  logic [Width-1:0] mem [Depth];
      |
      |  always @(posedge clk_a_i) begin
      |    if (a_req_i) begin
      |      if (a_write_i) begin
      |        for (int i = 0; i < 4; i++) begin
      |          if (&a_wmask_i[i*8 +: 8]) begin
      |            mem[a_addr_i][i*8 +: 8] <= a_wdata_i[i*8 +: 8];
      |          end
      |        end
      |      end else begin
      |        a_rdata_o <= mem[a_addr_i];
      |      end
      |    end
      |  end
      |
      |  always @(posedge clk_b_i) begin
      |    if (b_req_i) begin
      |      if (b_write_i) begin
      |        for (int i = 0; i < 4; i++) begin
      |          if (&b_wmask_i[i*8 +: 8]) begin
      |            mem[b_addr_i][i*8 +: 8] <= b_wdata_i[i*8 +: 8];
      |          end
      |        end
      |      end else begin
      |        b_rdata_o <= mem[b_addr_i];
      |      end
      |    end
      |  end
      |
      |`ifndef SYNTHESIS
      |  export "DPI-C" task simutil_memload;
      |  task simutil_memload;
      |    input string file;
      |    $readmemh(file, mem);
      |  endtask
      |
      |  export "DPI-C" function simutil_set_mem;
      |  function int simutil_set_mem(input int index, input bit [311:0] val);
      |    int valid;
      |    valid = Width > 312 || index >= Depth ? 0 : 1;
      |    if (valid == 1) mem[index] = val[Width-1:0];
      |    return valid;
      |  endfunction
      |
      |  export "DPI-C" function simutil_get_mem;
      |  function int simutil_get_mem(input int index, output bit [311:0] val);
      |    int valid;
      |    valid = Width > 312 || index >= Depth ? 0 : 1;
      |    if (valid == 1) begin
      |      val = 0;
      |      val[Width-1:0] = mem[index];
      |    end
      |    return valid;
      |  endfunction
      |`endif
      |
      |  initial begin
      |`ifndef SYNTHESIS
      |    logic show_mem_paths;
      |    void'($value$plusargs("show_mem_paths=%0b", show_mem_paths));
      |    if (show_mem_paths) $display("%m");
      |`endif
      |
      |    if (MemInitFile != "") begin : gen_meminit
      |      $display("Initializing memory %m from file '%s'.", MemInitFile);
      |      $readmemh(MemInitFile, mem);
      |    end
      |  end
      |endmodule
      |""".stripMargin)
}

class Ram2P(depth: Int = 128, bExtraDelay: Int = 0, memInitFile: String = "") extends RawModule {
  require(depth > 0 && isPow2(depth), s"Depth must be a positive power of two, got $depth")
  require(bExtraDelay >= 0, s"BExtraDelay must be non-negative, got $bExtraDelay")

  private val aw = log2Ceil(depth)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val a_req_i = IO(Input(Bool()))
  val a_we_i = IO(Input(Bool()))
  val a_be_i = IO(Input(UInt(4.W)))
  val a_addr_i = IO(Input(UInt(32.W)))
  val a_wdata_i = IO(Input(UInt(32.W)))
  val a_rvalid_o = IO(Output(Bool()))
  val a_rdata_o = IO(Output(UInt(32.W)))

  val b_req_i = IO(Input(Bool()))
  val b_we_i = IO(Input(Bool()))
  val b_be_i = IO(Input(UInt(4.W)))
  val b_addr_i = IO(Input(UInt(32.W)))
  val b_wdata_i = IO(Input(UInt(32.W)))
  val b_rvalid_o = IO(Output(Bool()))
  val b_rdata_o = IO(Output(UInt(32.W)))

  dontTouch(a_req_i)
  dontTouch(a_we_i)
  dontTouch(a_be_i)
  dontTouch(a_addr_i)
  dontTouch(a_wdata_i)
  dontTouch(b_req_i)
  dontTouch(b_we_i)
  dontTouch(b_be_i)
  dontTouch(b_addr_i)
  dontTouch(b_wdata_i)

  private def byteMask(be: UInt): UInt =
    Cat((0 until 4).reverse.map(i => Fill(8, be(i))))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val a_addr_idx = a_addr_i(aw + 1, 2)
    val b_addr_idx = b_addr_i(aw + 1, 2)
    val u_ram = Module(new Ram2PStorage(depth, memInitFile))

    val a_rvalid_q = RegInit(false.B)
    val b_rvalid_pipe = RegInit(VecInit(Seq.fill(if (bExtraDelay == 0) 1 else bExtraDelay)(false.B)))
    val b_rdata_pipe = RegInit(VecInit(Seq.fill(if (bExtraDelay == 0) 1 else bExtraDelay)(0.U(32.W))))

    u_ram.clk_a_i := clk_i
    u_ram.clk_b_i := clk_i
    u_ram.a_req_i := a_req_i
    u_ram.a_write_i := a_we_i
    u_ram.a_addr_i := a_addr_idx
    u_ram.a_wdata_i := a_wdata_i
    u_ram.a_wmask_i := byteMask(a_be_i)
    u_ram.b_req_i := b_req_i
    u_ram.b_write_i := b_we_i
    u_ram.b_addr_i := b_addr_idx
    u_ram.b_wdata_i := b_wdata_i
    u_ram.b_wmask_i := byteMask(b_be_i)

    a_rvalid_q := a_req_i

    b_rvalid_pipe(0) := b_req_i
    b_rdata_pipe(0) := u_ram.b_rdata_o

    for (i <- (if (bExtraDelay == 0) 0 else bExtraDelay - 1) to 1 by -1) {
      b_rvalid_pipe(i) := b_rvalid_pipe(i - 1)
      b_rdata_pipe(i) := b_rdata_pipe(i - 1)
    }

    a_rvalid_o := a_rvalid_q
    a_rdata_o := u_ram.a_rdata_o
    b_rvalid_o := b_rvalid_pipe(b_rvalid_pipe.length - 1)
    b_rdata_o := (if (bExtraDelay == 0) u_ram.b_rdata_o else b_rdata_pipe(b_rdata_pipe.length - 1))
  }
}
