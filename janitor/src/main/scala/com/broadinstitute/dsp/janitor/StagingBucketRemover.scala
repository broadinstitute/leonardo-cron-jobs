package com.broadinstitute.dsp
package janitor

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.google2.GoogleStorageService
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.typelevel.log4cats.Logger

object StagingBucketRemover {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: CheckRunnerDeps[F]
  )(implicit
    F: Concurrent[F],
    logger: Logger[F],
    ev: Ask[F, TraceId]
  ): CheckRunner[F, BucketToRemove] =
    new CheckRunner[F, BucketToRemove] {
      override def appName: String = janitor.appName
      override def configs = CheckRunnerConfigs("remove-staging-buckets", shouldAlert = false)
      override def dependencies: CheckRunnerDeps[F] = deps
      override def resourceToScan: fs2.Stream[F, BucketToRemove] = dbReader.getStagingBucketsToDelete

      // We're ignoring isDryRun flag here since we do want to delete these staging buckets
      // We can improve this by checking if the bucket exists first, but it doesn't hurt to blindly issue deleting bucket
      override def checkResource(a: BucketToRemove, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[BucketToRemove]] =
        a match {
          case BucketToRemove.Gcp(googleProject, bucketOpt) =>
            bucketOpt.flatTraverse { b =>
              deps.storageService.deleteBucket(googleProject, b, isRecursive = true).compile.last.map {
                // deleteBucket() will return `true` if the bucket is deleted; else return `false`
                // We only need to report buckets that are actually being deleted.
                case Some(true)  => Some(a)
                case Some(false) => None
                case None        => None
              }
            }
          case BucketToRemove.Azure(_, _) => F.pure(None) // TODO: implement it for Azure as well
        }
    }
}

final case class StagingBucketRemoverDeps[F[_]](reportDestinationBucket: GcsBucketName,
                                                storageService: GoogleStorageService[F]
)
