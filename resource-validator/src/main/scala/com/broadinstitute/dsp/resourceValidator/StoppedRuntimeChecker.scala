package com.broadinstitute.dsp
package resourceValidator

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.mtl.Ask
import com.broadinstitute.dsp.CloudService.{Dataproc, Gce}
import com.google.cloud.compute.v1.Instance
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import org.broadinstitute.dsde.workbench.google2.{DataprocClusterName, InstanceName, RegionName, ZoneName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

// Implements CheckRunner[F[_], A]
object StoppedRuntimeChecker {
  def iml[F[_]: Timer](
    dbReader: DbReader[F],
    deps: RuntimeCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Runtime] =
    new CheckRunner[F, Runtime] {
      override def appName: String = resourceValidator.appName
      override def configs = CheckRunnerConfigs(s"stopped-runtime", shouldAlert = true)
      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps
      override def resourceToScan: fs2.Stream[F, Runtime] = dbReader.getStoppedRuntimes

      override def checkResource(runtime: Runtime,
                                 isDryRun: Boolean)(implicit ev: Ask[F, TraceId]): F[Option[Runtime]] =
        runtime.cloudService match {
          case Dataproc =>
            checkDataprocCluster(runtime, isDryRun)
          case Gce =>
            checkGceRuntime(runtime, isDryRun)
        }

      private def checkGceRuntime(runtime: Runtime, isDryRun: Boolean)(
        implicit ev: Ask[F, TraceId]
      ): F[Option[Runtime]] =
        for {
          runtimeOpt <- deps.computeService
            .getInstance(runtime.googleProject, zoneName, InstanceName(runtime.runtimeName))
          runningRuntimeOpt <- runtimeOpt.flatTraverse { rt =>
            if (rt.getStatus == "RUNNING")
              if (isDryRun)
                logger.warn(s"${runtime} is running. It needs to be stopped.").as(Option(runtime))
              else
                logger.warn(s"${runtime} is running. Going to stop it.") >>
                  // In contrast to in Leo, we're not setting the shutdown script metadata before stopping the instance
                  // in order to keep things simple since our main goal here is to prevent unintended cost to users.
                  deps.computeService
                    .stopInstance(runtime.googleProject, zoneName, InstanceName(runtime.runtimeName))
                    .void
                    .as(Option(runtime))
            else F.pure(none[Runtime])
          }
        } yield runningRuntimeOpt

      private def checkDataprocCluster(runtime: Runtime, isDryRun: Boolean)(
        implicit ev: Ask[F, TraceId]
      ): F[Option[Runtime]] = {
        val clusterName = DataprocClusterName(runtime.runtimeName)
        val project = runtime.googleProject

        for {
          clusterOpt <- deps.dataprocService
            .getCluster(runtime.googleProject, regionName, DataprocClusterName(runtime.runtimeName))
          runningClusterOpt <- clusterOpt.flatTraverse { cluster =>
            if (cluster.getStatus.getState.name.toUpperCase == "RUNNING") {
              for {
                // When we stop Dataproc clusters, we actually stop the underlying instances since it is not
                // possible to stop Dataproc clusters otherwise. Therefore here, we are also checking that there is
                // at least one underlying instance that is RUNNING.
                runningInstanceExists <- containsRunningInstance(project, zoneName, regionName, clusterName)
                rt <- if (runningInstanceExists) {
                  if (isDryRun)
                    logger
                      .warn(s"Cluster (${runtime}) has running instance(s). It needs to be stopped.")
                      .as(Option(runtime))
                  else
                    logger.warn(s"Cluster (${runtime}) has running instances(s). Going to stop it.") >> deps.dataprocService
                    // In contrast to in Leo, we're not setting the shutdown script metadata before stopping the instance
                    // in order to keep things simple since our main goal here is to prevent unintended cost to users.
                      .stopCluster(project, regionName, clusterName, metadata = None)
                      .void
                      .as(Option(runtime))
                } else F.pure(none[Runtime])
              } yield rt
            } else F.pure(none[Runtime])
          }
        } yield runningClusterOpt
      }

      private def containsRunningInstance(project: GoogleProject,
                                          zone: ZoneName,
                                          region: RegionName,
                                          cluster: DataprocClusterName): F[Boolean] =
        for {
          instances <- deps.dataprocService.getClusterInstances(project, region, cluster)
          doesThereExistARunningInstance <- Stream
            .emits(instances.values.toSeq.flatten)
            .covary[F]
            .parEvalMapUnordered(10) { instance =>
              deps.computeService
                .getInstance(project, zone, instance)
                .handleErrorWith(_ => F.pure(none[Instance]))
            }
            .exists(_.exists(_.getStatus == "RUNNING"))
            .compile
            .lastOrError
        } yield doesThereExistARunningInstance
    }
}
