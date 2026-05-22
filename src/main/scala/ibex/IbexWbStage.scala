// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

class IbexWbStage(
    resetAll: Boolean = false,
    writebackStage: Boolean = false,
    dummyInstructions: Boolean = false)
    extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val en_wb_i = IO(Input(Bool()))
  val instr_type_wb_i = IO(Input(UInt(2.W)))
  val pc_id_i = IO(Input(UInt(32.W)))
  val instr_is_compressed_id_i = IO(Input(Bool()))
  val instr_perf_count_id_i = IO(Input(Bool()))

  val ready_wb_o = IO(Output(Bool()))
  val rf_write_wb_o = IO(Output(Bool()))
  val outstanding_load_wb_o = IO(Output(Bool()))
  val outstanding_store_wb_o = IO(Output(Bool()))
  val pc_wb_o = IO(Output(UInt(32.W)))
  val perf_instr_ret_wb_o = IO(Output(Bool()))
  val perf_instr_ret_compressed_wb_o = IO(Output(Bool()))
  val perf_instr_ret_wb_spec_o = IO(Output(Bool()))
  val perf_instr_ret_compressed_wb_spec_o = IO(Output(Bool()))

  val rf_waddr_id_i = IO(Input(UInt(5.W)))
  val rf_wdata_id_i = IO(Input(UInt(32.W)))
  val rf_we_id_i = IO(Input(Bool()))

  val dummy_instr_id_i = IO(Input(Bool()))

  val rf_wdata_lsu_i = IO(Input(UInt(32.W)))
  val rf_we_lsu_i = IO(Input(Bool()))

  val rf_wdata_fwd_wb_o = IO(Output(UInt(32.W)))
  val rf_waddr_wb_o = IO(Output(UInt(5.W)))
  val rf_wdata_wb_o = IO(Output(UInt(32.W)))
  val rf_we_wb_o = IO(Output(Bool()))
  val dummy_instr_wb_o = IO(Output(Bool()))

  val lsu_resp_valid_i = IO(Input(Bool()))
  val lsu_resp_err_i = IO(Input(Bool()))
  val instr_done_wb_o = IO(Output(Bool()))

  private val wbInstrLoad = 0.U(2.W)
  private val wbInstrStore = 1.U(2.W)
  private val wbInstrOther = 2.U(2.W)

  val rf_wdata_wb_mux_0 = Wire(UInt(32.W))
  val rf_wdata_wb_mux_1 = Wire(UInt(32.W))
  val rf_wdata_wb_mux_we_0 = Wire(Bool())
  val rf_wdata_wb_mux_we_1 = Wire(Bool())

  rf_wdata_wb_mux_1 := rf_wdata_lsu_i

  if (writebackStage) {
    val wb_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(false.B)
    }

    val rf_wdata_wb_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
    } else {
      withClock(clk_i) { Reg(UInt(32.W)) }
    }
    val rf_we_wb_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    } else {
      withClock(clk_i) { Reg(Bool()) }
    }
    val rf_waddr_wb_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
    } else {
      withClock(clk_i) { Reg(UInt(5.W)) }
    }
    val wb_instr_type_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(2.W)) }
    } else {
      withClock(clk_i) { Reg(UInt(2.W)) }
    }
    val wb_pc_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(32.W)) }
    } else {
      withClock(clk_i) { Reg(UInt(32.W)) }
    }
    val wb_compressed_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    } else {
      withClock(clk_i) { Reg(Bool()) }
    }
    val wb_count_q = if (resetAll) {
      withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
    } else {
      withClock(clk_i) { Reg(Bool()) }
    }

    val wb_done = (wb_instr_type_q === wbInstrOther) || lsu_resp_valid_i
    ready_wb_o := !wb_valid_q || wb_done
    val wb_valid_d = (en_wb_i && ready_wb_o) || (wb_valid_q && !wb_done)

    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      wb_valid_q := wb_valid_d
    }
    withClock(clk_i) {
      when(en_wb_i) {
        rf_we_wb_q := rf_we_id_i
        rf_waddr_wb_q := rf_waddr_id_i
        rf_wdata_wb_q := rf_wdata_id_i
        wb_instr_type_q := instr_type_wb_i
        wb_pc_q := pc_id_i
        wb_compressed_q := instr_is_compressed_id_i
        wb_count_q := instr_perf_count_id_i
      }
    }

    rf_waddr_wb_o := rf_waddr_wb_q
    rf_wdata_wb_mux_0 := rf_wdata_wb_q
    rf_wdata_wb_mux_we_0 := rf_we_wb_q && wb_valid_q
    rf_wdata_wb_mux_we_1 := rf_we_lsu_i

    rf_write_wb_o := wb_valid_q && (rf_we_wb_q || (wb_instr_type_q === wbInstrLoad))
    outstanding_load_wb_o := wb_valid_q && (wb_instr_type_q === wbInstrLoad)
    outstanding_store_wb_o := wb_valid_q && (wb_instr_type_q === wbInstrStore)
    pc_wb_o := wb_pc_q
    instr_done_wb_o := wb_valid_q && wb_done

    perf_instr_ret_wb_spec_o := wb_count_q
    perf_instr_ret_compressed_wb_spec_o := perf_instr_ret_wb_spec_o && wb_compressed_q
    perf_instr_ret_wb_o := instr_done_wb_o && wb_count_q && !(lsu_resp_valid_i && lsu_resp_err_i)
    perf_instr_ret_compressed_wb_o := perf_instr_ret_wb_o && wb_compressed_q
    rf_wdata_fwd_wb_o := rf_wdata_wb_q

    if (dummyInstructions) {
      val dummy_instr_wb_q = if (resetAll) {
        withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(false.B) }
      } else {
        withClock(clk_i) { Reg(Bool()) }
      }
      withClock(clk_i) {
        when(en_wb_i) {
          dummy_instr_wb_q := dummy_instr_id_i
        }
      }
      dummy_instr_wb_o := dummy_instr_wb_q
    } else {
      dummy_instr_wb_o := false.B
    }
  } else {
    rf_waddr_wb_o := rf_waddr_id_i
    rf_wdata_wb_mux_0 := rf_wdata_id_i
    rf_wdata_wb_mux_we_0 := rf_we_id_i
    rf_wdata_wb_mux_we_1 := rf_we_lsu_i

    dummy_instr_wb_o := dummy_instr_id_i
    perf_instr_ret_wb_spec_o := false.B
    perf_instr_ret_compressed_wb_spec_o := false.B
    perf_instr_ret_wb_o := instr_perf_count_id_i && en_wb_i && !(lsu_resp_valid_i && lsu_resp_err_i)
    perf_instr_ret_compressed_wb_o := perf_instr_ret_wb_o && instr_is_compressed_id_i
    ready_wb_o := true.B
    outstanding_load_wb_o := false.B
    outstanding_store_wb_o := false.B
    pc_wb_o := 0.U
    rf_write_wb_o := false.B
    rf_wdata_fwd_wb_o := 0.U
    instr_done_wb_o := false.B
  }

  rf_wdata_wb_o := Mux(rf_wdata_wb_mux_we_0, rf_wdata_wb_mux_0, 0.U) |
    Mux(rf_wdata_wb_mux_we_1, rf_wdata_wb_mux_1, 0.U)
  rf_we_wb_o := rf_wdata_wb_mux_we_0 || rf_wdata_wb_mux_we_1

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(!(rf_wdata_wb_mux_we_0 && rf_wdata_wb_mux_we_1),
      "RFWriteFromOneSourceOnly")
  }
}
