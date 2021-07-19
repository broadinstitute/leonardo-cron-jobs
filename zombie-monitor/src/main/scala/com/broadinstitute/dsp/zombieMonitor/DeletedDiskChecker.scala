package com.broadinstitute.dsp
package zombieMonitor

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import cats.mtl.Ask
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.broadinstitute.dsde.workbench.model.TraceId

/**
 * Similar to `DeletedDiskChecker` in `resource-validator`, but this process all disks and check if they still exists in google.
 * If not, we update leonardo DB to reflect that they're deleted
 */
object DeletedDiskChecker {
  def impl[F[_]: Timer](
    dbReader: DbReader[F],
    deps: DiskCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Disk] =
    new CheckRunner[F, Disk] {
      override def resourceToScan: Stream[F, Disk] = dbReader.getDisksToDeleteCandidate

      override def configs = CheckRunnerConfigs(s"deleted-disk", false)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      def checkResource(disk: Disk, isDryRun: Boolean)(implicit ev: Ask[F, TraceId]): F[Option[Disk]] =
        for {
          diskOpt <- deps.googleDiskService.getDisk(disk.googleProject, disk.zone, disk.diskName)
          _ <-
            if (isDryRun) F.unit
            else
              diskOpt match {
                case None    => dbReader.updateDiskStatus(disk.id)
                case Some(_) => F.unit
              }
        } yield diskOpt.fold[Option[Disk]](Some(disk))(_ => none[Disk])

      override def appName: String = zombieMonitor.appName
    }
}
