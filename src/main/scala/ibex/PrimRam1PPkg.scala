// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._

object PrimRam1PPkg {
  final class Cfg extends Bundle {
    val test = Bool()
    val cfg_en = Bool()
    val cfg = UInt(4.W)
  }

  final class Ram1PCfg extends Bundle {
    val ram_cfg = new Cfg
    val rf_cfg = new Cfg
  }

  final class Ram1PCfgRsp extends Bundle {
    val done = Bool()
  }
}
