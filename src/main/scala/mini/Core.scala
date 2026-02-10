// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util.Valid

case class CoreConfig(
    xlen:       Int,
    makeAlu:    Int => Alu = new AluSimple(_),
    makeBrCond: Int => BrCond = new BrCondSimple(_),
    makeImmGen: Int => ImmGen = new ImmGenWire(_)
)

class HostIO(xlen: Int) extends Bundle {
  val fromhost = Flipped(Valid(UInt(xlen.W)))
  val tohost   = Output(UInt(xlen.W))
}

class CoreIO(xlen: Int) extends Bundle {
  val host   = new HostIO(xlen)
  val icache = Flipped(new CacheIO(xlen, xlen))
  val dcache = Flipped(new CacheIO(xlen, xlen))
}

class Core(val conf: CoreConfig) extends Module {
  val io   = IO(new CoreIO(conf.xlen))
  val pipe = Module(new Pipeline(conf))

  io.host <> pipe.io.host
  pipe.io.icache <> io.icache
  pipe.io.dcache <> io.dcache
}
