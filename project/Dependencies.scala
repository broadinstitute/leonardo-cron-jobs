import sbt._

object Dependencies {

  val workbenchLibsHash = "92757f1"
  val workbenchGoogle2Version = s"0.32-${workbenchLibsHash}"
  val workbenchAzureVersion = s"0.5-${workbenchLibsHash}"
  val openTelemetryVersion = s"0.6-${workbenchLibsHash}"
  val doobieVersion = "1.0.0-RC4"
  val declineVersion = "2.4.1"

  val excludeBouncyCastle = ExclusionRule(organization = "org.bouncycastle", name = s"bcprov-jdk15on")
  val excludeBouncyCastleExt = ExclusionRule(organization = "org.bouncycastle", name = s"bcprov-ext-jdk15on")
  val excludeBouncyCastleUtil = ExclusionRule(organization = "org.bouncycastle", name = s"bcutil-jdk15on")
  val excludeBouncyCastlePkix = ExclusionRule(organization = "org.bouncycastle", name = s"bcpkix-jdk15on")
  val excludeBigQuery = ExclusionRule(organization = "com.google.cloud", name = s"google-cloud-bigquery")

  val excludeResourceManagerRelay =
    ExclusionRule(organization = "com.azure.resourcemanager", name = s"azure-resourcemanager-relay")
  val excludeResourceManagerNetwork =
    ExclusionRule(organization = "com.azure.resourcemanager", name = s"azure-resourcemanager-network")
  val excludeResourceManagerMsi =
    ExclusionRule(organization = "com.azure.resourcemanager", name = s"azure-resourcemanager-msi")

  val core = Seq(
    "net.logstash.logback" % "logstash-logback-encoder" % "7.4",
    "ch.qos.logback" % "logback-classic" % "1.4.11",
    "ch.qos.logback" % "logback-core" % "1.4.11",
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-hikari" % doobieVersion,
    "org.tpolecat" %% "doobie-scalatest" % doobieVersion % Test,
    "com.github.pureconfig" %% "pureconfig" % "0.17.4",
    "mysql" % "mysql-connector-java" % "8.0.31",
    "org.scalatest" %% "scalatest" % "3.2.16" % Test,
    "com.monovore" %% "decline" % declineVersion,
    "com.monovore" %% "decline-effect" % declineVersion,
    "dev.optics" %% "monocle-core" % "3.1.0",
    "dev.optics" %% "monocle-macro" % "3.1.0",
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % openTelemetryVersion excludeAll (excludeBouncyCastle,
    excludeBouncyCastleExt,
    excludeBouncyCastleUtil,
    excludeBouncyCastlePkix),
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % openTelemetryVersion % Test classifier "tests",
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version excludeAll (excludeBouncyCastle,
    excludeBouncyCastleExt,
    excludeBouncyCastleUtil,
    excludeBouncyCastlePkix,
    excludeBigQuery),
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2Version % Test classifier "tests",
    "org.broadinstitute.dsde.workbench" %% "workbench-azure" % workbenchAzureVersion excludeAll (excludeResourceManagerMsi, excludeResourceManagerRelay),
    "org.broadinstitute.dsde.workbench" %% "workbench-azure" % workbenchAzureVersion % Test classifier "tests",
    "org.scalatestplus" %% "scalacheck-1-16" % "3.2.14.0" % Test,
    "org.scalatestplus" %% "mockito-3-12" % "3.2.10.0" % Test, // https://github.com/scalatest/scalatestplus-selenium
    "ca.mrvisser" %% "sealerate" % "0.0.6"
  )

  val resourceValidator =
    core

  val zombieMonitor =
    core

  val janitor =
    core

  val nuker = core
}
