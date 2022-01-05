package com.broadinstitute.dsp
package resourceValidator

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

object InitBucketChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: CheckRunnerDeps[F]
  )(implicit
    F: Concurrent[F],
    logger: Logger[F],
    ev: Ask[F, TraceId]
  ): CheckRunner[F, InitBucketToRemove] =
    new CheckRunner[F, InitBucketToRemove] {
      override def appName: String = resourceValidator.appName
      override def configs = CheckRunnerConfigs("remove-init-buckets", true)
      override def dependencies: CheckRunnerDeps[F] = deps
      override def resourceToScan: fs2.Stream[F, InitBucketToRemove] = dbReader.getInitBucketsToDelete

      override def checkResource(a: InitBucketToRemove, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[InitBucketToRemove]] =
        a.bucket
          .flatTraverse { b =>
            for {
              ctx <- ev.ask
              bucket <- deps.storageService.getBucket(a.googleProject, b.asGcsBucketName, traceId = Some(ctx))
              deletedBucket <- bucket.traverse { _ =>
                // This is currently run in dry run mode
                if (isDryRun) logger.warn(s"bucket $b still exists in Google. It needs to be deleted").as(a)
                else
                  deps.storageService
                    .deleteBucket(a.googleProject, b.asGcsBucketName, true)
                    .compile
                    .drain
                    .as(a)
              }
            } yield deletedBucket
          }
    }
}
