package ibex

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PrimRam1PScrSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def allOnes(width: Int): BigInt = (BigInt(1) << width) - 1

  class Harness(
      width: Int = 32,
      replicateKeyStream: Boolean = false) extends Module {
    private val nonceWidth = 64 * (if (replicateKeyStream) 1 else ((width + 63) / 64))
    val io = IO(new Bundle {
      val key_valid_i = Input(Bool())
      val key_i = Input(UInt(IbexPkg.SCRAMBLE_KEY_W.W))
      val nonce_i = Input(UInt(nonceWidth.W))
      val req_i = Input(Bool())
      val gnt_o = Output(Bool())
      val write_i = Input(Bool())
      val addr_i = Input(UInt(4.W))
      val wdata_i = Input(UInt(width.W))
      val wmask_i = Input(UInt(width.W))
      val intg_error_i = Input(Bool())
      val rdata_o = Output(UInt(width.W))
      val rvalid_o = Output(Bool())
      val wr_collision_o = Output(Bool())
      val write_pending_o = Output(Bool())
    })

    val dut = Module(new PrimRam1PScr(width = width, depth = 16, dataBitsPerMask = width, replicateKeyStream = replicateKeyStream))
    dut.clk_i := clock
    dut.rst_ni := !reset.asBool
    dut.key_valid_i := io.key_valid_i
    dut.key_i := io.key_i
    dut.nonce_i := io.nonce_i
    dut.req_i := io.req_i
    io.gnt_o := dut.gnt_o
    dut.write_i := io.write_i
    dut.addr_i := io.addr_i
    dut.wdata_i := io.wdata_i
    dut.wmask_i := io.wmask_i
    dut.intg_error_i := io.intg_error_i
    io.rdata_o := dut.rdata_o
    io.rvalid_o := dut.rvalid_o
    io.wr_collision_o := dut.wr_collision_o
    io.write_pending_o := dut.write_pending_o
    dut.cfg_i := 0.U.asTypeOf(new PrimRam1PPkg.Ram1PCfg)
  }

  private def init(dut: Harness): Unit = {
    dut.io.key_valid_i.poke(true.B)
    dut.io.key_i.poke("h00000000000000000000000000000011".U)
    dut.io.nonce_i.poke((BigInt(0x22)).U)
    dut.io.req_i.poke(false.B)
    dut.io.write_i.poke(false.B)
    dut.io.addr_i.poke(0.U)
    dut.io.wdata_i.poke(0.U)
    dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
    dut.io.intg_error_i.poke(false.B)
  }

  "PrimRam1PScr" - {
    "gates requests until a valid key is available" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.key_valid_i.poke(false.B)
        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.gnt_o.expect(false.B)
        dut.clock.step()

        dut.io.key_valid_i.poke(true.B)
        dut.io.gnt_o.expect(true.B)
      }
    }

    "descrambles data with the same key and nonce used for writes" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(3.U)
        dut.io.wdata_i.poke("hdeadbeef".U)
        dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
        dut.io.gnt_o.expect(true.B)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.io.req_i.poke(true.B)
        dut.io.addr_i.poke(3.U)
        dut.io.rvalid_o.expect(false.B)
        dut.io.rdata_o.expect(0.U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("hdeadbeef".U)
      }
    }

    "forwards pending write data on a read-after-write collision" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(2.U)
        dut.io.wdata_i.poke("h11223344".U)
        dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.io.addr_i.poke(2.U)
        dut.io.req_i.poke(true.B)
        dut.clock.step()
        dut.io.wr_collision_o.expect(true.B)
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("h11223344".U)
        dut.io.write_pending_o.expect(true.B)
      }
    }

    "drops an integrity-error write and masks the following read response" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(6.U)
        dut.io.wdata_i.poke("hfeedcafe".U)
        dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
        dut.io.gnt_o.expect(true.B)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.io.req_i.poke(true.B)
        dut.io.addr_i.poke(6.U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("hfeedcafe".U)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(6.U)
        dut.io.wdata_i.poke("hdeadbeef".U)
        dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
        dut.io.intg_error_i.poke(true.B)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.io.intg_error_i.poke(false.B)
        dut.io.addr_i.poke(6.U)
        dut.io.req_i.poke(true.B)
        dut.io.rvalid_o.expect(false.B)
        dut.io.write_pending_o.expect(false.B)
        dut.clock.step()

        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("hfeedcafe".U)
      }
    }

    "keeps a pending write alive across an intervening read and then commits the write" in {
      simulate(new Harness) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(5.U)
        dut.io.wdata_i.poke("h55667788".U)
        dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
        dut.clock.step()

        dut.io.write_i.poke(false.B)
        dut.io.addr_i.poke(1.U)
        dut.io.req_i.poke(true.B)
        dut.clock.step()
        dut.io.write_pending_o.expect(true.B)
        dut.io.rvalid_o.expect(true.B)

        dut.io.req_i.poke(false.B)
        dut.clock.step()
        dut.io.write_pending_o.expect(false.B)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(false.B)
        dut.io.addr_i.poke(5.U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("h55667788".U)
      }
    }

    "round-trips a wider non-replicated keystream path" in {
      simulate(new Harness(width = 96, replicateKeyStream = false)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.key_valid_i.poke(true.B)
        dut.io.key_i.poke("h00000000000000000000000000000011".U)
        dut.io.nonce_i.poke(BigInt("00000000000000220000000000000033", 16).U)
        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(4.U)
        dut.io.wdata_i.poke(BigInt("0123456789abcdeffedcba98", 16).U)
        dut.io.wmask_i.poke(allOnes(dut.io.wmask_i.getWidth).U)
        dut.clock.step()
        dut.io.write_i.poke(false.B)
        dut.io.req_i.poke(true.B)
        dut.io.addr_i.poke(4.U)
        dut.clock.step()
        dut.io.wr_collision_o.expect(true.B)
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect(BigInt("0123456789abcdeffedcba98", 16).U)
      }
    }

    "merges masked writes with the previously stored data" in {
      simulate(new Harness(width = 32, replicateKeyStream = false)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        init(dut)

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(true.B)
        dut.io.addr_i.poke(7.U)
        dut.io.wdata_i.poke("hffff0000".U)
        dut.io.wmask_i.poke("hffffffff".U)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(false.B)
        dut.io.addr_i.poke(7.U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("hffff0000".U)

        dut.io.write_i.poke(true.B)
        dut.io.wdata_i.poke("h12345678".U)
        dut.io.wmask_i.poke("h00ff00ff".U)
        dut.clock.step()

        dut.io.req_i.poke(false.B)
        dut.io.write_i.poke(false.B)
        dut.clock.step()

        dut.io.req_i.poke(true.B)
        dut.io.write_i.poke(false.B)
        dut.io.addr_i.poke(7.U)
        dut.clock.step()
        dut.io.rvalid_o.expect(true.B)
        dut.io.rdata_o.expect("hff340078".U)
      }
    }
  }
}
