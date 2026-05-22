// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class Bus(
    nrDevices: Int = 1,
    nrHosts: Int = 1,
    dataWidth: Int = 32,
    addressWidth: Int = 32)
    extends RawModule {
  override def desiredName: String =
    if (nrDevices == 1 && nrHosts == 1 && dataWidth == 32 && addressWidth == 32) {
      "Bus"
    } else {
      s"Bus_${nrDevices}d_${nrHosts}h_${dataWidth}w_${addressWidth}a"
    }

  require(nrDevices > 0, s"NrDevices must be positive, got $nrDevices")
  require(nrHosts > 0, s"NrHosts must be positive, got $nrHosts")
  require(dataWidth > 0 && dataWidth % 8 == 0, s"DataWidth must be positive and byte-aligned, got $dataWidth")
  require(addressWidth > 0, s"AddressWidth must be positive, got $addressWidth")

  private val numBitsHostSel = if (nrHosts > 1) log2Ceil(nrHosts) else 1
  private val numBitsDeviceSel = if (nrDevices > 1) log2Ceil(nrDevices) else 1

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val host_req_i = IO(Input(Vec(nrHosts, Bool())))
  val host_gnt_o = IO(Output(Vec(nrHosts, Bool())))
  val host_addr_i = IO(Input(Vec(nrHosts, UInt(addressWidth.W))))
  val host_we_i = IO(Input(Vec(nrHosts, Bool())))
  val host_be_i = IO(Input(Vec(nrHosts, UInt((dataWidth / 8).W))))
  val host_wdata_i = IO(Input(Vec(nrHosts, UInt(dataWidth.W))))
  val host_rvalid_o = IO(Output(Vec(nrHosts, Bool())))
  val host_rdata_o = IO(Output(Vec(nrHosts, UInt(dataWidth.W))))
  val host_err_o = IO(Output(Vec(nrHosts, Bool())))

  val device_req_o = IO(Output(Vec(nrDevices, Bool())))
  val device_addr_o = IO(Output(Vec(nrDevices, UInt(addressWidth.W))))
  val device_we_o = IO(Output(Vec(nrDevices, Bool())))
  val device_be_o = IO(Output(Vec(nrDevices, UInt((dataWidth / 8).W))))
  val device_wdata_o = IO(Output(Vec(nrDevices, UInt(dataWidth.W))))
  val device_rvalid_i = IO(Input(Vec(nrDevices, Bool())))
  val device_rdata_i = IO(Input(Vec(nrDevices, UInt(dataWidth.W))))
  val device_err_i = IO(Input(Vec(nrDevices, Bool())))

  val cfg_device_addr_base = IO(Input(Vec(nrDevices, UInt(addressWidth.W))))
  val cfg_device_addr_mask = IO(Input(Vec(nrDevices, UInt(addressWidth.W))))

  val host_sel_valid = WireDefault(false.B)
  val device_sel_valid = WireDefault(false.B)
  val host_sel_req = WireDefault(0.U(numBitsHostSel.W))
  val device_sel_req = WireDefault(0.U(numBitsDeviceSel.W))
  val host_req_sel = Wire(Bool())
  val host_addr_sel = Wire(UInt(addressWidth.W))
  val host_we_sel = Wire(Bool())
  val host_be_sel = Wire(UInt((dataWidth / 8).W))
  val host_wdata_sel = Wire(UInt(dataWidth.W))

  // Matches the SV descending loop: host 0 has highest priority because it is assigned last.
  for (host <- (nrHosts - 1) to 0 by -1) {
    when(host_req_i(host)) {
      host_sel_valid := true.B
      host_sel_req := host.U(numBitsHostSel.W)
    }
  }

  if (nrHosts == 1) {
    host_req_sel := host_req_i(0)
    host_addr_sel := host_addr_i(0)
    host_we_sel := host_we_i(0)
    host_be_sel := host_be_i(0)
    host_wdata_sel := host_wdata_i(0)
  } else {
    host_req_sel := host_req_i(host_sel_req)
    host_addr_sel := host_addr_i(host_sel_req)
    host_we_sel := host_we_i(host_sel_req)
    host_be_sel := host_be_i(host_sel_req)
    host_wdata_sel := host_wdata_i(host_sel_req)
  }

  // Matches the SV ascending loop: later matching devices override earlier matches.
  for (device <- 0 until nrDevices) {
    when((host_addr_sel & cfg_device_addr_mask(device)) === cfg_device_addr_base(device)) {
      device_sel_valid := true.B
      device_sel_req := device.U(numBitsDeviceSel.W)
    }
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val host_sel_resp = RegInit(0.U(numBitsHostSel.W))
    val device_sel_resp = RegInit(0.U(numBitsDeviceSel.W))
    val decode_err_resp = RegInit(false.B)

    device_sel_resp := device_sel_req
    host_sel_resp := host_sel_req
    decode_err_resp := host_sel_valid && !device_sel_valid

    for (device <- 0 until nrDevices) {
      when(device_sel_valid && device.U(numBitsDeviceSel.W) === device_sel_req) {
        device_req_o(device) := host_req_sel
        device_we_o(device) := host_we_sel
        device_addr_o(device) := host_addr_sel
        device_wdata_o(device) := host_wdata_sel
        device_be_o(device) := host_be_sel
      }.otherwise {
        device_req_o(device) := false.B
        device_we_o(device) := false.B
        device_addr_o(device) := 0.U
        device_wdata_o(device) := 0.U
        device_be_o(device) := 0.U
      }
    }

    for (host <- 0 until nrHosts) {
      host_gnt_o(host) := false.B
      when(host.U(numBitsHostSel.W) === host_sel_resp) {
        host_rvalid_o(host) := device_rvalid_i(device_sel_resp) || decode_err_resp
        host_err_o(host) := device_err_i(device_sel_resp) || decode_err_resp
        host_rdata_o(host) := device_rdata_i(device_sel_resp)
      }.otherwise {
        host_rvalid_o(host) := false.B
        host_err_o(host) := false.B
        host_rdata_o(host) := 0.U
      }
    }
    if (nrHosts == 1) {
      host_gnt_o(0) := host_req_i(0)
    } else {
      host_gnt_o(host_sel_req) := host_req_sel
    }
  }
}
