package ibex

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexPmpSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Harness extends Module {
    val io = IO(new Bundle {
      val csr_pmp_cfg_i = Input(Vec(2, new IbexPkg.PmpCfg))
      val csr_pmp_addr_i = Input(Vec(2, UInt(34.W)))
      val csr_pmp_mseccfg_i = Input(new IbexPkg.PmpMseccfg)
      val debug_mode_i = Input(Bool())
      val priv_mode_i = Input(Vec(1, UInt(2.W)))
      val pmp_req_addr_i = Input(Vec(1, UInt(34.W)))
      val pmp_req_type_i = Input(Vec(1, UInt(2.W)))
      val pmp_req_err_o = Output(Vec(1, Bool()))
    })

    val dut = Module(new IbexPmp(pmpNumChan = 1, pmpNumRegions = 2))
    dut.csr_pmp_cfg_i := io.csr_pmp_cfg_i
    dut.csr_pmp_addr_i := io.csr_pmp_addr_i
    dut.csr_pmp_mseccfg_i := io.csr_pmp_mseccfg_i
    dut.debug_mode_i := io.debug_mode_i
    dut.priv_mode_i := io.priv_mode_i
    dut.pmp_req_addr_i := io.pmp_req_addr_i
    dut.pmp_req_type_i := io.pmp_req_type_i
    io.pmp_req_err_o := dut.pmp_req_err_o
  }

  private def init(dut: Harness): Unit = {
    for (r <- 0 until 2) {
      dut.io.csr_pmp_cfg_i(r).lock.poke(false.B)
      dut.io.csr_pmp_cfg_i(r).mode.poke(IbexPkg.PmpCfgMode.Off)
      dut.io.csr_pmp_cfg_i(r).exec.poke(false.B)
      dut.io.csr_pmp_cfg_i(r).write.poke(false.B)
      dut.io.csr_pmp_cfg_i(r).read.poke(false.B)
      dut.io.csr_pmp_addr_i(r).poke(0.U)
    }
    dut.io.csr_pmp_mseccfg_i.rlb.poke(false.B)
    dut.io.csr_pmp_mseccfg_i.mmwp.poke(false.B)
    dut.io.csr_pmp_mseccfg_i.mml.poke(false.B)
    dut.io.debug_mode_i.poke(false.B)
    dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.M)
    dut.io.pmp_req_addr_i(0).poke(0.U)
    dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Read)
  }

  private def setCfg(
      dut: Harness,
      region: Int,
      mode: UInt,
      read: Boolean,
      write: Boolean,
      exec: Boolean,
      lock: Boolean = false): Unit = {
    dut.io.csr_pmp_cfg_i(region).mode.poke(mode)
    dut.io.csr_pmp_cfg_i(region).read.poke(read.B)
    dut.io.csr_pmp_cfg_i(region).write.poke(write.B)
    dut.io.csr_pmp_cfg_i(region).exec.poke(exec.B)
    dut.io.csr_pmp_cfg_i(region).lock.poke(lock.B)
  }

  "IbexPmp" - {
    "allows unmatched M-mode accesses and denies unmatched U-mode accesses" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.pmp_req_addr_i(0).poke("h00001000".U)

        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.M)
        dut.io.pmp_req_err_o(0).expect(false.B)

        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.U)
        dut.io.pmp_req_err_o(0).expect(true.B)
      }
    }

    "matches TOR regions and enforces permissions outside M-mode" in {
      simulate(new Harness) { dut =>
        init(dut)
        setCfg(dut, 0, IbexPkg.PmpCfgMode.Tor, read = true, write = false, exec = false)
        dut.io.csr_pmp_addr_i(0).poke("h00002000".U)
        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.U)
        dut.io.pmp_req_addr_i(0).poke("h00001000".U)

        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Read)
        dut.io.pmp_req_err_o(0).expect(false.B)

        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Write)
        dut.io.pmp_req_err_o(0).expect(true.B)
      }
    }

    "uses the lowest-numbered matching PMP region for priority" in {
      simulate(new Harness) { dut =>
        init(dut)
        setCfg(dut, 0, IbexPkg.PmpCfgMode.Tor, read = false, write = false, exec = false)
        dut.io.csr_pmp_addr_i(0).poke("h00002000".U)
        setCfg(dut, 1, IbexPkg.PmpCfgMode.Tor, read = true, write = true, exec = true)
        dut.io.csr_pmp_addr_i(1).poke("h00003000".U)

        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.U)
        dut.io.pmp_req_addr_i(0).poke("h00001000".U)
        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Read)
        dut.io.pmp_req_err_o(0).expect(true.B)
      }
    }

    "allows debug module accesses in debug mode even when default policy denies" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.csr_pmp_mseccfg_i.mmwp.poke(true.B)
        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.U)
        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Exec)

        dut.io.debug_mode_i.poke(true.B)
        dut.io.pmp_req_addr_i(0).poke("h1a110004".U)
        dut.io.pmp_req_err_o(0).expect(false.B)

        dut.io.pmp_req_addr_i(0).poke("h1a120000".U)
        dut.io.pmp_req_err_o(0).expect(true.B)
      }
    }

    "applies Smepmp MML shared-region permission rules" in {
      simulate(new Harness) { dut =>
        init(dut)
        dut.io.csr_pmp_mseccfg_i.mml.poke(true.B)
        setCfg(dut, 0, IbexPkg.PmpCfgMode.Napot, read = false, write = true, exec = false, lock = false)
        dut.io.csr_pmp_addr_i(0).poke("h00001007".U)
        dut.io.pmp_req_addr_i(0).poke("h00001000".U)

        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.U)
        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Read)
        dut.io.pmp_req_err_o(0).expect(false.B)
        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Write)
        dut.io.pmp_req_err_o(0).expect(true.B)
        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Exec)
        dut.io.pmp_req_err_o(0).expect(true.B)

        dut.io.priv_mode_i(0).poke(IbexPkg.PrivLvl.M)
        dut.io.pmp_req_type_i(0).poke(IbexPkg.PmpReq.Write)
        dut.io.pmp_req_err_o(0).expect(false.B)
      }
    }
  }
}
