package com.broadinstitute.dsp
package nuker

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline._
import com.monovore.decline.effect.CommandIOApp

object Main
    extends CommandIOApp(name = "nuker",
                         header = "Clean up cloud resources created by Leonardo in dev/qa projects",
                         version = "2022.1.5"
    ) {
  override def main: Opts[IO[ExitCode]] = {
    val enableDryRun = Opts.flag("dryRun", "Default to true").orFalse.withDefault(true)
    val shouldRunAll = Opts.flag("all", "run all checks").orFalse
    val shouldDeletePubsubTopics =
      Opts.flag("deletePubsubTopics", "delete all fiab pubsub topics").orFalse

    (enableDryRun, shouldRunAll, shouldDeletePubsubTopics).mapN { (dryRun, runAll, deletePubsubTopics) =>
      Nuker
        .run[IO](dryRun, runAll, deletePubsubTopics)
        .compile
        .drain
        .as(ExitCode.Success)
    }
  }
}
