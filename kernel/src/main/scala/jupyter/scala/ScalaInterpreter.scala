package jupyter.scala

import java.io.File

import ammonite.api._
import ammonite.interpreter.Classes
import ammonite.Interpreter
import ammonite.util.Load
import ammonite.interpreter._
import coursier.core.Repository
import coursier.maven.MavenRepository
import coursier.util.ClasspathFilter

import jupyter.api._
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.protocol.Output.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage

object ScalaInterpreter {

  trait InterpreterDefaults extends interpreter.Interpreter {
    override def resultDisplay = true // Displaying results directly, not under Jupyter "Out" prompt

    val languageInfo = LanguageInfo(
      name=s"scala${scalaBinaryVersion.filterNot(_ == '.')}",
      version = scalaVersion,
      codemirror_mode = "text/x-scala",
      file_extension = ".scala",
      mimetype = "text/x-scala",
      pygments_lexer = "scala"
    )

    override val implementation = ("jupyter-scala", s"${BuildInfo.version} (scala $scalaVersion)")
    override val banner =
      s"""Jupyter Scala ${BuildInfo.version} (Ammonite ${BuildInfo.ammoniteVersion} fork) (Scala $scalaVersion)
         |Start dependencies: ${modules0(ClassLoaderType.Main).map{case (org, name, ver) => s"  $org:$name:$ver"}.mkString("\n", "\n", "")}
       """.stripMargin
  }


  val scalaVersion = scala.util.Properties.versionNumberString
  val scalaBinaryVersion = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  import ammonite.api.ModuleConstructor._

  val modules0 = Map[ClassLoaderType, Seq[(String, String, String)]](
    ClassLoaderType.Main -> Seq(
      "org.scala-lang" % "scala-library" % scalaVersion,
      "com.github.alexarchambault.jupyter" % s"jupyter-scala-api_$scalaVersion" % BuildInfo.version
    ),
    ClassLoaderType.Macro -> Seq(
      "org.scala-lang" % "scala-library" % scalaVersion,
      "com.github.alexarchambault.jupyter" % s"jupyter-scala-api_$scalaVersion" % BuildInfo.version,
      "org.scala-lang" % "scala-compiler" % scalaVersion
    ),
    ClassLoaderType.Plugin -> Seq.empty
  )

  val repositories =
    Seq(coursier.Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")) ++ {
      if (BuildInfo.version endsWith "-SNAPSHOT")
        Seq(MavenRepository("https://oss.sonatype.org/content/repositories/snapshots"))
      else
        Seq()
    }

  lazy val pathMap = Classes.jarMap(getClass.getClassLoader)

  lazy val paths0 = {
    modules0.map { case (tpe, modules) =>
      tpe -> Load.resolve(modules, repositories)
        .map(pathMap)
        .filter(_.exists())
    }
  }

  lazy val classLoaders0 = paths0.map { case (tpe, paths) =>
    tpe -> new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ paths).toSet, exclude = false)
  }

  def classes0 =
    new Classes(
      classLoaders0(ClassLoaderType.Main),
      macroClassLoader0 = classLoaders0(ClassLoaderType.Macro),
      startPaths = paths0
    )


  def classPathOrIvy(allDeps: String, modules: Seq[(String, String, String)]) =
    Classes.fromClasspath(allDeps, getClass.getClassLoader) match {
      case Right(jars) => (jars.toSeq, Nil)

      case Left(missing) =>
        println(s"Cannot find the following dependencies on the classpath:\n${missing.mkString("\n")}")
        println("Resolving shared dependencies with Ivy")
        Load.resolve(modules, repositories).toList
          .map(pathMap)
          .distinct
          .filter(_.exists())
          .partition(_.getName endsWith ".jar")
    }

  def apply(
    paths0: => Map[ClassLoaderType, Seq[File]] = paths0,
    modules0: => Map[ClassLoaderType, Seq[(String, String, String)]] = modules0,
    pathMap: => File => File = pathMap,
    repositories0: => Seq[Repository] = repositories,
    classLoaders: => Map[ClassLoaderType, ClassLoader] = classLoaders0,
    pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig.copy(width = 80, height = 20),
    colors: Colors = Colors.Default,
    filterUnitResults: Boolean = true
  ): interpreter.Interpreter { def stop(): Unit } = {

    var currentPublish = Option.empty[Publish[Evidence]]
    var currentMessage = Option.empty[ParsedMessage[_]]

    var initialized0 = false

    def print0(items: Seq[CodeItem], colors: Colors): String =
      s""" Iterator[Iterator[String]](${items.map(WebDisplay(_, colors)).mkString(", ")}).filter(_.nonEmpty).flatMap(_ ++ Iterator("\\n")) """

    lazy val underlying = {
      val intp = new Interpreter(
        imports = new ammonite.interpreter.Imports(useClassWrapper = true),
        classes = classes0
      ) {
        def hasObjWrapSpecialImport(d: ParsedCode): Boolean =
          d.items.exists {
            case CodeItem.Import("special.wrap.obj") => true
            case _                                   => false
          }

        override def wrap(
          decls: Seq[ParsedCode],
          imports: String,
          unfilteredImports: String,
          wrapper: String
        ) = {
          val (doClassWrap, decls0) =
            if (decls.exists(hasObjWrapSpecialImport))
              (false, decls.filterNot(hasObjWrapSpecialImport))
            else
              (true, decls)

          if (doClassWrap)
            Interpreter.classWrap(print0(_, colors), decls0, imports, unfilteredImports, wrapper)
          else
            Interpreter.wrap(print0(_, colors), decls0, imports, unfilteredImports, wrapper)
        }
      }

      val init = Interpreter.init(
        new BridgeConfig(
          currentPublish,
          currentMessage,
          paths0 = paths0,
          modules0 = modules0,
          pathMap = pathMap,
          repositories0 = repositories0,
          pprintConfig = pprintConfig,
          colors = colors
        ),
        None,
        None
      )

      // FIXME Check result
      init(intp)


      initialized0 = true
      intp
    }

    new interpreter.Interpreter with InterpreterDefaults {
      def stop() = underlying.stop()

      override def initialized = initialized0
      override def init() = underlying
      var executionCount0 = 0
      def executionCount = executionCount0

      override def publish(publish: Publish[ParsedMessage[_]]) = {
        currentPublish = Some(publish.contramap[Evidence](e => e.underlying.asInstanceOf[ParsedMessage[_]]))
      }

      def complete(code: String, pos: Int) = {
        val (pos0, completions, _) = underlying.complete(pos, code)
        (pos0, completions)
      }

      def interpret(
        line: String,
        output: Option[(String => Unit, String => Unit)],
        storeHistory: Boolean,
        current: Option[ParsedMessage[_]]
      ) = {

        currentMessage = current

        try {
          val run = Interpreter.run(
            line,
            { executionCount0 += 1 },
            output.map(_._1),
            output.map(_._2),
            it => new DisplayData.RawData(
              it.asInstanceOf[Iterator[String]].mkString.stripSuffix("\n")
            )
          )

          run(underlying) match {
            case Left(err) =>
              interpreter.Interpreter.Error(err.msg)
            case Right(Evaluated(_, _, data)) =>
              interpreter.Interpreter.Value(data)
          }
        }
        finally
          currentMessage = None
      }
    }
  }

}
