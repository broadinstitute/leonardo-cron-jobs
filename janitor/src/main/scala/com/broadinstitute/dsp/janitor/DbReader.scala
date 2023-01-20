package com.broadinstitute.dsp
package janitor

import cats.effect.Async
import com.broadinstitute.dsp.DbReaderImplicits.{cloudProviderMeta, kubernetesClusterToRemoveRead, nodepoolRead}
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._ // Mapping for temporal types require a specific import https://github.com/tpolecat/doobie/releases/tag/v0.8.8
import fs2.Stream
import org.broadinstitute.dsde.workbench.azure.{AzureCloudContext, ContainerName}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

import java.sql.{SQLDataException, Timestamp}

trait DbReader[F[_]] {
  def getKubernetesClustersToDelete: Stream[F, KubernetesClusterToRemove]
  def getNodepoolsToDelete: Stream[F, Nodepool]
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
   * Return all non-deleted clusters with non-default nodepools that have apps that were all deleted
   * or errored outside the grace period (1 hour)
   * We are including clusters with no nodepools and apps as well.
   * We are calculating the grace period for cluster deletion assuming that the following are valid proxies
   * for an app's last activity:
   *    - destroyedDate for deleted apps
   *    - createdDate for error'ed apps
   */
  // TODO: Read the grace period (hardcoded to '1 HOUR' below) from config
  val kubernetesClustersToDeleteQuery =
    sql"""
      SELECT kc.id, kc.cloudContext, kc.cloudProvider
      FROM KUBERNETES_CLUSTER kc
      WHERE
        kc.status != "DELETED" AND
        NOT EXISTS (
          SELECT *
          FROM NODEPOOL np
          RIGHT JOIN APP a ON np.id = a.nodepoolId
          WHERE
            kc.id = np.clusterId AND np.isDefault = 0 AND
            (
              (a.status != "DELETED" AND a.status != "ERROR") OR
              (a.status = "DELETED" AND a.destroyedDate > now() - INTERVAL 1 HOUR) OR
              (a.status = "ERROR" AND a.createdDate > now() - INTERVAL 1 HOUR) OR
              (a.id IS NULL)
            )
        );
    """
      .query[KubernetesClusterToRemove]

  /**
   * We are calculating the grace period for nodepool deletion assuming that the following are valid proxies for an app's last activity:
   *   - destroyedDate for deleted apps
   *   - createdDate for error'ed apps
   * We explicitly check nodepools with 5 out of 11 statuses that exist.
   * The statuses we exclude are PROVISIONING, STOPPING, DELETED, PRECREATING, PREDELETING, and PREDELETING.
   * We exclude all "...ing" statuses because they are transitional, and this checker is not intended to handle timeouts.
   * We are excluding default nodepools, as these should remain for the lifetime of the cluster.
   */
  // TODO: Read the grace period (hardcoded to '1 HOUR' below) from config
  val applessNodepoolQuery =
    sql"""
        SELECT np.id, np.nodepoolName, kc.clusterName, kc.cloudProvider, kc.cloudContext, kc.location
        FROM NODEPOOL AS np
        INNER JOIN KUBERNETES_CLUSTER AS kc
        ON np.clusterId = kc.id
        WHERE
        (
            np.status IN ("STATUS_UNSPECIFIED", "RUNNING", "RECONCILING", "ERROR", "RUNNING_WITH_ERROR")
            AND np.isDefault = 0
            AND NOT EXISTS
            (
                SELECT * FROM APP AS a
                WHERE np.id = a.nodepoolId
                AND
                (
                    (a.status != "DELETED" AND a.status != "ERROR")
                    OR (a.status = "DELETED" AND a.destroyedDate > now() - INTERVAL 1 HOUR)
                    OR (a.status = "ERROR" AND a.createdDate > now() - INTERVAL 1 HOUR)
                    OR (a.id IS NULL)
                )
            )
        )
    """
      .query[Nodepool]

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
    override def getKubernetesClustersToDelete: Stream[F, KubernetesClusterToRemove] =
      kubernetesClustersToDeleteQuery.stream.transact(xa)

    override def getNodepoolsToDelete: Stream[F, Nodepool] =
      applessNodepoolQuery.stream.transact(xa)

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
