lazy val akkaV = "2.6.19"
lazy val akkaHttpV = "10.2.9"
lazy val alpakkaV = "3.0.4"
lazy val slf4jV = "1.7.36"

lazy val commonSettings = Seq(
  name := "semantic-repo",
  version := "1.1.0",
  scalaVersion := "3.1.3",

  // Packages available only with Scala 2.13
  libraryDependencies ++= Seq(
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpakkaV,
    "com.lightbend.akka" %% "akka-stream-alpakka-file" % alpakkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % Test,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaV,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV % Test,
    "ch.megard" %% "akka-http-cors" % "1.1.3",
    "fr.davit" %% "akka-http-metrics-prometheus" % "1.7.1",
    "org.mongodb.scala" %% "mongo-scala-driver" % "4.7.1",
  ).map(_.cross(CrossVersion.for3Use2_13))
  .map(_ excludeAll (
    // Resolve conflicting cross-version suffixes
    ExclusionRule(organization = "org.scala-lang.modules", name = "scala-xml_2.13")
  )),

  // Compiled with Scala 3 or not Scala at all
  libraryDependencies ++= Seq(
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.13.4",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "org.planet42" %% "laika-io" % "0.19.0",
    "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
    "org.scalatest" %% "scalatest" % "3.2.12" % Test,
    "org.slf4j" % "slf4j-api" % slf4jV,
    "org.slf4j" % "slf4j-simple" % slf4jV,
  ),

  // Discard module-info.class files
  // Just Java Things (tm), I guess
  assembly / assemblyMergeStrategy := {
    case PathList("module-info.class") => MergeStrategy.discard
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case PathList("reference.conf") => MergeStrategy.concat
    case _ => MergeStrategy.first
  },
  assembly / assemblyJarName := "semantic-repo-assembly.jar",
  artifactName := { (_: ScalaVersion, _: ModuleID, artifact: Artifact) =>
    artifact.name + "." + artifact.extension
  },
  fork := true,
  Test / logBuffered := false,
  scalacOptions ++= Seq(
    "-deprecation",
    "-Werror"
  )
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
  buildInfoPackage := "eu.assistiot.semantic_repo.core.buildinfo",
  buildInfoObject := "BuildInfo"
)

lazy val settings = commonSettings ++ buildInfoSettings

lazy val semantic_repo_core = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(settings: _*)
