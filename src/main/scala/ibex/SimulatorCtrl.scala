// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class SimulatorCtrl(
    logName: String = "ibex_out.log",
    flushOnChar: Boolean = true,
    finishOnHalt: Boolean = true)
    extends ExtModule(Map(
      "LogName" -> StringParam(logName),
      "FlushOnChar" -> IntParam(if (flushOnChar) 1 else 0),
      "FinishOnHalt" -> IntParam(if (finishOnHalt) 1 else 0))) {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val req_i = IO(Input(Bool()))
  val we_i = IO(Input(Bool()))
  val be_i = IO(Input(UInt(4.W)))
  val addr_i = IO(Input(UInt(32.W)))
  val wdata_i = IO(Input(UInt(32.W)))
  val rvalid_o = IO(Output(Bool()))
  val rdata_o = IO(Output(UInt(32.W)))
  val halt_o = IO(Output(Bool()))

  override def desiredName: String = "SimulatorCtrl"

  setInline("SimulatorCtrl.sv",
    """// Chisel-emitted equivalent of Ibex's shared simulator_ctrl.
      |module SimulatorCtrl #(
      |  parameter string LogName = "ibex_out.log",
      |  parameter bit FlushOnChar = 1'b1,
      |  parameter bit FinishOnHalt = 1'b1
      |) (
      |  input               clk_i,
      |  input               rst_ni,
      |
      |  input               req_i,
      |  input               we_i,
      |  input        [ 3:0] be_i,
      |  input        [31:0] addr_i,
      |  input        [31:0] wdata_i,
      |  output logic        rvalid_o,
      |  output logic [31:0] rdata_o,
      |  output logic        halt_o
      |);
      |
      |  localparam logic [7:0] CHAR_OUT_ADDR = 8'h0;
      |  localparam logic [7:0] SIM_CTRL_ADDR = 8'h2;
      |
      |  logic [7:0] ctrl_addr;
      |  logic [2:0] sim_finish;
      |  integer log_fd;
      |
      |  initial begin
      |    log_fd = $fopen(LogName, "w");
      |  end
      |
      |  final begin
      |    $fclose(log_fd);
      |  end
      |
      |  assign ctrl_addr = addr_i[9:2];
      |  assign halt_o = sim_finish >= 3'b010;
      |  assign rdata_o = '0;
      |
      |  always_ff @(posedge clk_i or negedge rst_ni) begin
      |    if (~rst_ni) begin
      |      rvalid_o <= 1'b0;
      |      sim_finish <= '0;
      |    end else begin
      |      rvalid_o <= req_i;
      |
      |      if (req_i & we_i) begin
      |        case (ctrl_addr)
      |          CHAR_OUT_ADDR: begin
      |            if (be_i[0]) begin
      |              $fwrite(log_fd, "%c", wdata_i[7:0]);
      |              if (FlushOnChar) begin
      |                $fflush(log_fd);
      |              end
      |            end
      |          end
      |          SIM_CTRL_ADDR: begin
      |            if ((be_i[0] & wdata_i[0]) && (sim_finish == '0)) begin
      |              $display("Terminating simulation by software request.");
      |              sim_finish <= 3'b001;
      |            end
      |          end
      |          default: ;
      |        endcase
      |      end
      |
      |      if (sim_finish != '0) begin
      |        sim_finish <= sim_finish + 3'b001;
      |      end
      |
      |      if (FinishOnHalt && (sim_finish >= 3'b010)) begin
      |        $finish;
      |      end
      |    end
      |  end
      |endmodule
      |""".stripMargin)
}
