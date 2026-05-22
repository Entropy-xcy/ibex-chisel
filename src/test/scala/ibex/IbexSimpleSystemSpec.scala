package ibex

import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexSimpleSystemSpec extends AnyFreeSpec with Matchers {
  "IbexSimpleSystem" - {
    "elaborates the system-level interconnect around IbexTopTracing" in {
      val verilog = ChiselStage.emitSystemVerilog(new IbexSimpleSystem(instrCycleDelay = 1, ramDepth = 16))
      verilog must include("module ibex_simple_system")
      verilog must include("module IbexTopTracing")
      verilog must include("module IbexTop")
      verilog must include("module IbexTracer")
      verilog must include("module Bus_3d_1h_32w_32a")
      verilog must include("module Ram2P")
      verilog must include("module Timer")
      verilog must include("Bus_3d_1h_32w_32a u_bus")
      verilog must include("Ram2P u_ram")
      verilog must include("module SimulatorCtrl")
      verilog must include("u_simulator_ctrl")
      verilog must include("Timer u_timer")
      verilog must include("IbexTopTracing u_top")
      verilog must include(".scramble_key_valid_i                 (1'h1)")
      verilog must include(".scramble_key_i                       (128'h14E8CECAE3040D5E12286BB3CC113298)")
      verilog must include(".scramble_nonce_i                     (64'hF79780BC735F3843)")
    }
  }
}
