package com.broadinstitute.dsp
package resourceValidator

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.google2.{DataprocClusterName, InstanceName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

// Implements CheckRunner[F[_], A]
object ErroredRuntimeChecker {
  def iml[F[_]](
    dbReader: DbReader[F],
    deps: RuntimeCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Runtime] =
    new CheckRunner[F, Runtime] {
      override def appName: String = resourceValidator.appName
      override def configs = CheckRunnerConfigs(s"errored-runtime", shouldAlert = true)
      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps
      override def resourceToScan: fs2.Stream[F, Runtime] = dbReader.getErroredRuntimes

      override def checkResource(runtime: Runtime, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] = runtime match {
        case x: Runtime.Dataproc =>
          checkDataprocCluster(x, isDryRun)
        case x: Runtime.Gce =>
          checkGceRuntime(x, isDryRun)
        case x: Runtime.AzureVM =>
          //TODO: IA-3289 Implement check Azure VM
          logger.info(s"Azure VM is not supported yet").as(None)
      }

      def checkDataprocCluster(runtime: Runtime.Dataproc, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] =
        for {
          clusterOpt <- deps.dataprocService
            .getCluster(runtime.googleProject, runtime.region, DataprocClusterName(runtime.runtimeName))
          r <- clusterOpt.flatTraverse[F, Runtime] { cluster =>
            for {
              isBillingEnabled <- deps.billingService.isBillingEnabled(runtime.googleProject)
              r <-
                if (cluster.getStatus.getState.name.toUpperCase == "ERROR")
                  logger
                    .warn(
                      s"${runtime} still exists in ${cluster.getStatus.getState.name} status. It needs to be deleted. isBillingEnabled: $isBillingEnabled. Project: ${runtime.googleProject}"
                    )
                    .as(None)
                else
                  isBillingEnabled match {
                    case true =>
                      logger.warn(
                        s"${runtime} still exists in ${cluster.getStatus.getState.name} status with billing enabled. Going to delete it."
                      ) >> {
                        if (isDryRun) F.pure(Some(runtime))
                        else
                          deps.dataprocService
                            .deleteCluster(runtime.googleProject,
                                           runtime.region,
                                           DataprocClusterName(runtime.runtimeName)
                            )
                            .void
                            .as(Option(runtime))
                      }
                    case false =>
                      logger
                        .info(
                          s"$runtime has been reported from getCluster, but billing is disabled so cannot perform any actions"
                        )
                        .as(none[Runtime])
                  }
            } yield r
          }
        } yield r

      def checkGceRuntime(runtime: Runtime.Gce, isDryRun: Boolean): F[Option[Runtime]] =
        for {
          runtimeOpt <- deps.computeService
            .getInstance(runtime.googleProject, runtime.zone, InstanceName(runtime.runtimeName))
          _ <- runtimeOpt.traverse_ { rt =>
            if (isDryRun)
              logger.warn(s"${runtime} still exists in ${rt.getStatus} status. It needs to be deleted.")
            else
              logger.warn(s"${runtime} still exists in ${rt.getStatus} status. Going to delete it.") >>
                deps.computeService
                  .deleteInstance(runtime.googleProject, runtime.zone, InstanceName(runtime.runtimeName))
                  .void
          }
        } yield runtimeOpt.fold(none[Runtime])(_ => Some(runtime))

    }
}
