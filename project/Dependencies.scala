import sbt._

object Dependencies {
  val workbenchLibsHash = "3159fd9"
  val workbenchGoogle2Version = s"0.24-${workbenchLibsHash}"
  val workbenchAzureVersion = s"0.1-${workbenchLibsHash}"
  val openTelemetryVersion = s"0.3-${workbenchLibsHash}"
  val doobieVersion = "1.0.0-RC2"
  val declineVersion = "2.2.0"

  val core = Seq(
    "net.logstash.logback" % "logstash-logback-encoder" % "7.1.1",
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    "ch.qos.logback" % "logback-core" % "1.2.11",
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-hikari" % doobieVersion,
    "org.tpolecat" %% "doobie-scalatest" % doobieVersion % Test,
    "com.github.pureconfig" %% "pureconfig" % "0.17.1",
    "mysql" % "mysql-connector-java" % "8.0.29",
    "org.scalatest" %% "scalatest" % "3.2.11" % Test,
    "com.monovore" %% "decline" % declineVersion,
    "com.monovore" %% "decline-effect" % declineVersion,
    "dev.optics" %% "monocle-core" % "3.1.0",
    "dev.optics" %% "monocle-macro" % "3.1.0",
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % openTelemetryVersion,
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % openTelemetryVersion % Test classifier "tests",
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version,
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version % Test classifier "tests",
    "org.broadinstitute.dsde.workbench" %% "workbench-azure" % workbenchAzureVersion,
    "org.broadinstitute.dsde.workbench" %% "workbench-azure" % workbenchAzureVersion % Test classifier "tests",
    "org.scalatestplus" %% "scalacheck-1-15" % "3.2.11.0" % Test,
    "org.scalatestplus" %% "mockito-3-12" % "3.2.10.0" % Test, // https://github.com/scalatest/scalatestplus-selenium
    "ca.mrvisser" %% "sealerate" % "0.0.6"
  )

  val resourceValidator = core

  val zombieMonitor = core

  val janitor = core

  val nuker = core
}
