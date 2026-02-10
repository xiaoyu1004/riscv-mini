// See LICENSE for license details.

package mini

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

object Consts {
  val PC_START = 0x80000000L
  val PC_EVEC  = 0x80000004L
}

class Fetch2ExecuteBusData(xlen: Int) extends Bundle {
  val pc   = Output(UInt(xlen.W))
  val inst = Output(UInt(xlen.W))
}

class Execute2WritebackBusData(xlen: Int) extends Bundle {
  val pc        = UInt(xlen.W)
  val inst      = UInt(xlen.W)
  val alu_out   = UInt(xlen.W)
  val csr_in    = UInt(xlen.W)
  val st_type   = UInt(2.W)
  val ld_type   = UInt(3.W)
  val wb_sel    = UInt(2.W)
  val wb_en     = Bool()
  val csr_cmd   = UInt(3.W)
  val illegal   = Bool()
  val pc_check  = Bool()
  val pc_sel    = UInt(2.W)
  val inst_kill = Output(Bool())
}

class Execute2FetchCtrl(xlen: Int) extends Bundle {
  val br_taken = Output(Bool())
  val br_pc    = Output(UInt(xlen.W))
}

class Writeback2FetchCtrl(xlen: Int) extends Bundle {
  val flush    = Output(Bool())
  val flush_pc = Output(UInt(xlen.W))
}

class Writeback2ExecuteCtrl(xlen: Int) extends Bundle {
  val wb_en   = Output(Bool())
  val wb_addr = Output(UInt(5.W))
  val wb_data = Output(UInt(xlen.W))
  val flush   = Output(Bool())
}

class PipelineIO(xlen: Int) extends Bundle {
  val host   = new HostIO(xlen)
  val icache = Flipped(new CacheIO(xlen, xlen))
  val dcache = Flipped(new CacheIO(xlen, xlen))
}

class Pipeline(val conf: CoreConfig) extends Module {
  val io = IO(new PipelineIO(conf.xlen))

  val regFile   = Module(new RegFile(conf.xlen))
  val fetch     = Module(new FetchStage(conf))
  val execute   = Module(new ExecuteStage(conf))
  val writeback = Module(new WritebackStage(conf))

  // fetch
  fetch.io.icache <> io.icache
  fetch.io.to_es <> execute.io.from_fs
  fetch.io.from_es <> execute.io.to_fs
  fetch.io.from_ws <> writeback.io.to_fs

  // execute
  execute.io.dcache_req <> io.dcache.req
  execute.io.dcache_resp <> io.dcache.resp
  execute.io.from_ws <> writeback.io.to_es

  regFile.io.raddr1 := execute.io.rs1_addr
  regFile.io.raddr2 := execute.io.rs2_addr

  execute.io.rs1_data := regFile.io.rdata1
  execute.io.rs2_data := regFile.io.rdata2

  // writeback
  writeback.io.host <> io.host
  writeback.io.from_es <> execute.io.to_ws
  io.dcache.abort := writeback.io.dcache_abort
  writeback.io.dache_resp <> io.dcache.resp

  // rf write
  regFile.io.wen   := writeback.io.to_es.wb_en
  regFile.io.waddr := writeback.io.to_es.wb_addr
  regFile.io.wdata := writeback.io.to_es.wb_data

  // debug
  BoringUtils.drive(fetch.cycle)  := BoringUtils.bore(writeback.csr.cycle)
  BoringUtils.drive(fetch.cycleh) := BoringUtils.bore(writeback.csr.cycleh)

  BoringUtils.drive(execute.cycle)  := BoringUtils.bore(writeback.csr.cycle)
  BoringUtils.drive(execute.cycleh) := BoringUtils.bore(writeback.csr.cycleh)

  BoringUtils.drive(writeback.cycle)  := BoringUtils.bore(writeback.csr.cycle)
  BoringUtils.drive(writeback.cycleh) := BoringUtils.bore(writeback.csr.cycleh)
}
