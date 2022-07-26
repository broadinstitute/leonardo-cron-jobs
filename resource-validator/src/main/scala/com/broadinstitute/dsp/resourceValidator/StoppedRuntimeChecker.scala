package com.broadinstitute.dsp
package resourceValidator

import cats.Parallel
import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import com.google.cloud.compute.v1.Instance
import com.google.cloud.dataproc.v1.ClusterStatus
import org.broadinstitute.dsde.workbench.google2.DataprocClusterName
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.util2.InstanceName
import org.typelevel.log4cats.Logger

// Implements CheckRunner[F[_], A]
object StoppedRuntimeChecker {
  def iml[F[_]: Parallel](
    dbReader: DbReader[F],
    deps: RuntimeCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Runtime] =
    new CheckRunner[F, Runtime] {
      override def appName: String = resourceValidator.appName
      override def configs = CheckRunnerConfigs(s"stopped-runtime", shouldAlert = true)
      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps
      override def resourceToScan: fs2.Stream[F, Runtime] = dbReader.getStoppedRuntimes

      override def checkResource(runtime: Runtime, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] =
        runtime match {
          case x: Runtime.Dataproc =>
            checkDataprocCluster(x, isDryRun)
          case x: Runtime.Gce =>
            checkGceRuntime(x, isDryRun)
          case _: Runtime.AzureVM =>
            // TODO: IA-3289 Implement check Azure VM
            logger.info(s"Azure VM is not supported yet").as(None)
        }

      private def checkGceRuntime(runtime: Runtime.Gce, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] =
        for {
          runtimeOpt <- deps.computeService
            .getInstance(runtime.googleProject, runtime.zone, InstanceName(runtime.runtimeName))
          runningRuntimeOpt <- runtimeOpt.flatTraverse { rt =>
            val expectedStatus =
              Set(Instance.Status.STOPPED.name().toUpperCase, Instance.Status.TERMINATED.name().toUpperCase())
            if (!expectedStatus.contains(rt.getStatus.toUpperCase))
              if (isDryRun)
                logger
                  .warn(s"${runtime} is ${rt.getStatus}. It needs to be stopped.")
                  .as[Option[Runtime]](Some(runtime))
              else
                logger.warn(s"${runtime} is ${rt.getStatus}. Going to stop it.") >>
                  // In contrast to in Leo, we're not setting the shutdown script metadata before stopping the instance
                  // in order to keep things simple since our main goal here is to prevent unintended cost to users.
                  deps.computeService
                    .stopInstance(runtime.googleProject, runtime.zone, InstanceName(runtime.runtimeName))
                    .void
                    .as[Option[Runtime]](Some(runtime))
            else F.pure(none[Runtime])
          }
        } yield runningRuntimeOpt

      private def checkDataprocCluster(runtime: Runtime.Dataproc, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] = {
        val clusterName = DataprocClusterName(runtime.runtimeName)
        val project = runtime.googleProject

        for {
          clusterOpt <- deps.dataprocService
            .getCluster(runtime.googleProject, runtime.region, DataprocClusterName(runtime.runtimeName))
          runningClusterOpt <- clusterOpt.flatTraverse { cluster =>
            if (cluster.getStatus.getState == ClusterStatus.State.RUNNING) {
              for {
                rt <-
                  if (isDryRun)
                    logger
                      .warn(s"Dry run: Cluster (${runtime}) is Running It needs to be stopped.")
                      .as(runtime.some)
                  else
                    logger.warn(
                      s"Cluster (${runtime}) has running instances(s). Going to stop it."
                    ) >> deps.dataprocService
                      // In contrast to in Leo, we're not setting the shutdown script metadata before stopping the instance
                      // in order to keep things simple since our main goal here is to prevent unintended cost to users.
                      .stopCluster(project, runtime.region, clusterName, metadata = None, true)
                      .void
                      .as(runtime.some)
              } yield rt
            } else F.pure(none[Runtime.Dataproc])
          }
        } yield runningClusterOpt: Option[Runtime]
      }
    }
}
