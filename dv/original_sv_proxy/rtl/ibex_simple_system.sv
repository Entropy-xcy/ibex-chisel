// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

`ifndef RV32M
  `define RV32M ibex_pkg::RV32MFast
`endif

`ifndef RV32B
  `define RV32B ibex_pkg::RV32BNone
`endif

`ifndef RV32ZC
  `define RV32ZC ibex_pkg::RV32ZcaZcbZcmp
`endif

`ifndef RegFile
  `define RegFile ibex_pkg::RegFileFF
`endif

`ifndef INSTR_CYCLE_DELAY
  `define INSTR_CYCLE_DELAY 0
`endif

module ibex_simple_system (
  input IO_CLK,
  input IO_RST_N
);

  parameter bit                 SecureIbex               = 1'b0;
  parameter int unsigned        LockstepOffset           = 1;
  parameter bit                 ICacheScramble           = 1'b0;
  parameter bit                 PMPEnable                = 1'b0;
  parameter int unsigned        PMPGranularity           = 0;
  parameter int unsigned        PMPNumRegions            = 4;
  parameter int unsigned        MHPMCounterNum           = 0;
  parameter int unsigned        MHPMCounterWidth         = 40;
  parameter bit                 RV32E                    = 1'b0;
  parameter ibex_pkg::rv32m_e   RV32M                    = `RV32M;
  parameter ibex_pkg::rv32b_e   RV32B                    = `RV32B;
  parameter ibex_pkg::rv32zc_e  RV32ZC                   = `RV32ZC;
  parameter ibex_pkg::regfile_e RegFile                  = `RegFile;
  parameter bit                 BranchTargetALU          = 1'b0;
  parameter bit                 WritebackStage           = 1'b0;
  parameter bit                 ICache                   = 1'b0;
  parameter bit                 DbgTriggerEn             = 1'b0;
  parameter bit                 ICacheECC                = 1'b0;
  parameter bit                 ICacheTweakInfection     = 1'b0;
  parameter bit                 BranchPredictor          = 1'b0;
  parameter                     SRAMInitFile             = "";

  logic clk_sys = 1'b0, rst_sys_n;
  logic [31:0] boot_addr;

  typedef enum logic [2:0] {
    Ram,
    SimCtrl,
    Timer,
    UvmStatus,
    TohostStatus
  } bus_device_e;

  localparam int NrDevices = 5;
  localparam int NrHosts = 1;

  logic timer_irq;

  logic        host_req    [NrHosts];
  logic        host_gnt    [NrHosts];
  logic [31:0] host_addr   [NrHosts];
  logic        host_we     [NrHosts];
  logic [ 3:0] host_be     [NrHosts];
  logic [31:0] host_wdata  [NrHosts];
  logic        host_rvalid [NrHosts];
  logic [31:0] host_rdata  [NrHosts];
  logic        host_err    [NrHosts];

  logic [6:0] data_rdata_intg;
  logic [6:0] instr_rdata_intg;

  logic        device_req    [NrDevices];
  logic [31:0] device_addr   [NrDevices];
  logic        device_we     [NrDevices];
  logic [ 3:0] device_be     [NrDevices];
  logic [31:0] device_wdata  [NrDevices];
  logic        device_rvalid [NrDevices];
  logic [31:0] device_rdata  [NrDevices];
  logic        device_err    [NrDevices];

  logic [31:0] cfg_device_addr_base [NrDevices];
  logic [31:0] cfg_device_addr_mask [NrDevices];
  assign cfg_device_addr_base[Ram] = 32'h0000_0000;
  assign cfg_device_addr_mask[Ram] = 32'h0000_0000;
  assign cfg_device_addr_base[SimCtrl] = 32'h0002_0000;
  assign cfg_device_addr_mask[SimCtrl] = 32'hffff_fc00;
  assign cfg_device_addr_base[Timer] = 32'h0003_0000;
  assign cfg_device_addr_mask[Timer] = 32'hffff_fc00;
  assign cfg_device_addr_base[UvmStatus] = 32'h8fff_fff0;
  assign cfg_device_addr_mask[UvmStatus] = 32'hffff_fff0;
  assign cfg_device_addr_base[TohostStatus] = 32'h8000_1000;
  assign cfg_device_addr_mask[TohostStatus] = 32'hffff_fff0;

  logic        instr_req;
  logic        instr_gnt;
  logic        instr_rvalid;
  logic [31:0] instr_addr;
  logic [31:0] instr_rdata;
  logic        instr_err;

  assign instr_gnt = instr_req;
  assign instr_err = 1'b0;

  `ifdef VERILATOR
    assign clk_sys = IO_CLK;
    assign rst_sys_n = IO_RST_N;
  `else
    initial begin
      rst_sys_n = 1'b0;
      #8
      rst_sys_n = 1'b1;
    end
    always begin
      #1 clk_sys = 1'b0;
      #1 clk_sys = 1'b1;
    end
  `endif

  initial begin
    boot_addr = 32'h0000_0000;
    void'($value$plusargs("boot_addr=%h", boot_addr));
  end

  assign device_err[Ram] = 1'b0;
  assign device_err[SimCtrl] = 1'b0;
  assign device_err[UvmStatus] = 1'b0;
  assign device_err[TohostStatus] = 1'b0;

  bus #(
    .NrDevices(NrDevices),
    .NrHosts(NrHosts),
    .DataWidth(32),
    .AddressWidth(32)
  ) u_bus (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),

    .host_req_i(host_req),
    .host_gnt_o(host_gnt),
    .host_addr_i(host_addr),
    .host_we_i(host_we),
    .host_be_i(host_be),
    .host_wdata_i(host_wdata),
    .host_rvalid_o(host_rvalid),
    .host_rdata_o(host_rdata),
    .host_err_o(host_err),

    .device_req_o(device_req),
    .device_addr_o(device_addr),
    .device_we_o(device_we),
    .device_be_o(device_be),
    .device_wdata_o(device_wdata),
    .device_rvalid_i(device_rvalid),
    .device_rdata_i(device_rdata),
    .device_err_i(device_err),

    .cfg_device_addr_base,
    .cfg_device_addr_mask
  );

  if (SecureIbex) begin : g_mem_rdata_ecc
    logic [31:0] unused_data_rdata;
    logic [31:0] unused_instr_rdata;

    prim_secded_inv_39_32_enc u_data_rdata_intg_gen (
      .data_i(host_rdata[0]),
      .data_o({data_rdata_intg, unused_data_rdata})
    );

    prim_secded_inv_39_32_enc u_instr_rdata_intg_gen (
      .data_i(instr_rdata),
      .data_o({instr_rdata_intg, unused_instr_rdata})
    );
  end else begin : g_no_mem_rdata_ecc
    assign data_rdata_intg = '0;
    assign instr_rdata_intg = '0;
  end

  ibex_top_tracing #(
    .SecureIbex(SecureIbex),
    .LockstepOffset(LockstepOffset),
    .ICacheScramble(ICacheScramble),
    .PMPEnable(PMPEnable),
    .PMPGranularity(PMPGranularity),
    .PMPNumRegions(PMPNumRegions),
    .MHPMCounterNum(MHPMCounterNum),
    .MHPMCounterWidth(MHPMCounterWidth),
    .RV32E(RV32E),
    .RV32M(RV32M),
    .RV32B(RV32B),
    .RV32ZC(RV32ZC),
    .RegFile(RegFile),
    .BranchTargetALU(BranchTargetALU),
    .ICache(ICache),
    .ICacheECC(ICacheECC),
    .ICacheTweakInfection(ICacheTweakInfection),
    .WritebackStage(WritebackStage),
    .BranchPredictor(BranchPredictor),
    .DbgTriggerEn(DbgTriggerEn),
    .DmBaseAddr(32'h0000_0000),
    .DmAddrMask(32'h0000_0003),
    .DmHaltAddr(32'h0000_0000),
    .DmExceptionAddr(32'h0000_0000)
  ) u_top (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),

    .test_en_i(1'b0),
    .scan_rst_ni(1'b1),
    .ram_cfg_icache_tag_i(prim_ram_1p_pkg::RAM_1P_CFG_DEFAULT),
    .ram_cfg_rsp_icache_tag_o(),
    .ram_cfg_icache_data_i(prim_ram_1p_pkg::RAM_1P_CFG_DEFAULT),
    .ram_cfg_rsp_icache_data_o(),

    .hart_id_i(32'b0),
    .boot_addr_i(boot_addr),

    .instr_req_o(instr_req),
    .instr_gnt_i(instr_gnt),
    .instr_rvalid_i(instr_rvalid),
    .instr_addr_o(instr_addr),
    .instr_rdata_i(instr_rdata),
    .instr_rdata_intg_i(instr_rdata_intg),
    .instr_err_i(instr_err),

    .data_req_o(host_req[0]),
    .data_gnt_i(host_gnt[0]),
    .data_rvalid_i(host_rvalid[0]),
    .data_we_o(host_we[0]),
    .data_be_o(host_be[0]),
    .data_addr_o(host_addr[0]),
    .data_wdata_o(host_wdata[0]),
    .data_wdata_intg_o(),
    .data_rdata_i(host_rdata[0]),
    .data_rdata_intg_i(data_rdata_intg),
    .data_err_i(host_err[0]),

    .irq_software_i(1'b0),
    .irq_timer_i(timer_irq),
    .irq_external_i(1'b0),
    .irq_fast_i(15'b0),
    .irq_nm_i(1'b0),

    .scramble_key_valid_i(1'b1),
    .scramble_key_i(ibex_pkg::RndCnstIbexKeyDefault),
    .scramble_nonce_i(ibex_pkg::RndCnstIbexNonceDefault),
    .scramble_req_o(),

    .debug_req_i(1'b0),
    .crash_dump_o(),
    .double_fault_seen_o(),

    .fetch_enable_i(ibex_pkg::IbexMuBiOn),
    .alert_minor_o(),
    .alert_major_internal_o(),
    .alert_major_bus_o(),
    .core_sleep_o(),

    .lockstep_cmp_en_o(),

    .data_req_shadow_o(),
    .data_we_shadow_o(),
    .data_be_shadow_o(),
    .data_addr_shadow_o(),
    .data_wdata_shadow_o(),
    .data_wdata_intg_shadow_o(),

    .instr_req_shadow_o(),
    .instr_addr_shadow_o()
  );

  ram_2p #(
    .Depth(1024 * 1024),
    .BExtraDelay(`INSTR_CYCLE_DELAY),
    .MemInitFile(SRAMInitFile)
  ) u_ram (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),

    .a_req_i(device_req[Ram]),
    .a_we_i(device_we[Ram]),
    .a_be_i(device_be[Ram]),
    .a_addr_i(device_addr[Ram]),
    .a_wdata_i(device_wdata[Ram]),
    .a_rvalid_o(device_rvalid[Ram]),
    .a_rdata_o(device_rdata[Ram]),

    .b_req_i(instr_req),
    .b_we_i(1'b0),
    .b_be_i(4'b0),
    .b_addr_i(instr_addr),
    .b_wdata_i(32'b0),
    .b_rvalid_o(instr_rvalid),
    .b_rdata_o(instr_rdata)
  );

  simulator_ctrl #(
    .LogName("ibex_simple_system.log")
  ) u_simulator_ctrl (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),
    .req_i(device_req[SimCtrl]),
    .we_i(device_we[SimCtrl]),
    .be_i(device_be[SimCtrl]),
    .addr_i(device_addr[SimCtrl]),
    .wdata_i(device_wdata[SimCtrl]),
    .rvalid_o(device_rvalid[SimCtrl]),
    .rdata_o(device_rdata[SimCtrl])
  );

  timer #(
    .DataWidth(32),
    .AddressWidth(32)
  ) u_timer (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),
    .timer_req_i(device_req[Timer]),
    .timer_we_i(device_we[Timer]),
    .timer_be_i(device_be[Timer]),
    .timer_addr_i(device_addr[Timer]),
    .timer_wdata_i(device_wdata[Timer]),
    .timer_rvalid_o(device_rvalid[Timer]),
    .timer_rdata_o(device_rdata[Timer]),
    .timer_err_o(device_err[Timer]),
    .timer_intr_o(timer_irq)
  );

  uvm_test_status_ctrl #(
    .LogName("ibex_uvm_test_status.log")
  ) u_uvm_test_status_ctrl (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),
    .req_i(device_req[UvmStatus]),
    .we_i(device_we[UvmStatus]),
    .be_i(device_be[UvmStatus]),
    .addr_i(device_addr[UvmStatus]),
    .wdata_i(device_wdata[UvmStatus]),
    .rvalid_o(device_rvalid[UvmStatus]),
    .rdata_o(device_rdata[UvmStatus])
  );

  uvm_test_status_ctrl #(
    .LogName("ibex_tohost_test_status.log"),
    .StatusAddrLowNibble(0)
  ) u_tohost_test_status_ctrl (
    .clk_i(clk_sys),
    .rst_ni(rst_sys_n),
    .req_i(device_req[TohostStatus]),
    .we_i(device_we[TohostStatus]),
    .be_i(device_be[TohostStatus]),
    .addr_i(device_addr[TohostStatus]),
    .wdata_i(device_wdata[TohostStatus]),
    .rvalid_o(device_rvalid[TohostStatus]),
    .rdata_o(device_rdata[TohostStatus])
  );

  export "DPI-C" function mhpmcounter_num;

  function automatic int unsigned mhpmcounter_num();
    return u_top.u_ibex_top.u_ibex_core.cs_registers_i.MHPMCounterNum;
  endfunction

  export "DPI-C" function mhpmcounter_get;

  function automatic longint unsigned mhpmcounter_get(int index);
    return u_top.u_ibex_top.u_ibex_core.cs_registers_i.mhpmcounter[index];
  endfunction
endmodule
