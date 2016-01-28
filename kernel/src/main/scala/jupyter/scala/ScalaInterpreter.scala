package jupyter.scala

import ammonite.api._
import ammonite.Interpreter
import ammonite.util.Classpath
import ammonite.interpreter._

import jupyter.api._
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.protocol.Output.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage

import coursier._

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

    private val initialDependencies0 = initialDependencies.map {
      case (cfg, dep) =>
        s"${dep.module}:${dep.version}:$cfg"
    }

    override val implementation = ("jupyter-scala", s"${BuildInfo.version} (scala $scalaVersion)")
    override val banner =
      s"""Jupyter Scala ${BuildInfo.version} (Ammonium ${BuildInfo.ammoniumVersion}) (Scala $scalaVersion)
         |Initial dependencies:
         |${initialDependencies0.map("  "+_).mkString("\n")}
       """.stripMargin
  }


  val scalaVersion = scala.util.Properties.versionNumberString
  val scalaBinaryVersion = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")


  val initialDependencies = Seq(
    "compile" -> Dependency(
      Module("com.github.alexarchambault.jupyter", s"scala-api_$scalaVersion"), BuildInfo.version
    ),
    "macro" -> Dependency(
      Module("org.scala-lang", "scala-compiler"), scalaVersion
    )
  ) ++ {
    if (scalaVersion.startsWith("2.10."))
      Seq(
        "compile" -> Dependency(
          Module("org.scalamacros", "quasiquotes_2.10"), "2.0.1"
        ),
        "compile" -> Dependency(
          Module("org.scala-lang", "scala-compiler"), scalaVersion
        )
      )
    else
      Seq()
  }

  val initialRepositories = Seq(
    coursier.Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  ) ++ {
    if (BuildInfo.version.endsWith("-SNAPSHOT")) Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
    ) else Nil
  }

  val defaultLoader = Thread.currentThread().getContextClassLoader

  val compileLoader = Classpath.isolatedLoader(defaultLoader, "jupyter-scala-compile").getOrElse(defaultLoader)
  val macroLoader = Classpath.isolatedLoader(defaultLoader, "jupyter-scala-macro").getOrElse(compileLoader)

  lazy val classLoaders0 = Map(
    "runtime" -> compileLoader,
    "compile" -> compileLoader,
    "macro" -> macroLoader,
    "plugin" -> defaultLoader
  )

  val configs = Map(
    "compile" -> Nil,
    "runtime" -> Seq("compile"),
    "macro" -> Seq("compile"),
    "plugin" -> Nil
  )


  def apply(
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
      lazy val classpath: Classpath = new Classpath(
        initialRepositories,
        initialDependencies,
        classLoaders0,
        configs,
        Interpreter.initCompiler()(intp)
      )

      lazy val intp = new Interpreter(
        imports = new ammonite.interpreter.Imports(useClassWrapper = true),
        classpath = classpath
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
          // FIXME More or less the same thing in ammonium...

          val (doClassWrap, decls0) =
            if (decls.exists(hasObjWrapSpecialImport))
              (false, decls.filterNot(hasObjWrapSpecialImport))
            else
              (true, decls)

          if (doClassWrap)
            Interpreter.classWrap(print0(_, colors), decls0, imports, unfilteredImports, wrapper)
          else
            Interpreter.wrap(print0(_, colors), decls0, imports, unfilteredImports, "special" + wrapper)
        }
      }

      val init = Interpreter.init(
        new BridgeConfig(
          currentPublish,
          currentMessage,
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
