package com.broadinstitute.dsp.resourceValidator

import cats.effect.IO
import com.broadinstitute.dsp.{CheckRunnerDeps, RuntimeCheckerDeps}
import org.broadinstitute.dsde.workbench.azure.AzureVmService
import org.broadinstitute.dsde.workbench.azure.mock.FakeAzureVmService
import org.broadinstitute.dsde.workbench.google2.mock._
import org.broadinstitute.dsde.workbench.google2.{
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
}
