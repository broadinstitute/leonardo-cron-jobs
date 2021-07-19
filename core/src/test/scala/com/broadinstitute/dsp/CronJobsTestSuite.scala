package com.broadinstitute.dsp

import cats.effect.{Blocker, ContextShift, IO, Timer}
import cats.mtl.Ask
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.broadinstitute.dsde.workbench.model.TraceId
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.Configuration
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

trait CronJobsTestSuite extends Matchers with ScalaCheckPropertyChecks with Configuration {
  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val unsafeLogger: StructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  val fakeTraceId = TraceId("fakeTraceId")
  implicit val traceId: Ask[IO, TraceId] = Ask.const[IO, TraceId](fakeTraceId)
  val blocker: Blocker = Blocker.liftExecutionContext(global)
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 3)
}
