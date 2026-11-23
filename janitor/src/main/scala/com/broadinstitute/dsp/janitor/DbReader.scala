package com.broadinstitute.dsp
package janitor

import cats.effect.Async
import com.broadinstitute.dsp.DbReaderImplicits.cloudProviderMeta
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._ // Mapping for temporal types require a specific import https://github.com/tpolecat/doobie/releases/tag/v0.8.8
import fs2.Stream
import org.broadinstitute.dsde.workbench.azure.{AzureCloudContext, ContainerName}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

import java.sql.Timestamp

trait DbReader[F[_]] {
  def getStagingBucketsToDelete: Stream[F, BucketToRemove]
  def getStuckAppToReport: Stream[F, AppToReport]
}

object DbReader {

  implicit val bucketToRemoveRead: Read[BucketToRemove] =
    Read[(CloudProvider, String, Option[String])].map { case (cloudProvider, cloudContextDb, stagingBucketOpt) =>
      cloudProvider match {
        case CloudProvider.Azure =>
          AzureCloudContext.fromString(cloudContextDb) match {
            case Left(value) =>
              throw new RuntimeException(
                s"${value} is not valid azure cloud context"
              )
            case Right(azureContext) =>
              stagingBucketOpt match {
                case Some(value) => BucketToRemove.Azure(azureContext, Some(AzureStagingBucket(ContainerName(value))))
                case None        => BucketToRemove.Azure(azureContext, None)
              }

          }
        case CloudProvider.Gcp =>
          BucketToRemove.Gcp(GoogleProject(cloudContextDb), stagingBucketOpt.map(GcsBucketName))
      }
    }

  implicit def apply[F[_]](implicit ev: DbReader[F]): DbReader[F] = ev

  /**
   * When we delete runtimes, we keep their staging buckets for 10 days. Hence we're only deleting staging buckets whose
   * runtimes have been deleted more than 15 days ago.
   * Checker will blindly delete all buckets returned by this function. Since we've started running the cron job daily,
   * we really only need to delete any new buckets; hence we're skipping buckets whose runtimes were deleted more than 20 days ago
   */
  val stagingBucketsToDeleteQuery =
    sql"""
        SELECT cloudProvider, cloudContext, stagingBucket
        FROM CLUSTER
        WHERE
          status="Deleted" AND
          destroyedDate < now() - interval 15 DAY AND
          destroyedDate > now() - interval 20 DAY;
        """
      .query[BucketToRemove]

  /**
   * We want to flag apps that have been stuck in creating for more than 2 hours and report them to the user
   * to avoid hidden costs. Since apps cannot be deleted while creating, there is not much more we can do other
   * than report.
   * We similarly want to flag apps that have been stuck in deleting for more than 1 hour.
   *
   * Note that there are no `Creating` status for apps, it corresponds to `PRECREATING`, or `PROVISIONING`
   */
  val appStuckQuery =
    sql"""
        SELECT id, appName, status, createdDate
        FROM APP
        WHERE
          (status in ("PRECREATING", "PROVISIONING") AND createdDate < now() - INTERVAL 2 HOUR) OR
          (status="DELETING" AND createdDate < now() - INTERVAL 1 HOUR);
        """
      .query[AppToReport]

  def impl[F[_]](xa: Transactor[F])(implicit F: Async[F]): DbReader[F] = new DbReader[F] {
    override def getStagingBucketsToDelete: Stream[F, BucketToRemove] =
      stagingBucketsToDeleteQuery.stream.transact(xa)

    override def getStuckAppToReport: Stream[F, AppToReport] =
      appStuckQuery.stream.transact(xa)
  }
}

sealed trait BucketToRemove extends Serializable with Product
object BucketToRemove {
  final case class Gcp(googleProject: GoogleProject, bucket: Option[GcsBucketName]) extends BucketToRemove {
    override def toString: String = s"${googleProject.value},${bucket.getOrElse("null")}"
  }
  final case class Azure(azureCloudContext: AzureCloudContext, bucket: Option[AzureStagingBucket])
      extends BucketToRemove {
    override def toString: String = s"${azureCloudContext.asString},${bucket.getOrElse("null")}"
  }
}

final case class AppToReport(id: Long, name: String, status: String, createdDate: Timestamp) {
  override def toString: String =
    s"App id:${id}, App name:${name}, App status:${status}, App creation time: ${createdDate}"
}
