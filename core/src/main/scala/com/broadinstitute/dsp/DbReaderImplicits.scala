package com.broadinstitute.dsp

import cats.syntax.all._
import doobie.implicits.javasql.TimestampMeta
import doobie.{Get, Meta, Read}
import org.broadinstitute.dsde.workbench.azure.AzureCloudContext
import org.broadinstitute.dsde.workbench.google2.GKEModels.{
  KubernetesClusterId,
  KubernetesClusterName,
  NodepoolId,
  NodepoolName
}
import org.broadinstitute.dsde.workbench.google2.{DiskName, Location, RegionName, ZoneName}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

import java.sql.{SQLDataException, Timestamp}
import java.time.Instant

object DbReaderImplicits {
  implicit val cloudServiceGet: Get[CloudService] = Get[String].temap {
    case "DATAPROC" => CloudService.Dataproc.asRight[String]
    case "GCE"      => CloudService.Gce.asRight[String]
    case "AZURE_VM" => CloudService.AzureVM.asRight[String]
    case x          => s"invalid cloudService value $x".asLeft[CloudService]
  }

  implicit val instantMeta: Meta[Instant] = Meta[Timestamp].imap(_.toInstant)(Timestamp.from)
  implicit val gcsBucketNameGet: Get[GcsBucketName] = Get[String].map(GcsBucketName)
  implicit val initBucketNameGet: Get[InitBucketName] = Get[String].temap(s => InitBucketName.withValidation(s))
  implicit val locationGet: Get[Location] = Get[String].map(Location)
  implicit val kubernetesClusterNameGet: Get[KubernetesClusterName] = Get[String].map(KubernetesClusterName)
  implicit val diskNameMeta: Meta[DiskName] = Meta[String].imap(DiskName)(_.value)
  implicit val zoneNameMeta: Meta[ZoneName] = Meta[String].imap(ZoneName(_))(_.value)
  implicit val googleProjectMeta: Meta[GoogleProject] = Meta[String].imap(GoogleProject)(_.value)
  implicit val cloudProviderMeta: Meta[CloudProvider] = Meta[String].imap(s =>
    CloudProvider.stringToCloudProvider.get(s).getOrElse(throw new SQLDataException(s"Invalid cloud provider ${s}"))
  )(_.asString)
  implicit val k8sClusterNameMeta: Meta[KubernetesClusterName] = Meta[String].imap(KubernetesClusterName)(_.value)
  implicit val locationMeta: Meta[Location] = Meta[String].imap(Location)(_.value)
  implicit val regionNameMeta: Meta[RegionName] = Meta[String].imap(RegionName(_))(_.value)

  implicit val cloudContextRead: Read[CloudContext] = Read[(String, CloudProvider)].map { case (s, cloudProvider) =>
    cloudProvider match {
      case CloudProvider.Azure =>
        AzureCloudContext.fromString(s) match {
          case Left(value) =>
            throw new RuntimeException(
              s"${value} is not valid azure cloud context"
            )
          case Right(value) =>
            CloudContext.Azure(value)
        }
      case CloudProvider.Gcp =>
        CloudContext.Gcp(GoogleProject(s))
    }
  }

  implicit val runtimeRead: Read[Runtime] =
    Read[(Long, String, CloudProvider, String, CloudService, String, Option[ZoneName], Option[RegionName])].map {
      case (id, cloudContextDb, cloudProvider, runtimeName, cloudService, status, zone, region) =>
        cloudProvider match {
          case CloudProvider.Azure =>
            AzureCloudContext.fromString(cloudContextDb) match {
              case Left(value) =>
                throw new RuntimeException(
                  s"${value} is not valid azure cloud context"
                )
              case Right(value) =>
                Runtime.AzureVM(id, CloudContext.Azure(value), runtimeName, cloudService, status)
            }
          case CloudProvider.Gcp =>
            (zone, region) match {
              case (Some(_), Some(_)) =>
                throw new RuntimeException(
                  s"${cloudService} Runtime ${id} has both zone and region defined. This is impossible. Fix this in DB"
                )
              case (Some(z), None) =>
                if (cloudService == CloudService.Gce)
                  Runtime.Gce(id, GoogleProject(cloudContextDb), runtimeName, cloudService, status, z)
                else
                  throw new RuntimeException(
                    s"Dataproc runtime ${id} has no region defined. This is impossible. Fix this in DB"
                  )
              case (None, Some(r)) =>
                if (cloudService == CloudService.Dataproc)
                  Runtime.Dataproc(id, GoogleProject(cloudContextDb), runtimeName, cloudService, status, r)
                else
                  throw new RuntimeException(
                    s"Gce runtime ${id} has no zone defined. This is impossible. Fix this in DB"
                  )
              case (None, None) =>
                throw new RuntimeException(
                  s"${cloudService} Runtime ${id} has no zone and no region defined. This is impossible. Fix this in DB"
                )
            }
        }
    }

  implicit val kubernetesClusterToRemoveRead: Read[KubernetesClusterToRemove] =
    Read[(Long, String, CloudProvider)].map { case (id, cloudContextDb, cloudProvider) =>
      cloudProvider match {
        case CloudProvider.Azure =>
          AzureCloudContext.fromString(cloudContextDb) match {
            case Left(value) =>
              throw new RuntimeException(
                s"${value} is not valid azure cloud context"
              )
            case Right(value) =>
              KubernetesClusterToRemove(id, CloudContext.Azure(value))
          }
        case CloudProvider.Gcp =>
          KubernetesClusterToRemove(id, CloudContext.Gcp(GoogleProject(cloudContextDb)))
      }
    }

  implicit val kubernetesClusterRead: Read[KubernetesCluster] =
    Read[(KubernetesClusterName, String, Location, CloudProvider)].map {
      case (name, cloudContextDb, location, cloudProvider) =>
        cloudProvider match {
          case CloudProvider.Azure =>
            AzureCloudContext.fromString(cloudContextDb) match {
              case Left(value) =>
                throw new RuntimeException(
                  s"${value} is not valid azure cloud context"
                )
              case Right(value) =>
                KubernetesCluster(name, CloudContext.Azure(value), location)
            }
          case CloudProvider.Gcp =>
            KubernetesCluster(name, CloudContext.Gcp(GoogleProject(cloudContextDb)), location)
        }
    }

  implicit val nodepoolRead: Read[Nodepool] =
    Read[(Long, NodepoolName, KubernetesClusterName, CloudProvider, String, Location)].map {
      case (id, nodepoolName, k8sClusterName, cloudProvider, cloudContextDb, location) =>
        cloudProvider match {
          case CloudProvider.Azure =>
            AzureCloudContext.fromString(cloudContextDb) match {
              case Left(value) =>
                throw new RuntimeException(
                  s"${value} is not valid azure cloud context"
                )
              case Right(value) =>
                Nodepool(id, nodepoolName, k8sClusterName, CloudContext.Azure(value), location)
            }
          case CloudProvider.Gcp =>
            Nodepool(id, nodepoolName, k8sClusterName, CloudContext.Gcp(GoogleProject(cloudContextDb)), location)
        }
    }

  implicit val k8sToScanRead: Read[K8sClusterToScan] =
    Read[(Long, KubernetesClusterName, String, Location, CloudProvider)].map {
      case (id, name, cloudContextDb, location, cloudProvider) =>
        cloudProvider match {
          case CloudProvider.Azure =>
            AzureCloudContext.fromString(cloudContextDb) match {
              case Left(value) =>
                throw new RuntimeException(
                  s"${value} is not valid azure cloud context"
                )
              case Right(value) =>
                throw new RuntimeException(
                  s"Azure is not supported yet" // TODO: IA-3623
                )
            }
          case CloudProvider.Gcp =>
            K8sClusterToScan(id, KubernetesClusterId(GoogleProject(cloudContextDb), location, name))
        }
    }

  implicit val nodepoolToScanRead: Read[NodepoolToScan] =
    Read[(Long, CloudProvider, String, Location, KubernetesClusterName, NodepoolName)].map {
      case (id, cloudProvider, cloudContextDb, location, clusterName, nodepoolName) =>
        cloudProvider match {
          case CloudProvider.Azure =>
            AzureCloudContext.fromString(cloudContextDb) match {
              case Left(value) =>
                throw new RuntimeException(
                  s"${value} is not a valid azure cloud context"
                )
              case Right(_) =>
                throw new RuntimeException(
                  s"Azure is not supported yet" // TODO: IA-3623
                )
            }
          case CloudProvider.Gcp =>
            NodepoolToScan(
              id,
              NodepoolId(KubernetesClusterId(GoogleProject(cloudContextDb), location, clusterName), nodepoolName)
            )
        }
    }
}
