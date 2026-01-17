// See LICENSE for license details.

package mini

import circt.stage.ChiselStage
object Main extends App {
  val config = MiniConfig()
  ChiselStage.emitSystemVerilogFile(
    new Tile(
      coreParams = config.core,
      nastiParams = config.nasti,
      cacheParams = config.cache
    ),
    args,
    firtoolOpts = Array(
      // "-disable-layers=Verification",
      // "-disable-layers=Verification.Assert",
      // "-disable-layers=Verification.Assume",
      // "-disable-layers=Verification.Cover",
      "-disable-all-randomization",
      // "-strip-debug-info",
      "--lowering-options=disallowPackedArrays",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
