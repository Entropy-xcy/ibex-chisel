package ibex

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class IbexPkgSpec extends AnyFreeSpec with Matchers {
  "IbexPkg" - {
    "matches selected SystemVerilog opcode encodings" in {
      IbexPkg.Opcode.encoding must contain allOf (
        "OPCODE_LOAD" -> 0x03,
        "OPCODE_OP_IMM" -> 0x13,
        "OPCODE_OP" -> 0x33,
        "OPCODE_BRANCH" -> 0x63,
        "OPCODE_JAL" -> 0x6f,
        "OPCODE_SYSTEM" -> 0x73
      )
    }

    "matches sequential ALU enum encodings from ibex_pkg.sv" in {
      IbexPkg.AluOp.encoding("ALU_ADD") mustBe 0
      IbexPkg.AluOp.encoding("ALU_SUB") mustBe 1
      IbexPkg.AluOp.encoding("ALU_AND") mustBe 4
      IbexPkg.AluOp.encoding("ALU_SLL") mustBe 10
      IbexPkg.AluOp.encoding("ALU_SLTU") mustBe 44
      IbexPkg.AluOp.encoding("ALU_CRC32C_W") mustBe 64
    }

    "matches cache, PMP, CSR, random constant, and MuBi parameters" in {
      IbexPkg.IC_NUM_LINES mustBe 256
      IbexPkg.IC_TAG_SIZE mustBe 22
      IbexPkg.PMP_ADDR_MSB mustBe 33
      IbexPkg.CsrNum.MVENDORID mustBe 0xf11
      IbexPkg.CsrNum.MSECCFG mustBe 0x747
      IbexPkg.CsrNum.CPUCTRLSTS mustBe 0x7c0
      IbexPkg.CsrNum.pmpAddr.last mustBe 0x3bf
      IbexPkg.CsrNum.mhpmCounter.last mustBe 0xb1f
      IbexPkg.RndCnstLfsrPermDefault mustBe BigInt("1e35ecba467fd1b12e958152c04fa43878a8daed", 16)
      IbexPkg.RndCnstIbexKeyDefault mustBe BigInt("14e8cecae3040d5e12286bb3cc113298", 16)
      IbexPkg.RndCnstIbexNonceDefault mustBe BigInt("f79780bc735f3843", 16)
    }
  }
}
