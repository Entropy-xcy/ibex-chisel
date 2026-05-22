package ibex

import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexTopTracingSpec extends AnyFreeSpec with Matchers {
  "IbexTopTracing" - {
    "elaborates the tracing wrapper with an attached IbexTracer" in {
      val verilog = ChiselStage.emitSystemVerilog(new IbexTopTracing)
      verilog must include("module IbexTopTracing")
      verilog must include("module IbexTracer")
      verilog must include("rvfi_insn")
      verilog must include("rvfi_ext_expanded_insn")
    }

    "elaborates the tracing wrapper with WritebackStage enabled" in {
      val verilog = ChiselStage.emitSystemVerilog(new IbexTopTracing(writebackStage = true))
      verilog must include("module IbexTopTracing")
      verilog must include("module IbexTracer")
      verilog must include("module IbexWbStage")
    }
  }
}
