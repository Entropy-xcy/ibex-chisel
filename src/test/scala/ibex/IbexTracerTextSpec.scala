package ibex

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexTracerTextSpec extends AnyFreeSpec with Matchers {
  "IbexTracerText" - {
    "formats register names" in {
      IbexTracerText.regAddrToStr(3) mustBe " x3"
      IbexTracerText.regAddrToStr(12) mustBe "x12"
      IbexTracerText.regAddrToAbiStr(0) mustBe "zero"
      IbexTracerText.regAddrToAbiStr(10) mustBe "a0"
      IbexTracerText.regAddrToAbiStr(31) mustBe "t6"
    }

    "looks up CSR names" in {
      IbexTracerText.getCsrName(768) mustBe "mstatus"
      IbexTracerText.getCsrName(3860) mustBe "mhartid"
      IbexTracerText.getCsrName(1234) mustBe "0x4d2"
    }

    "formats fence fields" in {
      IbexTracerText.getFenceDescription(0x0) mustBe ""
      IbexTracerText.getFenceDescription(0x5) mustBe "ow"
      IbexTracerText.getFenceDescription(0xf) mustBe "iorw"
    }

    "decodes representative 32-bit instructions with upstream tracer formatting" in {
      IbexTracerText.decodeInstruction(BigInt("123452b7", 16)) mustBe "lui\tx5,0x12345"
      IbexTracerText.decodeInstruction(BigInt("008000ef", 16), pcWdata = BigInt("00100088", 16)) mustBe
        "jal\tx1,100088"
      IbexTracerText.decodeInstruction(BigInt("fe208ee3", 16), pcRdata = BigInt("00100080", 16)) mustBe
        "beq\tx1,x2,10007c"
      IbexTracerText.decodeInstruction(BigInt("fff28293", 16)) mustBe "addi\tx5,x5,-1"
      IbexTracerText.decodeInstruction(BigInt("003100b3", 16)) mustBe "add\tx1,x2,x3"
      IbexTracerText.decodeInstruction(BigInt("403100b3", 16)) mustBe "sub\tx1,x2,x3"
      IbexTracerText.decodeInstruction(BigInt("34129073", 16)) mustBe "csrrw\tx0,mepc,x5"
      IbexTracerText.decodeInstruction(BigInt("3412d073", 16)) mustBe "csrrwi\tx0,mepc,5"
      IbexTracerText.decodeInstruction(BigInt("00812083", 16)) mustBe "lw\tx1,8(x2)"
      IbexTracerText.decodeInstruction(BigInt("fe112c23", 16)) mustBe "sw\tx1,-8(x2)"
      IbexTracerText.decodeInstruction(BigInt("0ff0000f", 16)) mustBe "fence\tiorw,iorw"
    }

    "decodes representative M and bitmanip instructions" in {
      IbexTracerText.decodeInstruction(BigInt("023100b3", 16)) mustBe "mul\tx1,x2,x3"
      IbexTracerText.decodeInstruction(BigInt("023160b3", 16)) mustBe "rem\tx1,x2,x3"
      IbexTracerText.decodeInstruction(BigInt("60011093", 16)) mustBe "clz\tx1,x2"
      IbexTracerText.decodeInstruction(BigInt("6025d513", 16)) mustBe "rori\tx10,x11,0x2"
      IbexTracerText.decodeInstruction(BigInt("403140b3", 16)) mustBe "xnor\tx1,x2,x3"
    }

    "decodes expanded Zcmp instructions" in {
      val cmPushAllRegs = IbexTracerPkg.INSN_CMPUSH.value.toInt | (15 << 4)
      IbexTracerText.decodeExpandedInsn(cmPushAllRegs) mustBe
        "cm.push\t{ra, s0-s11},-64"
      IbexTracerText.decodeExpandedInsn(IbexTracerPkg.INSN_CMMVSA01.value.toInt) must startWith
        ("cm.mvsa01\t")
    }
  }
}
