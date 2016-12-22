package jupyter.spark.internals

import java.io.File

import coursier.cli.{CommonOptions, Helper}

import jupyter.spark.{scalaBinaryVersion, sparkVersion}

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
      extraDependencies.toList,
      options = CommonOptions(
        exclude = exclusions.toList,
        profile = profiles.toList,
        checksum = List("SHA-1") // should not be required with coursier > 1.0.0-M14-9
      )
    ) match {
      case Left(err) =>
        throw new Exception(err)
      case Right((assembly, _)) =>
        assembly.getAbsolutePath
    }

  def sparkBaseDependencies(scalaVersion: String, sparkVersion: String) = {

    val extra =
      if (sparkVersion.startsWith("2."))
        Seq()
      else
        Seq(
          s"org.apache.spark:spark-bagel_$scalaVersion:$sparkVersion"
        )

    Seq(
      s"org.apache.spark:spark-core_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-mllib_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-streaming_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-graphx_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-sql_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-repl_$scalaVersion:$sparkVersion",
      s"org.apache.spark:spark-yarn_$scalaVersion:$sparkVersion"
    ) ++ extra
  }

  def sparkAssemblyJars(
    extraDependencies: Seq[String] = Nil,
    exclusions: Seq[String] = Nil,
    profiles: Seq[String] = Nil
  ) = {

    val base = sparkBaseDependencies(
      scalaBinaryVersion,
      sparkVersion
    )
    val helper = new Helper(
      CommonOptions(
        exclude = exclusions.toList,
        profile = profiles.toList
      ),
      extraDependencies ++ base
    )

    helper.fetch(sources = false, javadoc = false, artifactTypes = Set("jar"))
      .map(_.getAbsolutePath)
  }

}