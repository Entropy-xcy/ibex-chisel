package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexWbStageSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(writebackStage: Boolean, resetAll: Boolean = true, dummyInstructions: Boolean = false)
      extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val en_wb_i = Input(Bool())
      val instr_type_wb_i = Input(UInt(2.W))
      val pc_id_i = Input(UInt(32.W))
      val instr_is_compressed_id_i = Input(Bool())
      val instr_perf_count_id_i = Input(Bool())
      val ready_wb_o = Output(Bool())
      val rf_write_wb_o = Output(Bool())
      val outstanding_load_wb_o = Output(Bool())
      val outstanding_store_wb_o = Output(Bool())
      val pc_wb_o = Output(UInt(32.W))
      val perf_instr_ret_wb_o = Output(Bool())
      val perf_instr_ret_compressed_wb_o = Output(Bool())
      val perf_instr_ret_wb_spec_o = Output(Bool())
      val perf_instr_ret_compressed_wb_spec_o = Output(Bool())
      val rf_waddr_id_i = Input(UInt(5.W))
      val rf_wdata_id_i = Input(UInt(32.W))
      val rf_we_id_i = Input(Bool())
      val dummy_instr_id_i = Input(Bool())
      val rf_wdata_lsu_i = Input(UInt(32.W))
      val rf_we_lsu_i = Input(Bool())
      val rf_wdata_fwd_wb_o = Output(UInt(32.W))
      val rf_waddr_wb_o = Output(UInt(5.W))
      val rf_wdata_wb_o = Output(UInt(32.W))
      val rf_we_wb_o = Output(Bool())
      val dummy_instr_wb_o = Output(Bool())
      val lsu_resp_valid_i = Input(Bool())
      val lsu_resp_err_i = Input(Bool())
      val instr_done_wb_o = Output(Bool())
    })

    val dut = Module(new IbexWbStage(resetAll, writebackStage, dummyInstructions))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.en_wb_i := io.en_wb_i
    dut.instr_type_wb_i := io.instr_type_wb_i
    dut.pc_id_i := io.pc_id_i
    dut.instr_is_compressed_id_i := io.instr_is_compressed_id_i
    dut.instr_perf_count_id_i := io.instr_perf_count_id_i
    io.ready_wb_o := dut.ready_wb_o
    io.rf_write_wb_o := dut.rf_write_wb_o
    io.outstanding_load_wb_o := dut.outstanding_load_wb_o
    io.outstanding_store_wb_o := dut.outstanding_store_wb_o
    io.pc_wb_o := dut.pc_wb_o
    io.perf_instr_ret_wb_o := dut.perf_instr_ret_wb_o
    io.perf_instr_ret_compressed_wb_o := dut.perf_instr_ret_compressed_wb_o
    io.perf_instr_ret_wb_spec_o := dut.perf_instr_ret_wb_spec_o
    io.perf_instr_ret_compressed_wb_spec_o := dut.perf_instr_ret_compressed_wb_spec_o
    dut.rf_waddr_id_i := io.rf_waddr_id_i
    dut.rf_wdata_id_i := io.rf_wdata_id_i
    dut.rf_we_id_i := io.rf_we_id_i
    dut.dummy_instr_id_i := io.dummy_instr_id_i
    dut.rf_wdata_lsu_i := io.rf_wdata_lsu_i
    dut.rf_we_lsu_i := io.rf_we_lsu_i
    io.rf_wdata_fwd_wb_o := dut.rf_wdata_fwd_wb_o
    io.rf_waddr_wb_o := dut.rf_waddr_wb_o
    io.rf_wdata_wb_o := dut.rf_wdata_wb_o
    io.rf_we_wb_o := dut.rf_we_wb_o
    io.dummy_instr_wb_o := dut.dummy_instr_wb_o
    dut.lsu_resp_valid_i := io.lsu_resp_valid_i
    dut.lsu_resp_err_i := io.lsu_resp_err_i
    io.instr_done_wb_o := dut.instr_done_wb_o
  }

  private val wbLoad = 0
  private val wbStore = 1
  private val wbOther = 2

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.en_wb_i.poke(false.B)
    dut.io.instr_type_wb_i.poke(wbOther.U)
    dut.io.pc_id_i.poke(0.U)
    dut.io.instr_is_compressed_id_i.poke(false.B)
    dut.io.instr_perf_count_id_i.poke(false.B)
    dut.io.rf_waddr_id_i.poke(0.U)
    dut.io.rf_wdata_id_i.poke(0.U)
    dut.io.rf_we_id_i.poke(false.B)
    dut.io.dummy_instr_id_i.poke(false.B)
    dut.io.rf_wdata_lsu_i.poke(0.U)
    dut.io.rf_we_lsu_i.poke(false.B)
    dut.io.lsu_resp_valid_i.poke(false.B)
    dut.io.lsu_resp_err_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "IbexWbStage" - {
    "passes register writes directly when the writeback stage is disabled" in {
      simulate(new Harness(writebackStage = false)) { dut =>
        reset(dut)
        dut.io.en_wb_i.poke(true.B)
        dut.io.rf_we_id_i.poke(true.B)
        dut.io.rf_waddr_id_i.poke(3.U)
        dut.io.rf_wdata_id_i.poke("h12345678".U)
        dut.io.instr_perf_count_id_i.poke(true.B)
        dut.io.instr_is_compressed_id_i.poke(true.B)

        dut.io.ready_wb_o.expect(true.B)
        dut.io.rf_we_wb_o.expect(true.B)
        dut.io.rf_waddr_wb_o.expect(3.U)
        dut.io.rf_wdata_wb_o.expect("h12345678".U)
        dut.io.perf_instr_ret_wb_o.expect(true.B)
        dut.io.perf_instr_ret_compressed_wb_o.expect(true.B)
      }
    }

    "captures an ordinary instruction and retires it in the writeback stage" in {
      simulate(new Harness(writebackStage = true, dummyInstructions = true)) { dut =>
        reset(dut)
        dut.io.en_wb_i.poke(true.B)
        dut.io.instr_type_wb_i.poke(wbOther.U)
        dut.io.pc_id_i.poke("h00000100".U)
        dut.io.instr_is_compressed_id_i.poke(true.B)
        dut.io.instr_perf_count_id_i.poke(true.B)
        dut.io.rf_we_id_i.poke(true.B)
        dut.io.rf_waddr_id_i.poke(5.U)
        dut.io.rf_wdata_id_i.poke("habcd1234".U)
        dut.io.dummy_instr_id_i.poke(true.B)
        dut.clock.step()

        dut.io.ready_wb_o.expect(true.B)
        dut.io.instr_done_wb_o.expect(true.B)
        dut.io.rf_we_wb_o.expect(true.B)
        dut.io.rf_waddr_wb_o.expect(5.U)
        dut.io.rf_wdata_wb_o.expect("habcd1234".U)
        dut.io.rf_wdata_fwd_wb_o.expect("habcd1234".U)
        dut.io.pc_wb_o.expect("h00000100".U)
        dut.io.perf_instr_ret_wb_o.expect(true.B)
        dut.io.perf_instr_ret_compressed_wb_o.expect(true.B)
        dut.io.dummy_instr_wb_o.expect(true.B)
      }
    }

    "holds a load until an LSU response and muxes LSU write data" in {
      simulate(new Harness(writebackStage = true)) { dut =>
        reset(dut)
        dut.io.en_wb_i.poke(true.B)
        dut.io.instr_type_wb_i.poke(wbLoad.U)
        dut.io.rf_we_id_i.poke(false.B)
        dut.io.rf_waddr_id_i.poke(7.U)
        dut.io.rf_wdata_id_i.poke("h11111111".U)
        dut.clock.step()

        dut.io.en_wb_i.poke(false.B)
        dut.io.ready_wb_o.expect(false.B)
        dut.io.outstanding_load_wb_o.expect(true.B)
        dut.io.rf_write_wb_o.expect(true.B)
        dut.io.instr_done_wb_o.expect(false.B)

        dut.io.lsu_resp_valid_i.poke(true.B)
        dut.io.rf_we_lsu_i.poke(true.B)
        dut.io.rf_wdata_lsu_i.poke("hfeedc0de".U)
        dut.io.ready_wb_o.expect(true.B)
        dut.io.instr_done_wb_o.expect(true.B)
        dut.io.rf_we_wb_o.expect(true.B)
        dut.io.rf_wdata_wb_o.expect("hfeedc0de".U)
      }
    }
  }
}
