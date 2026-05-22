#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

src_dir="${IBEX_SPIKE_SRC_DIR:-tools/src/riscv-isa-sim-cosim}"
prefix="${IBEX_SPIKE_PREFIX:-tools/spike-ibex-cosim}"
jobs="${IBEX_SPIKE_JOBS:-$(nproc)}"
repo="${IBEX_SPIKE_REPO:-https://github.com/lowRISC/riscv-isa-sim.git}"
branch="${IBEX_SPIKE_BRANCH:-ibex_cosim}"

mkdir -p "$(dirname "${src_dir}")" "${prefix}"

if [[ ! -d "${src_dir}/.git" ]]; then
  git clone --depth 1 -b "${branch}" "${repo}" "${src_dir}"
fi

mkdir -p "${src_dir}/build"
(
  cd "${src_dir}/build"
  if [[ ! -f Makefile ]]; then
    ../configure \
      --enable-commitlog \
      --enable-misaligned \
      --prefix="${repo_root}/${prefix}"
  fi
  make -j"${jobs}" install
)

cat <<EOF
Installed lowRISC Spike to ${prefix}
Use:
  export PKG_CONFIG_PATH="${repo_root}/${prefix}/lib/pkgconfig:\${PKG_CONFIG_PATH:-}"
  export LD_LIBRARY_PATH="${repo_root}/${prefix}/lib:\${LD_LIBRARY_PATH:-}"
EOF
