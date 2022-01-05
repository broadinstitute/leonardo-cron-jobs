package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.syntax.all._
import com.monovore.decline.{CommandApp, _}
import cats.effect.unsafe.implicits.global

object Main
    extends CommandApp(
      name = "resource-validator",
      header = "Update Google resources to reflect Leonardo database",
      version = "0.0.1",
      main = {
        val enableDryRun = Opts.flag("dryRun", "Default to true").orFalse.withDefault(true)
        val shouldCheckAll = Opts.flag("all", "run all checks").orFalse
        val shouldCheckDeletedRuntimes = Opts.flag("checkDeletedRuntimes", "check all deleted runtimes").orFalse
        val shouldCheckErroredRuntimes = Opts.flag("checkErroredRuntimes", "check all errored runtimes").orFalse
        val shouldCheckStoppedRuntimes = Opts.flag("checkStoppedRuntimes", "check all stopped runtimes").orFalse
        val shouldCheckDeletedKubernetesClusters =
          Opts.flag("checkDeletedKubernetesClusters", "check all deleted or errored kubernetes clusters").orFalse
        val shouldCheckDeletedNodepools =
          Opts.flag("checkDeletedNodepools", "check all deleted or errored nodepools").orFalse
        val shouldCheckDeletedDisks = Opts.flag("checkDeletedDisks", "check all deleted disks").orFalse
        val shouldCheckInitBuckets =
          Opts.flag("checkInitBuckets", "checks that init buckets for deleted runtimes are deleted").orFalse
        val shouldCheckDataprocWorkers =
          Opts.flag("checkDataprocWorkers", "check dataproc workers").orFalse

        (enableDryRun,
         shouldCheckAll,
         shouldCheckDeletedRuntimes,
         shouldCheckErroredRuntimes,
         shouldCheckStoppedRuntimes,
         shouldCheckDeletedKubernetesClusters,
         shouldCheckDeletedNodepools,
         shouldCheckDeletedDisks,
         shouldCheckInitBuckets,
         shouldCheckDataprocWorkers
        ).mapN {
          (dryRun,
           checkAll,
           shouldCheckDeletedRuntimes,
           shouldCheckErroredRuntimes,
           shouldCheckStoppedRuntimes,
           shouldCheckDeletedKubernetesClusters,
           shouldCheckDeletedNodepools,
           shouldCheckDeletedDisks,
           shouldCheckInitBuckets,
           shouldCheckDataprocWorkers
          ) =>
            ResourceValidator
              .run[IO](
                isDryRun = dryRun,
                shouldCheckAll = checkAll,
                shouldCheckDeletedRuntimes = shouldCheckDeletedRuntimes,
                shouldCheckErroredRuntimes = shouldCheckErroredRuntimes,
                shouldCheckStoppedRuntimes = shouldCheckStoppedRuntimes,
                shouldCheckDeletedKubernetesCluster = shouldCheckDeletedKubernetesClusters,
                shouldCheckDeletedNodepool = shouldCheckDeletedNodepools,
                shouldCheckDeletedDisks = shouldCheckDeletedDisks,
                shouldCheckInitBuckets = shouldCheckInitBuckets,
                shouldCheckDataprocWorkers = shouldCheckDataprocWorkers
              )
              .compile
              .drain
              .unsafeRunSync()
        }
      }
    )
