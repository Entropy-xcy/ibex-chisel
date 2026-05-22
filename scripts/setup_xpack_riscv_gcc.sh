#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
install_root="${repo_root}/tools/xpack-riscv"
version="${XPACK_RISCV_GCC_VERSION:-15.2.0-1}"
archive="xpack-riscv-none-elf-gcc-${version}-linux-x64.tar.gz"
url="https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v${version}/${archive}"

mkdir -p "${install_root}"

if [[ ! -x "${install_root}/xpack-riscv-none-elf-gcc-${version}/bin/riscv-none-elf-gcc" ]]; then
  wget -q -O "${install_root}/${archive}" "${url}"
  tar -xzf "${install_root}/${archive}" -C "${install_root}"
fi

"${install_root}/xpack-riscv-none-elf-gcc-${version}/bin/riscv-none-elf-gcc" --version | head -n 1
