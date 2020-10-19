package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.implicits._
import com.monovore.decline.{CommandApp, _}
import scala.concurrent.ExecutionContext.global

object Main
    extends CommandApp(
      name = "resource-validator",
      header = "Update Google resources to reflect Leonardo database",
      version = "0.0.1",
      main = {
        implicit val cs = IO.contextShift(global)
        implicit val timer = IO.timer(global)

        val enableDryRun = Opts.flag("dryRun", "Default to true").map(_ => true).withDefault(true)
        val shouldRunAll = Opts.flag("all", "run all checks").orFalse
        val shouldRunCheckDeletedRuntimes = Opts.flag("checkDeletedRuntimes", "check all deleted runtimes").orFalse
        val shouldRunCheckErroredRuntimes = Opts.flag("checkErroredRuntimes", "check all errored runtimes").orFalse
        val shouldRunCheckDeletedDisks = Opts.flag("checkDeletedDisks", "check all deleted disks").orFalse

        (enableDryRun,
         shouldRunAll,
         shouldRunCheckDeletedRuntimes,
         shouldRunCheckErroredRuntimes,
         shouldRunCheckDeletedDisks).mapN {
          (dryRun, runAll, runCheckDeletedRuntimes, runCheckErroredRuntimes, ifRunCheckDeletedDisks) =>
            ResourceValidator
              .run[IO](dryRun, runAll, runCheckDeletedRuntimes, runCheckErroredRuntimes, ifRunCheckDeletedDisks)
              .compile
              .drain
              .unsafeRunSync()
        }
      }
    )
