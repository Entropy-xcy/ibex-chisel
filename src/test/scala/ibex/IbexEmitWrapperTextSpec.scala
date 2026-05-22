package ibex

import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexEmitWrapperTextSpec extends AnyFreeSpec with Matchers {
  "IbexEmitWrapperText" - {
    "emits a CSR DV wrapper that omits ports optimized away in the small config" in {
      val wrapper = IbexEmitWrapperText.csrRegistersWrapper(IbexEmitConfig("small"))

      wrapper must include("module ibex_cs_registers")
      wrapper must include("IbexCsRegisters u_regs")
      wrapper must include(".csr_access_i(csr_access_i)")
      wrapper must include(".double_fault_seen_o()")

      wrapper must not include ".debug_mode_entering_i"
      wrapper must not include ".pc_wb_i"
      wrapper must not include ".csr_save_wb_i"
      wrapper must not include ".instr_ret_compressed_i"
      wrapper must not include ".instr_ret_spec_i"
      wrapper must not include ".iside_wait_i"
      wrapper must not include ".branch_taken_i"
    }

    "emits a CSR DV wrapper that connects OpenTitan-only CSR ports" in {
      val wrapper = IbexEmitWrapperText.csrRegistersWrapper(IbexEmitConfig("opentitan"))

      wrapper must include(".debug_mode_entering_i(1'b0)")
      wrapper must include(".pc_wb_i(32'b0)")
      wrapper must include(".csr_save_wb_i(1'b0)")
      wrapper must include(".instr_ret_compressed_i(1'b0)")
      wrapper must include(".instr_ret_spec_i(1'b0)")
      wrapper must include(".instr_ret_compressed_spec_i(1'b0)")
      wrapper must include(".iside_wait_i(1'b0)")
      wrapper must include(".branch_taken_i(1'b0)")
      wrapper must include(".div_wait_i(1'b0)")
    }

    "emits simple-system performance counter DPI exports" in {
      val small = IbexEmitWrapperText.simpleSystemPcountDpi(IbexEmitConfig("small"))

      small must include("""export "DPI-C" function mhpmcounter_num;""")
      small must include("return 0;")
      small must include("0: return u_top.ibex_top.ibex_core_i.cs_registers_i.mcycle;")
      small must include("2: return u_top.ibex_top.ibex_core_i.cs_registers_i.minstret;")
      small must not include "mhpm_0"

      val opentitan = IbexEmitWrapperText.simpleSystemPcountDpi(IbexEmitConfig("opentitan"))

      opentitan must include("return 10;")
      opentitan must include("3: return u_top.ibex_top.ibex_core_i.cs_registers_i.mhpm_0;")
      opentitan must include("12: return u_top.ibex_top.ibex_core_i.cs_registers_i.mhpm_9;")
      opentitan must not include "13: return"
    }

    "elaborates the RISC-V compliance harness from Chisel" in {
      val rtl = ChiselStage.emitSystemVerilog(new IbexRiscvCompliance)

      rtl must include("module ibex_riscv_compliance")
      rtl must include("IbexTopTracing u_top")
      rtl must include("RiscvTestUtil u_riscv_testutil")
      rtl must include("Ram1P u_ram")
      rtl must include(".scramble_key_valid_i                 (1'h1)")
      rtl must include(".scramble_key_i                       (128'h14E8CECAE3040D5E12286BB3CC113298)")
      rtl must include(".scramble_nonce_i                     (64'hF79780BC735F3843)")
      rtl must not include "prim_secded_inv_39_32_enc"
    }

    "keeps simple-system software image loading compatible with upstream FuseSoC" in {
      val rtl =
        """module ibex_simple_system(
          |  input IO_CLK,
          |        IO_RST_N
          |);
          |  Ram2P u_ram (
          |  );
          |endmodule
          |""".stripMargin
      val patched = IbexEmitWrapperText.simpleSystemCompatibility(rtl, IbexEmitConfig("opentitan"))

      patched must include("""parameter                    SRAMInitFile = """"")
      patched must include("Ram2P #(")
      patched must include(".MemInitFile(SRAMInitFile)")
      patched must include("""export "DPI-C" function mhpmcounter_get;""")
      patched must include(");\n  /* verilator public_module */\n")
      patched must not include ") (\n  /* verilator public_module */"
    }

    "keeps Ram2P memory initialization overrideable by simple-system wrappers" in {
      val rtl =
        """module Ram2P(
          |  input clk_i
          |);
          |  Ram2PStorage #(
          |    .Depth(262144),
          |    .MemInitFile("")
          |  ) u_ram (
          |  );
          |endmodule
          |""".stripMargin
      val patched = IbexEmitWrapperText.ram2PMemInitCompatibility(rtl)

      patched must include("""module Ram2P #(
                             |  parameter MemInitFile = ""
                             |) (""".stripMargin)
      patched must include(".MemInitFile(MemInitFile)")
    }
  }
}
