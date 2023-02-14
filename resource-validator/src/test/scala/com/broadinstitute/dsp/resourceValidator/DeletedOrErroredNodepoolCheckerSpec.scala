package com.broadinstitute.dsp
package resourceValidator

import cats.effect.IO
import cats.mtl.Ask
import com.broadinstitute.dsp.Generators._
import com.broadinstitute.dsp.resourceValidator.InitDependenciesHelper.initNodepoolCheckerDeps
import fs2.Stream
import io.circe.Encoder
import org.broadinstitute.dsde.workbench.google2.GKEModels.NodepoolId
import org.broadinstitute.dsde.workbench.google2.mock.{FakeGooglePublisher, MockGKEService}
import org.broadinstitute.dsde.workbench.model.TraceId
import com.google.container.v1.NodePool
import org.scalatest.flatspec.AnyFlatSpec
import cats.effect.unsafe.implicits.global
class DeletedOrErroredNodepoolCheckerSpec extends AnyFlatSpec with CronJobsTestSuite {

  it should "not publish to subscriber if dryRun" in {
    forAll { (nodepool: Nodepool, dryRun: Boolean) =>
      val dbReader = new FakeDbReader {
        override def getDeletedAndErroredNodepools: fs2.Stream[IO, Nodepool] = Stream.emit(nodepool)
      }

      val gkeService = new MockGKEService {
        override def getNodepool(nodepoolId: NodepoolId)(implicit
          ev: Ask[IO, TraceId]
        ): IO[Option[NodePool]] = {
          val nodepool = NodePool.newBuilder().build()
          IO.pure(Some(nodepool))
        }
      }

      var count = 0

      val publisher = new FakeGooglePublisher {
        override def publishOne[MessageType](message: MessageType)(implicit
          evidence$2: Encoder[MessageType],
          ev: Ask[IO, TraceId]
        ): IO[Unit] =
          if (dryRun)
            IO.raiseError(fail("Shouldn't publish message in dryRun mode"))
          else {
            count = count + 1
            super.publishOne(message)(evidence$2, ev)
          }
      }

      val deps = initNodepoolCheckerDeps(gkeService = gkeService, publisher = publisher)
      val deletedOrErroredNodepoolChecker = DeletedOrErroredNodepoolChecker.impl(dbReader, deps)
      val res = deletedOrErroredNodepoolChecker.checkResource(nodepool, dryRun)

      res.unsafeRunSync() shouldBe (if (nodepool.cloudContext.cloudProvider == CloudProvider.Gcp) Some(nodepool)
                                    else None)
      if (dryRun) count shouldBe 0
      else count shouldBe 1
    }
  }
}
