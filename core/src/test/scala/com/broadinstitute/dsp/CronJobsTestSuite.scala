package com.broadinstitute.dsp

import cats.effect.IO
import cats.mtl.Ask
import org.broadinstitute.dsde.workbench.model.TraceId
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.Configuration
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.ExecutionContext

trait CronJobsTestSuite extends Matchers with ScalaCheckPropertyChecks with Configuration {
  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val unsafeLogger: StructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  val fakeTraceId = TraceId("fakeTraceId")
  implicit val traceId: Ask[IO, TraceId] = Ask.const[IO, TraceId](fakeTraceId)
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 3)
}
