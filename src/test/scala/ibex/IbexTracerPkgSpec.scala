package ibex

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexTracerPkgSpec extends AnyFreeSpec with Matchers {
  "IbexTracerPkg" - {
    "matches base opcode and funct masks from ibex_tracer_pkg.sv" in {
      IbexTracerPkg.OPCODE_C0 mustBe 0
      IbexTracerPkg.OPCODE_C1 mustBe 1
      IbexTracerPkg.OPCODE_C2 mustBe 2

      IbexTracerPkg.INSN_LUI.mask mustBe 0x7f
      IbexTracerPkg.INSN_LUI.value mustBe IbexPkg.Opcode.encoding("OPCODE_LUI")

      IbexTracerPkg.INSN_JALR.mask mustBe BigInt("0000707f", 16)
      IbexTracerPkg.INSN_JALR.value mustBe BigInt("00000067", 16)

      IbexTracerPkg.INSN_ADD.mask mustBe BigInt("fe00707f", 16)
      IbexTracerPkg.INSN_ADD.value mustBe BigInt("00000033", 16)
      IbexTracerPkg.INSN_SUB.value mustBe BigInt("40000033", 16)

      IbexTracerPkg.INSN_EBREAK.mask mustBe BigInt("ffffffff", 16)
      IbexTracerPkg.INSN_EBREAK.value mustBe BigInt("00100073", 16)
      IbexTracerPkg.INSN_DRET.value mustBe BigInt("7b200073", 16)
    }

    "matches representative 32-bit instructions with don't-care operands" in {
      IbexTracerPkg.INSN_ADDI.matches(BigInt("fff28293", 16)) mustBe true
      IbexTracerPkg.INSN_ADDI.matches(BigInt("0002a293", 16)) mustBe false

      IbexTracerPkg.INSN_BEQ.matches(BigInt("fe208ee3", 16)) mustBe true
      IbexTracerPkg.INSN_BNE.matches(BigInt("fe208ee3", 16)) mustBe false

      IbexTracerPkg.INSN_CLZ.matches(BigInt("60011093", 16)) mustBe true
      IbexTracerPkg.INSN_CTZ.matches(BigInt("60011093", 16)) mustBe false

      IbexTracerPkg.INSN_RORI.matches(BigInt("6025d513", 16)) mustBe true
      IbexTracerPkg.INSN_FSRI.matches(BigInt("6425d513", 16)) mustBe true
      IbexTracerPkg.INSN_RORI.matches(BigInt("6425d513", 16)) mustBe false
    }

    "matches bitmanip pseudo-instruction masks from the tracer package" in {
      IbexTracerPkg.INSN_REV.mask mustBe BigInt("fdf0707f", 16)
      IbexTracerPkg.INSN_REV.value mustBe BigInt("69f05013", 16)
      IbexTracerPkg.INSN_REV.matches(BigInt("6bf2d293", 16)) mustBe true
      IbexTracerPkg.INSN_REV2.matches(BigInt("6bf2d293", 16)) mustBe false

      IbexTracerPkg.INSN_ZIP.mask mustBe BigInt("fcf0707f", 16)
      IbexTracerPkg.INSN_ZIP.value mustBe BigInt("08f01013", 16)
      IbexTracerPkg.INSN_ZIP.matches(BigInt("0bf29293", 16)) mustBe true
      IbexTracerPkg.INSN_UNZIP.matches(BigInt("0bf2d293", 16)) mustBe true
      IbexTracerPkg.INSN_ZIP.matches(BigInt("0bf2d293", 16)) mustBe false
    }

    "matches representative compressed instruction masks" in {
      IbexTracerPkg.INSN_CADDI.mask mustBe 0xe003
      IbexTracerPkg.INSN_CADDI.value mustBe 0x0001
      IbexTracerPkg.INSN_CADDI.matches(0x0001) mustBe true
      IbexTracerPkg.INSN_CADDI.matches(0x0002) mustBe false

      IbexTracerPkg.INSN_CSUB.mask mustBe 0xfc63
      IbexTracerPkg.INSN_CSUB.value mustBe 0x8c01
      IbexTracerPkg.INSN_CXOR.value mustBe 0x8c21

      IbexTracerPkg.INSN_CEBREAK.mask mustBe 0xffff
      IbexTracerPkg.INSN_CEBREAK.value mustBe 0x9002
      IbexTracerPkg.INSN_CEBREAK.matches(0x9002) mustBe true
      IbexTracerPkg.INSN_CJALR.matches(0x9502) mustBe true
      IbexTracerPkg.INSN_CJR.matches(0x8502) mustBe false

      IbexTracerPkg.INSN_CMPUSH.mask mustBe 0xff03
      IbexTracerPkg.INSN_CMPUSH.value mustBe 0xb802
      IbexTracerPkg.INSN_CMPOPRET.value mustBe 0xbe02
    }
  }
}
