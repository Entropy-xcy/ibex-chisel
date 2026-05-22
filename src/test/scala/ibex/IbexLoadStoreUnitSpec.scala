package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexLoadStoreUnitSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val data_req_o = Output(Bool())
      val data_gnt_i = Input(Bool())
      val data_rvalid_i = Input(Bool())
      val data_bus_err_i = Input(Bool())
      val data_pmp_err_i = Input(Bool())
      val data_addr_o = Output(UInt(32.W))
      val data_we_o = Output(Bool())
      val data_be_o = Output(UInt(4.W))
      val data_wdata_o = Output(UInt(32.W))
      val data_rdata_i = Input(UInt(32.W))
      val lsu_we_i = Input(Bool())
      val lsu_type_i = Input(UInt(2.W))
      val lsu_wdata_i = Input(UInt(32.W))
      val lsu_sign_ext_i = Input(Bool())
      val lsu_rdata_o = Output(UInt(32.W))
      val lsu_rdata_valid_o = Output(Bool())
      val lsu_req_i = Input(Bool())
      val adder_result_ex_i = Input(UInt(32.W))
      val addr_incr_req_o = Output(Bool())
      val addr_last_o = Output(UInt(32.W))
      val lsu_req_done_o = Output(Bool())
      val lsu_resp_valid_o = Output(Bool())
      val load_err_o = Output(Bool())
      val load_resp_intg_err_o = Output(Bool())
      val store_err_o = Output(Bool())
      val store_resp_intg_err_o = Output(Bool())
      val busy_o = Output(Bool())
      val perf_load_o = Output(Bool())
      val perf_store_o = Output(Bool())
    })

    val dut = Module(new IbexLoadStoreUnit())
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    io.data_req_o := dut.data_req_o
    dut.data_gnt_i := io.data_gnt_i
    dut.data_rvalid_i := io.data_rvalid_i
    dut.data_bus_err_i := io.data_bus_err_i
    dut.data_pmp_err_i := io.data_pmp_err_i
    io.data_addr_o := dut.data_addr_o
    io.data_we_o := dut.data_we_o
    io.data_be_o := dut.data_be_o
    io.data_wdata_o := dut.data_wdata_o
    dut.data_rdata_i := io.data_rdata_i
    dut.lsu_we_i := io.lsu_we_i
    dut.lsu_type_i := io.lsu_type_i
    dut.lsu_wdata_i := io.lsu_wdata_i
    dut.lsu_sign_ext_i := io.lsu_sign_ext_i
    io.lsu_rdata_o := dut.lsu_rdata_o
    io.lsu_rdata_valid_o := dut.lsu_rdata_valid_o
    dut.lsu_req_i := io.lsu_req_i
    dut.adder_result_ex_i := io.adder_result_ex_i
    io.addr_incr_req_o := dut.addr_incr_req_o
    io.addr_last_o := dut.addr_last_o
    io.lsu_req_done_o := dut.lsu_req_done_o
    io.lsu_resp_valid_o := dut.lsu_resp_valid_o
    io.load_err_o := dut.load_err_o
    io.load_resp_intg_err_o := dut.load_resp_intg_err_o
    io.store_err_o := dut.store_err_o
    io.store_resp_intg_err_o := dut.store_resp_intg_err_o
    io.busy_o := dut.busy_o
    io.perf_load_o := dut.perf_load_o
    io.perf_store_o := dut.perf_store_o
  }

  class MemEcCHarness extends Module {
    val io = IO(new Bundle {
      val data_req_o = Output(Bool())
      val data_gnt_i = Input(Bool())
      val data_rvalid_i = Input(Bool())
      val data_bus_err_i = Input(Bool())
      val data_pmp_err_i = Input(Bool())
      val data_wdata_o = Output(UInt(39.W))
      val data_rdata_i = Input(UInt(39.W))
      val lsu_we_i = Input(Bool())
      val lsu_type_i = Input(UInt(2.W))
      val lsu_wdata_i = Input(UInt(32.W))
      val lsu_sign_ext_i = Input(Bool())
      val lsu_rdata_valid_o = Output(Bool())
      val lsu_req_i = Input(Bool())
      val adder_result_ex_i = Input(UInt(32.W))
      val load_resp_intg_err_o = Output(Bool())
      val store_resp_intg_err_o = Output(Bool())
    })

    val dut = Module(new IbexLoadStoreUnit(memECC = true))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    io.data_req_o := dut.data_req_o
    dut.data_gnt_i := io.data_gnt_i
    dut.data_rvalid_i := io.data_rvalid_i
    dut.data_bus_err_i := io.data_bus_err_i
    dut.data_pmp_err_i := io.data_pmp_err_i
    io.data_wdata_o := dut.data_wdata_o
    dut.data_rdata_i := io.data_rdata_i
    dut.lsu_we_i := io.lsu_we_i
    dut.lsu_type_i := io.lsu_type_i
    dut.lsu_wdata_i := io.lsu_wdata_i
    dut.lsu_sign_ext_i := io.lsu_sign_ext_i
    io.lsu_rdata_valid_o := dut.lsu_rdata_valid_o
    dut.lsu_req_i := io.lsu_req_i
    dut.adder_result_ex_i := io.adder_result_ex_i
    io.load_resp_intg_err_o := dut.load_resp_intg_err_o
    io.store_resp_intg_err_o := dut.store_resp_intg_err_o
  }

  private def init(dut: Harness): Unit = {
    dut.io.data_gnt_i.poke(false.B)
    dut.io.data_rvalid_i.poke(false.B)
    dut.io.data_bus_err_i.poke(false.B)
    dut.io.data_pmp_err_i.poke(false.B)
    dut.io.data_rdata_i.poke(0.U)
    dut.io.lsu_we_i.poke(false.B)
    dut.io.lsu_type_i.poke(0.U)
    dut.io.lsu_wdata_i.poke(0.U)
    dut.io.lsu_sign_ext_i.poke(false.B)
    dut.io.lsu_req_i.poke(false.B)
    dut.io.adder_result_ex_i.poke(0.U)
  }

  private def initMemEcc(dut: MemEcCHarness): Unit = {
    dut.io.data_gnt_i.poke(false.B)
    dut.io.data_rvalid_i.poke(false.B)
    dut.io.data_bus_err_i.poke(false.B)
    dut.io.data_pmp_err_i.poke(false.B)
    dut.io.data_rdata_i.poke(0.U)
    dut.io.lsu_we_i.poke(false.B)
    dut.io.lsu_type_i.poke(0.U)
    dut.io.lsu_wdata_i.poke(0.U)
    dut.io.lsu_sign_ext_i.poke(false.B)
    dut.io.lsu_req_i.poke(false.B)
    dut.io.adder_result_ex_i.poke(0.U)
  }

  private def encode(data: BigInt): BigInt = {
    def parity(value: BigInt, mask: BigInt): BigInt = (value & mask).bitCount % 2
    val masks = Seq(
      BigInt("002606BD25", 16), BigInt("00DEBA8050", 16), BigInt("00413D89AA", 16),
      BigInt("0031234ED1", 16), BigInt("00C2C1323B", 16), BigInt("002DCC624C", 16),
      BigInt("0098505586", 16))
    val withParity = masks.zipWithIndex.foldLeft(data) { case (acc, (mask, bit)) =>
      acc | (parity(acc, mask) << (32 + bit))
    }
    withParity ^ BigInt("2A00000000", 16)
  }

  "IbexLoadStoreUnit" - {
    "issues an aligned word load and returns the response data" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_type_i.poke("b00".U)
        dut.io.adder_result_ex_i.poke("h00001004".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.io.data_req_o.expect(true.B)
        dut.io.data_addr_o.expect("h00001004".U)
        dut.io.data_be_o.expect("b1111".U)
        dut.io.perf_load_o.expect(true.B)
        dut.io.lsu_req_done_o.expect(true.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_rvalid_i.poke(true.B)
        dut.io.data_rdata_i.poke("h89abcdef".U)
        dut.io.lsu_resp_valid_o.expect(true.B)
        dut.io.lsu_rdata_valid_o.expect(true.B)
        dut.io.lsu_rdata_o.expect("h89abcdef".U)
      }
    }

    "aligns store byte enables and write data" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_we_i.poke(true.B)
        dut.io.lsu_type_i.poke("b01".U)
        dut.io.lsu_wdata_i.poke("haabbccdd".U)
        dut.io.adder_result_ex_i.poke("h00002001".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.io.data_we_o.expect(true.B)
        dut.io.data_addr_o.expect("h00002000".U)
        dut.io.data_be_o.expect("b0110".U)
        dut.io.data_wdata_o.expect("hbbccddaa".U)
        dut.io.perf_store_o.expect(true.B)
      }
    }

    "completes an aligned store when the bus response returns" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_we_i.poke(true.B)
        dut.io.lsu_type_i.poke("b00".U)
        dut.io.lsu_wdata_i.poke("h12345678".U)
        dut.io.adder_result_ex_i.poke("h00002000".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.io.data_req_o.expect(true.B)
        dut.io.lsu_req_done_o.expect(true.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.lsu_we_i.poke(false.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_rvalid_i.poke(true.B)
        dut.io.lsu_resp_valid_o.expect(true.B)
        dut.io.store_err_o.expect(false.B)
        dut.io.busy_o.expect(false.B)
      }
    }

    "sign extends byte and halfword load responses" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_type_i.poke("b10".U)
        dut.io.lsu_sign_ext_i.poke(true.B)
        dut.io.adder_result_ex_i.poke("h00000002".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_rvalid_i.poke(true.B)
        dut.io.data_rdata_i.poke("h00800000".U)
        dut.io.lsu_rdata_valid_o.expect(true.B)
        dut.io.lsu_rdata_o.expect("hffffff80".U)
      }
    }

    "splits a misaligned word load into two requests" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_type_i.poke("b00".U)
        dut.io.adder_result_ex_i.poke("h00003001".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.io.data_be_o.expect("b1110".U)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.data_rvalid_i.poke(true.B)
        dut.io.data_rdata_i.poke("hddccbbaa".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.io.addr_incr_req_o.expect(true.B)
        dut.io.data_be_o.expect("b0001".U)
        dut.io.data_addr_o.expect("h00003000".U)
        dut.clock.step()

        dut.io.data_rvalid_i.poke(true.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_rdata_i.poke("h000000ee".U)
        dut.io.lsu_resp_valid_o.expect(true.B)
        dut.io.lsu_rdata_valid_o.expect(true.B)
        dut.io.lsu_rdata_o.expect("heeddccbb".U)
      }
    }

    "reports load and store errors from bus or PMP responses" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_type_i.poke("b00".U)
        dut.io.adder_result_ex_i.poke("h00004000".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_rvalid_i.poke(true.B)
        dut.io.data_bus_err_i.poke(true.B)
        dut.io.load_err_o.expect(true.B)
        dut.io.lsu_rdata_valid_o.expect(false.B)

        dut.io.data_rvalid_i.poke(false.B)
        dut.io.data_bus_err_i.poke(false.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_we_i.poke(true.B)
        dut.io.adder_result_ex_i.poke("h00005000".U)
        dut.io.data_pmp_err_i.poke(true.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.data_pmp_err_i.poke(false.B)
        dut.clock.step()

        dut.io.store_err_o.expect(true.B)
        dut.io.lsu_resp_valid_o.expect(true.B)
      }
    }

    "returns a deferred PMP error after the request leaves the address phase" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_type_i.poke("b00".U)
        dut.io.adder_result_ex_i.poke("h00007000".U)
        dut.io.data_pmp_err_i.poke(true.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_req_o.expect(true.B)
        dut.clock.step()

        dut.io.lsu_req_i.poke(false.B)
        dut.io.data_pmp_err_i.poke(false.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.lsu_resp_valid_o.expect(false.B)
        dut.io.busy_o.expect(true.B)
        dut.clock.step()

        dut.io.lsu_resp_valid_o.expect(true.B)
        dut.io.load_err_o.expect(true.B)
        dut.io.busy_o.expect(false.B)
      }
    }

    "generates and checks memory integrity bits when MemECC is enabled" in {
      simulate(new MemEcCHarness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        initMemEcc(dut)

        dut.io.lsu_req_i.poke(true.B)
        dut.io.lsu_we_i.poke(true.B)
        dut.io.lsu_type_i.poke("b00".U)
        dut.io.lsu_wdata_i.poke("h89abcdef".U)
        dut.io.adder_result_ex_i.poke("h00006000".U)
        dut.io.data_gnt_i.poke(true.B)
        dut.io.data_wdata_o.expect(encode(BigInt("89abcdef", 16)).U)

        dut.clock.step()
        dut.io.lsu_req_i.poke(false.B)
        dut.io.lsu_we_i.poke(false.B)
        dut.io.data_gnt_i.poke(false.B)
        dut.io.data_rvalid_i.poke(true.B)
        dut.io.data_rdata_i.poke((encode(BigInt("12345678", 16)) ^ 3).U)
        dut.io.store_resp_intg_err_o.expect(true.B)
        dut.io.lsu_rdata_valid_o.expect(false.B)
      }
    }
  }
}
