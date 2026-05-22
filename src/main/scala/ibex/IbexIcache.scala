// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

class IbexIcache(
    iCacheECC: Boolean = false,
    resetAll: Boolean = false,
    busSizeECC: Int = IbexPkg.BUS_SIZE,
    tagSizeECC: Int = IbexPkg.IC_TAG_SIZE,
    lineSizeECC: Int = IbexPkg.IC_LINE_SIZE,
    branchCache: Boolean = false,
    tweakInfection: Boolean = false)
    extends RawModule {
  require(busSizeECC == (if (iCacheECC) IbexPkg.BUS_SIZE + IbexPkg.IC_DATA_ECC_SIZE else IbexPkg.BUS_SIZE),
    "IbexIcache BusSizeECC must match ICacheECC")
  require(tagSizeECC == (if (iCacheECC) IbexPkg.IC_TAG_SIZE + IbexPkg.IC_TAG_ECC_SIZE else IbexPkg.IC_TAG_SIZE),
    "IbexIcache TagSizeECC must match ICacheECC")
  require(lineSizeECC == busSizeECC * IbexPkg.IC_LINE_BEATS,
    "IbexIcache LineSizeECC must match BusSizeECC and cache line beats")

  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val req_i = IO(Input(Bool()))
  val branch_i = IO(Input(Bool()))
  val addr_i = IO(Input(UInt(32.W)))

  val ready_i = IO(Input(Bool()))
  val valid_o = IO(Output(Bool()))
  val rdata_o = IO(Output(UInt(32.W)))
  val addr_o = IO(Output(UInt(32.W)))
  val err_o = IO(Output(Bool()))
  val err_plus2_o = IO(Output(Bool()))

  val instr_req_o = IO(Output(Bool()))
  val instr_gnt_i = IO(Input(Bool()))
  val instr_addr_o = IO(Output(UInt(32.W)))
  val instr_rdata_i = IO(Input(UInt(IbexPkg.BUS_SIZE.W)))
  val instr_err_i = IO(Input(Bool()))
  val instr_rvalid_i = IO(Input(Bool()))

  val ic_tag_req_o = IO(Output(UInt(IbexPkg.IC_NUM_WAYS.W)))
  val ic_tag_write_o = IO(Output(Bool()))
  val ic_tag_addr_o = IO(Output(UInt(IbexPkg.IC_INDEX_W.W)))
  val ic_tag_wdata_o = IO(Output(UInt(tagSizeECC.W)))
  val ic_tag_rdata_i = IO(Input(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W))))
  val ic_data_req_o = IO(Output(UInt(IbexPkg.IC_NUM_WAYS.W)))
  val ic_data_write_o = IO(Output(Bool()))
  val ic_data_addr_o = IO(Output(UInt(IbexPkg.IC_INDEX_W.W)))
  val ic_data_wdata_o = IO(Output(UInt(lineSizeECC.W)))
  val ic_data_rdata_i = IO(Input(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W))))
  val ic_scr_key_valid_i = IO(Input(Bool()))
  val ic_scr_key_req_o = IO(Output(Bool()))

  val icache_enable_i = IO(Input(Bool()))
  val icache_inval_i = IO(Input(Bool()))
  val busy_o = IO(Output(Bool()))
  val ecc_error_o = IO(Output(Bool()))

  private val IC_LINE_W = IbexPkg.IC_LINE_W
  private val BUS_W = IbexPkg.BUS_W
  private val IC_LINE_BEATS = IbexPkg.IC_LINE_BEATS
  private val IC_LINE_BEATS_W = IbexPkg.IC_LINE_BEATS_W
  private val IC_OUTPUT_BEATS = IbexPkg.IC_OUTPUT_BEATS

  val lookup_addr_aligned = Wire(UInt(IbexPkg.ADDR_W.W))
  val prefetch_addr_d = Wire(UInt(IbexPkg.ADDR_W.W))
  val prefetch_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    Reg(UInt(IbexPkg.ADDR_W.W))
  }
  val prefetch_addr_en = Wire(Bool())
  val lookup_req_ic0 = Wire(Bool())
  val lookup_addr_ic0 = Wire(UInt(IbexPkg.ADDR_W.W))
  val lookup_grant_ic0 = Wire(Bool())
  val lookup_actual_ic0 = Wire(Bool())

  val instr_req = Wire(Bool())
  val instr_addr = Wire(UInt((IbexPkg.ADDR_W - BUS_W).W))

  val skid_complete_instr = Wire(Bool())
  val skid_ready = Wire(Bool())
  val output_compressed = Wire(Bool())
  val skid_valid_d = Wire(Bool())
  val skid_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val skid_en = Wire(Bool())
  val skid_data_d = Wire(UInt(16.W))
  val skid_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(16.W)) else Reg(UInt(16.W))
  }
  val skid_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(false.B) else Reg(Bool())
  }
  val output_valid = Wire(Bool())
  val addr_incr_two = Wire(Bool())
  val output_addr_en = Wire(Bool())
  val output_addr_incr = Wire(UInt((IbexPkg.ADDR_W - 1).W))
  val output_addr_d = Wire(UInt((IbexPkg.ADDR_W - 1).W))
  val output_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U((IbexPkg.ADDR_W - 1).W)) else Reg(UInt((IbexPkg.ADDR_W - 1).W))
  }
  val output_data_lo = Wire(UInt(16.W))
  val output_data_hi = Wire(UInt(16.W))
  val data_valid = Wire(Bool())
  val output_ready = Wire(Bool())
  val output_data = Wire(UInt(32.W))
  val output_err = Wire(Bool())
  val output_rdata = Wire(UInt(32.W))
  val output_err_plus2 = Wire(Bool())
  val cache_lookup_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val lookup_pending_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val cache_lookup_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.ADDR_W.W)) else Reg(UInt(IbexPkg.ADDR_W.W))
  }
  val cache_fill_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.ADDR_W.W)) else Reg(UInt(IbexPkg.ADDR_W.W))
  }
  val cache_fill_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.IC_LINE_SIZE.W)) else Reg(UInt(IbexPkg.IC_LINE_SIZE.W))
  }
  val cache_line_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.IC_LINE_SIZE.W)) else Reg(UInt(IbexPkg.IC_LINE_SIZE.W))
  }
  val cache_line_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U((IbexPkg.ADDR_W - 1).W)) else Reg(UInt((IbexPkg.ADDR_W - 1).W))
  }
  val cache_line_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val cache_fill_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val cache_fill_way_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(1.U(IbexPkg.IC_NUM_WAYS.W))
  }
  val cache_miss_state_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U(3.W))
  }
  val cache_tag_match = Wire(Bool())
  val tag_match_ic1 = Wire(Vec(IbexPkg.IC_NUM_WAYS, Bool()))
  val tag_invalid_ic1 = Wire(Vec(IbexPkg.IC_NUM_WAYS, Bool()))
  val tag_err_ic1 = Wire(Vec(IbexPkg.IC_NUM_WAYS, Bool()))
  val lowest_invalid_way_ic1 = Wire(UInt(IbexPkg.IC_NUM_WAYS.W))
  val round_robin_way_ic1 = Wire(UInt(IbexPkg.IC_NUM_WAYS.W))
  val round_robin_way_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(1.U(IbexPkg.IC_NUM_WAYS.W))
  }
  val sel_way_ic1 = Wire(UInt(IbexPkg.IC_NUM_WAYS.W))
  val cache_hit_line = Wire(UInt(IbexPkg.IC_LINE_SIZE.W))
  val cache_data_err = Wire(Bool())
  val cache_hit_valid = Wire(Bool())
  val cache_miss_start = Wire(Bool())
  val cache_miss_req = Wire(Bool())
  val cache_miss_addr = Wire(UInt(32.W))
  val cache_write_req = Wire(Bool())
  val fill_cache_new = Wire(Bool())
  val fill_cache_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val cache_fill_stale_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val cache_lookup_req = Wire(Bool())
  val cache_ram_lookup_addr = Wire(UInt(IbexPkg.ADDR_W.W))
  val cache_spec_req = Wire(Bool())
  val cache_spec_gnt_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val cache_bypass_pending_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val bypass_discard_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val bypass_replay_valid_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(false.B)
  }
  val branch_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U(32.W))
  }
  val bypass_addr_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.ADDR_W.W)) else Reg(UInt(IbexPkg.ADDR_W.W))
  }
  val bypass_replay_data_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.BUS_SIZE.W)) else Reg(UInt(IbexPkg.BUS_SIZE.W))
  }
  val bypass_replay_err_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(false.B) else Reg(Bool())
  }
  val cache_cnt_q = if (branchCache) {
    Some(withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
      RegInit(0.U(2.W))
    })
  } else {
    None
  }

  val inval_state_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U(2.W))
  }
  val inval_state_d = Wire(UInt(2.W))
  val inval_index_q = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    if (resetAll) RegInit(0.U(IbexPkg.IC_INDEX_W.W)) else Reg(UInt(IbexPkg.IC_INDEX_W.W))
  }
  val inval_index_d = Wire(UInt(IbexPkg.IC_INDEX_W.W))
  val inval_index_en = Wire(Bool())
  val inval_write_req = Wire(Bool())
  val inval_block_cache = Wire(Bool())
  val inval_active = Wire(Bool())

  val data_tweak_lw_ic0 = Wire(UInt(lineSizeECC.W))
  val data_tweak_lw_ic1 = Wire(UInt(lineSizeECC.W))
  val tag_tweak_lw_ic0 = Wire(UInt(tagSizeECC.W))
  val tag_tweak_lw_ic1 = Wire(UInt(tagSizeECC.W))
  val tag_rdata_ic1 = Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(tagSizeECC.W)))
  val data_rdata_ic1 = Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(lineSizeECC.W)))

  private def repeatedTweak(base: UInt, outWidth: Int, step: Int): UInt = {
    (0 until IC_LINE_BEATS).map { i =>
      (base.pad(outWidth) << (i * step))(outWidth - 1, 0)
    }.reduce(_ | _)
  }

  lookup_addr_aligned := Cat(lookup_addr_ic0(IbexPkg.ADDR_W - 1, IC_LINE_W), 0.U(IC_LINE_W.W))
  val prefetch_addr_incr = if (branchCache) {
    lookup_addr_aligned + (1.U << IC_LINE_W)
  } else {
    Mux(icache_enable_i, lookup_addr_aligned + (1.U << IC_LINE_W), lookup_addr_ic0 + (1.U << BUS_W))
  }
  prefetch_addr_d := Mux(
    lookup_grant_ic0,
    prefetch_addr_incr,
    addr_i)
  prefetch_addr_en := branch_i || lookup_grant_ic0
  when(prefetch_addr_en) {
    prefetch_addr_q := prefetch_addr_d
  }
  when(branch_i) {
    branch_addr_q := addr_i
  }

  val bypass_replay_flush = branch_i && !icache_enable_i && bypass_replay_valid_q
  lookup_req_ic0 := {
    if (branchCache) {
      req_i && !inval_block_cache && !bypass_replay_flush
    } else {
      req_i && !inval_block_cache && !lookup_pending_q && !cache_lookup_valid_q &&
        !cache_bypass_pending_q && !bypass_replay_flush && cache_miss_state_q === 0.U
    }
  }
  lookup_addr_ic0 := Mux(branch_i, addr_i, prefetch_addr_q)
  lookup_grant_ic0 := lookup_req_ic0
  lookup_actual_ic0 := lookup_grant_ic0 && !icache_enable_i
  cache_lookup_req := lookup_grant_ic0 && icache_enable_i && !inval_block_cache && cache_miss_state_q === 0.U
  if (branchCache) {
    val cacheCntDec = lookup_grant_ic0 && cache_cnt_q.get.orR
    val cacheCntD = Mux(branch_i, 2.U(2.W), cache_cnt_q.get - cacheCntDec)
    cache_cnt_q.get := cacheCntD
    fill_cache_new := (branch_i || cache_cnt_q.get.orR) && icache_enable_i && !inval_block_cache
  } else {
    fill_cache_new := icache_enable_i && !inval_block_cache
  }
  when(lookup_grant_ic0) {
    cache_lookup_addr_q := lookup_addr_ic0
    cache_fill_addr_q := Cat(lookup_addr_ic0(IbexPkg.ADDR_W - 1, IC_LINE_W), 0.U(IC_LINE_W.W))
    fill_cache_q := fill_cache_new
  }
  cache_lookup_valid_q := cache_lookup_req

  val tag_req_ic0 = inval_write_req || cache_lookup_req || cache_write_req
  val data_req_ic0 = cache_lookup_req || cache_write_req
  val tag_index_ic0 = Mux(
    inval_write_req,
    inval_index_q,
    Mux(cache_write_req, cache_fill_addr_q(IbexPkg.IC_INDEX_HI, IC_LINE_W), lookup_addr_ic0(IbexPkg.IC_INDEX_HI, IC_LINE_W))
  )
  val data_address_ic0 = Mux(inval_write_req, 0.U(IbexPkg.ADDR_W.W), Mux(cache_write_req, cache_fill_addr_q, lookup_addr_ic0))
  val data_tweak_ic0 = data_address_ic0(IbexPkg.ADDR_W - 1, IC_LINE_W)
  val data_tweak_ic1 = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U((IbexPkg.ADDR_W - IC_LINE_W).W))
  }
  val tag_index_ic1 = withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    RegInit(0.U(IbexPkg.IC_INDEX_W.W))
  }
  when(data_req_ic0) {
    data_tweak_ic1 := data_tweak_ic0
  }
  when(tag_req_ic0) {
    tag_index_ic1 := tag_index_ic0
  }
  if (tweakInfection) {
    data_tweak_lw_ic0 := repeatedTweak(Cat(data_tweak_ic0, 0.U(IC_LINE_W.W)), lineSizeECC, busSizeECC)
    data_tweak_lw_ic1 := repeatedTweak(Cat(data_tweak_ic1, 0.U(IC_LINE_W.W)), lineSizeECC, busSizeECC)
    tag_tweak_lw_ic0 := repeatedTweak(tag_index_ic0, tagSizeECC, IbexPkg.IC_INDEX_W + (if (iCacheECC) IbexPkg.IC_TAG_ECC_SIZE else 0))
    tag_tweak_lw_ic1 := repeatedTweak(tag_index_ic1, tagSizeECC, IbexPkg.IC_INDEX_W + (if (iCacheECC) IbexPkg.IC_TAG_ECC_SIZE else 0))
  } else {
    data_tweak_lw_ic0 := 0.U
    data_tweak_lw_ic1 := 0.U
    tag_tweak_lw_ic0 := 0.U
    tag_tweak_lw_ic1 := 0.U
  }
  for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
    tag_rdata_ic1(way) := ic_tag_rdata_i(way) ^ tag_tweak_lw_ic1
    data_rdata_ic1(way) := ic_data_rdata_i(way) ^ data_tweak_lw_ic1
  }

  cache_spec_req := cache_lookup_req && branch_i
  when(cache_spec_req && instr_gnt_i) {
    cache_spec_gnt_q := true.B
  }.elsewhen(cache_miss_state_q === 2.U && instr_rvalid_i) {
    cache_spec_gnt_q := false.B
  }.elsewhen(cache_hit_valid) {
    cache_spec_gnt_q := false.B
  }

  val cache_tag_expected = Cat(1.U(1.W), cache_lookup_addr_q(IbexPkg.ADDR_W - 1, IbexPkg.IC_INDEX_HI + 1))
  val way_hit_data = Wire(Vec(IbexPkg.IC_NUM_WAYS, UInt(IbexPkg.IC_LINE_SIZE.W)))
  val way_data_err = Wire(Vec(IbexPkg.IC_NUM_WAYS, Bool()))
  for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
    val tag_data = Wire(UInt(IbexPkg.IC_TAG_SIZE.W))
    if (iCacheECC) {
      val tagDec = Module(new PrimSecdedInv2822Dec)
      tagDec.data_i := Cat(
        tag_rdata_ic1(way)(tagSizeECC - 1, IbexPkg.IC_TAG_SIZE),
        0.U((22 - IbexPkg.IC_TAG_SIZE).W),
        tag_rdata_ic1(way)(IbexPkg.IC_TAG_SIZE - 1, 0))
      tag_data := tagDec.data_o(IbexPkg.IC_TAG_SIZE - 1, 0)
      tag_err_ic1(way) := tagDec.err_o.orR

      val dataErrs = Wire(Vec(IC_LINE_BEATS, Bool()))
      val dataWords = Wire(Vec(IC_LINE_BEATS, UInt(IbexPkg.BUS_SIZE.W)))
      for (beat <- 0 until IC_LINE_BEATS) {
        val dataDec = Module(new PrimSecdedInv3932Dec)
        dataDec.data_i := data_rdata_ic1(way)(beat * busSizeECC + busSizeECC - 1, beat * busSizeECC)
        dataWords(beat) := dataDec.data_o
        dataErrs(beat) := dataDec.err_o.orR
      }
      way_hit_data(way) := Cat(dataWords.reverse)
      way_data_err(way) := dataErrs.asUInt.orR
    } else {
      tag_data := tag_rdata_ic1(way)(IbexPkg.IC_TAG_SIZE - 1, 0)
      tag_err_ic1(way) := false.B
      way_hit_data(way) := data_rdata_ic1(way)(IbexPkg.IC_LINE_SIZE - 1, 0)
      way_data_err(way) := false.B
    }
    tag_match_ic1(way) := tag_data === cache_tag_expected
    tag_invalid_ic1(way) := !tag_data(IbexPkg.IC_TAG_SIZE - 1)
  }

  cache_tag_match := tag_match_ic1.asUInt.orR
  cache_hit_line := 0.U
  cache_data_err := false.B
  for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
    when(tag_match_ic1(way)) {
      cache_hit_line := way_hit_data(way)
      cache_data_err := way_data_err(way)
    }
  }
  lowest_invalid_way_ic1 := 0.U
  for (way <- 0 until IbexPkg.IC_NUM_WAYS) {
    val lowerInvalid = if (way == 0) false.B else (0 until way).map(tag_invalid_ic1(_)).reduce(_ || _)
    when(tag_invalid_ic1(way) && !lowerInvalid) {
      lowest_invalid_way_ic1 := (1 << way).U(IbexPkg.IC_NUM_WAYS.W)
    }
  }
  round_robin_way_ic1 := Cat(round_robin_way_q(IbexPkg.IC_NUM_WAYS - 2, 0), round_robin_way_q(IbexPkg.IC_NUM_WAYS - 1))
  when(cache_lookup_valid_q) {
    round_robin_way_q := round_robin_way_ic1
  }
  sel_way_ic1 := Mux(tag_invalid_ic1.asUInt.orR, lowest_invalid_way_ic1, round_robin_way_q)

  val cache_tag_err = tag_err_ic1.asUInt.orR
  cache_hit_valid := cache_lookup_valid_q && cache_tag_match && !cache_tag_err && !cache_data_err && cache_miss_state_q === 0.U
  cache_miss_start := cache_lookup_valid_q && (!cache_tag_match || cache_tag_err || cache_data_err) && cache_miss_state_q === 0.U
  val cache_spec_rsp = cache_miss_start && cache_spec_gnt_q && instr_rvalid_i

  val cache_lookup_error = cache_lookup_valid_q && (cache_tag_err || cache_data_err)
  val output_word_addr = output_addr_q(IbexPkg.ADDR_W - 2, BUS_W - 1)
  val output_data_word_addr = output_word_addr + skid_valid_q.asUInt
  val output_data_line_addr = output_data_word_addr(IbexPkg.ADDR_W - BUS_W - 1, IC_LINE_W - BUS_W)
  val cache_line_addr_match = icache_enable_i && cache_line_valid_q && !cache_lookup_error &&
    (cache_line_addr_q(IbexPkg.ADDR_W - 2, IC_LINE_W - 1) === output_data_line_addr)
  val cache_line_data = Mux(cache_hit_valid, cache_hit_line, cache_line_data_q)
  val cache_line_beat = output_addr_q(IC_LINE_W - 2, BUS_W - 1) + skid_valid_q.asUInt
  val cache_line_word = Wire(UInt(32.W))
  cache_line_word := 0.U
  for (beat <- 0 until IC_OUTPUT_BEATS) {
    when(cache_line_beat === beat.U) {
      cache_line_word := cache_line_data(beat * 32 + 31, beat * 32)
    }
  }
  val cache_line_switch = cache_hit_valid || cache_write_req
  val cache_line_switch_diff = cache_line_switch &&
    (cache_line_addr_q(IbexPkg.ADDR_W - 2, IC_LINE_W - 1) =/= output_addr_q(IbexPkg.ADDR_W - 2, IC_LINE_W - 1))
  val first_fill_word_addr = cache_fill_addr_q(IbexPkg.ADDR_W - 1, BUS_W)
  val second_fill_word_addr = (cache_fill_addr_q + (1.U << BUS_W))(IbexPkg.ADDR_W - 1, BUS_W)
  val fill_rsp_word_addr = Mux(cache_miss_state_q === 4.U, second_fill_word_addr, first_fill_word_addr)
  val cache_fill_stale = cache_fill_stale_q || (branch_i && (cache_miss_state_q =/= 0.U || cache_lookup_valid_q))
  val fill_rsp_valid = instr_rvalid_i && !cache_fill_stale &&
    (cache_spec_rsp || cache_miss_state_q === 2.U || cache_miss_state_q === 4.U)
  val fill_rsp_matches_output = fill_rsp_word_addr === output_data_word_addr
  val fill_first_replay_valid = (cache_miss_state_q === 3.U || cache_miss_state_q === 4.U || cache_miss_state_q === 5.U) &&
    first_fill_word_addr === output_data_word_addr
  val fill_second_replay_valid = cache_miss_state_q === 5.U && second_fill_word_addr === output_data_word_addr
  val fill_replay_valid = !cache_fill_stale && (fill_first_replay_valid || fill_second_replay_valid)
  val fill_replay_data = Mux(fill_second_replay_valid, cache_fill_data_q(63, 32), cache_fill_data_q(31, 0))
  val bypass_rsp_matches_output = bypass_addr_q(IbexPkg.ADDR_W - 1, BUS_W) === output_data_word_addr
  val bypass_rsp_discard = instr_rvalid_i && cache_bypass_pending_q &&
    (bypass_discard_q || branch_i || !bypass_rsp_matches_output)
  val bypass_rsp_valid = instr_rvalid_i && cache_bypass_pending_q && !bypass_discard_q &&
    !branch_i && bypass_rsp_matches_output
  val bypass_rsp_to_replay = bypass_rsp_valid && output_valid && !ready_i
  val bypass_replay_data_valid = bypass_replay_valid_q && !branch_i
  val bus_data_valid = bypass_rsp_valid && !skid_complete_instr && !bypass_replay_valid_q
  val fill_data_valid = if (branchCache) {
    fill_rsp_valid
  } else {
    fill_rsp_valid && fill_rsp_matches_output && !skid_complete_instr
  }
  when(cache_miss_start) {
    cache_fill_data_q := Mux(cache_spec_rsp, Cat(0.U(32.W), instr_rdata_i), 0.U)
    cache_fill_err_q := Mux(cache_spec_rsp, instr_err_i, false.B)
    cache_fill_way_q := sel_way_ic1
    cache_miss_state_q := Mux(cache_spec_gnt_q, Mux(cache_spec_rsp, 3.U, 2.U), 1.U)
  }.elsewhen(cache_miss_state_q === 1.U && instr_gnt_i) {
    cache_miss_state_q := 2.U
  }.elsewhen(cache_miss_state_q === 2.U && instr_rvalid_i) {
    cache_fill_data_q := Cat(0.U(32.W), instr_rdata_i)
    cache_fill_err_q := instr_err_i
    cache_miss_state_q := 3.U
  }.elsewhen(cache_miss_state_q === 3.U && instr_gnt_i) {
    cache_miss_state_q := 4.U
  }.elsewhen(cache_miss_state_q === 4.U && instr_rvalid_i) {
    cache_fill_data_q := Cat(instr_rdata_i, cache_fill_data_q(31, 0))
    cache_fill_err_q := cache_fill_err_q || instr_err_i
    cache_miss_state_q := 5.U
  }.elsewhen(cache_miss_state_q === 5.U) {
    cache_miss_state_q := 0.U
  }
  when(cache_miss_start) {
    cache_fill_stale_q := branch_i
  }.elsewhen(branch_i && (cache_miss_state_q =/= 0.U || cache_lookup_valid_q)) {
    cache_fill_stale_q := true.B
  }.elsewhen(cache_miss_state_q === 5.U) {
    cache_fill_stale_q := false.B
  }
  when(cache_hit_valid) {
    cache_line_data_q := cache_hit_line
    cache_line_addr_q := cache_lookup_addr_q(IbexPkg.ADDR_W - 1, 1)
    cache_line_valid_q := true.B
  }.elsewhen(cache_write_req) {
    cache_line_data_q := cache_fill_data_q
    cache_line_addr_q := cache_fill_addr_q(IbexPkg.ADDR_W - 1, 1)
    cache_line_valid_q := true.B
  }.elsewhen(cache_lookup_error) {
    cache_line_valid_q := false.B
  }

  cache_miss_req := cache_miss_state_q === 1.U || cache_miss_state_q === 3.U
  cache_miss_addr := Mux(cache_miss_state_q === 3.U, cache_fill_addr_q + 4.U, cache_fill_addr_q)
  cache_write_req := cache_miss_state_q === 5.U && !cache_fill_stale_q && !cache_fill_err_q && fill_cache_q

  when(lookup_grant_ic0 && !inval_block_cache) {
    lookup_pending_q := true.B
  }
  when(cache_miss_start || cache_hit_valid || bypass_rsp_valid || fill_rsp_valid || bypass_rsp_discard ||
      (branch_i && !lookup_grant_ic0)) {
    lookup_pending_q := false.B
  }
  when(bypass_rsp_discard) {
    prefetch_addr_q := Cat(output_data_word_addr, 0.U(BUS_W.W))
  }

  instr_req := lookup_actual_ic0 || cache_spec_req || cache_miss_req
  instr_addr := Mux(cache_miss_req, cache_miss_addr(IbexPkg.ADDR_W - 1, BUS_W), lookup_addr_ic0(IbexPkg.ADDR_W - 1, BUS_W))
  instr_req_o := instr_req
  instr_addr_o := Cat(instr_addr, 0.U(BUS_W.W))

  when(lookup_actual_ic0) {
    cache_bypass_pending_q := true.B
    bypass_addr_q := lookup_addr_ic0
  }.elsewhen(bypass_rsp_valid || bypass_rsp_discard) {
    cache_bypass_pending_q := false.B
  }
  when(bypass_rsp_discard) {
    bypass_discard_q := false.B
  }.elsewhen(branch_i && !icache_enable_i && !lookup_grant_ic0) {
    bypass_discard_q := true.B
  }
  when(branch_i) {
    bypass_replay_valid_q := false.B
  }.elsewhen(bypass_rsp_to_replay) {
    bypass_replay_valid_q := true.B
    bypass_replay_data_q := instr_rdata_i
    bypass_replay_err_q := instr_err_i
  }.elsewhen(bypass_replay_valid_q && output_ready) {
    bypass_replay_valid_q := false.B
  }
  when(bypass_replay_valid_q && output_ready) {
    lookup_pending_q := false.B
  }

  output_data := Mux(
    bypass_replay_data_valid,
    bypass_replay_data_q,
    Mux(bus_data_valid || fill_data_valid,
    instr_rdata_i,
    Mux(fill_replay_valid, fill_replay_data, cache_line_word)))
  output_err := Mux(
    bypass_replay_data_valid,
    bypass_replay_err_q,
    Mux(bus_data_valid || fill_data_valid,
    instr_err_i,
    false.B))
  data_valid := cache_hit_valid || cache_line_addr_match || bus_data_valid || bypass_replay_data_valid || fill_data_valid || fill_replay_valid

  skid_data_d := output_data(31, 16)
  skid_en := data_valid && (ready_i || skid_ready)
  when(skid_en) {
    skid_data_q := skid_data_d
    skid_err_q := output_err
  }

  skid_complete_instr := skid_valid_q && ((skid_data_q(1, 0) =/= "b11".U) || skid_err_q)
  skid_ready := output_addr_q(0) && !skid_valid_q && (!output_compressed || output_err)
  output_ready := (ready_i || skid_ready) && !skid_complete_instr
  output_compressed := rdata_o(1, 0) =/= "b11".U

  skid_valid_d := Mux(
    branch_i,
    false.B,
    Mux(
      cache_line_switch_diff,
      false.B,
    Mux(
      skid_valid_q,
      !(ready_i && ((skid_data_q(1, 0) =/= "b11".U) || skid_err_q)),
      data_valid && (
        (output_addr_q(0) && (!output_compressed || output_err)) ||
          (!output_addr_q(0) && output_compressed && !output_err && ready_i)
      )
    ))
  )
  skid_valid_q := skid_valid_d

  output_valid := skid_complete_instr ||
    (data_valid && (!output_addr_q(0) || skid_valid_q || output_err || (output_data(17, 16) =/= "b11".U)))

  output_addr_en := branch_i || (ready_i && valid_o)
  addr_incr_two := output_compressed && !err_o
  output_addr_incr := output_addr_q + Cat(0.U(28.W), !addr_incr_two, addr_incr_two)
  output_addr_d := Mux(branch_i, addr_i(31, 1), output_addr_incr)
  when(output_addr_en) {
    output_addr_q := output_addr_d
  }
  when(output_addr_en && !branch_i && icache_enable_i &&
    (output_addr_d(IbexPkg.ADDR_W - 2, IC_LINE_W - 1) =/= output_addr_q(IbexPkg.ADDR_W - 2, IC_LINE_W - 1))) {
    lookup_pending_q := false.B
    prefetch_addr_q := Cat(output_addr_d, 0.U(1.W))
  }

  output_data_lo := 0.U
  for (i <- 0 until IC_OUTPUT_BEATS) {
    when(output_addr_q(BUS_W - 2, 0) === i.U((BUS_W - 1).W)) {
      output_data_lo := output_data(16 * i + 15, 16 * i)
    }
  }

  output_data_hi := 0.U
  for (i <- 0 until IC_OUTPUT_BEATS - 1) {
    when(output_addr_q(BUS_W - 2, 0) === i.U((BUS_W - 1).W)) {
      output_data_hi := output_data(16 * (i + 1) + 15, 16 * (i + 1))
    }
  }
  when(output_addr_q(BUS_W - 2, 0).andR) {
    output_data_hi := output_data(15, 0)
  }

  output_rdata := Cat(output_data_hi, Mux(skid_valid_q, skid_data_q, output_data_lo))
  output_err_plus2 := skid_valid_q && !skid_err_q

  valid_o := output_valid
  rdata_o := output_rdata
  addr_o := Cat(output_addr_q, 0.U(1.W))
  err_o := (skid_valid_q && skid_err_q) || (!skid_complete_instr && output_err)
  err_plus2_o := output_err_plus2

  inval_state_d := inval_state_q
  inval_index_d := inval_index_q
  inval_index_en := false.B
  inval_write_req := false.B
  ic_scr_key_req_o := false.B
  inval_block_cache := true.B
  switch(inval_state_q) {
    is(0.U) {
      inval_state_d := 1.U
      when(!ic_scr_key_valid_i) {
        ic_scr_key_req_o := true.B
      }
    }
    is(1.U) {
      when(ic_scr_key_valid_i) {
        inval_state_d := 2.U
        inval_index_d := 0.U
        inval_index_en := true.B
      }
    }
    is(2.U) {
      inval_write_req := true.B
      inval_index_d := inval_index_q + 1.U
      inval_index_en := true.B
      when(icache_inval_i) {
        ic_scr_key_req_o := true.B
        inval_state_d := 1.U
      }.elsewhen(inval_index_q.andR) {
        inval_state_d := 3.U
      }
    }
    is(3.U) {
      when(icache_inval_i) {
        ic_scr_key_req_o := true.B
        inval_state_d := 1.U
      }.otherwise {
        inval_block_cache := false.B
      }
    }
  }
  inval_active := inval_state_q =/= 3.U
  inval_state_q := inval_state_d
  when(inval_index_en) {
    inval_index_q := inval_index_d
  }

  ic_tag_req_o := Mux(
    tag_req_ic0,
    Mux(cache_write_req, cache_fill_way_q, Fill(IbexPkg.IC_NUM_WAYS, true.B)),
    0.U
  )
  ic_tag_write_o := inval_write_req || cache_write_req
  cache_ram_lookup_addr := Mux(cache_lookup_valid_q, cache_lookup_addr_q, lookup_addr_ic0)
  ic_tag_addr_o := Mux(
    inval_write_req,
    inval_index_q,
    Mux(cache_write_req, cache_fill_addr_q(IbexPkg.IC_INDEX_HI, IC_LINE_W), cache_ram_lookup_addr(IbexPkg.IC_INDEX_HI, IC_LINE_W))
  )
  val cache_tag_wdata = Mux(
    cache_write_req,
    Cat(1.U(1.W), cache_fill_addr_q(IbexPkg.ADDR_W - 1, IbexPkg.IC_INDEX_HI + 1)),
    0.U(IbexPkg.IC_TAG_SIZE.W))
  val tag_wdata_ecc = Wire(UInt(tagSizeECC.W))
  val data_wdata_ecc = Wire(UInt(lineSizeECC.W))
  if (iCacheECC) {
    val tagEnc = Module(new PrimSecdedInv2822Enc)
    tagEnc.data_i := Cat(0.U((22 - IbexPkg.IC_TAG_SIZE).W), cache_tag_wdata)
    tag_wdata_ecc := Cat(tagEnc.data_o(27, 22), tagEnc.data_o(IbexPkg.IC_TAG_SIZE - 1, 0))

    val dataEncoded = Wire(Vec(IC_LINE_BEATS, UInt(busSizeECC.W)))
    for (beat <- 0 until IC_LINE_BEATS) {
      val dataEnc = Module(new PrimSecdedInv3932Enc)
      dataEnc.data_i := cache_fill_data_q(beat * IbexPkg.BUS_SIZE + IbexPkg.BUS_SIZE - 1, beat * IbexPkg.BUS_SIZE)
      dataEncoded(beat) := dataEnc.data_o
    }
    data_wdata_ecc := Cat(dataEncoded.reverse)
  } else {
    tag_wdata_ecc := cache_tag_wdata
    data_wdata_ecc := cache_fill_data_q
  }
  ic_tag_wdata_o := tag_wdata_ecc ^ tag_tweak_lw_ic0
  ic_data_req_o := Mux(data_req_ic0, Mux(cache_write_req, cache_fill_way_q, Fill(IbexPkg.IC_NUM_WAYS, true.B)), 0.U)
  ic_data_write_o := cache_write_req
  ic_data_addr_o := Mux(cache_write_req, cache_fill_addr_q(IbexPkg.IC_INDEX_HI, IC_LINE_W), cache_ram_lookup_addr(IbexPkg.IC_INDEX_HI, IC_LINE_W))
  ic_data_wdata_o := data_wdata_ecc ^ data_tweak_lw_ic0

  busy_o := inval_active || instr_req_o || cache_miss_state_q =/= 0.U
  ecc_error_o := iCacheECC.B && cache_lookup_valid_q && (cache_tag_err || (cache_tag_match && cache_data_err))

  dontTouch(ic_tag_rdata_i)
  dontTouch(ic_data_rdata_i)
  dontTouch(instr_gnt_i)
}
