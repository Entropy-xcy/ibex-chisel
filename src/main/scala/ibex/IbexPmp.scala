// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexPmp(
    dmBaseAddr: BigInt = BigInt("1a110000", 16),
    dmAddrMask: BigInt = BigInt("00000fff", 16),
    pmpGranularity: Int = 0,
    pmpNumChan: Int = 2,
    pmpNumRegions: Int = 4)
    extends RawModule {
  require(pmpGranularity >= 0)
  require(pmpNumChan > 0)
  require(pmpNumRegions > 0)

  private val pmpAddrMsb = IbexPkg.PMP_ADDR_MSB
  private val pmpAddrLsb = IbexPkg.PMP_ADDR_LSB
  private val cmpLo = pmpGranularity + pmpAddrLsb
  private val cmpWidth = pmpAddrMsb - cmpLo + 1

  val csr_pmp_cfg_i = IO(Input(Vec(pmpNumRegions, new IbexPkg.PmpCfg)))
  val csr_pmp_addr_i = IO(Input(Vec(pmpNumRegions, UInt((pmpAddrMsb + 1).W))))
  val csr_pmp_mseccfg_i = IO(Input(new IbexPkg.PmpMseccfg))
  val debug_mode_i = IO(Input(Bool()))
  val priv_mode_i = IO(Input(Vec(pmpNumChan, UInt(2.W))))
  val pmp_req_addr_i = IO(Input(Vec(pmpNumChan, UInt((pmpAddrMsb + 1).W))))
  val pmp_req_type_i = IO(Input(Vec(pmpNumChan, UInt(2.W))))
  val pmp_req_err_o = IO(Output(Vec(pmpNumChan, Bool())))

  private def andReduceRange(value: UInt, hi: Int, lo: Int): Bool = {
    if (hi < lo) true.B else value(hi, lo).andR
  }

  private def modeIs(mode: UInt, value: UInt): Bool = mode === value

  private def basicPerm(cfg: IbexPkg.PmpCfg, reqType: UInt): Bool = {
    (reqType === IbexPkg.PmpReq.Exec && cfg.exec) ||
      (reqType === IbexPkg.PmpReq.Write && cfg.write) ||
      (reqType === IbexPkg.PmpReq.Read && cfg.read)
  }

  private def mmlPermCheck(cfg: IbexPkg.PmpCfg, reqType: UInt, privMode: UInt, permissionCheck: Bool): Bool = {
    val result = WireDefault(false.B)
    when(!cfg.read && cfg.write) {
      switch(Cat(cfg.lock, cfg.exec)) {
        is("b00".U) {
          result := (reqType === IbexPkg.PmpReq.Read) ||
            ((reqType === IbexPkg.PmpReq.Write) && (privMode === IbexPkg.PrivLvl.M))
        }
        is("b01".U) {
          result := (reqType === IbexPkg.PmpReq.Read) || (reqType === IbexPkg.PmpReq.Write)
        }
        is("b10".U) {
          result := reqType === IbexPkg.PmpReq.Exec
        }
        is("b11".U) {
          result := (reqType === IbexPkg.PmpReq.Exec) ||
            ((reqType === IbexPkg.PmpReq.Read) && (privMode === IbexPkg.PrivLvl.M))
        }
      }
    }.otherwise {
      when(cfg.read && cfg.write && cfg.exec && cfg.lock) {
        result := reqType === IbexPkg.PmpReq.Read
      }.otherwise {
        result := permissionCheck &&
          Mux(privMode === IbexPkg.PrivLvl.M, cfg.lock, !cfg.lock)
      }
    }
    result
  }

  private def origPermCheck(lock: Bool, privMode: UInt, permissionCheck: Bool): Bool = {
    Mux(privMode === IbexPkg.PrivLvl.M, !lock || permissionCheck, permissionCheck)
  }

  private def permCheckWrapper(mml: Bool, cfg: IbexPkg.PmpCfg, reqType: UInt, privMode: UInt, permissionCheck: Bool): Bool = {
    Mux(mml, mmlPermCheck(cfg, reqType, privMode, permissionCheck), origPermCheck(cfg.lock, privMode, permissionCheck))
  }

  private def accessFaultCheck(mmwp: Bool, mml: Bool, reqType: UInt, matchAll: Seq[Bool], privMode: UInt, finalPermCheck: Seq[Bool]): Bool = {
    val defaultFail = mmwp || (privMode =/= IbexPkg.PrivLvl.M) || (mml && (reqType === IbexPkg.PmpReq.Exec))
    val matched = Wire(Vec(pmpNumRegions + 1, Bool()))
    val accessFail = Wire(Vec(pmpNumRegions + 1, Bool()))
    matched(0) := false.B
    accessFail(0) := defaultFail
    for (r <- 0 until pmpNumRegions) {
      val takeRegion = !matched(r) && matchAll(r)
      accessFail(r + 1) := Mux(takeRegion, !finalPermCheck(r), accessFail(r))
      matched(r + 1) := matched(r) || matchAll(r)
    }
    accessFail(pmpNumRegions)
  }

  val region_start_addr = Wire(Vec(pmpNumRegions, UInt((pmpAddrMsb + 1).W)))
  val region_addr_mask = Wire(Vec(pmpNumRegions, UInt(cmpWidth.W)))

  for (r <- 0 until pmpNumRegions) {
    val torMode = modeIs(csr_pmp_cfg_i(r).mode, IbexPkg.PmpCfgMode.Tor)
    region_start_addr(r) := Mux(torMode, if (r == 0) 0.U else csr_pmp_addr_i(r - 1), csr_pmp_addr_i(r))

    val maskBits = (cmpLo to pmpAddrMsb).map { b =>
      if (b == pmpAddrLsb) {
        !modeIs(csr_pmp_cfg_i(r).mode, IbexPkg.PmpCfgMode.Napot)
      } else if (pmpGranularity == 0) {
        !modeIs(csr_pmp_cfg_i(r).mode, IbexPkg.PmpCfgMode.Napot) || !andReduceRange(csr_pmp_addr_i(r), b - 1, pmpAddrLsb)
      } else {
        !modeIs(csr_pmp_cfg_i(r).mode, IbexPkg.PmpCfgMode.Napot) || !andReduceRange(csr_pmp_addr_i(r), b - 1, pmpGranularity + 1)
      }
    }
    region_addr_mask(r) := Cat(maskBits.reverse)
  }

  for (c <- 0 until pmpNumChan) {
    val regionMatchAll = Seq.fill(pmpNumRegions)(Wire(Bool()))
    val regionPermCheck = Seq.fill(pmpNumRegions)(Wire(Bool()))
    val reqCmp = pmp_req_addr_i(c)(pmpAddrMsb, cmpLo)

    for (r <- 0 until pmpNumRegions) {
      val startCmp = region_start_addr(r)(pmpAddrMsb, cmpLo)
      val endCmp = csr_pmp_addr_i(r)(pmpAddrMsb, cmpLo)
      val regionMatchEq = (reqCmp & region_addr_mask(r)) === (startCmp & region_addr_mask(r))
      val regionMatchGt = reqCmp > startCmp
      val regionMatchLt = reqCmp < endCmp

      regionMatchAll(r) := false.B
      switch(csr_pmp_cfg_i(r).mode) {
        is(IbexPkg.PmpCfgMode.Off) {
          regionMatchAll(r) := false.B
        }
        is(IbexPkg.PmpCfgMode.Na4) {
          regionMatchAll(r) := regionMatchEq
        }
        is(IbexPkg.PmpCfgMode.Napot) {
          regionMatchAll(r) := regionMatchEq
        }
        is(IbexPkg.PmpCfgMode.Tor) {
          regionMatchAll(r) := (regionMatchEq || regionMatchGt) && regionMatchLt
        }
      }

      val regionBasicPermCheck = basicPerm(csr_pmp_cfg_i(r), pmp_req_type_i(c))
      regionPermCheck(r) := permCheckWrapper(
        csr_pmp_mseccfg_i.mml,
        csr_pmp_cfg_i(r),
        pmp_req_type_i(c),
        priv_mode_i(c),
        regionBasicPermCheck
      )
    }

    val debugModeAllowedAccess = debug_mode_i &&
      ((pmp_req_addr_i(c)(31, 0) & (~dmAddrMask & 0xffffffffL).U(32.W)) === dmBaseAddr.U(32.W))
    val accessFaultCheckRes = accessFaultCheck(
      csr_pmp_mseccfg_i.mmwp,
      csr_pmp_mseccfg_i.mml,
      pmp_req_type_i(c),
      regionMatchAll,
      priv_mode_i(c),
      regionPermCheck
    )
    pmp_req_err_o(c) := !debugModeAllowedAccess && accessFaultCheckRes
  }
}
