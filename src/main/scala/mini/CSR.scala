// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util._

object CSR {
  val N = 0.U(3.W)
  val W = 1.U(3.W)
  val S = 2.U(3.W)
  val C = 3.U(3.W)
  val P = 4.U(3.W)

  // Supports machine & user modes
  val PRV_U = 0x0.U(2.W)
  val PRV_M = 0x3.U(2.W)

  // User-level CSR addrs
  val cycle    = 0xc00.U(12.W)
  val time     = 0xc01.U(12.W)
  val instret  = 0xc02.U(12.W)
  val cycleh   = 0xc80.U(12.W)
  val timeh    = 0xc81.U(12.W)
  val instreth = 0xc82.U(12.W)

  // Supervisor-level CSR addrs

  // Machine-level CSR addrs
  // Machine Information Registers
  val mimpid  = 0xf13.U(12.W)
  val mhartid = 0xf14.U(12.W)
  // Machine Trap Setup
  val mstatus = 0x300.U(12.W)
  val mtvec   = 0x305.U(12.W)
  val medeleg = 0x302.U(12.W)
  val mie     = 0x304.U(12.W)
  // Machine Trap Handling
  val mscratch = 0x340.U(12.W)
  val mepc     = 0x341.U(12.W)
  val mcause   = 0x342.U(12.W)
  val mtval    = 0x343.U(12.W)
  val mip      = 0x344.U(12.W)
  // Machine HITF
  val mtohost   = 0x780.U(12.W)
  val mfromhost = 0x781.U(12.W)

  val regs = List(
    cycle,
    time,
    instret,
    cycleh,
    timeh,
    instreth,
    mimpid,
    mhartid,
    mtvec,
    medeleg,
    mie,
    // mtimecmp,
    // mtime,
    // mtimeh,
    mscratch,
    mepc,
    mcause,
    mtval,
    mip,
    mtohost,
    mfromhost,
    mstatus
  )
}

object Cause {
  val InstAddrMisaligned  = 0x0.U
  val IllegalInst         = 0x2.U
  val Breakpoint          = 0x3.U
  val LoadAddrMisaligned  = 0x4.U
  val StoreAddrMisaligned = 0x6.U
  val Ecall               = 0x8.U
}

class CSRIO(xlen: Int) extends Bundle {
  val stall = Input(Bool())
  val cmd   = Input(UInt(3.W))
  val in    = Input(UInt(xlen.W))
  val out   = Output(UInt(xlen.W))
  // Excpetion
  val pc       = Input(UInt(xlen.W))
  val addr     = Input(UInt(xlen.W))
  val inst     = Input(UInt(xlen.W))
  val illegal  = Input(Bool())
  val st_type  = Input(UInt(2.W))
  val ld_type  = Input(UInt(3.W))
  val pc_check = Input(Bool())
  val expt     = Output(Bool())
  val evec     = Output(UInt(xlen.W))
  val epc      = Output(UInt(xlen.W))
  // HTIF
  val host = new HostIO(xlen)
}

class CSR(val xlen: Int) extends Module {
  val io = IO(new CSRIO(xlen))

  val csr_addr = io.inst(31, 20)
  val rs1_addr = io.inst(19, 15)
  dontTouch(csr_addr)

  // user counters
  val time     = RegInit(0.U(xlen.W))
  val timeh    = RegInit(0.U(xlen.W))
  val cycle    = RegInit(0.U(xlen.W))
  val cycleh   = RegInit(0.U(xlen.W))
  val instret  = RegInit(0.U(xlen.W))
  val instreth = RegInit(0.U(xlen.W))

  val mimpid  = 0.U(xlen.W) // not implemented
  val mhartid = 0.U(xlen.W) // only one hart

  // interrupt enable stack
  val MIE  = RegInit(false.B)
  val MPIE = RegInit(false.B)
  val MPP  = RegInit(0.U(2.W))
  // extention context status
  val FS   = 0.U(2.W)
  val XS   = 0.U(2.W)
  val MPRV = RegInit(0.U(1.W))
  val SD   = 0.U(1.W)

  val PRV = RegInit(CSR.PRV_M)

  val mstatus = Cat(
    SD,
    0.U(13.W),
    MPRV,
    XS,
    FS,
    MPP,
    0.U(2.W),
    0.U(1.W),
    MPIE,
    0.U(1.W),
    0.U(1.W),
    0.U(1.W),
    MIE,
    0.U(1.W),
    0.U(1.W),
    0.U(1.W)
  )
  val mtvec   = Consts.PC_EVEC.U(xlen.W)
  val medeleg = 0x0.U(xlen.W)

  // interrupt registers
  // val mip = Cat(0.U((xlen - 8).W), MTIP, HTIP, STIP, false.B, MSIP, HSIP, SSIP, false.B)
  // val mie = Cat(0.U((xlen - 8).W), MTIE, HTIE, STIE, false.B, MSIE, HSIE, SSIE, false.B)

  val mtimecmp = Reg(UInt(xlen.W))

  val mscratch = Reg(UInt(xlen.W))

  val mepc   = Reg(UInt(xlen.W))
  val mcause = Reg(UInt(xlen.W))
  val mtval  = Reg(UInt(xlen.W))

  val mtohost   = RegInit(0.U(xlen.W))
  val mfromhost = Reg(UInt(xlen.W))
  io.host.tohost := mtohost
  when(io.host.fromhost.valid) {
    mfromhost := io.host.fromhost.bits
  }

  val csrFile = Seq(
    BitPat(CSR.cycle)    -> cycle,
    BitPat(CSR.time)     -> time,
    BitPat(CSR.instret)  -> instret,
    BitPat(CSR.cycleh)   -> cycleh,
    BitPat(CSR.timeh)    -> timeh,
    BitPat(CSR.instreth) -> instreth,
    BitPat(CSR.mimpid)   -> mimpid,
    BitPat(CSR.mhartid)  -> mhartid,
    BitPat(CSR.mtvec)    -> mtvec,
    BitPat(CSR.medeleg)  -> medeleg,
    // BitPat(CSR.mie) -> mie,
    // BitPat(CSR.mtimecmp) -> mtimecmp,
    // BitPat(CSR.mtime)    -> time,
    // BitPat(CSR.mtimeh)   -> timeh,
    BitPat(CSR.mscratch) -> mscratch,
    BitPat(CSR.mepc)     -> mepc,
    BitPat(CSR.mcause)   -> mcause,
    BitPat(CSR.mtval)    -> mtval,
    // BitPat(CSR.mip) -> mip,
    BitPat(CSR.mtohost)   -> mtohost,
    BitPat(CSR.mfromhost) -> mfromhost,
    BitPat(CSR.mstatus)   -> mstatus
  )

  io.out := Lookup(csr_addr, 0.U, csrFile)

  val privValid = csr_addr(9, 8) <= PRV
  val privInst  = io.cmd === CSR.P

  val privDecTable = Array(
    Instructions.ECALL  -> List(Control.Y, Control.N, Control.N),
    Instructions.EBREAK -> List(Control.N, Control.Y, Control.N),
    Instructions.MRET   -> List(Control.N, Control.N, Control.Y)
  )

  val isEcall :: isEbreak :: isEret :: Nil = ListLookup(io.inst, List(Control.N, Control.N, Control.N), privDecTable)

  // println("isEcall type: " + isEcall.getClass.getName)

  val csrValid = csrFile.map(_._1 === csr_addr).reduce(_ || _)
  val csrRO    = csr_addr(11, 10).andR
  val wdata = MuxLookup(io.cmd, 0.U)(
    Seq(
      CSR.W -> io.in,
      CSR.S -> (io.out | io.in),
      CSR.C -> (io.out & ~io.in)
    )
  )
  val wen = (io.cmd === CSR.W) || (((io.cmd === CSR.S) || (io.cmd === CSR.C)) && wdata.orR)

  val iaddrInvalid = io.pc_check && io.addr(1)
  val laddrInvalid = MuxLookup(io.ld_type, false.B)(
    Seq(
      Control.LD_LW  -> io.addr(1, 0).orR,
      Control.LD_LH  -> io.addr(0),
      Control.LD_LHU -> io.addr(0)
    )
  )
  val saddrInvalid =
    MuxLookup(io.st_type, false.B)(Seq(
      Control.ST_SW -> io.addr(1, 0).orR,
      Control.ST_SH -> io.addr(0)
    ))
  val csrInvalid      = io.cmd(1, 0).orR && !csrValid;
  val csrPrivInvalid  = io.cmd(1, 0).orR && !privValid;
  val privInstInvalid = privInst && !privValid;
  val writeInvalid    = wen && csrRO;
  io.expt := io.illegal || iaddrInvalid || laddrInvalid || saddrInvalid ||
    csrInvalid || csrPrivInvalid || writeInvalid ||
    privInstInvalid || isEcall || isEbreak
  io.evec := mtvec
  io.epc  := mepc

  // Counters
  time                    := time + 1.U
  when(time.andR)(timeh   := timeh + 1.U)
  cycle                   := cycle + 1.U
  when(cycle.andR)(cycleh := cycleh + 1.U)
  val isInstRet =
    io.inst =/= Instructions.NOP && (!io.expt || isEcall || isEbreak) && !io.stall
  when(isInstRet)(instret                  := instret + 1.U)
  when(isInstRet && instret.andR)(instreth := instreth + 1.U)

  val cause = Mux(
    iaddrInvalid,
    Cause.InstAddrMisaligned,
    Mux(
      laddrInvalid,
      Cause.LoadAddrMisaligned,
      Mux(
        saddrInvalid,
        Cause.StoreAddrMisaligned,
        Mux(
          isEcall,
          Cause.Ecall + PRV,
          Mux(isEbreak, Cause.Breakpoint, Cause.IllegalInst)
        )
      )
    )
  )

  when(!io.stall) {
    when(io.expt) {
      mepc   := io.pc >> 2 << 2
      mcause := cause

      // xpp = mode
      MPP := PRV
      // XPIE = XIE
      MPIE := MIE
      // mode = x
      PRV := CSR.PRV_M
      // XIE = 0
      MIE := false.B

      when(iaddrInvalid || laddrInvalid || saddrInvalid)(mtval := io.addr)
    }.elsewhen(isEret) {
      PRV  := MPP
      MIE  := MPIE
      MPP  := CSR.PRV_U
      MPIE := true.B
    }.elsewhen(wen) {
      when(csr_addr === CSR.mstatus) {
        MPP  := wdata(12, 11)
        MPIE := wdata(7)
        MIE  := wdata(3)
      }
        // .elsewhen(csr_addr === CSR.mip) {
        //   MTIP := wdata(7)
        //   MSIP := wdata(3)
        // }.elsewhen(csr_addr === CSR.mie) {
        //   MTIE := wdata(7)
        //   MSIE := wdata(3)
        // }
        // .elsewhen(csr_addr === CSR.mtime)(time := wdata)
        //   .elsewhen(csr_addr === CSR.mtimeh)(timeh := wdata)
        //   .elsewhen(csr_addr === CSR.mtimecmp)(mtimecmp := wdata)
        .elsewhen(csr_addr === CSR.mscratch)(mscratch := wdata)
        .elsewhen(csr_addr === CSR.mepc)(mepc := wdata >> 2.U << 2.U)
        .elsewhen(csr_addr === CSR.mcause)(mcause := wdata & (BigInt(
          1
        ) << (xlen - 1) | 0xf).U)
        .elsewhen(csr_addr === CSR.mtval)(mtval := wdata)
        .elsewhen(csr_addr === CSR.mtohost)(mtohost := wdata)
        .elsewhen(csr_addr === CSR.mfromhost)(mfromhost := wdata)
    }
  }

  // debug
  when(io.expt) {
    printf(
      "Exception: pc=%x cause=0x%x csr_addr=0x%x mtvec=0x%x [illegal=%x iaddrInvalid=%x laddrInvalid=%x saddrInvalid=%x csrInvalid=%x csrPrivInvalid=%x writeInvalid=%x privInstInvalid=%x isEcall=%x isEbreak=%x]\n",
      io.pc,
      cause,
      csr_addr,
      mtvec,
      io.illegal,
      iaddrInvalid,
      laddrInvalid,
      saddrInvalid,
      csrInvalid,
      csrPrivInvalid,
      writeInvalid,
      privInstInvalid,
      isEcall,
      isEbreak
    )
  }
}
