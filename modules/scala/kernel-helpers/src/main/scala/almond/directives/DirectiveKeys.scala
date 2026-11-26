package almond.directives

/** Keys of the using directives (`//> using key value…`) Almond accepts
  *
  * In the kernel, directives are handled by Ammonite's directive hooks (see
  * `ammonite.runtime.DirectiveHook`): Ammonite's own ones for dependencies, repositories, and
  * scripts, and ours for the others (see `almond.Execute.directiveHooks`). The launcher, that
  * doesn't embed Ammonite, needs to know which directives it passes to the kernel.
  */
object DirectiveKeys {

  // Like Scala CLI and Ammonite (see ammonite.runtime.DirectiveHook.defaults), most directives
  // accept both singular and plural keys. The first key of each list is the main one.

  // Kernel directives - these can be used in any cell, the launcher passes them to the kernel
  val dependency = Seq("dep", "deps", "dependency", "dependencies", "lib", "libs")
  val repository = Seq("repository", "repositories")
  val scalacOption = Seq(
    "option",
    "options",
    "scalacOption",
    "scalacOptions",
    "scalac-option",
    "scalac-options"
  )
  val script      = Seq("script", "scripts")
  val outputStyle = Seq("outputStyle", "output-style")

  val kernel: Seq[String] = dependency ++ repository ++ scalacOption ++ script ++ outputStyle

  // Launcher directives - these are only taken into account by the launcher, before the kernel
  // is started
  val scala   = Seq("scala")
  val jvm     = Seq("jvm")
  val javaOpt = Seq("javaOpt", "javaOptions", "java-opt", "java-options")
  val javaCmd = Seq("javaCmd", "java-cmd")

  val launcher: Seq[String] = scala ++ jvm ++ javaOpt ++ javaCmd
}
