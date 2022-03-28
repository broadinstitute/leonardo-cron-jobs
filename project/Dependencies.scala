import sbt._

object Dependencies {
  val workbenchGoogle2Version = "0.23-3b927f8"
  val doobieVersion = "1.0.0-RC2"
  val openTelemetryVersion = "0.3-97318e4"
  val declineVersion = "2.2.0"

  val core = Seq(
    "net.logstash.logback" % "logstash-logback-encoder" % "7.0.1",
    "ch.qos.logback" % "logback-classic" % "1.2.10",
    "ch.qos.logback" % "logback-core" % "1.2.10",
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-hikari" % doobieVersion,
    "org.tpolecat" %% "doobie-scalatest" % doobieVersion % Test,
    "com.github.pureconfig" %% "pureconfig" % "0.17.1",
    "mysql" % "mysql-connector-java" % "8.0.27",
    "org.scalatest" %% "scalatest" % "3.2.10" % Test,
    "com.monovore" %% "decline" % declineVersion,
    "com.monovore" %% "decline-effect" % declineVersion,
    "dev.optics" %% "monocle-core" % "3.1.0",
    "dev.optics" %% "monocle-macro" % "3.1.0",
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % openTelemetryVersion,
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % openTelemetryVersion % Test classifier "tests",
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version,
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version,
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version % Test classifier "tests",
    "org.scalatestplus" %% "scalacheck-1-14" % "3.2.2.0" % Test,
    "org.scalatestplus" %% "mockito-3-4" % "3.2.10.0" % Test, // https://github.com/scalatest/scalatestplus-selenium
    "ca.mrvisser" %% "sealerate" % "0.0.6"
  )

  val resourceValidator = core

  val zombieMonitor = core

  val janitor = core

  val nuker = core
}
