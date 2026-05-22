package ibex

object IbexTracerText {
  private val Mask32 = BigInt("ffffffff", 16)

  def regAddrToStr(addr: Int): String =
    if (addr < 10) f" x$addr%d" else f"x$addr%d"

  def regAddrToAbiStr(addr: Int): String = addr match {
    case 0 => "zero"
    case 1 => "ra"
    case 2 => "sp"
    case 3 => "gp"
    case 4 => "tp"
    case 5 | 6 | 7 => s"t${addr - 5}"
    case 8 | 9 => s"s${addr - 8}"
    case 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 => s"a${addr - 10}"
    case 18 | 19 | 20 | 21 | 22 | 23 | 24 | 25 | 26 | 27 => s"s${addr - 16}"
    case 28 | 29 | 30 | 31 => s"t${addr - 25}"
    case _ => s"x$addr"
  }

  private val csrNames: Map[Int, String] = Map(
    0 -> "ustatus",
    1 -> "fflags",
    2 -> "frm",
    3 -> "fcsr",
    4 -> "uie",
    5 -> "utvec",
    64 -> "uscratch",
    65 -> "uepc",
    66 -> "ucause",
    67 -> "utval",
    68 -> "uip",
    256 -> "sstatus",
    258 -> "sedeleg",
    259 -> "sideleg",
    260 -> "sie",
    261 -> "stvec",
    262 -> "scounteren",
    320 -> "sscratch",
    321 -> "sepc",
    322 -> "scause",
    323 -> "stval",
    324 -> "sip",
    384 -> "satp",
    512 -> "hstatus",
    514 -> "hedeleg",
    515 -> "hideleg",
    516 -> "hie",
    517 -> "htvec",
    576 -> "hscratch",
    577 -> "hepc",
    578 -> "hcause",
    579 -> "hbadaddr",
    580 -> "hip",
    768 -> "mstatus",
    769 -> "misa",
    770 -> "medeleg",
    771 -> "mideleg",
    772 -> "mie",
    773 -> "mtvec",
    774 -> "mcounteren",
    800 -> "mcountinhibit",
    803 -> "mhpmevent3",
    804 -> "mhpmevent4",
    805 -> "mhpmevent5",
    806 -> "mhpmevent6",
    807 -> "mhpmevent7",
    808 -> "mhpmevent8",
    809 -> "mhpmevent9",
    810 -> "mhpmevent10",
    811 -> "mhpmevent11",
    812 -> "mhpmevent12",
    813 -> "mhpmevent13",
    814 -> "mhpmevent14",
    815 -> "mhpmevent15",
    816 -> "mhpmevent16",
    817 -> "mhpmevent17",
    818 -> "mhpmevent18",
    819 -> "mhpmevent19",
    820 -> "mhpmevent20",
    821 -> "mhpmevent21",
    822 -> "mhpmevent22",
    823 -> "mhpmevent23",
    824 -> "mhpmevent24",
    825 -> "mhpmevent25",
    826 -> "mhpmevent26",
    827 -> "mhpmevent27",
    828 -> "mhpmevent28",
    829 -> "mhpmevent29",
    830 -> "mhpmevent30",
    831 -> "mhpmevent31",
    832 -> "mscratch",
    833 -> "mepc",
    834 -> "mcause",
    835 -> "mtval",
    836 -> "mip",
    928 -> "pmpcfg0",
    929 -> "pmpcfg1",
    930 -> "pmpcfg2",
    931 -> "pmpcfg3",
    944 -> "pmpaddr0",
    945 -> "pmpaddr1",
    946 -> "pmpaddr2",
    947 -> "pmpaddr3",
    948 -> "pmpaddr4",
    949 -> "pmpaddr5",
    950 -> "pmpaddr6",
    951 -> "pmpaddr7",
    952 -> "pmpaddr8",
    953 -> "pmpaddr9",
    954 -> "pmpaddr10",
    955 -> "pmpaddr11",
    956 -> "pmpaddr12",
    957 -> "pmpaddr13",
    958 -> "pmpaddr14",
    959 -> "pmpaddr15",
    1952 -> "tselect",
    1953 -> "tdata1",
    1954 -> "tdata2",
    1955 -> "tdata3",
    1968 -> "dcsr",
    1969 -> "dpc",
    1970 -> "dscratch",
    2816 -> "mcycle",
    2818 -> "minstret",
    2819 -> "mhpmcounter3",
    2820 -> "mhpmcounter4",
    2821 -> "mhpmcounter5",
    2822 -> "mhpmcounter6",
    2823 -> "mhpmcounter7",
    2824 -> "mhpmcounter8",
    2825 -> "mhpmcounter9",
    2826 -> "mhpmcounter10",
    2827 -> "mhpmcounter11",
    2828 -> "mhpmcounter12",
    2829 -> "mhpmcounter13",
    2830 -> "mhpmcounter14",
    2831 -> "mhpmcounter15",
    2832 -> "mhpmcounter16",
    2833 -> "mhpmcounter17",
    2834 -> "mhpmcounter18",
    2835 -> "mhpmcounter19",
    2836 -> "mhpmcounter20",
    2837 -> "mhpmcounter21",
    2838 -> "mhpmcounter22",
    2839 -> "mhpmcounter23",
    2840 -> "mhpmcounter24",
    2841 -> "mhpmcounter25",
    2842 -> "mhpmcounter26",
    2843 -> "mhpmcounter27",
    2844 -> "mhpmcounter28",
    2845 -> "mhpmcounter29",
    2846 -> "mhpmcounter30",
    2847 -> "mhpmcounter31",
    2944 -> "mcycleh",
    2946 -> "minstreth",
    2947 -> "mhpmcounter3h",
    2948 -> "mhpmcounter4h",
    2949 -> "mhpmcounter5h",
    2950 -> "mhpmcounter6h",
    2951 -> "mhpmcounter7h",
    2952 -> "mhpmcounter8h",
    2953 -> "mhpmcounter9h",
    2954 -> "mhpmcounter10h",
    2955 -> "mhpmcounter11h",
    2956 -> "mhpmcounter12h",
    2957 -> "mhpmcounter13h",
    2958 -> "mhpmcounter14h",
    2959 -> "mhpmcounter15h",
    2960 -> "mhpmcounter16h",
    2961 -> "mhpmcounter17h",
    2962 -> "mhpmcounter18h",
    2963 -> "mhpmcounter19h",
    2964 -> "mhpmcounter20h",
    2965 -> "mhpmcounter21h",
    2966 -> "mhpmcounter22h",
    2967 -> "mhpmcounter23h",
    2968 -> "mhpmcounter24h",
    2969 -> "mhpmcounter25h",
    2970 -> "mhpmcounter26h",
    2971 -> "mhpmcounter27h",
    2972 -> "mhpmcounter28h",
    2973 -> "mhpmcounter29h",
    2974 -> "mhpmcounter30h",
    2975 -> "mhpmcounter31h",
    3072 -> "cycle",
    3073 -> "time",
    3074 -> "instret",
    3075 -> "hpmcounter3",
    3076 -> "hpmcounter4",
    3077 -> "hpmcounter5",
    3078 -> "hpmcounter6",
    3079 -> "hpmcounter7",
    3080 -> "hpmcounter8",
    3081 -> "hpmcounter9",
    3082 -> "hpmcounter10",
    3083 -> "hpmcounter11",
    3084 -> "hpmcounter12",
    3085 -> "hpmcounter13",
    3086 -> "hpmcounter14",
    3087 -> "hpmcounter15",
    3088 -> "hpmcounter16",
    3089 -> "hpmcounter17",
    3090 -> "hpmcounter18",
    3091 -> "hpmcounter19",
    3092 -> "hpmcounter20",
    3093 -> "hpmcounter21",
    3094 -> "hpmcounter22",
    3095 -> "hpmcounter23",
    3096 -> "hpmcounter24",
    3097 -> "hpmcounter25",
    3098 -> "hpmcounter26",
    3099 -> "hpmcounter27",
    3100 -> "hpmcounter28",
    3101 -> "hpmcounter29",
    3102 -> "hpmcounter30",
    3103 -> "hpmcounter31",
    3200 -> "cycleh",
    3201 -> "timeh",
    3202 -> "instreth",
    3203 -> "hpmcounter3h",
    3204 -> "hpmcounter4h",
    3205 -> "hpmcounter5h",
    3206 -> "hpmcounter6h",
    3207 -> "hpmcounter7h",
    3208 -> "hpmcounter8h",
    3209 -> "hpmcounter9h",
    3210 -> "hpmcounter10h",
    3211 -> "hpmcounter11h",
    3212 -> "hpmcounter12h",
    3213 -> "hpmcounter13h",
    3214 -> "hpmcounter14h",
    3215 -> "hpmcounter15h",
    3216 -> "hpmcounter16h",
    3217 -> "hpmcounter17h",
    3218 -> "hpmcounter18h",
    3219 -> "hpmcounter19h",
    3220 -> "hpmcounter20h",
    3221 -> "hpmcounter21h",
    3222 -> "hpmcounter22h",
    3223 -> "hpmcounter23h",
    3224 -> "hpmcounter24h",
    3225 -> "hpmcounter25h",
    3226 -> "hpmcounter26h",
    3227 -> "hpmcounter27h",
    3228 -> "hpmcounter28h",
    3229 -> "hpmcounter29h",
    3230 -> "hpmcounter30h",
    3231 -> "hpmcounter31h",
    3857 -> "mvendorid",
    3858 -> "marchid",
    3859 -> "mimpid",
    3860 -> "mhartid"
  )

  def getCsrName(csrAddr: Int): String =
    csrNames.getOrElse(csrAddr, f"0x$csrAddr%x")

  def getFenceDescription(bits: Int): String = {
    var desc = ""
    if ((bits & 0x8) != 0) desc += "i"
    if ((bits & 0x4) != 0) desc += "o"
    if ((bits & 0x2) != 0) desc += "r"
    if ((bits & 0x1) != 0) desc += "w"
    desc
  }

  def decodeInstruction(insn: BigInt, pcRdata: BigInt = 0, pcWdata: BigInt = 0): String = {
    val i = insn & Mask32
    val rd = bits(i, 11, 7)
    val rs1 = bits(i, 19, 15)
    val rs2 = bits(i, 24, 20)
    val csr = bits(i, 31, 20)

    def r(mnemonic: String): String = s"$mnemonic\tx$rd,x$rs1,x$rs2"
    def r1(mnemonic: String): String = s"$mnemonic\tx$rd,x$rs1"
    def iFmt(mnemonic: String): String = s"$mnemonic\tx$rd,x$rs1,${signed(bits(i, 31, 20), 12)}"
    def iShift(mnemonic: String): String = s"$mnemonic\tx$rd,x$rs1,0x${bits(i, 24, 20).toHexString}"
    def iJalr(mnemonic: String): String = s"$mnemonic\tx$rd,${signed(bits(i, 31, 20), 12)}(x$rs1)"
    def u(mnemonic: String): String = s"$mnemonic\tx$rd,0x${bits(i, 31, 12).toHexString}"
    def j(mnemonic: String): String = s"$mnemonic\tx$rd,${hex32(pcWdata)}"
    def b(mnemonic: String): String = {
      val imm =
        (bit(i, 31) << 12) |
          (bit(i, 7) << 11) |
          (bits(i, 30, 25) << 5) |
          (bits(i, 11, 8) << 1)
      val target = (pcRdata + signed(imm, 13)) & Mask32
      s"$mnemonic\tx$rs1,x$rs2,${hex32(target)}"
    }
    def csrFmt(mnemonic: String): String = {
      val csrName = getCsrName(csr)
      if (((i >> 14) & 1) == 0) s"$mnemonic\tx$rd,$csrName,x$rs1"
      else s"$mnemonic\tx$rd,$csrName,$rs1"
    }
    def load(): String = {
      val mnemonic = bits(i, 14, 12) match {
        case 0 => "lb"
        case 1 => "lh"
        case 2 => "lw"
        case 4 => "lbu"
        case 5 => "lhu"
        case _ => return "INVALID"
      }
      s"$mnemonic\tx$rd,${signed(bits(i, 31, 20), 12)}(x$rs1)"
    }
    def store(): String = {
      val mnemonic = bits(i, 13, 12) match {
        case 0 => "sb"
        case 1 => "sh"
        case 2 => "sw"
        case _ => return "INVALID"
      }
      if (bit(i, 14) != 0) "INVALID"
      else {
        val imm = (bits(i, 31, 25) << 5) | bits(i, 11, 7)
        s"$mnemonic\tx$rs2,${signed(imm, 12)}(x$rs1)"
      }
    }
    def fence(): String =
      s"fence\t${getFenceDescription(bits(i, 27, 24))},${getFenceDescription(bits(i, 23, 20))}"

    Seq[(IbexTracerPkg.InsnPattern, () => String)](
      IbexTracerPkg.INSN_LUI -> (() => u("lui")),
      IbexTracerPkg.INSN_AUIPC -> (() => u("auipc")),
      IbexTracerPkg.INSN_JAL -> (() => j("jal")),
      IbexTracerPkg.INSN_JALR -> (() => iJalr("jalr")),
      IbexTracerPkg.INSN_BEQ -> (() => b("beq")),
      IbexTracerPkg.INSN_BNE -> (() => b("bne")),
      IbexTracerPkg.INSN_BLT -> (() => b("blt")),
      IbexTracerPkg.INSN_BGE -> (() => b("bge")),
      IbexTracerPkg.INSN_BLTU -> (() => b("bltu")),
      IbexTracerPkg.INSN_BGEU -> (() => b("bgeu")),
      IbexTracerPkg.INSN_ADDI -> (() => iFmt("addi")),
      IbexTracerPkg.INSN_SLTI -> (() => iFmt("slti")),
      IbexTracerPkg.INSN_SLTIU -> (() => iFmt("sltiu")),
      IbexTracerPkg.INSN_XORI -> (() => iFmt("xori")),
      IbexTracerPkg.INSN_ORI -> (() => iFmt("ori")),
      IbexTracerPkg.INSN_ANDI -> (() => iFmt("andi")),
      IbexTracerPkg.INSN_SLLI -> (() => iShift("slli")),
      IbexTracerPkg.INSN_SRLI -> (() => iShift("srli")),
      IbexTracerPkg.INSN_SRAI -> (() => iShift("srai")),
      IbexTracerPkg.INSN_ADD -> (() => r("add")),
      IbexTracerPkg.INSN_SUB -> (() => r("sub")),
      IbexTracerPkg.INSN_SLL -> (() => r("sll")),
      IbexTracerPkg.INSN_SLT -> (() => r("slt")),
      IbexTracerPkg.INSN_SLTU -> (() => r("sltu")),
      IbexTracerPkg.INSN_XOR -> (() => r("xor")),
      IbexTracerPkg.INSN_SRL -> (() => r("srl")),
      IbexTracerPkg.INSN_SRA -> (() => r("sra")),
      IbexTracerPkg.INSN_OR -> (() => r("or")),
      IbexTracerPkg.INSN_AND -> (() => r("and")),
      IbexTracerPkg.INSN_CSRRW -> (() => csrFmt("csrrw")),
      IbexTracerPkg.INSN_CSRRS -> (() => csrFmt("csrrs")),
      IbexTracerPkg.INSN_CSRRC -> (() => csrFmt("csrrc")),
      IbexTracerPkg.INSN_CSRRWI -> (() => csrFmt("csrrwi")),
      IbexTracerPkg.INSN_CSRRSI -> (() => csrFmt("csrrsi")),
      IbexTracerPkg.INSN_CSRRCI -> (() => csrFmt("csrrci")),
      IbexTracerPkg.INSN_ECALL -> (() => "ecall"),
      IbexTracerPkg.INSN_EBREAK -> (() => "ebreak"),
      IbexTracerPkg.INSN_MRET -> (() => "mret"),
      IbexTracerPkg.INSN_DRET -> (() => "dret"),
      IbexTracerPkg.INSN_WFI -> (() => "wfi"),
      IbexTracerPkg.INSN_PMUL -> (() => r("mul")),
      IbexTracerPkg.INSN_PMUH -> (() => r("mulh")),
      IbexTracerPkg.INSN_PMULHSU -> (() => r("mulhsu")),
      IbexTracerPkg.INSN_PMULHU -> (() => r("mulhu")),
      IbexTracerPkg.INSN_DIV -> (() => r("div")),
      IbexTracerPkg.INSN_DIVU -> (() => r("divu")),
      IbexTracerPkg.INSN_REM -> (() => r("rem")),
      IbexTracerPkg.INSN_REMU -> (() => r("remu")),
      IbexTracerPkg.INSN_LOAD -> (() => load()),
      IbexTracerPkg.INSN_STORE -> (() => store()),
      IbexTracerPkg.INSN_FENCE -> (() => fence()),
      IbexTracerPkg.INSN_FENCEI -> (() => "fence.i"),
      IbexTracerPkg.INSN_SH1ADD -> (() => r("sh1add")),
      IbexTracerPkg.INSN_SH2ADD -> (() => r("sh2add")),
      IbexTracerPkg.INSN_SH3ADD -> (() => r("sh3add")),
      IbexTracerPkg.INSN_RORI -> (() => iShift("rori")),
      IbexTracerPkg.INSN_ROL -> (() => r("rol")),
      IbexTracerPkg.INSN_ROR -> (() => r("ror")),
      IbexTracerPkg.INSN_MIN -> (() => r("min")),
      IbexTracerPkg.INSN_MAX -> (() => r("max")),
      IbexTracerPkg.INSN_MINU -> (() => r("minu")),
      IbexTracerPkg.INSN_MAXU -> (() => r("maxu")),
      IbexTracerPkg.INSN_XNOR -> (() => r("xnor")),
      IbexTracerPkg.INSN_ORN -> (() => r("orn")),
      IbexTracerPkg.INSN_ANDN -> (() => r("andn")),
      IbexTracerPkg.INSN_PACK -> (() => r("pack")),
      IbexTracerPkg.INSN_PACKH -> (() => r("packh")),
      IbexTracerPkg.INSN_PACKU -> (() => r("packu")),
      IbexTracerPkg.INSN_CLZ -> (() => r1("clz")),
      IbexTracerPkg.INSN_CTZ -> (() => r1("ctz")),
      IbexTracerPkg.INSN_CPOP -> (() => r1("cpop")),
      IbexTracerPkg.INSN_SEXTB -> (() => r1("sext.b")),
      IbexTracerPkg.INSN_SEXTH -> (() => r1("sext.h"))
    ).collectFirst { case (pattern, render) if pattern.matches(i) => render() }.getOrElse("INVALID")
  }

  private def bit(value: BigInt, index: Int): Int =
    ((value >> index) & 1).toInt

  private def bits(value: BigInt, high: Int, low: Int): Int =
    ((value >> low) & ((BigInt(1) << (high - low + 1)) - 1)).toInt

  private def signed(value: Int, width: Int): Int = {
    val sign = 1 << (width - 1)
    val mask = (1 << width) - 1
    val masked = value & mask
    if ((masked & sign) != 0) masked - (1 << width) else masked
  }

  private def hex32(value: BigInt): String =
    (value & Mask32).toString(16)

  def cmRegToStr(addr: Int): String = {
    val hi = if (((addr >> 1) & 0x3) > 0) 1 else 0
    val mid = if (((addr >> 1) & 0x3) == 0) 1 else 0
    val xreg = (hi << 4) | (mid << 3) | (addr & 0x7)
    regAddrToAbiStr(xreg)
  }

  def decodeExpandedInsn(expandedInsn: Int): String = expandedInsn match {
    case x if IbexTracerPkg.INSN_CMPUSH.matches(x) =>
      decodeZcmpCmppInsn("cm.push", expandedInsn)
    case x if IbexTracerPkg.INSN_CMPOP.matches(x) =>
      decodeZcmpCmppInsn("cm.pop", expandedInsn)
    case x if IbexTracerPkg.INSN_CMPOPRETZ.matches(x) =>
      decodeZcmpCmppInsn("cm.popretz", expandedInsn)
    case x if IbexTracerPkg.INSN_CMPOPRET.matches(x) =>
      decodeZcmpCmppInsn("cm.popret", expandedInsn)
    case x if IbexTracerPkg.INSN_CMMVSA01.matches(x) =>
      decodeZcmpCmmvInsn("cm.mvsa01", expandedInsn)
    case x if IbexTracerPkg.INSN_CMMVA01S.matches(x) =>
      decodeZcmpCmmvInsn("cm.mva01s", expandedInsn)
    case _ => "Decoding error"
  }

  private def decodeZcmpCmmvInsn(mnemonic: String, expandedInsn: Int): String =
    s"$mnemonic\t${cmRegToStr((expandedInsn >> 7) & 0x7)},${cmRegToStr((expandedInsn >> 2) & 0x7)}"

  private def decodeZcmpCmppInsn(mnemonic: String, expandedInsn: Int): String = {
    val rlist = (expandedInsn >> 4) & 0xf
    val spimm = (expandedInsn >> 2) & 0x3
    val rlistStr =
      if (rlist < 4) s"{INVALID ($rlist)}"
      else rlist match {
        case 4  => "{ra}"
        case 5  => "{ra, s0}"
        case 15 => "{ra, s0-s11}"
        case _  => s"{ra, s0-s${rlist - 5}}"
      }
    val base = if (rlist == 15) 64 else (rlist >> 2) * 16
    val spimmVal = base + (spimm * 16)
    val signedSpimm = if (mnemonic == "cm.push") -spimmVal else spimmVal
    s"$mnemonic\t$rlistStr,$signedSpimm"
  }
}
