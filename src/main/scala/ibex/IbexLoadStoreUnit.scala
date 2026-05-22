// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexLoadStoreUnit(memECC: Boolean = false, memDataWidth: Int = 0)
    extends RawModule {
  private val MemDataWidth = if (memDataWidth == 0) {
    if (memECC) 39 else 32
  } else {
    memDataWidth
  }
  require(MemDataWidth >= 32)

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val data_req_o = IO(Output(Bool()))
  val data_gnt_i = IO(Input(Bool()))
  val data_rvalid_i = IO(Input(Bool()))
  val data_bus_err_i = IO(Input(Bool()))
  val data_pmp_err_i = IO(Input(Bool()))

  val data_addr_o = IO(Output(UInt(32.W)))
  val data_we_o = IO(Output(Bool()))
  val data_be_o = IO(Output(UInt(4.W)))
  val data_wdata_o = IO(Output(UInt(MemDataWidth.W)))
  val data_rdata_i = IO(Input(UInt(MemDataWidth.W)))

  val lsu_we_i = IO(Input(Bool()))
  val lsu_type_i = IO(Input(UInt(2.W)))
  val lsu_wdata_i = IO(Input(UInt(32.W)))
  val lsu_sign_ext_i = IO(Input(Bool()))

  val lsu_rdata_o = IO(Output(UInt(32.W)))
  val lsu_rdata_valid_o = IO(Output(Bool()))
  val lsu_req_i = IO(Input(Bool()))

  val adder_result_ex_i = IO(Input(UInt(32.W)))

  val addr_incr_req_o = IO(Output(Bool()))
  val addr_last_o = IO(Output(UInt(32.W)))

  val lsu_req_done_o = IO(Output(Bool()))
  val lsu_resp_valid_o = IO(Output(Bool()))

  val load_err_o = IO(Output(Bool()))
  val load_resp_intg_err_o = IO(Output(Bool()))
  val store_err_o = IO(Output(Bool()))
  val store_resp_intg_err_o = IO(Output(Bool()))

  val busy_o = IO(Output(Bool()))

  val perf_load_o = IO(Output(Bool()))
  val perf_store_o = IO(Output(Bool()))

  private object LsState {
    private val states = Enum(5)
    val IDLE = states(0)
    val WAIT_GNT_MIS = states(1)
    val WAIT_RVALID_MIS = states(2)
    val WAIT_GNT = states(3)
    val WAIT_RVALID_MIS_GNTS_DONE = states(4)
  }

  val data_addr = adder_result_ex_i
  val data_offset = data_addr(1, 0)
  val data_addr_w_aligned = Cat(data_addr(31, 2), 0.U(2.W))

  val ls_fsm_cs = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(LsState.IDLE) }
  val handle_misaligned_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val pmp_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val lsu_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val addr_last_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
  val rdata_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(24.W)) }
  val rdata_offset_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(2.W)) }
  val data_type_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(2.W)) }
  val data_sign_ext_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
  val data_we_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }

  val ls_fsm_ns = WireDefault(ls_fsm_cs)
  val handle_misaligned_d = WireDefault(handle_misaligned_q)
  val pmp_err_d = WireDefault(pmp_err_q)
  val lsu_err_d = WireDefault(lsu_err_q)
  val addr_update = WireDefault(false.B)
  val ctrl_update = WireDefault(false.B)
  val rdata_update = WireDefault(false.B)

  data_req_o := false.B
  addr_incr_req_o := false.B
  perf_load_o := false.B
  perf_store_o := false.B

  val data_be = WireDefault("b1111".U(4.W))
  switch(lsu_type_i) {
    is("b00".U) {
      when(!handle_misaligned_q) {
        data_be := MuxLookup(data_offset, "b1111".U)(Seq(
          "b00".U -> "b1111".U, "b01".U -> "b1110".U, "b10".U -> "b1100".U, "b11".U -> "b1000".U))
      }.otherwise {
        data_be := MuxLookup(data_offset, "b1111".U)(Seq(
          "b00".U -> "b0000".U, "b01".U -> "b0001".U, "b10".U -> "b0011".U, "b11".U -> "b0111".U))
      }
    }
    is("b01".U) {
      when(!handle_misaligned_q) {
        data_be := MuxLookup(data_offset, "b1111".U)(Seq(
          "b00".U -> "b0011".U, "b01".U -> "b0110".U, "b10".U -> "b1100".U, "b11".U -> "b1000".U))
      }.otherwise {
        data_be := "b0001".U
      }
    }
    is("b10".U, "b11".U) {
      data_be := MuxLookup(data_offset, "b1111".U)(Seq(
        "b00".U -> "b0001".U, "b01".U -> "b0010".U, "b10".U -> "b0100".U, "b11".U -> "b1000".U))
    }
  }

  val data_wdata = MuxLookup(data_offset, lsu_wdata_i)(Seq(
    "b00".U -> lsu_wdata_i,
    "b01".U -> Cat(lsu_wdata_i(23, 0), lsu_wdata_i(31, 24)),
    "b10".U -> Cat(lsu_wdata_i(15, 0), lsu_wdata_i(31, 16)),
    "b11".U -> Cat(lsu_wdata_i(7, 0), lsu_wdata_i(31, 8))
  ))

  val data_rdata_32 = data_rdata_i(31, 0)
  val rdata_w_ext = MuxLookup(rdata_offset_q, data_rdata_32)(Seq(
    "b00".U -> data_rdata_32,
    "b01".U -> Cat(data_rdata_32(7, 0), rdata_q(23, 0)),
    "b10".U -> Cat(data_rdata_32(15, 0), rdata_q(23, 8)),
    "b11".U -> Cat(data_rdata_32(23, 0), rdata_q(23, 16))
  ))

  val h00 = data_rdata_32(15, 0)
  val h01 = data_rdata_32(23, 8)
  val h10 = data_rdata_32(31, 16)
  val h11 = Cat(data_rdata_32(7, 0), rdata_q(23, 16))
  val hsel = MuxLookup(rdata_offset_q, h00)(Seq("b00".U -> h00, "b01".U -> h01, "b10".U -> h10, "b11".U -> h11))
  val rdata_h_ext = Mux(data_sign_ext_q, Cat(Fill(16, hsel(15)), hsel), Cat(0.U(16.W), hsel))

  val b00 = data_rdata_32(7, 0)
  val b01 = data_rdata_32(15, 8)
  val b10 = data_rdata_32(23, 16)
  val b11 = data_rdata_32(31, 24)
  val bsel = MuxLookup(rdata_offset_q, b00)(Seq("b00".U -> b00, "b01".U -> b01, "b10".U -> b10, "b11".U -> b11))
  val rdata_b_ext = Mux(data_sign_ext_q, Cat(Fill(24, bsel(7)), bsel), Cat(0.U(24.W), bsel))

  val data_rdata_ext = MuxLookup(data_type_q, rdata_w_ext)(Seq(
    "b00".U -> rdata_w_ext,
    "b01".U -> rdata_h_ext,
    "b10".U -> rdata_b_ext,
    "b11".U -> rdata_b_ext
  ))

  val data_intg_err = Wire(Bool())
  if (memECC) {
    val data_intg_dec = Module(new PrimSecdedInv3932Dec)
    data_intg_dec.data_i := data_rdata_i
    data_intg_err := data_intg_dec.err_o.orR
  } else {
    data_intg_err := false.B
  }
  val split_misaligned_access = (lsu_type_i === "b00".U && data_offset =/= 0.U) ||
    (lsu_type_i === "b01".U && data_offset === "b11".U)

  switch(ls_fsm_cs) {
    is(LsState.IDLE) {
      pmp_err_d := false.B
      when(lsu_req_i) {
        data_req_o := true.B
        pmp_err_d := data_pmp_err_i
        lsu_err_d := false.B
        perf_load_o := !lsu_we_i
        perf_store_o := lsu_we_i
        when(data_gnt_i) {
          ctrl_update := true.B
          addr_update := true.B
          handle_misaligned_d := split_misaligned_access
          ls_fsm_ns := Mux(split_misaligned_access, LsState.WAIT_RVALID_MIS, LsState.IDLE)
        }.otherwise {
          ls_fsm_ns := Mux(split_misaligned_access, LsState.WAIT_GNT_MIS, LsState.WAIT_GNT)
        }
      }
    }
    is(LsState.WAIT_GNT_MIS) {
      data_req_o := true.B
      when(data_gnt_i || pmp_err_q) {
        addr_update := true.B
        ctrl_update := true.B
        handle_misaligned_d := true.B
        ls_fsm_ns := LsState.WAIT_RVALID_MIS
      }
    }
    is(LsState.WAIT_RVALID_MIS) {
      data_req_o := true.B
      addr_incr_req_o := true.B
      when(data_rvalid_i || pmp_err_q) {
        pmp_err_d := data_pmp_err_i
        lsu_err_d := data_bus_err_i || pmp_err_q
        rdata_update := !data_we_q
        ls_fsm_ns := Mux(data_gnt_i, LsState.IDLE, LsState.WAIT_GNT)
        addr_update := data_gnt_i && !(data_bus_err_i || pmp_err_q)
        handle_misaligned_d := !data_gnt_i
      }.elsewhen(data_gnt_i) {
        ls_fsm_ns := LsState.WAIT_RVALID_MIS_GNTS_DONE
        handle_misaligned_d := false.B
      }
    }
    is(LsState.WAIT_GNT) {
      addr_incr_req_o := handle_misaligned_q
      data_req_o := true.B
      when(data_gnt_i || pmp_err_q) {
        ctrl_update := true.B
        addr_update := !lsu_err_q
        ls_fsm_ns := LsState.IDLE
        handle_misaligned_d := false.B
      }
    }
    is(LsState.WAIT_RVALID_MIS_GNTS_DONE) {
      addr_incr_req_o := true.B
      when(data_rvalid_i) {
        pmp_err_d := data_pmp_err_i
        lsu_err_d := data_bus_err_i
        addr_update := !data_bus_err_i
        rdata_update := !data_we_q
        ls_fsm_ns := LsState.IDLE
      }
    }
  }

  val addr_last_d = Mux(addr_incr_req_o, data_addr_w_aligned, data_addr)

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    when(rdata_update) {
      rdata_q := data_rdata_32(31, 8)
    }
    when(ctrl_update) {
      rdata_offset_q := data_offset
      data_type_q := lsu_type_i
      data_sign_ext_q := lsu_sign_ext_i
      data_we_q := lsu_we_i
    }
    when(addr_update) {
      addr_last_q := addr_last_d
    }
    ls_fsm_cs := ls_fsm_ns
    handle_misaligned_q := handle_misaligned_d
    pmp_err_q := pmp_err_d
    lsu_err_q := lsu_err_d
  }

  lsu_req_done_o := (lsu_req_i || (ls_fsm_cs =/= LsState.IDLE)) && (ls_fsm_ns === LsState.IDLE)

  val data_or_pmp_err = lsu_err_q || data_bus_err_i || pmp_err_q
  lsu_resp_valid_o := (data_rvalid_i || pmp_err_q) && (ls_fsm_cs === LsState.IDLE)
  lsu_rdata_valid_o := (ls_fsm_cs === LsState.IDLE) && data_rvalid_i && !data_or_pmp_err && !data_we_q && !data_intg_err
  lsu_rdata_o := data_rdata_ext

  data_addr_o := data_addr_w_aligned
  data_we_o := lsu_we_i
  data_be_o := data_be
  if (memECC) {
    val data_gen = Module(new PrimSecdedInv3932Enc)
    data_gen.data_i := data_wdata
    data_wdata_o := data_gen.data_o
  } else {
    data_wdata_o := data_wdata
  }
  addr_last_o := addr_last_q

  load_err_o := data_or_pmp_err && !data_we_q && lsu_resp_valid_o
  store_err_o := data_or_pmp_err && data_we_q && lsu_resp_valid_o
  load_resp_intg_err_o := data_intg_err && data_rvalid_i && !data_we_q
  store_resp_intg_err_o := data_intg_err && data_rvalid_i && data_we_q
  busy_o := ls_fsm_cs =/= LsState.IDLE

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(ls_fsm_cs === LsState.IDLE ||
      ls_fsm_cs === LsState.WAIT_GNT_MIS ||
      ls_fsm_cs === LsState.WAIT_RVALID_MIS ||
      ls_fsm_cs === LsState.WAIT_GNT ||
      ls_fsm_cs === LsState.WAIT_RVALID_MIS_GNTS_DONE,
      "IbexLsuStateValid")
    assert(!data_req_o || data_addr_o(1, 0) === 0.U,
      "IbexDataAddrUnaligned")
  }
}
