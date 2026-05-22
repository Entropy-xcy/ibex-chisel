// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

private class Ram1PStorage(depth: Int, memInitFile: String) extends ExtModule(Map(
    "Depth" -> IntParam(depth),
    "MemInitFile" -> StringParam(memInitFile))) {
  require(depth > 0 && isPow2(depth), s"Depth must be a positive power of two, got $depth")

  private val aw = log2Ceil(depth)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))
  val req_i = IO(Input(Bool()))
  val write_i = IO(Input(Bool()))
  val addr_i = IO(Input(UInt(aw.W)))
  val wdata_i = IO(Input(UInt(32.W)))
  val wmask_i = IO(Input(UInt(32.W)))
  val rdata_o = IO(Output(UInt(32.W)))

  override def desiredName: String = "Ram1PStorage"

  setInline("Ram1PStorage.sv",
    """// Chisel-emitted equivalent of Ibex's generic prim_ram_1p storage model.
      |// The instance is intentionally named u_ram by Ram1P so upstream MemArea
      |// users can scope to TOP.ibex_riscv_compliance.u_ram.u_ram.
      |module Ram1PStorage #(
      |  parameter int Depth = 128,
      |  parameter MemInitFile = "",
      |  localparam int Aw = $clog2(Depth)
      |) (
      |  input              clk_i,
      |  input              rst_ni,
      |  input              req_i,
      |  input              write_i,
      |  input  [Aw-1:0]    addr_i,
      |  input  [31:0]      wdata_i,
      |  input  [31:0]      wmask_i,
      |  output logic [31:0] rdata_o
      |);
      |
      |  localparam int Width = 32;
      |  logic [Width-1:0] mem [Depth];
      |  logic unused_rst_ni;
      |  assign unused_rst_ni = rst_ni;
      |
      |  always @(posedge clk_i) begin
      |    if (req_i) begin
      |      if (write_i) begin
      |        for (int i = 0; i < 4; i++) begin
      |          if (&wmask_i[i*8 +: 8]) begin
      |            mem[addr_i][i*8 +: 8] <= wdata_i[i*8 +: 8];
      |          end
      |        end
      |      end else begin
      |        rdata_o <= mem[addr_i];
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

class Ram1P(depth: Int = 128, memInitFile: String = "") extends RawModule {
  require(depth > 0 && isPow2(depth), s"Depth must be a positive power of two, got $depth")

  private val aw = log2Ceil(depth)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val req_i = IO(Input(Bool()))
  val we_i = IO(Input(Bool()))
  val be_i = IO(Input(UInt(4.W)))
  val addr_i = IO(Input(UInt(32.W)))
  val wdata_i = IO(Input(UInt(32.W)))
  val rvalid_o = IO(Output(Bool()))
  val rdata_o = IO(Output(UInt(32.W)))

  dontTouch(req_i)
  dontTouch(we_i)
  dontTouch(be_i)
  dontTouch(addr_i)
  dontTouch(wdata_i)

  private def byteMask(be: UInt): UInt =
    Cat((0 until 4).reverse.map(i => Fill(8, be(i))))

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val u_ram = Module(new Ram1PStorage(depth, memInitFile))
    val rvalid_q = RegInit(false.B)
    val addr_idx = addr_i(aw + 1, 2)

    u_ram.clk_i := clk_i
    u_ram.rst_ni := rst_ni
    u_ram.req_i := req_i
    u_ram.write_i := we_i
    u_ram.addr_i := addr_idx
    u_ram.wdata_i := wdata_i
    u_ram.wmask_i := byteMask(be_i)

    rvalid_q := req_i

    rvalid_o := rvalid_q
    rdata_o := u_ram.rdata_o
  }
}
