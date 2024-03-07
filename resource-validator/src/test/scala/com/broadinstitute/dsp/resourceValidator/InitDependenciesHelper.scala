package com.broadinstitute.dsp.resourceValidator

import cats.effect.IO
import com.broadinstitute.dsp.{CheckRunnerDeps, KubernetesClusterCheckerDeps, NodepoolCheckerDeps, RuntimeCheckerDeps}
import org.broadinstitute.dsde.workbench.azure.{AzureContainerService, AzureVmService}
import org.broadinstitute.dsde.workbench.azure.mock.{FakeAzureContainerService, FakeAzureVmService}
import org.broadinstitute.dsde.workbench.google2.mock._
import org.broadinstitute.dsde.workbench.google2.{
  GKEService,
  GoogleBillingService,
  GoogleComputeService,
  GoogleDataprocService,
  GoogleStorageService
}
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter

object InitDependenciesHelper {
  val config = Config.appConfig.toOption.get

  def initRuntimeCheckerDeps(googleComputeService: GoogleComputeService[IO] = FakeGoogleComputeService,
                             googleStorageService: GoogleStorageService[IO] = FakeGoogleStorageInterpreter,
                             googleDataprocService: GoogleDataprocService[IO] = FakeGoogleDataprocService,
                             googleBillingService: GoogleBillingService[IO] = FakeGoogleBillingInterpreter,
                             azureVmService: AzureVmService[IO] = FakeAzureVmService
  ) =
    RuntimeCheckerDeps(
      googleComputeService,
      googleDataprocService,
      CheckRunnerDeps(config.reportDestinationBucket, googleStorageService, FakeOpenTelemetryMetricsInterpreter),
      googleBillingService,
      azureVmService
    )

  def initKubernetesClusterCheckerDeps(gkeService: GKEService[IO] = MockGKEService,
                                       aksService: AzureContainerService[IO] = new FakeAzureContainerService,
                                       googleStorageService: GoogleStorageService[IO] = FakeGoogleStorageInterpreter
  ) =
    KubernetesClusterCheckerDeps(
      CheckRunnerDeps(config.reportDestinationBucket, googleStorageService, FakeOpenTelemetryMetricsInterpreter),
      gkeService,
      aksService
    )

  def initNodepoolCheckerDeps(gkeService: GKEService[IO] = MockGKEService,
                              googleStorageService: GoogleStorageService[IO] = FakeGoogleStorageInterpreter,
                              publisher: CloudPublisher[IO] = new FakeGooglePublisher
  ) =
    NodepoolCheckerDeps(
      CheckRunnerDeps(config.reportDestinationBucket, googleStorageService, FakeOpenTelemetryMetricsInterpreter),
      gkeService,
      publisher
    )
}
