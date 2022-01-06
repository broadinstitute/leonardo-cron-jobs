package com.broadinstitute.dsp.zombieMonitor

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline._

object Main
    extends CommandIOApp(name = "zombie-monitor",
                         header = "Update Leonardo database to reflect google resource status",
                         version = "2022.1.5"
    ) {
  override def main: Opts[IO[ExitCode]] = {
    val enableDryRun = Opts.flag("dryRun", "Default to true").orFalse.withDefault(true)
    val shouldRunAll = Opts.flag("all", "run all checks").orFalse
    val shouldCheckDeletedOrErrorRuntimes =
      Opts.flag("checkDeletedOrErroredRuntimes", "check all deleted or error-ed runtimes").orFalse
    val shouldCheckDeletedDisks = Opts.flag("checkDeletedDisks", "check all deleted runtimes").orFalse
    val shouldCheckDeletedK8sClusters = Opts.flag("checkDeletedK8sClusters", "check all deleted clusters").orFalse
    val shouldCheckDeletedOrErroredNodepools =
      Opts.flag("checkDeletedNodepools", "check all deleted or errored nodepools").orFalse

    (enableDryRun,
     shouldRunAll,
     shouldCheckDeletedOrErrorRuntimes,
     shouldCheckDeletedDisks,
     shouldCheckDeletedK8sClusters,
     shouldCheckDeletedOrErroredNodepools
    ).mapN { (dryRun, runAll, runCheckDeletedRuntimes, checkDisks, runCheckDeletedK8sClusters, checkNodepools) =>
      ZombieMonitor
        .run[IO](dryRun, runAll, runCheckDeletedRuntimes, checkDisks, runCheckDeletedK8sClusters, checkNodepools)
        .compile
        .drain
        .as(ExitCode.Success)
    }
  }
}
