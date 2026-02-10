// See LICENSE for license details.

package mini
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

class WritebackIO(xlen: Int) extends Bundle {
  val host         = new HostIO(xlen)
  val from_es      = Flipped(Decoupled(new Execute2WritebackBusData(xlen)))
  val dcache_abort = Output(Bool())
  val dache_resp   = Flipped(Valid(new CacheResp(xlen)))
  val to_fs        = new Writeback2FetchCtrl(xlen)
  val to_es        = new Writeback2ExecuteCtrl(xlen)
}

class WritebackStage(conf: CoreConfig) extends Module {
  val io = IO(new WritebackIO(conf.xlen))

  val csr = Module(new CSR(conf.xlen))

  val ws_valid_r = RegEnable(io.from_es.valid, false.B, io.from_es.ready)
  val ws_pipe_r =
    RegEnable(io.from_es.bits, io.from_es.valid && io.from_es.ready)

  val ws_busy = ws_pipe_r.ld_type.orR && !io.dache_resp.valid

  io.from_es.ready := !ws_valid_r || !ws_busy

  // Load
  val loffset = (ws_pipe_r.alu_out(1) << 4.U) | (ws_pipe_r.alu_out(0) << 3.U)
  val lshift  = io.dache_resp.bits.data >> loffset
  val load = MuxLookup(ws_pipe_r.ld_type, io.dache_resp.bits.data.zext)(
    Seq(
      Control.LD_LH  -> lshift(15, 0).asSInt,
      Control.LD_LB  -> lshift(7, 0).asSInt,
      Control.LD_LHU -> lshift(15, 0).zext,
      Control.LD_LBU -> lshift(7, 0).zext
    )
  )

  // CSR access
  csr.io.stall    := false.B
  csr.io.in       := ws_pipe_r.csr_in
  csr.io.cmd      := Mux(ws_valid_r, ws_pipe_r.csr_cmd, CSR.N)
  csr.io.inst     := Mux(ws_valid_r, ws_pipe_r.inst, Instructions.NOP)
  csr.io.pc       := ws_pipe_r.pc
  csr.io.addr     := ws_pipe_r.alu_out
  csr.io.illegal  := ws_valid_r && ws_pipe_r.illegal
  csr.io.pc_check := ws_valid_r && ws_pipe_r.pc_check
  csr.io.ld_type  := Mux(ws_valid_r, ws_pipe_r.ld_type, Control.LD_XXX)
  csr.io.st_type  := Mux(ws_valid_r, ws_pipe_r.st_type, Control.ST_XXX)

  io.host <> csr.io.host

  // Regfile Write
  val rf_wen   = ws_valid_r && ws_pipe_r.wb_en && !ws_busy && !csr.io.expt
  val rf_waddr = ws_pipe_r.inst(11, 7)
  val rf_wdata =
    MuxLookup(ws_pipe_r.wb_sel, ws_pipe_r.alu_out.zext)(
      Seq(
        Control.WB_MEM -> load,
        Control.WB_PC4 -> (ws_pipe_r.pc + 4.U).zext,
        Control.WB_CSR -> csr.io.out.zext
      )
    ).asUInt

  // Abort store when there's an excpetion
  io.dcache_abort := csr.io.expt

  val expt_ret = (ws_pipe_r.csr_cmd === CSR.P) && (ws_pipe_r.pc_sel === Control.PC_EPC)

  val flush = ws_valid_r && (ws_pipe_r.inst_kill || csr.io.expt || expt_ret)

  io.to_fs.flush := flush
  io.to_fs.flush_pc := MuxCase(
    ws_pipe_r.pc + 4.U,
    IndexedSeq(
      csr.io.expt         -> csr.io.evec,
      expt_ret            -> csr.io.epc,
      ws_pipe_r.inst_kill -> (ws_pipe_r.pc + 4.U)
    )
  )

  io.to_es.wb_en   := rf_wen
  io.to_es.wb_addr := rf_waddr
  io.to_es.wb_data := rf_wdata
  io.to_es.flush   := flush

  val cycle  = Wire(UInt(conf.xlen.W))
  val cycleh = Wire(UInt(conf.xlen.W))
  val cycle_ = Cat(cycleh, cycle)

  when(ws_valid_r) {
    printf(
      "[cycle=%0d][Writeback] pc=%x inst=%x ws_valid=%d ws_busy=%d rf_wen=%d rf_waddr=%x rf_wdata=%x ld_or_st_addr=%x \n",
      cycle_,
      ws_pipe_r.pc,
      ws_pipe_r.inst,
      ws_valid_r,
      ws_busy,
      rf_wen,
      rf_waddr,
      rf_wdata,
      ws_pipe_r.alu_out
    )
  }

}
