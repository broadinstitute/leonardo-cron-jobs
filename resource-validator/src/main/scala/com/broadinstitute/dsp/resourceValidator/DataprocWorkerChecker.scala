package com.broadinstitute.dsp.resourceValidator

import cats.effect.{Concurrent, Timer}
import cats.mtl.Ask
import cats.syntax.all._
import com.broadinstitute.dsp.{
  resourceValidator,
  CheckRunner,
  CheckRunnerConfigs,
  CheckRunnerDeps,
  RuntimeCheckerDeps,
  RuntimeWithWorkers
}
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.google2.DataprocClusterName
import org.broadinstitute.dsde.workbench.model.TraceId

object DataprocWorkerChecker {
  val unfixableAnomalyCheckType = "unfixable-dataproc-workers"
  def impl[F[_]: Timer](
    dbReader: DbReader[F],
    deps: RuntimeCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, RuntimeWithWorkers] =
    new CheckRunner[F, RuntimeWithWorkers] {
      override def appName: String = resourceValidator.appName

      val checkType = s"number-of-dataproc-workers"
      override def configs = CheckRunnerConfigs(checkType, shouldAlert = true)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      override def resourceToScan: fs2.Stream[F, RuntimeWithWorkers] = dbReader.getRuntimesWithWorkers

      override def checkResource(runtime: RuntimeWithWorkers, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[RuntimeWithWorkers]] =
        for {
          clusterOpt <- deps.dataprocService
            .getCluster(runtime.r.googleProject, runtime.r.region, DataprocClusterName(runtime.r.runtimeName))
          runtime <- clusterOpt.flatTraverse { c =>
            val doesPrimaryWorkerMatch =
              runtime.workerConfig.numberOfWorkers.getOrElse(0) == c.getConfig.getWorkerConfig.getNumInstances
            val doesSecondaryWorkerMatch =
              if (
                c.getConfig.getSecondaryWorkerConfig.getNumInstances == 0 && runtime.r.status.toLowerCase == "stopped"
              )
                true // We remove preemptibles before stopping clusters. Therefore, we don't consider this case an anomaly.
              else
                runtime.workerConfig.numberOfPreemptibleWorkers
                  .getOrElse(0) == c.getConfig.getSecondaryWorkerConfig.getNumInstances

            val isAnomalyDetected = !(doesPrimaryWorkerMatch && doesSecondaryWorkerMatch)

            isAnomalyDetected match {
              case true =>
                isDryRun match {
                  case true =>
                    logger
                      .warn(
                        s"${runtime} has an anomaly with the number of workers in google. \n\tPrimary worker match status: $doesPrimaryWorkerMatch\n\tSecondary worker match status: ${doesSecondaryWorkerMatch}"
                      )
                      .as(Option(runtime))
                  // If the number of primary workers is less than 2, modifying workers requires a creation and deletion. Leo does not handles these, so we will not either
                  case false if c.getConfig.getWorkerConfig.getNumInstances >= 2 =>
                    if (runtime.r.status.toLowerCase == "running")
                      deps.dataprocService
                        .resizeCluster(
                          runtime.r.googleProject,
                          runtime.r.region,
                          DataprocClusterName(runtime.r.runtimeName),
                          if (doesPrimaryWorkerMatch) None else Some(runtime.workerConfig.numberOfWorkers.getOrElse(0)),
                          if (doesSecondaryWorkerMatch) None
                          else Some(runtime.workerConfig.numberOfPreemptibleWorkers.getOrElse(0))
                        )
                        .void
                        .handleErrorWith { case e: com.google.api.gax.rpc.ApiException =>
                          logger.warn(e)(
                            s"${runtime} has an anomaly with the number of workers in google, and the resize failed."
                          ) >> deps.checkRunnerDeps.metrics.incrementCounter(s"$appName/$checkType/failure")
                        } >>
                        logger
                          .warn(
                            s"${runtime} has an anomaly with the number of workers in google. \n\tPrimary work match status: $doesPrimaryWorkerMatch\n\tSecondary worker match status: ${doesSecondaryWorkerMatch}"
                          )
                          .as(Option(runtime))
                    else
                      logger
                        .warn(
                          s"${runtime} has an anomaly with the number of workers in google, but we cannot attempt to fix it because the cluster is not running. \n\tNumber of primary workers in google: ${c.getConfig.getWorkerConfig.getNumInstances}. Number of secondary workers in google ${c.getConfig.getSecondaryWorkerConfig.getNumInstances}."
                        )
                        .as(Option(runtime))
                  case _ =>
                    // Here we log a metric when we detect the aforementioned anomaly is unfixable (workers < 2). This metric is in addition to the one already reported for finding a worker mismatch
                    deps.checkRunnerDeps.metrics.incrementCounter(s"$appName/$unfixableAnomalyCheckType") >>
                      logger
                        .warn(
                          s"${runtime.toString} has an anomaly with the number of workers in google. Unable to fix the anomaly. Recording a metric and moving on."
                        )
                        .as(Option(runtime))
                }
              case false => F.pure(none[RuntimeWithWorkers])
            }
          }
        } yield runtime

    }
}
