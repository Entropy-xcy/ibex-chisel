#!/usr/bin/env python3
"""Convert a 32-bit little-endian RISC-V ELF into Ram2P word VMEM."""

import argparse
import struct
import sys


SHF_ALLOC = 0x2
SHT_PROGBITS = 0x1


def parse_int(value: str) -> int:
    return int(value.replace("_", ""), 0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elf", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--ram-size-bytes", type=parse_int, required=True)
    parser.add_argument("--load-addr-offset", type=parse_int, default=0)
    args = parser.parse_args()

    with open(args.elf, "rb") as f:
        data = f.read()

    if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1:
        raise ValueError(f"{args.elf} is not an ELF32 little-endian file")

    e_entry = struct.unpack_from("<I", data, 0x18)[0]
    e_shoff = struct.unpack_from("<I", data, 0x20)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x2E)[0]
    e_shnum = struct.unpack_from("<H", data, 0x30)[0]

    words: dict[int, int] = {}
    lowest_load_addr: int | None = None
    for idx in range(e_shnum):
        off = e_shoff + idx * e_shentsize
        sh_type = struct.unpack_from("<I", data, off + 0x04)[0]
        sh_flags = struct.unpack_from("<I", data, off + 0x08)[0]
        sh_addr = struct.unpack_from("<I", data, off + 0x0C)[0]
        sh_offset = struct.unpack_from("<I", data, off + 0x10)[0]
        sh_size = struct.unpack_from("<I", data, off + 0x14)[0]

        if sh_type != SHT_PROGBITS or not (sh_flags & SHF_ALLOC):
            continue

        if lowest_load_addr is None or sh_addr < lowest_load_addr:
            lowest_load_addr = sh_addr

        section = data[sh_offset:sh_offset + sh_size]
        for byte_idx, byte in enumerate(section):
            mem_addr = (sh_addr + args.load_addr_offset + byte_idx) % args.ram_size_bytes
            word_idx = mem_addr // 4
            shift = (mem_addr % 4) * 8
            old = words.get(word_idx, 0)
            words[word_idx] = (old & ~(0xFF << shift)) | (byte << shift)

    with open(args.out, "w", encoding="ascii") as f:
        f.write(f"// entry=0x{(e_entry + args.load_addr_offset) & 0xFFFFFFFF:08x}\n")
        f.write(f"// load_addr_offset=0x{args.load_addr_offset & 0xFFFFFFFF:08x}\n")
        if lowest_load_addr is not None:
            f.write(f"// boot=0x{(lowest_load_addr + args.load_addr_offset + 0x80) & 0xFFFFFFFF:08x}\n")
        last_idx = None
        for word_idx in sorted(words):
            if last_idx is None or word_idx != last_idx + 1:
                f.write(f"@{word_idx:08x}\n")
            f.write(f"{words[word_idx]:08x}\n")
            last_idx = word_idx

    return 0


if __name__ == "__main__":
    sys.exit(main())
