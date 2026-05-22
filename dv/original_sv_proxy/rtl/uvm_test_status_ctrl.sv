// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

module uvm_test_status_ctrl #(
  parameter string       LogName = "ibex_uvm_test_status.log",
  parameter int unsigned StatusAddrLowNibble = 8
) (
  input               clk_i,
  input               rst_ni,

  input               req_i,
  input               we_i,
  input        [ 3:0] be_i,
  input        [31:0] addr_i,
  input        [31:0] wdata_i,
  output logic        rvalid_o,
  output logic [31:0] rdata_o
);

  integer log_fd;

  initial begin
    log_fd = $fopen(LogName, "w");
  end

  final begin
    $fclose(log_fd);
  end

  assign rdata_o = '0;

  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (!rst_ni) begin
      rvalid_o <= 1'b0;
    end else begin
      rvalid_o <= req_i;

      if (req_i && we_i && be_i[0] && (addr_i[3:0] == StatusAddrLowNibble[3:0])) begin
        $fwrite(log_fd, "0x%08x\n", wdata_i);
        $fflush(log_fd);

        unique case (wdata_i)
          32'h0000_0000: begin
          end
          32'h0000_0700: begin
          end
          32'h0000_0001: begin
            $display("UVM directed test PASS signature observed.");
            $finish;
          end
          default: begin
            $display("UVM directed test FAIL signature observed: 0x%08x", wdata_i);
            $fatal(1, "UVM directed test failed");
          end
        endcase
      end
    end
  end
endmodule
