// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util.{Cat, Enum, Fill, MuxCase, switch, is}

class IbexCompressedDecoder(rv32zc: Int = 3, resetAll: Boolean = false) extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val valid_i = IO(Input(Bool()))
  val id_in_ready_i = IO(Input(Bool()))
  val instr_i = IO(Input(UInt(32.W)))

  val instr_o = IO(Output(UInt(32.W)))
  val is_compressed_o = IO(Output(Bool()))
  val gets_expanded_o = IO(Output(UInt(2.W)))
  val illegal_instr_o = IO(Output(Bool()))

  private val zcbEnabled = rv32zc == 1 || rv32zc == 3
  private val zcmpEnabled = rv32zc == 2 || rv32zc == 3

  private val INSTR_NOT_EXPANDED = 0.U(2.W)
  private val INSTR_EXPANDED = 1.U(2.W)
  private val INSTR_EXPANDED_LAST = 2.U(2.W)

  private object CmState {
    private val states = Enum(8)
    val CmIdle = states(0)
    val CmPushStoreReg = states(1)
    val CmPushDecrSp = states(2)
    val CmPopLoadReg = states(3)
    val CmPopIncrSp = states(4)
    val CmPopZeroA0 = states(5)
    val CmPopRetRa = states(6)
    val CmMvSecondReg = states(7)
  }

  private def cm_stack_adj_base(rlist: UInt): UInt =
    MuxCase(0.U(7.W), Seq(
      (rlist >= 4.U && rlist <= 7.U) -> 16.U(7.W),
      (rlist >= 8.U && rlist <= 11.U) -> 32.U(7.W),
      (rlist >= 12.U && rlist <= 14.U) -> 48.U(7.W),
      (rlist === 15.U) -> 64.U(7.W)
    ))

  private def cm_stack_adj(rlist: UInt, spimm: UInt): UInt =
    cm_stack_adj_base(rlist) + Cat(spimm, 0.U(4.W))

  private def cm_stack_adj_word(rlist: UInt, spimm: UInt): UInt =
    cm_stack_adj(rlist, spimm)(6, 2)

  private def cm_rlist_top_reg(rlist: UInt): UInt =
    MuxCase(0.U(5.W), Seq(
      (rlist === 16.U) -> 27.U(5.W),
      (rlist >= 7.U && rlist <= 15.U) -> (11.U(5.W) + rlist),
      (rlist >= 5.U && rlist <= 6.U) -> (3.U(5.W) + rlist),
      (rlist === 4.U) -> 1.U(5.W)
    ))

  private def cm_push_store_reg(rlist: UInt, sp_offset: UInt): UInt = {
    val neg_offset = (0.U(12.W) - Cat(0.U(5.W), sp_offset, 0.U(2.W)))(11, 0)
    Cat(
      neg_offset(11, 5), cm_rlist_top_reg(rlist), 2.U(5.W), "b010".U(3.W),
      neg_offset(4, 0), IbexPkg.Opcode.STORE
    )
  }

  private def cm_pop_load_reg(rlist: UInt, sp_offset: UInt): UInt =
    Cat(0.U(5.W), sp_offset, 0.U(2.W), 2.U(5.W), "b010".U(3.W), cm_rlist_top_reg(rlist), IbexPkg.Opcode.LOAD)

  private def cm_sp_addi(rlist: UInt, spimm: UInt, decr: Bool): UInt = {
    val adj = Cat(0.U(5.W), cm_stack_adj(rlist, spimm))
    val imm = Mux(decr, (0.U(12.W) - adj)(11, 0), adj)
    Cat(imm, 2.U(5.W), "b000".U(3.W), 2.U(5.W), IbexPkg.Opcode.OP_IMM)
  }

  private def cm_mv_reg(src: UInt, dst: UInt): UInt =
    Cat(0.U(12.W), src, "b000".U(3.W), dst, IbexPkg.Opcode.OP_IMM)

  private def cm_zero_a0(): UInt = cm_mv_reg(0.U(5.W), 10.U(5.W))

  private def cm_ret_ra(): UInt =
    Cat(0.U(12.W), 1.U(5.W), "b000".U(3.W), 0.U(5.W), IbexPkg.Opcode.JALR)

  private def cm_reg_from_rs(rs: UInt): UInt =
    Cat((rs(2, 1) > 0.U).asUInt, (rs(2, 1) === 0.U).asUInt, rs)

  private def cm_mvsa01(a01: UInt, rs: UInt): UInt =
    cm_mv_reg(10.U(5.W) + a01, cm_reg_from_rs(rs))

  private def cm_mva01s(rs: UInt, a01: UInt): UInt =
    cm_mv_reg(cm_reg_from_rs(rs), 10.U(5.W) + a01)

  private def cm_rlist_init(instr_rlist: UInt): UInt =
    Mux(instr_rlist === 15.U, 16.U(5.W), Cat(0.U(1.W), instr_rlist))

  private val cm_rlist_q = Wire(UInt(5.W))
  private val cm_sp_offset_q = Wire(UInt(5.W))
  private val cm_state_q = Wire(UInt(3.W))
  private val cm_rlist_d = WireDefault(cm_rlist_q)
  private val cm_sp_offset_d = WireDefault(cm_sp_offset_q)
  private val cm_state_d = WireDefault(cm_state_q)

  private val cmStateReg = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(CmState.CmIdle)
  }
  private val cmRlistReg = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  } else {
    withClock(clk_i) { Reg(UInt(5.W)) }
  }
  private val cmSpOffsetReg = if (resetAll) {
    withClockAndReset(clk_i, (!rst_ni).asAsyncReset) { RegInit(0.U(5.W)) }
  } else {
    withClock(clk_i) { Reg(UInt(5.W)) }
  }

  cm_rlist_q := cmRlistReg
  cm_sp_offset_q := cmSpOffsetReg
  cm_state_q := cmStateReg

  private val out = WireDefault(instr_i)
  private val illegal = WireDefault(false.B)
  private val getsExpanded = WireDefault(INSTR_NOT_EXPANDED)

  is_compressed_o := instr_i(1, 0) =/= "b11".U

  when(is_compressed_o) {
    switch(instr_i(1, 0)) {
      is("b00".U) {
        switch(instr_i(15, 13)) {
          is("b000".U) {
            out := Cat(
              0.U(2.W), instr_i(10, 7), instr_i(12, 11), instr_i(5), instr_i(6), 0.U(2.W),
              "h02".U(5.W), "b000".U(3.W), "b01".U(2.W), instr_i(4, 2), IbexPkg.Opcode.OP_IMM
            )
            illegal := instr_i(12, 5) === 0.U
          }
          is("b010".U) {
            out := Cat(
              0.U(5.W), instr_i(5), instr_i(12, 10), instr_i(6), 0.U(2.W),
              "b01".U(2.W), instr_i(9, 7), "b010".U(3.W), "b01".U(2.W), instr_i(4, 2),
              IbexPkg.Opcode.LOAD
            )
          }
          is("b100".U) {
            if (zcbEnabled) {
              switch(instr_i(12, 10)) {
                is("b000".U) {
                  out := Cat(
                    0.U(10.W), instr_i(5), instr_i(6), "b01".U(2.W), instr_i(9, 7),
                    "b100".U(3.W), "b01".U(2.W), instr_i(4, 2), IbexPkg.Opcode.LOAD
                  )
                }
                is("b001".U) {
                  out := Cat(
                    0.U(10.W), instr_i(5), 0.U(1.W), "b01".U(2.W), instr_i(9, 7),
                    Mux(instr_i(6).asBool, "b001".U(3.W), "b101".U(3.W)), "b01".U(2.W),
                    instr_i(4, 2), IbexPkg.Opcode.LOAD
                  )
                }
                is("b010".U) {
                  out := Cat(
                    0.U(7.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W), instr_i(9, 7),
                    "b000".U(3.W), 0.U(3.W), instr_i(5), instr_i(6), IbexPkg.Opcode.STORE
                  )
                }
                is("b011".U) {
                  out := Cat(
                    0.U(7.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W), instr_i(9, 7),
                    "b001".U(3.W), 0.U(3.W), instr_i(5), 0.U(1.W), IbexPkg.Opcode.STORE
                  )
                  illegal := instr_i(6).asBool
                }
                is("b100".U, "b101".U, "b110".U, "b111".U) {
                  illegal := true.B
                }
              }
            } else {
              illegal := true.B
            }
          }
          is("b110".U) {
            out := Cat(
              0.U(5.W), instr_i(5), instr_i(12), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W),
              instr_i(9, 7), "b010".U(3.W), instr_i(11, 10), instr_i(6), 0.U(2.W),
              IbexPkg.Opcode.STORE
            )
          }
          is("b001".U, "b011".U, "b101".U, "b111".U) {
            illegal := true.B
          }
        }
      }

      is("b01".U) {
        switch(instr_i(15, 13)) {
          is("b000".U) {
            out := Cat(
              Fill(6, instr_i(12)), instr_i(12), instr_i(6, 2), instr_i(11, 7),
              "b000".U(3.W), instr_i(11, 7), IbexPkg.Opcode.OP_IMM
            )
          }
          is("b001".U, "b101".U) {
            out := Cat(
              instr_i(12), instr_i(8), instr_i(10, 9), instr_i(6), instr_i(7), instr_i(2),
              instr_i(11), instr_i(5, 3), Fill(9, instr_i(12)), 0.U(4.W), ~instr_i(15),
              IbexPkg.Opcode.JAL
            )
          }
          is("b010".U) {
            out := Cat(
              Fill(6, instr_i(12)), instr_i(12), instr_i(6, 2), 0.U(5.W),
              "b000".U(3.W), instr_i(11, 7), IbexPkg.Opcode.OP_IMM
            )
          }
          is("b011".U) {
            out := Cat(
              Fill(15, instr_i(12)), instr_i(6, 2), instr_i(11, 7), IbexPkg.Opcode.LUI
            )
            when(instr_i(11, 7) === "h02".U) {
              out := Cat(
                Fill(3, instr_i(12)), instr_i(4, 3), instr_i(5), instr_i(2), instr_i(6),
                0.U(4.W), "h02".U(5.W), "b000".U(3.W), "h02".U(5.W), IbexPkg.Opcode.OP_IMM
              )
            }
            illegal := Cat(instr_i(12), instr_i(6, 2)) === 0.U
          }
          is("b100".U) {
            switch(instr_i(11, 10)) {
              is("b00".U, "b01".U) {
                out := Cat(
                  0.U(1.W), instr_i(10), 0.U(5.W), instr_i(6, 2), "b01".U(2.W),
                  instr_i(9, 7), "b101".U(3.W), "b01".U(2.W), instr_i(9, 7),
                  IbexPkg.Opcode.OP_IMM
                )
                illegal := instr_i(12).asBool
              }
              is("b10".U) {
                out := Cat(
                  Fill(6, instr_i(12)), instr_i(12), instr_i(6, 2), "b01".U(2.W),
                  instr_i(9, 7), "b111".U(3.W), "b01".U(2.W), instr_i(9, 7),
                  IbexPkg.Opcode.OP_IMM
                )
              }
              is("b11".U) {
                switch(Cat(instr_i(12), instr_i(6, 5))) {
                  is("b000".U) {
                    out := Cat(
                      "b01".U(2.W), 0.U(5.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W),
                      instr_i(9, 7), "b000".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP
                    )
                  }
                  is("b001".U) {
                    out := Cat(
                      0.U(7.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W), instr_i(9, 7),
                      "b100".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP
                    )
                  }
                  is("b010".U) {
                    out := Cat(
                      0.U(7.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W), instr_i(9, 7),
                      "b110".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP
                    )
                  }
                  is("b011".U) {
                    out := Cat(
                      0.U(7.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W), instr_i(9, 7),
                      "b111".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP
                    )
                  }
                  is("b110".U) {
                    if (zcbEnabled) {
                      out := Cat(
                        "b0000001".U(7.W), "b01".U(2.W), instr_i(4, 2), "b01".U(2.W),
                        instr_i(9, 7), "b000".U(3.W), "b01".U(2.W), instr_i(9, 7),
                        IbexPkg.Opcode.OP
                      )
                    } else {
                      illegal := true.B
                    }
                  }
                  is("b111".U) {
                    if (zcbEnabled) {
                      switch(instr_i(4, 2)) {
                        is("b000".U) {
                          out := Cat(
                            0.U(4.W), "hff".U(8.W), "b01".U(2.W), instr_i(9, 7),
                            "b111".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP_IMM
                          )
                        }
                        is("b001".U) {
                          out := Cat(
                            "b0110000".U(7.W), "b00100".U(5.W), "b01".U(2.W), instr_i(9, 7),
                            "b001".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP_IMM
                          )
                        }
                        is("b010".U) {
                          out := Cat(
                            "b0000100".U(7.W), 0.U(5.W), "b01".U(2.W), instr_i(9, 7),
                            "b100".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP
                          )
                        }
                        is("b011".U) {
                          out := Cat(
                            "b0110000".U(7.W), "b00101".U(5.W), "b01".U(2.W), instr_i(9, 7),
                            "b001".U(3.W), "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP_IMM
                          )
                        }
                        is("b101".U) {
                          out := Cat(
                            "hfff".U(12.W), "b01".U(2.W), instr_i(9, 7), "b100".U(3.W),
                            "b01".U(2.W), instr_i(9, 7), IbexPkg.Opcode.OP_IMM
                          )
                        }
                        is("b100".U, "b110".U, "b111".U) {
                          illegal := true.B
                        }
                      }
                    } else {
                      illegal := true.B
                    }
                  }
                  is("b100".U, "b101".U) {
                    illegal := true.B
                  }
                }
              }
            }
          }
          is("b110".U, "b111".U) {
            out := Cat(
              Fill(4, instr_i(12)), instr_i(6, 5), instr_i(2), 0.U(5.W), "b01".U(2.W),
              instr_i(9, 7), 0.U(2.W), instr_i(13), instr_i(11, 10), instr_i(4, 3),
              instr_i(12), IbexPkg.Opcode.BRANCH
            )
          }
        }
      }

      is("b10".U) {
        switch(instr_i(15, 13)) {
          is("b000".U) {
            out := Cat(
              0.U(7.W), instr_i(6, 2), instr_i(11, 7), "b001".U(3.W), instr_i(11, 7),
              IbexPkg.Opcode.OP_IMM
            )
            illegal := instr_i(12).asBool
          }
          is("b010".U) {
            out := Cat(
              0.U(4.W), instr_i(3, 2), instr_i(12), instr_i(6, 4), 0.U(2.W), "h02".U(5.W),
              "b010".U(3.W), instr_i(11, 7), IbexPkg.Opcode.LOAD
            )
            illegal := instr_i(11, 7) === 0.U
          }
          is("b100".U) {
            when(!instr_i(12).asBool && instr_i(6, 2) =/= 0.U) {
              out := Cat(0.U(7.W), instr_i(6, 2), 0.U(5.W), "b000".U(3.W), instr_i(11, 7), IbexPkg.Opcode.OP)
            }.elsewhen(!instr_i(12).asBool) {
              out := Cat(0.U(12.W), instr_i(11, 7), "b000".U(3.W), 0.U(5.W), IbexPkg.Opcode.JALR)
              illegal := instr_i(11, 7) === 0.U
            }.elsewhen(instr_i(6, 2) =/= 0.U) {
              out := Cat(0.U(7.W), instr_i(6, 2), instr_i(11, 7), "b000".U(3.W), instr_i(11, 7), IbexPkg.Opcode.OP)
            }.otherwise {
              when(instr_i(11, 7) === 0.U) {
                out := "h00100073".U(32.W)
              }.otherwise {
                out := Cat(0.U(12.W), instr_i(11, 7), "b000".U(3.W), "h01".U(5.W), IbexPkg.Opcode.JALR)
              }
            }
          }
          is("b101".U) {
            if (zcmpEnabled) {
              switch(instr_i(12, 8)) {
                is("b11000".U) {
                  getsExpanded := INSTR_EXPANDED
                  switch(cm_state_q) {
                    is(CmState.CmIdle) {
                      cm_rlist_d := cm_rlist_init(instr_i(7, 4))
                      out := cm_push_store_reg(cm_rlist_init(instr_i(7, 4)), 1.U(5.W))
                      when(cm_rlist_init(instr_i(7, 4)) <= 3.U) {
                        illegal := true.B
                      }.elsewhen(cm_rlist_init(instr_i(7, 4)) === 4.U) {
                        when(valid_i && id_in_ready_i) {
                          cm_state_d := CmState.CmPushDecrSp
                        }
                      }.otherwise {
                        cm_rlist_d := cm_rlist_init(instr_i(7, 4)) - 1.U
                        cm_sp_offset_d := 2.U
                        when(valid_i && id_in_ready_i) {
                          cm_state_d := CmState.CmPushStoreReg
                        }
                      }
                    }
                    is(CmState.CmPushStoreReg) {
                      out := cm_push_store_reg(cm_rlist_q, cm_sp_offset_q)
                      when(id_in_ready_i) {
                        cm_rlist_d := cm_rlist_q - 1.U
                        cm_sp_offset_d := cm_sp_offset_q + 1.U
                        when(cm_rlist_q === 4.U) {
                          cm_state_d := CmState.CmPushDecrSp
                        }
                      }
                    }
                    is(CmState.CmPushDecrSp) {
                      out := cm_sp_addi(instr_i(7, 4), instr_i(3, 2), true.B)
                      when(id_in_ready_i) {
                        getsExpanded := INSTR_EXPANDED_LAST
                        cm_state_d := CmState.CmIdle
                      }
                    }
                    is(CmState.CmPopLoadReg, CmState.CmPopIncrSp, CmState.CmPopZeroA0, CmState.CmPopRetRa, CmState.CmMvSecondReg) {
                      cm_state_d := CmState.CmIdle
                    }
                  }
                }
                is("b11010".U, "b11100".U, "b11110".U) {
                  getsExpanded := INSTR_EXPANDED
                  switch(cm_state_q) {
                    is(CmState.CmIdle) {
                      val initRlist = cm_rlist_init(instr_i(7, 4))
                      val initOffset = cm_stack_adj_word(instr_i(7, 4), instr_i(3, 2)) - 1.U
                      cm_rlist_d := initRlist
                      cm_sp_offset_d := initOffset
                      out := cm_pop_load_reg(initRlist, initOffset)
                      when(initRlist <= 3.U) {
                        illegal := true.B
                      }.elsewhen(initRlist === 4.U) {
                        when(valid_i && id_in_ready_i) {
                          cm_state_d := CmState.CmPopIncrSp
                        }
                      }.otherwise {
                        cm_rlist_d := initRlist - 1.U
                        cm_sp_offset_d := initOffset - 1.U
                        when(valid_i && id_in_ready_i) {
                          cm_state_d := CmState.CmPopLoadReg
                        }
                      }
                    }
                    is(CmState.CmPopLoadReg) {
                      out := cm_pop_load_reg(cm_rlist_q, cm_sp_offset_q)
                      when(id_in_ready_i) {
                        cm_rlist_d := cm_rlist_q - 1.U
                        cm_sp_offset_d := cm_sp_offset_q - 1.U
                        when(cm_rlist_q === 4.U) {
                          cm_state_d := CmState.CmPopIncrSp
                        }
                      }
                    }
                    is(CmState.CmPopIncrSp) {
                      out := cm_sp_addi(instr_i(7, 4), instr_i(3, 2), false.B)
                      when(id_in_ready_i) {
                        switch(instr_i(12, 8)) {
                          is("b11100".U) {
                            cm_state_d := CmState.CmPopZeroA0
                          }
                          is("b11110".U) {
                            cm_state_d := CmState.CmPopRetRa
                          }
                          is("b11010".U) {
                            getsExpanded := INSTR_EXPANDED_LAST
                            cm_state_d := CmState.CmIdle
                          }
                        }
                      }
                    }
                    is(CmState.CmPopZeroA0) {
                      out := cm_zero_a0()
                      when(id_in_ready_i) {
                        cm_state_d := CmState.CmPopRetRa
                      }
                    }
                    is(CmState.CmPopRetRa) {
                      out := cm_ret_ra()
                      when(id_in_ready_i) {
                        getsExpanded := INSTR_EXPANDED_LAST
                        cm_state_d := CmState.CmIdle
                      }
                    }
                    is(CmState.CmPushStoreReg, CmState.CmPushDecrSp, CmState.CmMvSecondReg) {
                      cm_state_d := CmState.CmIdle
                    }
                  }
                }
                is("b01100".U, "b01101".U, "b01110".U, "b01111".U) {
                  switch(instr_i(6, 5)) {
                    is("b01".U) {
                      getsExpanded := INSTR_EXPANDED
                      switch(cm_state_q) {
                        is(CmState.CmIdle) {
                          out := cm_mvsa01(0.U(1.W), instr_i(9, 7))
                          when(valid_i && id_in_ready_i) {
                            cm_state_d := CmState.CmMvSecondReg
                          }
                        }
                        is(CmState.CmMvSecondReg) {
                          out := cm_mvsa01(1.U(1.W), instr_i(4, 2))
                          when(id_in_ready_i) {
                            getsExpanded := INSTR_EXPANDED_LAST
                            cm_state_d := CmState.CmIdle
                          }
                        }
                        is(CmState.CmPushStoreReg, CmState.CmPushDecrSp, CmState.CmPopLoadReg, CmState.CmPopIncrSp, CmState.CmPopZeroA0, CmState.CmPopRetRa) {
                          cm_state_d := CmState.CmIdle
                        }
                      }
                    }
                    is("b11".U) {
                      getsExpanded := INSTR_EXPANDED
                      switch(cm_state_q) {
                        is(CmState.CmIdle) {
                          out := cm_mva01s(instr_i(9, 7), 0.U(1.W))
                          when(valid_i && id_in_ready_i) {
                            cm_state_d := CmState.CmMvSecondReg
                          }
                        }
                        is(CmState.CmMvSecondReg) {
                          out := cm_mva01s(instr_i(4, 2), 1.U(1.W))
                          when(id_in_ready_i) {
                            getsExpanded := INSTR_EXPANDED_LAST
                            cm_state_d := CmState.CmIdle
                          }
                        }
                        is(CmState.CmPushStoreReg, CmState.CmPushDecrSp, CmState.CmPopLoadReg, CmState.CmPopIncrSp, CmState.CmPopZeroA0, CmState.CmPopRetRa) {
                          cm_state_d := CmState.CmIdle
                        }
                      }
                    }
                    is("b00".U, "b10".U) {
                      illegal := true.B
                    }
                  }
                }
                is("b00000".U, "b00001".U, "b00010".U, "b00011".U, "b00100".U, "b00101".U, "b00110".U, "b00111".U,
                   "b01000".U, "b01001".U, "b01010".U, "b01011".U, "b10000".U, "b10001".U, "b10010".U, "b10011".U,
                   "b10100".U, "b10101".U, "b10110".U, "b10111".U, "b11001".U, "b11011".U, "b11101".U, "b11111".U) {
                  illegal := true.B
                }
              }
            } else {
              illegal := true.B
            }
          }
          is("b110".U) {
            out := Cat(
              0.U(4.W), instr_i(8, 7), instr_i(12), instr_i(6, 2), "h02".U(5.W),
              "b010".U(3.W), instr_i(11, 9), 0.U(2.W), IbexPkg.Opcode.STORE
            )
          }
          is("b001".U, "b011".U, "b111".U) {
            illegal := true.B
          }
        }
      }
    }
  }

  instr_o := out
  illegal_instr_o := illegal
  gets_expanded_o := Mux(zcmpEnabled.B && !valid_i, INSTR_NOT_EXPANDED, getsExpanded)

  cmStateReg := cm_state_d
  cmRlistReg := cm_rlist_d
  cmSpOffsetReg := cm_sp_offset_d

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    assert(valid_i || cm_state_d === cm_state_q,
      "IbexPushPopFSMStable")
  }

  dontTouch(clk_i)
  dontTouch(rst_ni)
  dontTouch(id_in_ready_i)
}
