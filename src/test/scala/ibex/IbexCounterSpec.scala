package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexCounterSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class IbexCounterHarness(counterWidth: Int, provideValUpd: Boolean) extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val counter_inc_i = Input(Bool())
      val counterh_we_i = Input(Bool())
      val counter_we_i = Input(Bool())
      val counter_val_i = Input(UInt(32.W))
      val counter_val_o = Output(UInt(64.W))
      val counter_val_upd_o = Output(UInt(64.W))
    })

    val dut = Module(new IbexCounter(counterWidth, provideValUpd))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.counter_inc_i := io.counter_inc_i
    dut.counterh_we_i := io.counterh_we_i
    dut.counter_we_i := io.counter_we_i
    dut.counter_val_i := io.counter_val_i
    io.counter_val_o := dut.counter_val_o
    io.counter_val_upd_o := dut.counter_val_upd_o
  }

  private def reset(dut: IbexCounterHarness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.counter_inc_i.poke(false.B)
    dut.io.counterh_we_i.poke(false.B)
    dut.io.counter_we_i.poke(false.B)
    dut.io.counter_val_i.poke(0.U)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "IbexCounter" - {
    "increments and reports the updated value when ProvideValUpd is set" in {
      simulate(new IbexCounterHarness(counterWidth = 32, provideValUpd = true)) { dut =>
        reset(dut)
        dut.io.counter_val_o.expect(0.U)
        dut.io.counter_val_upd_o.expect(1.U)

        dut.io.counter_inc_i.poke(true.B)
        dut.clock.step()
        dut.io.counter_val_o.expect(1.U)
        dut.io.counter_val_upd_o.expect(2.U)
      }
    }

    "prioritizes software writes over increments and supports high-half writes for 64-bit counters" in {
      simulate(new IbexCounterHarness(counterWidth = 64, provideValUpd = false)) { dut =>
        reset(dut)

        dut.io.counter_inc_i.poke(true.B)
        dut.io.counter_we_i.poke(true.B)
        dut.io.counter_val_i.poke("hfffffff0".U)
        dut.clock.step()
        dut.io.counter_val_o.expect("h00000000fffffff0".U)
        dut.io.counter_val_upd_o.expect(0.U)

        dut.io.counter_we_i.poke(false.B)
        dut.io.counterh_we_i.poke(true.B)
        dut.io.counter_val_i.poke("h12345678".U)
        dut.clock.step()
        dut.io.counter_val_o.expect("h12345678fffffff0".U)

        dut.io.counterh_we_i.poke(false.B)
        dut.io.counter_inc_i.poke(true.B)
        dut.clock.step()
        dut.io.counter_val_o.expect("h12345678fffffff1".U)
      }
    }

    "masks narrow counters to CounterWidth and zero-extends their outputs" in {
      simulate(new IbexCounterHarness(counterWidth = 40, provideValUpd = true)) { dut =>
        reset(dut)

        dut.io.counter_we_i.poke(true.B)
        dut.io.counter_val_i.poke("hdeadbeef".U)
        dut.clock.step()
        dut.io.counter_val_o.expect("h00000000deadbeef".U)
        dut.io.counter_val_upd_o.expect("h00000000deadbef0".U)

        dut.io.counter_we_i.poke(false.B)
        dut.io.counterh_we_i.poke(true.B)
        dut.io.counter_val_i.poke("habcd1234".U)
        dut.clock.step()
        dut.io.counter_val_o.expect("h00000034deadbeef".U)
        dut.io.counter_val_upd_o.expect("h00000034deadbef0".U)

        dut.io.counter_val_i.poke("hffffffff".U)
        dut.clock.step()
        dut.io.counter_val_o.expect("h000000ffdeadbeef".U)

        dut.io.counterh_we_i.poke(false.B)
        dut.io.counter_we_i.poke(true.B)
        dut.io.counter_val_i.poke("hffffffff".U)
        dut.clock.step()
        dut.io.counterh_we_i.poke(true.B)
        dut.clock.step()
        dut.io.counterh_we_i.poke(false.B)
        dut.io.counter_we_i.poke(false.B)
        dut.io.counter_val_o.expect("h000000ffffffffff".U)
        dut.io.counter_val_upd_o.expect(0.U)

        dut.io.counter_inc_i.poke(true.B)
        dut.clock.step()
        dut.io.counter_val_o.expect(0.U)
        dut.io.counter_val_upd_o.expect(1.U)
      }
    }
  }
}
