package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexPrefetchBufferSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val rst_ni = Input(Bool())
      val req_i = Input(Bool())
      val branch_i = Input(Bool())
      val addr_i = Input(UInt(32.W))
      val ready_i = Input(Bool())
      val valid_o = Output(Bool())
      val rdata_o = Output(UInt(32.W))
      val addr_o = Output(UInt(32.W))
      val err_o = Output(Bool())
      val err_plus2_o = Output(Bool())
      val instr_req_o = Output(Bool())
      val instr_gnt_i = Input(Bool())
      val instr_addr_o = Output(UInt(32.W))
      val instr_rdata_i = Input(UInt(32.W))
      val instr_err_i = Input(Bool())
      val instr_rvalid_i = Input(Bool())
      val busy_o = Output(Bool())
    })

    val dut = Module(new IbexPrefetchBuffer(resetAll = true))
    dut.clk_i := clock
    dut.rst_ni := io.rst_ni
    dut.req_i := io.req_i
    dut.branch_i := io.branch_i
    dut.addr_i := io.addr_i
    dut.ready_i := io.ready_i
    io.valid_o := dut.valid_o
    io.rdata_o := dut.rdata_o
    io.addr_o := dut.addr_o
    io.err_o := dut.err_o
    io.err_plus2_o := dut.err_plus2_o
    io.instr_req_o := dut.instr_req_o
    dut.instr_gnt_i := io.instr_gnt_i
    io.instr_addr_o := dut.instr_addr_o
    dut.instr_rdata_i := io.instr_rdata_i
    dut.instr_err_i := io.instr_err_i
    dut.instr_rvalid_i := io.instr_rvalid_i
    io.busy_o := dut.busy_o
  }

  private def reset(dut: Harness): Unit = {
    dut.io.rst_ni.poke(false.B)
    dut.io.req_i.poke(false.B)
    dut.io.branch_i.poke(false.B)
    dut.io.addr_i.poke(0.U)
    dut.io.ready_i.poke(false.B)
    dut.io.instr_gnt_i.poke(false.B)
    dut.io.instr_rdata_i.poke(0.U)
    dut.io.instr_err_i.poke(false.B)
    dut.io.instr_rvalid_i.poke(false.B)
    dut.clock.step()
    dut.io.rst_ni.poke(true.B)
    dut.clock.step()
  }

  "IbexPrefetchBuffer" - {
    "issues an aligned request on a branch and returns the granted response through the fetch FIFO" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00000100".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00000100".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.req_i.poke(false.B)
        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.addr_i.poke("h00000100".U)
        dut.io.valid_o.expect(true.B)
        dut.io.addr_o.expect("h00000100".U)
        dut.io.rdata_o.expect("h00000013".U)
        dut.io.err_o.expect(false.B)
      }
    }

    "holds an ungranted request stable until grant" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00000200".U)
        dut.io.instr_gnt_i.poke(false.B)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00000200".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.branch_i.poke(false.B)
        dut.io.addr_i.poke("h00000300".U)
        dut.io.instr_req_o.expect(true.B)
        dut.io.instr_addr_o.expect("h00000200".U)

        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()
        dut.io.instr_req_o.expect(false.B)
      }
    }

    "discards an outstanding response after a branch flush" in {
      simulate(new Harness) { dut =>
        reset(dut)
        dut.io.req_i.poke(true.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00000400".U)
        dut.io.instr_gnt_i.poke(true.B)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.instr_gnt_i.poke(false.B)
        dut.io.branch_i.poke(true.B)
        dut.io.addr_i.poke("h00000800".U)
        dut.clock.step()

        dut.io.branch_i.poke(false.B)
        dut.io.instr_rvalid_i.poke(true.B)
        dut.io.instr_rdata_i.poke("h00000013".U)
        dut.io.valid_o.expect(false.B)
      }
    }

    "does not skip prefetched words while streaming compressed instructions" in {
      simulate(new Harness) { dut =>
        reset(dut)

        val start = BigInt("1004b4", 16)
        val words = Map[BigInt, BigInt](
          BigInt("1004b4", 16) -> BigInt("81064081", 16),
          BigInt("1004b8", 16) -> BigInt("82068186", 16),
          BigInt("1004bc", 16) -> BigInt("83068286", 16),
          BigInt("1004c0", 16) -> BigInt("84068386", 16),
          BigInt("1004c4", 16) -> BigInt("85068486", 16),
          BigInt("1004c8", 16) -> BigInt("86068586", 16),
          BigInt("1004cc", 16) -> BigInt("87068686", 16),
          BigInt("1004d0", 16) -> BigInt("88068786", 16)
        )
        val expected = Seq(
          BigInt("1004b4", 16) -> BigInt("4081", 16),
          BigInt("1004b6", 16) -> BigInt("8106", 16),
          BigInt("1004b8", 16) -> BigInt("8186", 16),
          BigInt("1004ba", 16) -> BigInt("8206", 16),
          BigInt("1004bc", 16) -> BigInt("8286", 16),
          BigInt("1004be", 16) -> BigInt("8306", 16),
          BigInt("1004c0", 16) -> BigInt("8386", 16),
          BigInt("1004c2", 16) -> BigInt("8406", 16),
          BigInt("1004c4", 16) -> BigInt("8486", 16),
          BigInt("1004c6", 16) -> BigInt("8506", 16),
          BigInt("1004c8", 16) -> BigInt("8586", 16),
          BigInt("1004ca", 16) -> BigInt("8606", 16)
        )

        var pendingResponse = Option.empty[BigInt]
        var seen = 0

        dut.io.req_i.poke(true.B)
        dut.io.ready_i.poke(true.B)
        dut.io.instr_gnt_i.poke(true.B)
        dut.io.instr_err_i.poke(false.B)

        for (cycle <- 0 until 32 if seen < expected.length) {
          dut.io.branch_i.poke((cycle == 0).B)
          dut.io.addr_i.poke(start.U)
          dut.io.instr_rvalid_i.poke(pendingResponse.isDefined.B)
          dut.io.instr_rdata_i.poke(pendingResponse.map(words).getOrElse(BigInt(0)).U)

          if (dut.io.valid_o.peek().litToBoolean) {
            val (addr, instr) = expected(seen)
            dut.io.addr_o.expect(addr.U)
            (dut.io.rdata_o.peek().litValue & BigInt("ffff", 16)) mustBe instr
            seen += 1
          }

          val nextResponse =
            if (dut.io.instr_req_o.peek().litToBoolean) Some(dut.io.instr_addr_o.peek().litValue)
            else None
          dut.clock.step()
          pendingResponse = nextResponse
        }

        seen mustBe expected.length
      }
    }
  }
}
