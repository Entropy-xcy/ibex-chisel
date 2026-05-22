#!/usr/bin/env python3
"""Convert RISC-V test images into Ram2P word VMEM."""

import argparse
import struct
import sys


SHF_ALLOC = 0x2
SHT_PROGBITS = 0x1


def parse_int(value: str) -> int:
    return int(value.replace("_", ""), 0)


def main() -> int:
    parser = argparse.ArgumentParser()
    input_group = parser.add_mutually_exclusive_group(required=True)
    input_group.add_argument("--elf")
    input_group.add_argument("--bin")
    parser.add_argument("--out", required=True)
    parser.add_argument("--ram-size-bytes", type=parse_int, required=True)
    parser.add_argument("--load-addr-offset", type=parse_int, default=0)
    parser.add_argument("--bin-base-addr", type=parse_int, default=0)
    parser.add_argument("--mseccfg-layout", action="store_true")
    args = parser.parse_args()

    def write_vmem(words: dict[int, int], entry: int, boot: int, load_addr_offset: int) -> None:
        with open(args.out, "w", encoding="ascii") as f:
            f.write(f"// entry=0x{entry & 0xFFFFFFFF:08x}\n")
            f.write(f"// load_addr_offset=0x{load_addr_offset & 0xFFFFFFFF:08x}\n")
            f.write(f"// boot=0x{boot & 0xFFFFFFFF:08x}\n")
            last_idx = None
            for word_idx in sorted(words):
                if last_idx is None or word_idx != last_idx + 1:
                    f.write(f"@{word_idx:08x}\n")
                f.write(f"{words[word_idx]:08x}\n")
                last_idx = word_idx

    def set_byte(words: dict[int, int], addr: int, byte: int) -> None:
        mem_addr = addr % args.ram_size_bytes
        word_idx = mem_addr // 4
        shift = (mem_addr % 4) * 8
        old = words.get(word_idx, 0)
        words[word_idx] = (old & ~(0xFF << shift)) | (byte << shift)

    if args.bin is not None:
        with open(args.bin, "rb") as f:
            data = f.read()

        words: dict[int, int] = {}
        for byte_idx, byte in enumerate(data):
            set_byte(words, args.bin_base_addr + byte_idx, byte)

        write_vmem(words, args.bin_base_addr + 0x80, args.bin_base_addr + 0x80, 0)
        return 0

    with open(args.elf, "rb") as f:
        data = f.read()

    if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1:
        raise ValueError(f"{args.elf} is not an ELF32 little-endian file")

    e_entry = struct.unpack_from("<I", data, 0x18)[0]
    e_shoff = struct.unpack_from("<I", data, 0x20)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x2E)[0]
    e_shnum = struct.unpack_from("<H", data, 0x30)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x32)[0]

    shstr = b""
    if e_shstrndx < e_shnum:
        shstr_off = e_shoff + e_shstrndx * e_shentsize
        shstr_file_off = struct.unpack_from("<I", data, shstr_off + 0x10)[0]
        shstr_size = struct.unpack_from("<I", data, shstr_off + 0x14)[0]
        shstr = data[shstr_file_off:shstr_file_off + shstr_size]

    def section_name(name_off: int) -> str:
        if name_off >= len(shstr):
            return ""
        end = shstr.find(b"\x00", name_off)
        if end < 0:
            end = len(shstr)
        return shstr[name_off:end].decode("ascii", errors="replace")

    def runtime_offset(name: str) -> int:
        if not args.mseccfg_layout:
            return args.load_addr_offset
        if name.startswith(".test") or name.startswith(".umode"):
            return 0x80000000
        return 0x7FF00000

    words: dict[int, int] = {}
    lowest_load_addr: int | None = None
    for idx in range(e_shnum):
        off = e_shoff + idx * e_shentsize
        sh_name = struct.unpack_from("<I", data, off + 0x00)[0]
        sh_type = struct.unpack_from("<I", data, off + 0x04)[0]
        sh_flags = struct.unpack_from("<I", data, off + 0x08)[0]
        sh_addr = struct.unpack_from("<I", data, off + 0x0C)[0]
        sh_offset = struct.unpack_from("<I", data, off + 0x10)[0]
        sh_size = struct.unpack_from("<I", data, off + 0x14)[0]

        if sh_type != SHT_PROGBITS or not (sh_flags & SHF_ALLOC):
            continue

        name = section_name(sh_name)
        section_runtime_addr = sh_addr + runtime_offset(name)
        if lowest_load_addr is None or section_runtime_addr < lowest_load_addr:
            lowest_load_addr = section_runtime_addr

        section = data[sh_offset:sh_offset + sh_size]
        for byte_idx, byte in enumerate(section):
            set_byte(words, section_runtime_addr + byte_idx, byte)

    entry_offset = 0x7FF00000 if args.mseccfg_layout else args.load_addr_offset
    boot = (lowest_load_addr + 0x80) if lowest_load_addr is not None else 0
    write_vmem(words, e_entry + entry_offset, boot, entry_offset)

    return 0


if __name__ == "__main__":
    sys.exit(main())
