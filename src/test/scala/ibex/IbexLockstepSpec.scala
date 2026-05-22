package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexLockstepSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness(lockstepOffset: Int = 1) extends Module {
    val io = IO(new Bundle {
      val lockstep_cmp_en_o = Output(UInt(IbexPkg.IbexMuBiWidth.W))
      val instr_req_shadow_o = Output(Bool())
      val instr_addr_shadow_o = Output(UInt(32.W))
      val data_req_shadow_o = Output(Bool())
      val alert_major_internal_o = Output(Bool())
      val alert_major_bus_o = Output(Bool())
      val alert_minor_o = Output(Bool())
    })

    val dut = Module(new IbexLockstep(resetAll = true, lockstepOffset = lockstepOffset))

    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.hart_id_i := 0.U
    dut.boot_addr_i := "h00001000".U

    dut.instr_req_i := false.B
    dut.instr_gnt_i := false.B
    dut.instr_rvalid_i := false.B
    dut.instr_addr_i := 0.U
    dut.instr_rdata_i := "h00000013".U
    dut.instr_err_i := false.B

    dut.data_req_i := false.B
    dut.data_gnt_i := false.B
    dut.data_rvalid_i := false.B
    dut.data_we_i := false.B
    dut.data_be_i := 0.U
    dut.data_addr_i := 0.U
    dut.data_wdata_i := 0.U
    dut.data_rdata_i := 0.U
    dut.data_err_i := false.B

    dut.rf_rdata_a_i := 0.U
    dut.rf_rdata_b_i := 0.U

    dut.ic_tag_req_i := 0.U
    dut.ic_tag_write_i := false.B
    dut.ic_tag_addr_i := 0.U
    dut.ic_tag_wdata_i := 0.U
    dut.ic_tag_rdata_i.foreach(_ := 0.U)
    dut.ic_data_req_i := 0.U
    dut.ic_data_write_i := false.B
    dut.ic_data_addr_i := 0.U
    dut.ic_data_wdata_i := 0.U
    dut.ic_data_rdata_i.foreach(_ := 0.U)
    dut.ic_scr_key_valid_i := true.B
    dut.ic_scr_key_req_i := false.B

    dut.irq_software_i := false.B
    dut.irq_timer_i := false.B
    dut.irq_external_i := false.B
    dut.irq_fast_i := 0.U
    dut.irq_nm_i := false.B
    dut.irq_pending_i := false.B

    dut.debug_req_i := false.B
    dut.crash_dump_i := 0.U.asTypeOf(new IbexPkg.CrashDump)
    dut.double_fault_seen_i := false.B

    dut.fetch_enable_i := IbexPkg.IbexMuBiOn
    dut.core_busy_i := IbexPkg.IbexMuBiOff
    dut.test_en_i := false.B
    dut.scan_rst_ni := true.B

    io.lockstep_cmp_en_o := dut.lockstep_cmp_en_o
    io.instr_req_shadow_o := dut.instr_req_shadow_o
    io.instr_addr_shadow_o := dut.instr_addr_shadow_o
    io.data_req_shadow_o := dut.data_req_shadow_o
    io.alert_major_internal_o := dut.alert_major_internal_o
    io.alert_major_bus_o := dut.alert_major_bus_o
    io.alert_minor_o := dut.alert_minor_o
  }

  "IbexLockstep" - {
    "elaborates the one-cycle shadow core wrapper and detects an output mismatch" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.clock.step(3)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.instr_req_shadow_o.expect(true.B)
        dut.io.instr_addr_shadow_o.expect("h00001080".U)
        dut.io.data_req_shadow_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(true.B)
        dut.io.alert_major_bus_o.expect(false.B)
        dut.io.alert_minor_o.expect(false.B)
      }
    }

    "elaborates the two-cycle shadow core wrapper and detects an output mismatch" in {
      simulate(new Harness(lockstepOffset = 2)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOff)
        dut.clock.step(5)

        dut.io.lockstep_cmp_en_o.expect(IbexPkg.IbexMuBiOn)
        dut.io.instr_req_shadow_o.expect(true.B)
        dut.io.instr_addr_shadow_o.expect("h00001080".U)
        dut.io.data_req_shadow_o.expect(false.B)
        dut.io.alert_major_internal_o.expect(true.B)
        dut.io.alert_major_bus_o.expect(false.B)
        dut.io.alert_minor_o.expect(false.B)
      }
    }
  }
}
