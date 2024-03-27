package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import com.broadinstitute.dsp.Generators._
import com.google.cloud.storage.{Bucket, Storage}
import fs2.Stream
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.google2.mock.{BaseFakeGoogleStorage, FakeGoogleStorageInterpreter}
import org.broadinstitute.dsde.workbench.google2.GoogleStorageService
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import cats.effect.unsafe.implicits.global

class InitBucketCheckerSpec extends AnyFlatSpec with CronJobsTestSuite with MockitoSugar {
  val mockBucket = mock[Bucket]

  it should "return None if bucket no longer exists in Google" in {
    val storageService = new BaseFakeGoogleStorage {
      override def getBucket(googleProject: GoogleProject,
                             bucketName: GcsBucketName,
                             bucketGetOptions: List[Storage.BucketGetOption],
                             traceId: Option[TraceId],
                             warnOnError: Boolean = false
      ): IO[Option[Bucket]] =
        IO.pure(None)
    }
    val checkerDeps =
      initBucketCheckerDeps(storageService)

    forAll { (bucket: InitBucketToRemove, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getInitBucketsToDelete: Stream[IO, InitBucketToRemove] = Stream.emit(bucket)
      }
      val checker = InitBucketChecker.impl(dbReader, checkerDeps)
      val res = checker.checkResource(bucket, dryRun)
      res.unsafeRunSync() shouldBe None
    }
  }

  it should "return bucket if bucket still exists in Google" in {
    forAll { (bucket: InitBucketToRemove, dryRun: Boolean) =>
      val storageService = new BaseFakeGoogleStorage {
        override def getBucket(googleProject: GoogleProject,
                               bucketName: GcsBucketName,
                               bucketGetOptions: List[Storage.BucketGetOption],
                               traceId: Option[TraceId],
                               warnOnError: Boolean = false
        ): IO[Option[Bucket]] =
          IO.pure(Some(mockBucket))

        override def deleteBucket(googleProject: GoogleProject,
                                  bucketName: GcsBucketName,
                                  isRecursive: Boolean,
                                  bucketSourceOptions: List[Storage.BucketSourceOption],
                                  traceId: Option[TraceId],
                                  retryConfig: RetryConfig
        ): Stream[IO, Boolean] =
          if (dryRun) Stream.emit(fail("this shouldn't be called")) else Stream.emit(true).covary[IO]
      }

      val dbReader = new FakeDbReader {
        override def getInitBucketsToDelete: Stream[IO, InitBucketToRemove] = Stream.emit(bucket)
      }

      val checkerDeps =
        initBucketCheckerDeps(storageService)
      val checker = InitBucketChecker.impl(dbReader, checkerDeps)
      val res = checker.checkResource(bucket, dryRun)
      res.unsafeRunSync() shouldBe Some(bucket)
    }
  }

  def initBucketCheckerDeps(
    googleDiskService: GoogleStorageService[IO] = FakeGoogleStorageInterpreter
  ): CheckRunnerDeps[IO] =
    CheckRunnerDeps(ConfigSpec.config.reportDestinationBucket, googleDiskService, FakeOpenTelemetryMetricsInterpreter)

}
