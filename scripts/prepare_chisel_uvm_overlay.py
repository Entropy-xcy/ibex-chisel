#!/usr/bin/env python3
"""Create a core_ibex UVM overlay that compiles Chisel-emitted Ibex RTL."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


RTL_MARKER = "// ibex CORE RTL files"
DV_MARKER = "// Core DV files"
SV_INTERNAL_FCOV_BIND = "${PRJ_DIR}/dv/uvm/core_ibex/fcov/core_ibex_fcov_bind.sv"


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
        if line.strip() == SV_INTERNAL_FCOV_BIND:
            dv_lines.extend([
                "// Chisel RTL does not preserve the original ibex_core internal hierarchy",
                "// required by this functional coverage bind.",
                f"// {SV_INTERNAL_FCOV_BIND}",
            ])
        else:
            dv_lines.append(line)

    out_lines = lines[:rtl_start] + replacement + dv_lines
    out_file.write_text("\n".join(out_lines) + "\n", encoding="ascii")


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
        if path.name in {"ibex_dv.f", "scripts"}:
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
    print(f"Created Chisel UVM overlay: {overlay_dir}")
    print(f"Set IBEX_UVM_CORE_IBEX_DIR={overlay_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
