sbtPlugin := true

name := "SbtLibResolver"

version <<= (sbtVersion) { "sbt" + _ + "_0.1-SNAPSHOT" }

//scalaVersion := "2.8.1"

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

organization := "com.github.xuwei-k"

scalacOptions := Seq("-deprecation", "-unchecked")

libraryDependencies ++= Seq(
   "net.sourceforge.htmlunit" % "htmlunit" % "2.4"
  ,"org.specs2" %% "specs2" % "1.5" % "test"
  ,"org.specs2" %% "specs2-scalaz-core" % "5.1-SNAPSHOT" % "test"
)

resolvers ++= Seq(
  "jboss.com" at "http://repository.jboss.com/maven2/"
  ,ScalaToolsSnapshots
)

publishArtifact in (Compile, packageBin) := true

publishArtifact in (Test, packageBin) := false

publishArtifact in (Compile, packageDoc) := false

publishArtifact in (Compile, packageSrc) := false

