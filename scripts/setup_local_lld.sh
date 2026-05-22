#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rpm_dir="${repo_root}/tools/rpms"
install_dir="${repo_root}/tools/lld-local"

mkdir -p "${rpm_dir}" "${install_dir}"

dnf -y --disablerepo='nvidia-container-toolkit' \
  download --destdir "${rpm_dir}" \
  lld-20.1.8-3.el9.x86_64 \
  lld-libs-20.1.8-3.el9.x86_64

rpm2cpio "${rpm_dir}/lld-20.1.8-3.el9.x86_64.rpm" | (cd "${install_dir}" && cpio -idmu)
rpm2cpio "${rpm_dir}/lld-libs-20.1.8-3.el9.x86_64.rpm" | (cd "${install_dir}" && cpio -idmu)

LD_LIBRARY_PATH="${install_dir}/usr/lib64:${LD_LIBRARY_PATH:-}" \
  "${install_dir}/usr/bin/ld.lld" --version
