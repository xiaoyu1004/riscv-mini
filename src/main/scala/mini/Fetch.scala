// See LICENSE for license details.

package mini
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

class FetchIO(xlen: Int) extends Bundle {
  val icache  = Flipped(new CacheIO(xlen, xlen))
  val to_es   = Decoupled(new Fetch2ExecuteBusData(xlen))
  val from_es = Flipped(new Execute2FetchCtrl(xlen))
  val from_ws = Flipped(new Writeback2FetchCtrl(xlen))
}

class FetchStage(val conf: CoreConfig) extends Module {
  val io = IO(new FetchIO(conf.xlen))

  val fs_valid_r = RegNext(!reset.asBool, false.B)

  val br_taken     = io.from_es.br_taken
  val icache_ready = io.icache.resp.valid
  val flush        = io.from_ws.flush

  val pending_discard = RegInit(false.B)

  when((flush || br_taken) && !icache_ready) {
    pending_discard := true.B
  }.elsewhen(pending_discard && icache_ready) {
    pending_discard := false.B
  }

  val next_pc_stall = pending_discard

  val next_pc = Wire(UInt(conf.xlen.W))
  val pc_en   = (icache_ready && io.to_es.ready && !pending_discard) || br_taken || flush
  val pc_r = RegEnable(
    next_pc,
    Consts.PC_START.U(conf.xlen.W) - 4.U(conf.xlen.W),
    pc_en
  )

  // Next Program Counter
  next_pc := MuxCase(
    pc_r + 4.U,
    IndexedSeq(
      flush         -> io.from_ws.flush_pc,
      br_taken      -> io.from_es.br_pc,
      next_pc_stall -> pc_r
    )
  )

  val fs_ready_go = icache_ready && !flush && !br_taken && !pending_discard

  io.to_es.bits.pc   := pc_r
  io.to_es.bits.inst := io.icache.resp.bits.data
  io.to_es.valid     := fs_valid_r && fs_ready_go

  val icache_req_valid = io.to_es.ready

  io.icache.req.bits.addr := next_pc
  io.icache.req.bits.data := 0.U
  io.icache.req.bits.mask := 0.U
  io.icache.req.valid     := icache_req_valid
  io.icache.abort         := false.B

  val cycle  = Wire(UInt(conf.xlen.W))
  val cycleh = Wire(UInt(conf.xlen.W))
  val cycle_ = Cat(cycleh, cycle)

  when(fs_valid_r) {
    printf(
      "[cycle=%0d][Fetch] pc=%x next_pc=%x fs_valid=%d fs_ready_go=%d next_pc_stall=%d flush=%d flush_pc=%x br_taken=%d br_pc=%x fs_to_es_valid=%d icache_ready=%d icache_resp_inst=%x es_ready=%d \n",
      cycle_,
      pc_r,
      next_pc,
      fs_valid_r,
      fs_ready_go,
      next_pc_stall,
      flush,
      io.from_ws.flush_pc,
      br_taken,
      io.from_es.br_pc,
      io.to_es.valid,
      icache_ready,
      io.icache.resp.bits.data,
      io.to_es.ready
    )
  }

}
