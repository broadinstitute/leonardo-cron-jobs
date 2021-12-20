package com.broadinstitute.dsp
package nuker

import cats.effect.IO
import cats.syntax.all._
import com.monovore.decline._
import cats.effect.unsafe.implicits.global

object Main
    extends CommandApp(
      name = "nuker",
      header = "Clean up cloud resources created by Leonardo in dev/qa projects",
      version = "0.0.1",
      main = {
        val enableDryRun = Opts.flag("dryRun", "Default to true").orFalse.withDefault(true)
        val shouldRunAll = Opts.flag("all", "run all checks").orFalse
        val shouldDeletePubsubTopics =
          Opts.flag("deletePubsubTopics", "delete all fiab pubsub topics").orFalse

        (enableDryRun, shouldRunAll, shouldDeletePubsubTopics).mapN { (dryRun, runAll, deletePubsubTopics) =>
          Nuker
            .run[IO](dryRun, runAll, deletePubsubTopics)
            .compile
            .drain
            .unsafeRunSync()
        }
      }
    )
