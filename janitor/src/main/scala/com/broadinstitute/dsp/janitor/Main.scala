package com.broadinstitute.dsp
package janitor

import cats.effect.IO
import cats.syntax.all._
import com.monovore.decline.{CommandApp, _}
import scala.concurrent.ExecutionContext.global

object Main
    extends CommandApp(
      name = "janitor",
      header = "Clean up prod resources deemed not utilized",
      version = "0.0.1",
      main = {
        implicit val cs = IO.contextShift(global)
        implicit val timer = IO.timer(global)

        val enableDryRun = Opts.flag("dryRun", "Default to true").orFalse.withDefault(true)
        val shouldCheckAll = Opts.flag("all", "run all checks").orFalse

        val shouldCheckKubernetesClustersToBeRemoved =
          Opts.flag("checkKubernetesClustersToRemove", "check kubernetes clusters that should be removed").orFalse
        val shouldCheckNodepoolsToBeRemoved =
          Opts.flag("checkNodepoolsToRemove", "check nodepools that should be removed").orFalse
        val shouldCheckStagingBucketsToBeRemoved =
          Opts.flag("checkStagingBucketsToRemove", "check staging buckets that should be removed").orFalse

        (enableDryRun,
         shouldCheckAll,
         shouldCheckKubernetesClustersToBeRemoved,
         shouldCheckNodepoolsToBeRemoved,
         shouldCheckStagingBucketsToBeRemoved
        ).mapN {
          (dryRun,
           checkAll,
           shouldCheckKubernetesClustersToBeRemoved,
           shouldCheckNodepoolsToBeRemoved,
           shouldCheckStagingBucketsToBeRemoved
          ) =>
            Janitor
              .run[IO](
                isDryRun = dryRun,
                shouldCheckAll = checkAll,
                shouldCheckKubernetesClustersToBeRemoved = shouldCheckKubernetesClustersToBeRemoved,
                shouldCheckNodepoolsToBeRemoved = shouldCheckNodepoolsToBeRemoved,
                shouldCheckStagingBucketsToBeRemoved = shouldCheckStagingBucketsToBeRemoved
              )
              .compile
              .drain
              .unsafeRunSync()
        }
      }
    )
