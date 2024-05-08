package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import fs2.Stream
import kotlin.NotImplementedError
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

/**
 * Similar to `DeletedDiskChecker` in `resource-validator`, but this process all disks and check if they still exists in google.
 * If not, we update leonardo DB to reflect that they're deleted
 */
object DeletedDiskChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: DiskCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Disk] =
    new CheckRunner[F, Disk] {
      override def resourceToScan: Stream[F, Disk] = dbReader.getDisksToDeleteCandidate

      override def configs = CheckRunnerConfigs(s"deleted-disk", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(disk: Disk, isDryRun: Boolean)(implicit ev: Ask[F, TraceId]): F[Option[Disk]] =
        for {
          googleProject <- disk.cloudContext match {
            case CloudContext.Azure(_) => F.raiseError(new NotImplementedError())
            case CloudContext.Gcp(p)   => F.pure(p)
          }
          getDiskRes <- deps.googleDiskService.getDisk(googleProject, disk.zone, disk.diskName).attempt
          res <- getDiskRes match {
            case Left(e) =>
              val result = e match {
                case ee: com.google.api.gax.rpc.PermissionDeniedException =>
                  if (ee.getCause.getMessage.contains("Compute Engine API has not been used")) {
                    if (isDryRun) F.unit else dbReader.updateDiskStatus(disk.id)
                  } else if (
                    ee.getCause.getMessage
                      .contains("This API method requires billing to be enabled")
                  ) {
                    logger.info(s"billing is disabled for ${disk.diskName}")
                  } else logger.error(e)(s"fail to get ${disk.diskName}")
                case e => logger.error(e)(s"fail to get ${disk.diskName}")
              }
              result.as(Some(disk))
            case Right(diskOpt) =>
              if (isDryRun) F.pure(diskOpt.fold[Option[Disk]](Some(disk))(_ => none[Disk]))
              else
                diskOpt match {
                  case None    => dbReader.updateDiskStatus(disk.id).as(Some(disk))
                  case Some(_) => F.pure(none[Disk])
                }
          }
        } yield res

      override def appName: String = zombieMonitor.appName
    }
}
