package ibex

import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexTracerSpec extends AnyFreeSpec with Matchers {
  "IbexTracer" - {
    "elaborates with the RVFI-facing port set" in {
      val verilog = ChiselStage.emitSystemVerilog(new IbexTracer)
      verilog must include("module IbexTracer")
      verilog must include("input [31:0] hart_id_i")
      verilog must include("input [31:0] rvfi_insn")
      verilog must include("input [15:0] rvfi_ext_expanded_insn")
      verilog must include("module IbexTracerLogger")
      verilog must include("ibex_tracer_enable=%b")
      verilog must include("ibex_tracer_file_base=%s")
      verilog must include("Time\\tCycle\\tPC\\tInsn\\tDecoded instruction\\tRegister and memory contents\\n")
      verilog must include("$fopen(file_name, \"w\")")
    }
  }
}
