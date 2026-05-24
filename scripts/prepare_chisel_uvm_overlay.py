#!/usr/bin/env python3
"""Create a core_ibex UVM overlay that compiles Chisel-emitted Ibex RTL."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


RTL_MARKER = "// ibex CORE RTL files"
DV_MARKER = "// Core DV files"
SV_INTERNAL_FCOV_BIND = "${PRJ_DIR}/dv/uvm/core_ibex/fcov/core_ibex_fcov_bind.sv"
ORIGINAL_TB_TOP = "${PRJ_DIR}/dv/uvm/core_ibex/tb/core_ibex_tb_top.sv"
SV_ONLY_PRIM_COUNT = "${LOWRISC_IP_DIR}/ip/prim/rtl/prim_count.sv"


def read_generated_filelist(chisel_dir: Path) -> list[str]:
    filelist = chisel_dir / "filelist.f"
    files: list[str] = []
    for raw in filelist.read_text(encoding="ascii").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line in {"ibex_simple_system.sv", "ibex_riscv_compliance.sv"}:
            continue
        files.append(line)
    return files


def rewrite_filelist(original: Path, chisel_dir: Path, out_file: Path) -> None:
    lines = original.read_text(encoding="ascii").splitlines()
    try:
        rtl_start = lines.index(RTL_MARKER)
        dv_start = lines.index(DV_MARKER)
    except ValueError as err:
        raise RuntimeError(f"Could not find RTL markers in {original}") from err

    chisel_files = read_generated_filelist(chisel_dir)
    replacement = [
        RTL_MARKER,
        f"+incdir+{chisel_dir}",
        "${PRJ_DIR}/rtl/ibex_pkg.sv",
        "${PRJ_DIR}/rtl/ibex_tracer_pkg.sv",
    ]
    replacement.extend(str((chisel_dir / path).resolve()) for path in chisel_files)
    replacement.append(str((chisel_dir / "ibex_top_tracing_chisel_wrapper.sv").resolve()))
    replacement.append("")

    dv_lines = []
    for line in lines[dv_start:]:
        if line.strip() == SV_ONLY_PRIM_COUNT:
            dv_lines.extend([
                "// Chisel lockstep does not instantiate lowRISC prim_count; compiling it as",
                "// an otherwise uninstantiated VCS top trips its standalone AssertConnected_A.",
                f"// {SV_ONLY_PRIM_COUNT}",
            ])
        elif line.strip() == SV_INTERNAL_FCOV_BIND:
            dv_lines.extend([
                "// Chisel RTL does not preserve the original ibex_core internal hierarchy",
                "// required by this functional coverage bind.",
                f"// {SV_INTERNAL_FCOV_BIND}",
            ])
        elif line.strip() == ORIGINAL_TB_TOP:
            dv_lines.append("${IBEX_UVM_CORE_IBEX_DIR}/tb/core_ibex_tb_top.sv")
        else:
            dv_lines.append(line)

    prefix_lines = []
    for line in lines[:rtl_start]:
        if line.strip() == SV_ONLY_PRIM_COUNT:
            prefix_lines.extend([
                "// Chisel lockstep does not instantiate lowRISC prim_count; compiling it as",
                "// an otherwise uninstantiated VCS top trips its standalone AssertConnected_A.",
                f"// {SV_ONLY_PRIM_COUNT}",
            ])
        else:
            prefix_lines.append(line)

    out_lines = prefix_lines + replacement + dv_lines
    out_file.write_text("\n".join(out_lines) + "\n", encoding="ascii")


def write_patched_tb_top(original: Path, out_file: Path) -> None:
    text = original.read_text(encoding="ascii")
    text = text.replace(
        """  `DV_ASSERT_CTRL("tb_no_spurious_response",
    core_ibex_tb_top.dut.u_ibex_top.u_ibex_core.NoMemResponseWithoutPendingAccess)
  `DV_ASSERT_CTRL("tb_no_spurious_response",
    core_ibex_tb_top.dut.u_ibex_top.MaxOutstandingDSideAccessesCorrect)
  `DV_ASSERT_CTRL("tb_no_spurious_response",
    core_ibex_tb_top.dut.u_ibex_top.PendingAccessTrackingCorrect)

  if (SecureIbex) begin : g_lockstep_assert_ctrl
    `define IBEX_LOCKSTEP_PATH core_ibex_tb_top.dut.u_ibex_top.gen_lockstep.u_ibex_lockstep
    `DV_ASSERT_CTRL("tb_no_spurious_response",
      `IBEX_LOCKSTEP_PATH.u_shadow_core.NoMemResponseWithoutPendingAccess)
  end

`ifndef DV_FCOV_DISABLE
  assign dut.u_ibex_top.u_ibex_core.u_fcov_bind.rf_glitch_err =
    dut.u_ibex_top.alert_major_internal_o;
  assign dut.u_ibex_top.u_ibex_core.u_fcov_bind.lockstep_glitch_err =
    dut.u_ibex_top.lockstep_alert_major_internal;
`endif
""",
        """  // These assertion and functional coverage hooks target the original SV ibex_top
  // internal hierarchy. The Chisel overlay keeps functional UVM/cosim active but does not
  // preserve these internal instance names.
""")

    start_marker = "  // RVFI interface connections\n"
    end_marker = "  initial begin\n"
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    replacement = """  // RVFI interface connections
  assign rvfi_if.reset                = ~rst_n;
  assign rvfi_if.valid                = dut.rvfi_valid;
  assign rvfi_if.order                = dut.rvfi_order;
  assign rvfi_if.insn                 = dut.rvfi_insn;
  assign rvfi_if.trap                 = dut.rvfi_trap;
  assign rvfi_if.intr                 = dut.rvfi_intr;
  assign rvfi_if.mode                 = dut.rvfi_mode;
  assign rvfi_if.ixl                  = dut.rvfi_ixl;
  assign rvfi_if.rs1_addr             = dut.rvfi_rs1_addr;
  assign rvfi_if.rs2_addr             = dut.rvfi_rs2_addr;
  assign rvfi_if.rs1_rdata            = dut.rvfi_rs1_rdata;
  assign rvfi_if.rs2_rdata            = dut.rvfi_rs2_rdata;
  assign rvfi_if.rd_addr              = dut.rvfi_rd_addr;
  assign rvfi_if.rd_wdata             = dut.rvfi_rd_wdata;
  assign rvfi_if.pc_rdata             = dut.rvfi_pc_rdata;
  assign rvfi_if.pc_wdata             = dut.rvfi_pc_wdata;
  assign rvfi_if.mem_addr             = dut.rvfi_mem_addr;
  assign rvfi_if.mem_rmask            = dut.rvfi_mem_rmask;
  assign rvfi_if.mem_rdata            = dut.rvfi_mem_rdata;
  assign rvfi_if.mem_wdata            = dut.rvfi_mem_wdata;
  assign rvfi_if.ext_pre_mip          = dut.rvfi_ext_pre_mip;
  assign rvfi_if.ext_post_mip         = dut.rvfi_ext_post_mip;
  assign rvfi_if.ext_nmi              = dut.rvfi_ext_nmi;
  assign rvfi_if.ext_nmi_int          = dut.rvfi_ext_nmi_int;
  assign rvfi_if.ext_debug_req        = dut.rvfi_ext_debug_req;
  assign rvfi_if.ext_rf_wr_suppress   = dut.rvfi_ext_rf_wr_suppress;
  assign rvfi_if.ext_mcycle           = dut.rvfi_ext_mcycle;
  assign rvfi_if.ext_mhpmcounters     = dut.rvfi_ext_mhpmcounters;
  assign rvfi_if.ext_mhpmcountersh    = dut.rvfi_ext_mhpmcountersh;
  assign rvfi_if.ext_ic_scr_key_valid = dut.rvfi_ext_ic_scr_key_valid;
  assign rvfi_if.ext_irq_valid        = dut.rvfi_ext_irq_valid;
  // Irq interface connections
  assign irq_vif.reset = ~rst_n;
  // Chisel overlay probe connections. Original UVM probe interfaces are kept
  // alive with RVFI and external bus observations where equivalent signals are
  // available; SV-only internal probes are driven to benign defaults.
  assign dut_if.ecall            = dut.rvfi_valid && dut.rvfi_insn == 32'h00000073;
  assign dut_if.wfi              = dut.rvfi_valid && dut.rvfi_insn == 32'h10500073;
  assign dut_if.ebreak           = dut.rvfi_valid && dut.rvfi_insn == 32'h00100073;
  assign dut_if.illegal_instr    = dut.rvfi_valid && dut.rvfi_trap;
  assign dut_if.dret             = dut.rvfi_valid && dut.rvfi_insn == 32'h7b200073;
  assign dut_if.mret             = dut.rvfi_valid && dut.rvfi_insn == 32'h30200073;
  assign dut_if.reset            = ~rst_n;
  assign dut_if.ic_tag_req       = '0;
  assign dut_if.ic_tag_write     = '0;
  assign dut_if.ic_tag_addr      = '0;
  assign dut_if.ic_data_req      = '0;
  assign dut_if.ic_data_write    = '0;
  assign dut_if.ic_data_addr     = '0;
  assign dut_if.priv_mode        = ibex_pkg::priv_lvl_e'(dut.rvfi_mode);
  assign dut_if.ctrl_fsm_cs      = '0;
  assign dut_if.debug_mode       = dut.probe_debug_mode;
  assign dut_if.rf_ren_a         = |dut.rvfi_rs1_addr;
  assign dut_if.rf_ren_b         = |dut.rvfi_rs2_addr;
  assign dut_if.rf_rd_a_wb_match = 1'b0;
  assign dut_if.rf_rd_b_wb_match = 1'b0;
  assign dut_if.rf_write_wb      = dut.rvfi_valid && |dut.rvfi_rd_addr;
  assign dut_if.sync_exc_seen    = dut.rvfi_valid && dut.rvfi_trap;
  assign dut_if.csr_save_cause   = dut.rvfi_valid && dut.rvfi_trap;
  assign dut_if.exc_cause        = '0;
  assign dut_if.wb_exception     = dut.rvfi_valid && dut.rvfi_trap;
  // Instruction monitor connections
  assign instr_monitor_if.reset        = ~rst_n;
  assign instr_monitor_if.valid_id     = dut.rvfi_valid;
  assign instr_monitor_if.rvfi_id_done = dut.rvfi_valid;
  assign instr_monitor_if.err_id       = dut.rvfi_valid && dut.rvfi_trap;
  assign instr_monitor_if.is_compressed_id = dut.rvfi_ext_expanded_insn_valid;
  assign instr_monitor_if.instr_compressed_id = dut.rvfi_ext_expanded_insn;
  assign instr_monitor_if.instr_id = dut.rvfi_insn;
  assign instr_monitor_if.pc_id    = dut.crash_dump_o.current_pc;
  assign instr_monitor_if.branch_taken_id = 1'b0;
  assign instr_monitor_if.branch_target_id = dut.rvfi_pc_wdata;
  assign instr_monitor_if.stall_id         = 1'b0;
  assign instr_monitor_if.jump_set_id      = 1'b0;
  assign instr_monitor_if.rvfi_order_id    = dut.rvfi_order;
  // CSR interface connections
  assign csr_if.csr_access = 1'b0;
  assign csr_if.csr_addr   = '0;
  assign csr_if.csr_wdata  = '0;
  assign csr_if.csr_rdata  = '0;
  assign csr_if.csr_op     = ibex_pkg::CSR_OP_READ;

  assign ifetch_if.reset           = ~rst_n;
  assign ifetch_if.fetch_ready     = instr_mem_vif.grant;
  assign ifetch_if.fetch_valid     = instr_mem_vif.request;
  assign ifetch_if.fetch_rdata     = instr_mem_vif.rdata;
  assign ifetch_if.fetch_addr      = instr_mem_vif.addr;
  assign ifetch_if.fetch_err       = instr_mem_vif.error;
  assign ifetch_if.fetch_err_plus2 = 1'b0;

  assign ifetch_pmp_if.reset         = ~rst_n;
  assign ifetch_pmp_if.fetch_valid   = instr_mem_vif.request;
  assign ifetch_pmp_if.fetch_addr    = instr_mem_vif.addr;
  assign ifetch_pmp_if.fetch_pmp_err = instr_mem_vif.error;

  assign data_mem_vif.misaligned_first =
      dut.probe_lsu_handle_misaligned_d |
      ((dut.probe_lsu_type == 2'b01) & (dut.probe_lsu_data_offset == 2'b01));
  assign data_mem_vif.misaligned_second = dut.probe_lsu_addr_incr_req;
  assign data_mem_vif.misaligned_first_saw_error =
      dut.probe_lsu_addr_incr_req & dut.probe_lsu_err_d;
  assign data_mem_vif.m_mode_access = dut.probe_priv_mode_lsu == ibex_pkg::PRIV_LVL_M;

"""
    text = text[:start] + replacement + text[end:]
    text = text.replace(
        """  // Manually set unused_assert_connected = 1 to disable the AssertConnected_A assertion for
  // prim_count in case lockstep (set by SecureIbex) is enabled and the lockstep offset is
  // larger than 1. If not disabled, DV fails.
  if (SecureIbex && LockstepOffset > 1) begin : gen_disable_count_check
    assign dut.u_ibex_top.gen_lockstep.u_ibex_lockstep.gen_reset_counter.u_rst_shadow_cnt.
          unused_assert_connected = 1;
  end

""",
        """  // Chisel overlay does not expose the original lockstep reset-counter assertion knob.

""")
    text = text.replace(
        """  assign controller_state      = dut.u_ibex_top.u_ibex_core.id_stage_i.controller_i.ctrl_fsm_cs;
  assign controller_handle_irq = dut.u_ibex_top.u_ibex_core.id_stage_i.controller_i.handle_irq;
  assign ibex_irqs             = dut.u_ibex_top.u_ibex_core.irqs;
""",
        """  assign controller_state      = ibex_pkg::RESET;
  assign controller_handle_irq = 1'b0;
  assign ibex_irqs             = '0;
""")
    if out_file.parent.is_symlink():
        out_file.parent.unlink()
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(text, encoding="ascii")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-ibex", type=Path, default=Path("externals/ibex/dv/uvm/core_ibex"))
    parser.add_argument("--chisel-dir", type=Path, required=True)
    parser.add_argument("--overlay-dir", type=Path, required=True)
    args = parser.parse_args()

    core_ibex = args.core_ibex.resolve()
    chisel_dir = args.chisel_dir.resolve()
    overlay_dir = args.overlay_dir.resolve()
    overlay_dir.mkdir(parents=True, exist_ok=True)

    for path in core_ibex.iterdir():
        dest = overlay_dir / path.name
        if dest.exists() or dest.is_symlink():
            continue
        if path.name in {"ibex_dv.f", "scripts", "tb"}:
            continue
        dest.symlink_to(path.resolve(), target_is_directory=path.is_dir())

    scripts_dir = overlay_dir / "scripts"
    if scripts_dir.exists():
        shutil.rmtree(scripts_dir)
    shutil.copytree(core_ibex / "scripts", scripts_dir, symlinks=True)
    setup_imports = scripts_dir / "setup_imports.py"
    setup_text = setup_imports.read_text(encoding="ascii")
    setup_text = setup_text.replace(
        "root = get_project_root()\n",
        "import os\n"
        "root = pathlib.Path(os.environ.get('IBEX_UVM_PRJ_DIR', get_project_root())).resolve()\n")
    setup_text = setup_text.replace(
        "_CORE_IBEX = root/'dv'/'uvm'/'core_ibex'\n",
        "_CORE_IBEX = pathlib.Path(os.environ.get('IBEX_UVM_CORE_IBEX_DIR', root/'dv'/'uvm'/'core_ibex')).resolve()\n")
    setup_imports.write_text(setup_text, encoding="ascii")

    rewrite_filelist(core_ibex / "ibex_dv.f", chisel_dir, overlay_dir / "ibex_dv.f")
    write_patched_tb_top(core_ibex / "tb" / "core_ibex_tb_top.sv",
                         overlay_dir / "tb" / "core_ibex_tb_top.sv")
    print(f"Created Chisel UVM overlay: {overlay_dir}")
    print(f"Set IBEX_UVM_CORE_IBEX_DIR={overlay_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
