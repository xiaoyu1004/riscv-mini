// See LICENSE for license details.

package mini
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

class ExecuteIO(xlen: Int) extends Bundle {
  val to_ws       = Decoupled(new Execute2WritebackBusData(xlen))
  val from_fs     = Flipped(Decoupled(new Fetch2ExecuteBusData(xlen)))
  val dcache_req  = Valid(new CacheReq(xlen, xlen))
  val dcache_resp = Flipped(Valid(new CacheResp(xlen)))
  val from_ws     = Flipped(new Writeback2ExecuteCtrl(xlen))
  val to_fs       = new Execute2FetchCtrl(xlen)
  // regfile
  val rs1_addr = Output(UInt(5.W))
  val rs2_addr = Output(UInt(5.W))
  val rs1_data = Input(UInt(xlen.W))
  val rs2_data = Input(UInt(xlen.W))
}

class ExecuteStage(val conf: CoreConfig) extends Module {
  val io = IO(new ExecuteIO(conf.xlen))

  val alu    = Module(conf.makeAlu(conf.xlen))
  val immGen = Module(conf.makeImmGen(conf.xlen))
  val brCond = Module(conf.makeBrCond(conf.xlen))
  val ctrl   = Module(new Control)

  val es_valid_r = RegEnable(io.from_fs.valid, false.B, io.from_fs.ready)
  val es_pipe_r =
    RegEnable(io.from_fs.bits, io.from_fs.valid && io.from_fs.ready)

  val ld_or_st     = ctrl.io.ld_type.orR || ctrl.io.st_type.orR
  val dcache_ready = io.dcache_resp.valid

  val es_busy = ld_or_st && !dcache_ready

  io.to_ws.valid   := es_valid_r && !es_busy && !io.from_ws.flush
  io.from_fs.ready := !es_valid_r || (!es_busy && io.to_ws.ready)

  ctrl.io.inst := es_pipe_r.inst

  io.rs1_addr := es_pipe_r.inst(19, 15)
  io.rs2_addr := es_pipe_r.inst(24, 20)

  val rs1_hazard = io.from_ws.wb_en && io.rs1_addr.orR && (io.rs1_addr === io.from_ws.wb_addr)
  val rs2_hazard = io.from_ws.wb_en && io.rs2_addr.orR && (io.rs2_addr === io.from_ws.wb_addr)

  val rs1 = Mux(rs1_hazard, io.from_ws.wb_data, io.rs1_data)
  val rs2 = Mux(rs2_hazard, io.from_ws.wb_data, io.rs2_data)

  // gen immdeates
  immGen.io.inst := es_pipe_r.inst
  immGen.io.sel  := ctrl.io.imm_sel

  // ALU operations
  alu.io.A      := Mux(ctrl.io.A_sel === Control.A_RS1, rs1, es_pipe_r.pc)
  alu.io.B      := Mux(ctrl.io.B_sel === Control.B_RS2, rs2, immGen.io.out)
  alu.io.alu_op := ctrl.io.alu_op

  // Branch condition calc
  brCond.io.rs1     := rs1
  brCond.io.rs2     := rs2
  brCond.io.br_type := ctrl.io.br_type

  io.to_fs.br_taken := es_valid_r && brCond.io.taken
  io.to_fs.br_pc    := alu.io.sum >> 2.U << 2.U

  // D$ access
  val daddr      = alu.io.sum >> 2.U << 2.U
  val bit_offset = (alu.io.sum(1) << 4.U) | (alu.io.sum(0) << 3.U)

  io.dcache_req.valid     := io.to_ws.ready && es_valid_r && ld_or_st
  io.dcache_req.bits.addr := daddr
  io.dcache_req.bits.data := rs2 << bit_offset
  io.dcache_req.bits.mask := MuxLookup(
    ctrl.io.st_type,
    "b0000".U
  )(
    Seq(
      Control.ST_SW -> "b1111".U,
      Control.ST_SH -> ("b11".U << alu.io.sum(1, 0)),
      Control.ST_SB -> ("b1".U << alu.io.sum(1, 0))
    )
  )

  io.to_ws.bits.pc        := es_pipe_r.pc
  io.to_ws.bits.inst      := es_pipe_r.inst
  io.to_ws.bits.alu_out   := alu.io.out
  io.to_ws.bits.csr_in    := Mux(ctrl.io.imm_sel === Control.IMM_Z, immGen.io.out, rs1)
  io.to_ws.bits.st_type   := ctrl.io.st_type
  io.to_ws.bits.ld_type   := ctrl.io.ld_type
  io.to_ws.bits.wb_sel    := ctrl.io.wb_sel
  io.to_ws.bits.wb_en     := ctrl.io.wb_en
  io.to_ws.bits.csr_cmd   := ctrl.io.csr_cmd
  io.to_ws.bits.illegal   := ctrl.io.illegal
  io.to_ws.bits.pc_check  := ctrl.io.pc_sel === Control.PC_ALU
  io.to_ws.bits.inst_kill := ctrl.io.inst_kill
  io.to_ws.bits.pc_sel    := ctrl.io.pc_sel

  val cycle  = Wire(UInt(conf.xlen.W))
  val cycleh = Wire(UInt(conf.xlen.W))
  val cycle_ = Cat(cycleh, cycle)

  when(es_valid_r) {
    printf(
      "[cycle=%0d][Execute] pc=%x inst=%x es_valid=%d es_busy=%d to_fs_ready=%d es_to_ws_valid=%d ws_ready=%d \n",
      cycle_,
      es_pipe_r.pc,
      es_pipe_r.inst,
      es_valid_r,
      es_busy,
      io.from_fs.ready,
      io.to_ws.valid,
      io.to_ws.ready
    )
  }

}
