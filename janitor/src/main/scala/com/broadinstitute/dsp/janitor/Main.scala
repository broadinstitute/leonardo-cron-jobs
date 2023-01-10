package com.broadinstitute.dsp
package janitor

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline._
import com.monovore.decline.effect.CommandIOApp

object Main
    extends CommandIOApp(name = "janitor",
                         header = "Clean up prod resources deemed not utilized",
                         version = "2022.1.5"
    ) {
  override def main: Opts[IO[ExitCode]] = {
    val enableDryRun = Opts.flag("dryRun", "Default to true").orFalse.withDefault(true)
    val shouldCheckAll = Opts.flag("all", "run all checks").orFalse

    val shouldCheckKubernetesClustersToBeRemoved =
      Opts.flag("checkKubernetesClustersToRemove", "check kubernetes clusters that should be removed").orFalse
    val shouldCheckNodepoolsToBeRemoved =
      Opts.flag("checkNodepoolsToRemove", "check nodepools that should be removed").orFalse
    val shouldCheckStagingBucketsToBeRemoved =
      Opts.flag("checkStagingBucketsToRemove", "check staging buckets that should be removed").orFalse
    val shouldCheckStuckAppsToBeReported =
      Opts.flag("checkStuckAppsToBeReported", "check apps that are stuck in creating or deleting status").orFalse

    (enableDryRun,
     shouldCheckAll,
     shouldCheckKubernetesClustersToBeRemoved,
     shouldCheckNodepoolsToBeRemoved,
     shouldCheckStagingBucketsToBeRemoved,
     shouldCheckStuckAppsToBeReported
    ).mapN {
      (dryRun,
       checkAll,
       shouldCheckKubernetesClustersToBeRemoved,
       shouldCheckNodepoolsToBeRemoved,
       shouldCheckStagingBucketsToBeRemoved,
       shouldCheckStuckAppsToBeReported
      ) =>
        Janitor
          .run[IO](
            isDryRun = dryRun,
            shouldCheckAll = checkAll,
            shouldCheckKubernetesClustersToBeRemoved = shouldCheckKubernetesClustersToBeRemoved,
            shouldCheckNodepoolsToBeRemoved = shouldCheckNodepoolsToBeRemoved,
            shouldCheckStagingBucketsToBeRemoved = shouldCheckStagingBucketsToBeRemoved,
            shouldCheckStuckAppsToBeReported = shouldCheckStuckAppsToBeReported
          )
          .compile
          .drain
          .as(ExitCode.Success)
    }
  }
}
