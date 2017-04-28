package jupyter.spark.internals

import java.io.File

import coursier.cli.{CommonOptions, Helper}

import jupyter.spark.{scalaBinaryVersion, scalaVersion, sparkVersion}

object Spark {

  def defaultYarnConf() =
    sys.env.get("YARN_CONF_DIR")
      .orElse(if (new File("/etc/hadoop/conf").exists()) Some("/etc/hadoop/conf") else None)
      .getOrElse {
        throw new NoSuchElementException("YARN_CONF_DIR not set and /etc/hadoop/conf not found")
      }

  def hadoopVersion =
    sys.env.get("HADOOP_VERSION")
      .orElse(sys.props.get("HADOOP_VERSION"))
      .getOrElse("2.7.3")

  private def scalaDependencies = Seq(
    s"org.scala-lang:scala-library:$scalaVersion",
    s"org.scala-lang:scala-reflect:$scalaVersion",
    s"org.scala-lang:scala-compiler:$scalaVersion"
  )

  def sparkAssembly(
    extraDependencies: Seq[String] = Nil,
    exclusions: Seq[String] = Nil,
    profiles: Seq[String] = Nil
  ): String =
    coursier.cli.spark.Assembly.spark(
      scalaBinaryVersion,
      sparkVersion,
      hadoopVersion,
      default = true,
      (scalaDependencies ++ extraDependencies).toList,
      options = CommonOptions(
        exclude = exclusions.toList,
        profile = profiles.toList,
        checksum = List("SHA-1") // should not be required with coursier > 1.0.0-M14-9
      ),
      artifactTypes = Set("jar", "bundle")
    ) match {
      case Left(err) =>
        throw new Exception(err)
      case Right((assembly, _)) =>
        assembly.getAbsolutePath
    }

  def sparkBaseDependencies = {

    val extra =
      if (sparkVersion.startsWith("2."))
        Seq()
      else
        Seq(
          s"org.apache.spark:spark-bagel_$scalaBinaryVersion:$sparkVersion"
        )

    scalaDependencies ++ Seq(
      s"org.apache.spark:spark-core_$scalaBinaryVersion:$sparkVersion",
      s"org.apache.spark:spark-mllib_$scalaBinaryVersion:$sparkVersion",
      s"org.apache.spark:spark-streaming_$scalaBinaryVersion:$sparkVersion",
      s"org.apache.spark:spark-graphx_$scalaBinaryVersion:$sparkVersion",
      s"org.apache.spark:spark-sql_$scalaBinaryVersion:$sparkVersion",
      s"org.apache.spark:spark-repl_$scalaBinaryVersion:$sparkVersion",
      s"org.apache.spark:spark-yarn_$scalaBinaryVersion:$sparkVersion"
    ) ++ extra
  }

  def sparkAssemblyJars(
    extraDependencies: Seq[String] = Nil,
    exclusions: Seq[String] = Nil,
    profiles: Seq[String] = Nil
  ) = {

    val helper = new Helper(
      CommonOptions(
        exclude = exclusions.toList,
        profile = profiles.toList
      ),
      extraDependencies ++ sparkBaseDependencies
    )

    helper.fetch(sources = false, javadoc = false, artifactTypes = Set("jar"))
      .map(_.getAbsoluteFile.toURI.toASCIIString)
  }

}