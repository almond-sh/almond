package almond.docs

import almond.kernel.install.OptionGroups
import almond.launcher.LauncherOptions
import caseapp.Name
import caseapp.core.Arg
import caseapp.core.help.Help
import caseapp.core.parser.Parser

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Updates the lists of options of the options page of the website, from the case-app annotations
  * of the options of the kernel and of the launcher.
  *
  * The page has regions like
  * {{{
  * <!-- BEGIN GENERATED OPTIONS kernel Installation -->
  * …
  * <!-- END GENERATED OPTIONS -->
  * }}}
  * whose content gets replaced by the options of a group (`Installation` here, see
  * [[OptionGroups]]), of the kernel or of the launcher. The options of the launcher that the kernel
  * accepts too are only listed among the kernel ones. The text outside of these regions is left as
  * is.
  *
  * Each option gets a section, with the Markdown documentation of its `@HelpMessage` annotation
  * (its second argument), or its help message if it has none.
  *
  * Usage: `OptionsPage [--check] path/to/install-options.md`. With `--check`, the page isn't
  * written, and this fails if it isn't up-to-date.
  */
object OptionsPage {

  // We derive the parsers here, rather than using those of the companions of the options. The
  // derivation of those of the kernel and of the launcher is inlined in the companions, along
  // with that of the options of the shared kernel module. Incremental compilation doesn't see
  // changes in annotations as API changes, so these wouldn't be updated when only the annotations
  // of the shared kernel options change. This module isn't compiled incrementally (see
  // build.mill), so that these derivations are always up-to-date.
  private lazy val kernelHelp: Help[almond.Options] = {
    given Parser[almond.Options] = Parser.derive[almond.Options]
    Help.derive[almond.Options]
  }
  private lazy val launcherHelp: Help[LauncherOptions] = {
    given Parser[LauncherOptions] = Parser.derive[LauncherOptions]
    Help.derive[LauncherOptions]
  }

  private enum Source(val id: String, getHelp: => Help[?]) {
    lazy val help: Help[?] = getHelp.withHelp
    case Kernel   extends Source("kernel", kernelHelp)
    case Launcher extends Source("launcher", launcherHelp)
  }

  private def optionName(help: Help[?], name: Name): String =
    if (name.name.startsWith("-")) help.nameFormatter.format(name)
    else if (name.name.length == 1) s"-${name.name}"
    else s"--${help.nameFormatter.format(name)}"

  private def groupOf(arg: Arg): String =
    arg.group.map(_.name).getOrElse("")

  private def shown(source: Source): Seq[Arg] =
    source.help.args.filter(!_.noHelp)

  private val errors = new scala.collection.mutable.ListBuffer[String]
  private def error(message: String): Unit =
    errors += message

  /** Checks that the groups of the options are consistent, and returns the options to document, per
    * source and group
    */
  private def options(): Map[(Source, String), Seq[Arg]] = {
    val kernelArgs   = shown(Source.Kernel)
    val launcherArgs = shown(Source.Launcher)
    val kernelGroups = kernelArgs.map(arg => arg.name.name -> groupOf(arg)).toMap

    for (arg <- kernelArgs if !OptionGroups.all.contains(groupOf(arg)))
      error(
        s"Kernel option ${arg.name.name} has no group or an unknown one (${groupOf(arg)}), " +
          s"expected one of ${OptionGroups.all.mkString(", ")}"
      )
    for (arg <- kernelArgs if groupOf(arg) == OptionGroups.launcher)
      error(s"Kernel option ${arg.name.name} can't be in the ${OptionGroups.launcher} group")

    for (arg <- launcherArgs)
      (kernelGroups.get(arg.name.name), groupOf(arg)) match {
        case (None, OptionGroups.launcher) =>
        case (Some(_), OptionGroups.launcher) =>
          error(
            s"Launcher option ${arg.name.name} is accepted by the kernel too, it should be in " +
              s"the same group as the kernel one rather than in the ${OptionGroups.launcher} group"
          )
        case (None, group) =>
          error(
            s"Launcher option ${arg.name.name} isn't accepted by the kernel, it should be in " +
              s"the ${OptionGroups.launcher} group (found: '$group')"
          )
        case (Some(kernelGroup), group) if kernelGroup != group =>
          error(
            s"Launcher option ${arg.name.name} is in the '$group' group, while the kernel one " +
              s"is in the '$kernelGroup' group"
          )
        case (Some(_), _) =>
      }

    for (arg <- kernelArgs ++ launcherArgs if arg.helpMessage.forall(_.message.trim.isEmpty))
      error(s"Option ${arg.name.name} has no help message")

    val kernelMap = kernelArgs.groupBy(groupOf).map {
      case (group, args) => (Source.Kernel, group) -> args
    }
    val launcherMap = launcherArgs
      .filter(arg => groupOf(arg) == OptionGroups.launcher)
      .groupBy(groupOf)
      .map {
        case (group, args) => (Source.Launcher, group) -> args
      }
    kernelMap ++ launcherMap
  }

  private def capitalized(message: String): String = {
    val message0 = message.trim.capitalize
    if (message0.endsWith(".") || message0.endsWith(")")) message0
    else message0 + "."
  }

  private def documentation(source: Source, arg: Arg, launcherNames: Set[String]): String = {
    val names = arg.names.map(optionName(source.help, _))
    val doc = arg.helpMessage
      .map { msg =>
        if (msg.messageMd.trim.nonEmpty) msg.messageMd.trim
        else capitalized(msg.message)
      }
      .getOrElse("")

    val notes = Seq(
      Option.when(names.length > 1) {
        val aliases = names.tail.map(n => s"`$n`")
        (if (aliases.length > 1) "Aliases: " else "Alias: ") + aliases.mkString(", ") + "."
      },
      Option.when(source == Source.Kernel && !launcherNames(arg.name.name)) {
        "Pass it after an extra `--` to the [newer launcher](#newer-launcher-options)."
      }
    ).flatten

    // the notes go right after the main part of the documentation, before its sub-sections if
    // it has some
    val docLines = doc.linesIterator.toVector
    val inCodeBlock = docLines
      .scanLeft(false)((inCode, line) => if (line.startsWith("```")) !inCode else inCode)
    val subSectionIdx = docLines.indices
      .find(idx => !inCodeBlock(idx) && docLines(idx).startsWith("#"))
      .getOrElse(docLines.length)
    val (main, subSections) = docLines.splitAt(subSectionIdx)
    val parts = Seq(
      s"#### `${names.head}`",
      main.mkString("\n").trim
    ) ++
      Option.when(notes.nonEmpty)(notes.mkString(" ")).toSeq ++
      Option.when(subSections.nonEmpty)(subSections.mkString("\n").trim).toSeq
    parts.filter(_.nonEmpty).mkString("\n\n")
  }

  private val beginPrefix = "<!-- BEGIN GENERATED OPTIONS "
  private val endMarker   = "<!-- END GENERATED OPTIONS -->"

  /** The page, with its generated regions updated */
  def update(page: String): String = {
    val opts          = options()
    val launcherNames = shown(Source.Launcher).map(_.name.name).toSet

    val b    = new StringBuilder
    val seen = new scala.collection.mutable.LinkedHashSet[(Source, String)]
    var rem  = page
    var done = false
    while (!done) {
      val beginIdx = rem.indexOf(beginPrefix)
      if (beginIdx < 0) {
        b.append(rem)
        done = true
      }
      else {
        val beginEndIdx = rem.indexOf("-->", beginIdx)
        val endIdx      = rem.indexOf(endMarker, beginIdx)
        if (beginEndIdx < 0 || endIdx < 0) {
          error(s"Unterminated generated region in page, at '${rem.drop(beginIdx).take(80)}'")
          b.append(rem)
          done = true
        }
        else {
          val beginMarker = rem.substring(beginIdx, beginEndIdx + "-->".length)
          val content = rem.substring(beginIdx + beginPrefix.length, beginEndIdx).trim
            .split("\\s+", 2) match {
            case Array(sourceId, group) =>
              Source.values.find(_.id == sourceId) match {
                case None =>
                  error(s"Unknown source '$sourceId' in '$beginMarker'")
                  ""
                case Some(source) =>
                  if (!seen.add((source, group)))
                    error(s"Duplicated region '$beginMarker'")
                  opts.get((source, group)) match {
                    case None =>
                      error(s"No ${source.id} option in group '$group' (from '$beginMarker')")
                      ""
                    case Some(args) =>
                      args.map(documentation(source, _, launcherNames)).mkString("\n\n")
                  }
              }
            case _ =>
              error(s"Malformed marker '$beginMarker', expected '$beginPrefix<source> <group> -->'")
              ""
          }
          b.append(rem.substring(0, beginIdx))
          b.append(beginMarker)
          b.append("\n\n")
          b.append(content)
          b.append("\n\n")
          b.append(endMarker)
          rem = rem.substring(endIdx + endMarker.length)
        }
      }
    }

    for (key @ (source, group) <- opts.keys.toVector.sortBy((s, g) => (s.ordinal, g)) if !seen(key))
      error(
        s"The page has no region for the $group ${source.id} options, add one like " +
          s"'$beginPrefix${source.id} $group -->' and '$endMarker'"
      )

    b.result()
  }

  def main(args: Array[String]): Unit = {
    val (check, pathStr) = args.toList match {
      case List("--check", path) => (true, path)
      case List(path)            => (false, path)
      case _ =>
        System.err.println("Usage: OptionsPage [--check] path/to/install-options.md")
        sys.exit(1)
    }
    val path    = Paths.get(pathStr)
    val page    = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    val updated = update(page)

    if (errors.nonEmpty) {
      for (err <- errors)
        System.err.println(s"Error: $err")
      sys.exit(1)
    }

    if (updated == page)
      System.err.println(s"$path is up-to-date")
    else if (check) {
      System.err.println(
        s"$path isn't up-to-date with the options of the kernel and of the launcher, " +
          "run ./mill docs.updateOptionsPage to update it"
      )
      sys.exit(1)
    }
    else {
      Files.write(path, updated.getBytes(StandardCharsets.UTF_8))
      System.err.println(s"Updated $path")
    }
  }
}
