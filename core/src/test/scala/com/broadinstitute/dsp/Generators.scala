package com.broadinstitute.dsp

import cats.data.NonEmptyList
import org.broadinstitute.dsde.workbench.azure.Generators.genAzureCloudContext
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterName
import org.broadinstitute.dsde.workbench.google2.Generators._
import org.scalacheck.{Arbitrary, Gen}

object Generators {
  // TODO IA-3289 When we implement Azure, make sure to add CloudService.AzureVM as an option in the line below so tests use it
  val genCloudService: Gen[CloudService] = Gen.oneOf(CloudService.Gce, CloudService.Dataproc)
  def genRuntime(possibleStatuses: Option[NonEmptyList[String]]): Gen[Runtime] = for {
    id <- Gen.chooseNum(0, 100)
    cloudService <- genCloudService
    project <- genGoogleProject
    azureCloudContext <- genAzureCloudContext
    runtimeName <- Gen.uuid.map(_.toString)
    status <- possibleStatuses.fold(Gen.oneOf("Running", "Creating", "Deleted", "Error"))(s => Gen.oneOf(s.toList))
  } yield cloudService match {
    case CloudService.Dataproc =>
      Runtime.Dataproc(id, project, runtimeName, cloudService, status, DbTestHelper.regionName)
    case CloudService.Gce =>
      Runtime.Gce(id, project, runtimeName, cloudService, status, DbTestHelper.zoneName)
    case CloudService.AzureVM =>
      Runtime.AzureVM(id, CloudContext.Azure(azureCloudContext), runtimeName, cloudService, status)
  }
  val genDataprocRuntime: Gen[Runtime.Dataproc] = for {
    id <- Gen.chooseNum(0, 100)
    project <- genGoogleProject
    runtimeName <- Gen.uuid.map(_.toString)
    status <- Gen.oneOf("Running", "Creating", "Deleted", "Error")
  } yield Runtime.Dataproc(id, project, runtimeName, CloudService.Dataproc, status, DbTestHelper.regionName)
  val genDisk: Gen[Disk] = for {
    id <- Gen.chooseNum(0, 100)
    project <- genGoogleProject
    diskName <- genDiskName
    zone <- genZoneName
  } yield Disk(id,
               CloudContext.Gcp(project),
               diskName,
               zone,
               formattedBy = Some("GCE")
  ) // TODO: update generator once we support Azure disks

  val genInitBucket: Gen[InitBucketToRemove] = for {
    project <- genGoogleProject
    bucketName <- genGcsBucketName
  } yield InitBucketToRemove(project, Some(InitBucketName(bucketName.value)))

  val genKubernetesCluster: Gen[KubernetesCluster] = for {
    id <- Gen.chooseNum(0, 100)
    name <- Gen.uuid.map(x => KubernetesClusterName(x.toString))
    cloudService <- genCloudService
    project <- genGoogleProject
    azureCloudContext <- genAzureCloudContext
    location <- genLocation
  } yield cloudService match {
    case CloudService.Gce      => KubernetesCluster(id, name, CloudContext.Gcp(project), location)
    case CloudService.Dataproc => KubernetesCluster(id, name, CloudContext.Gcp(project), location)
    case CloudService.AzureVM  => KubernetesCluster(id, name, CloudContext.Azure(azureCloudContext), location)
  }

  val genNodepool: Gen[Nodepool] = for {
    id <- Gen.chooseNum(0, 100)
    nodepoolName <- genNodepoolName
    clusterName <- Gen.uuid.map(x => KubernetesClusterName(x.toString))
    cloudService <- genCloudService
    project <- genGoogleProject
    azureCloudContext <- genAzureCloudContext
    location <- genLocation
  } yield cloudService match {
    case CloudService.Gce => Nodepool(id, nodepoolName, clusterName, CloudContext.Gcp(project), location)

    case CloudService.Dataproc => Nodepool(id, nodepoolName, clusterName, CloudContext.Gcp(project), location)

    case CloudService.AzureVM =>
      Nodepool(id, nodepoolName, clusterName, CloudContext.Azure(azureCloudContext), location)

  }

  val genKubernetesClusterToRemove: Gen[KubernetesClusterToRemove] = for {
    id <- Gen.chooseNum(0, 100)
    cloudService <- genCloudService
    project <- genGoogleProject
    azureCloudContext <- genAzureCloudContext
  } yield cloudService match {
    case CloudService.Gce      => KubernetesClusterToRemove(id, CloudContext.Gcp(project))
    case CloudService.Dataproc => KubernetesClusterToRemove(id, CloudContext.Gcp(project))
    case CloudService.AzureVM  => KubernetesClusterToRemove(id, CloudContext.Azure(azureCloudContext))
  }

  val genRuntimeWithWorkers: Gen[RuntimeWithWorkers] = for {
    runtime <- genDataprocRuntime
    num1 <- Gen.chooseNum(1, 100)
    num2 <- Gen.chooseNum(1, 100)
  } yield RuntimeWithWorkers(runtime, WorkerConfig(Some(num1), Some(num2)))

  val genRemovableNodepoolStatus: Gen[RemovableNodepoolStatus] = for {
    status <- Gen.oneOf(RemovableNodepoolStatus.removableStatuses)
  } yield status

  val arbDataprocRuntime: Arbitrary[Runtime.Dataproc] = Arbitrary(genDataprocRuntime)
  implicit val arbRuntime: Arbitrary[Runtime] = Arbitrary(genRuntime(None))
  implicit val arbCloudService: Arbitrary[CloudService] = Arbitrary(genCloudService)
  implicit val arbDisk: Arbitrary[Disk] = Arbitrary(genDisk)
  implicit val arbInitBucket: Arbitrary[InitBucketToRemove] = Arbitrary(genInitBucket)
  implicit val arbRemovableNodepoolStatus: Arbitrary[RemovableNodepoolStatus] = Arbitrary(genRemovableNodepoolStatus)
  implicit val arbKubernetesClusterToRemove: Arbitrary[KubernetesClusterToRemove] = Arbitrary(
    genKubernetesClusterToRemove
  )
  implicit val arbKubernetesCluster: Arbitrary[KubernetesCluster] = Arbitrary(genKubernetesCluster)
  implicit val arbNodepool: Arbitrary[Nodepool] = Arbitrary(genNodepool)
  implicit val arbRuntimeWithWorkers: Arbitrary[RuntimeWithWorkers] = Arbitrary(genRuntimeWithWorkers)
}
