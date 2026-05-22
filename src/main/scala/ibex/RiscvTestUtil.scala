// Copyright lowRISC contributors.
// Licensed under the Apache License, Version 2.0.
// SPDX-License-Identifier: Apache-2.0

package ibex

import chisel3._
import chisel3.util._

private class RiscvTestUtilTerminator(finishOnTerminate: Boolean) extends ExtModule(Map(
      "FinishOnTerminate" -> IntParam(if (finishOnTerminate) 1 else 0))) {
  val terminate_i = IO(Input(Bool()))

  override def desiredName: String = "RiscvTestUtilTerminator"

  setInline("RiscvTestUtilTerminator.sv",
    """// Simulator-only terminator for riscv_testutil.
      |module RiscvTestUtilTerminator #(
      |  parameter bit FinishOnTerminate = 1'b1
      |) (
      |  input logic terminate_i
      |);
      |`ifndef SYNTHESIS
      |  always_comb begin
      |    if (FinishOnTerminate && terminate_i) begin
      |      $display("Terminating simulation by software request.");
      |      $finish;
      |    end
      |  end
      |`endif
      |endmodule
      |""".stripMargin)
}

class RiscvTestUtil(finishOnTerminate: Boolean = true) extends RawModule {
  val clk_i = IO(Input(Clock()))
  val rst_ni = IO(Input(Bool()))

  val dev_req_i = IO(Input(Bool()))
  val dev_we_i = IO(Input(Bool()))
  val dev_addr_i = IO(Input(UInt(32.W)))
  val dev_wdata_i = IO(Input(UInt(32.W)))
  val dev_be_i = IO(Input(UInt(4.W)))
  val dev_rvalid_o = IO(Output(Bool()))
  val dev_rdata_o = IO(Output(UInt(32.W)))
  val dev_err_o = IO(Output(Bool()))

  val host_req_o = IO(Output(Bool()))
  val host_gnt_i = IO(Input(Bool()))
  val host_rvalid_i = IO(Input(Bool()))
  val host_addr_o = IO(Output(UInt(32.W)))
  val host_rdata_i = IO(Input(UInt(32.W)))

  dontTouch(dev_req_i)
  dontTouch(dev_we_i)
  dontTouch(dev_addr_i)
  dontTouch(dev_wdata_i)
  dontTouch(dev_be_i)
  dontTouch(dev_rvalid_o)
  dontTouch(dev_rdata_o)
  dontTouch(dev_err_o)
  dontTouch(host_req_o)
  dontTouch(host_gnt_i)
  dontTouch(host_rvalid_i)
  dontTouch(host_addr_o)
  dontTouch(host_rdata_i)

  private val addrHalt = 0
  private val addrSetBeginSignature = 4
  private val addrSetEndSignature = 8

  object ReadSigState extends ChiselEnum {
    val WAIT, READ, READ_FINISH, TERMINATE = Value
  }

  withClockAndReset(clk_i, (!rst_ni).asAsyncReset) {
    val beginSignatureAddr = Reg(UInt(32.W))
    val endSignatureAddr = Reg(UInt(32.W))
    val devRvalid = RegInit(false.B)
    val devErr = RegInit(false.B)
    val state = RegInit(ReadSigState.WAIT)
    val readAddr = RegInit(0.U(32.W))
    val terminator = Module(new RiscvTestUtilTerminator(finishOnTerminate))

    val readSignatureAndTerminate = WireDefault(false.B)
    val beginSignatureAddrNext = WireDefault(beginSignatureAddr)
    val endSignatureAddrNext = WireDefault(endSignatureAddr)

    when(dev_we_i && dev_req_i) {
      switch(dev_addr_i(9, 0)) {
        is(addrHalt.U) {
          readSignatureAndTerminate := true.B
        }
        is(addrSetBeginSignature.U) {
          beginSignatureAddrNext := dev_wdata_i
        }
        is(addrSetEndSignature.U) {
          endSignatureAddrNext := dev_wdata_i
        }
      }
    }

    beginSignatureAddr := beginSignatureAddrNext
    endSignatureAddr := endSignatureAddrNext
    devRvalid := dev_req_i
    devErr := (~dev_we_i || dev_be_i =/= "hf".U) && dev_req_i

    val stateNext = WireDefault(state)
    val readAddrNext = WireDefault(readAddr)
    switch(state) {
      is(ReadSigState.WAIT) {
        when(readSignatureAndTerminate) {
          printf(p"Reading signature from 0x${Hexadecimal(beginSignatureAddr)} to 0x${Hexadecimal(endSignatureAddr)}\n")
          stateNext := ReadSigState.READ
          readAddrNext := beginSignatureAddr
        }
      }
      is(ReadSigState.READ) {
        when(host_gnt_i) {
          readAddrNext := readAddr + 4.U
          when(readAddrNext === endSignatureAddr) {
            stateNext := ReadSigState.READ_FINISH
          }
        }
      }
      is(ReadSigState.READ_FINISH) {
        when(host_rvalid_i) {
          stateNext := ReadSigState.TERMINATE
        }
      }
      is(ReadSigState.TERMINATE) {
      }
    }

    state := stateNext
    readAddr := readAddrNext

    when(host_rvalid_i) {
      printf(p"SIGNATURE: 0x${Hexadecimal(host_rdata_i)}\n")
    }

    dev_rvalid_o := devRvalid
    dev_rdata_o := 0.U
    dev_err_o := devErr
    host_addr_o := readAddr
    host_req_o := state === ReadSigState.READ
    terminator.terminate_i := state === ReadSigState.TERMINATE
  }
}
