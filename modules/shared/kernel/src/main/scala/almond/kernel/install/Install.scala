package almond.kernel.install

import java.io.{ByteArrayOutputStream, File, IOException, InputStream}
import java.net.{URI, URL}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.jar.{Attributes, Manifest}
import java.util.zip.ZipFile

import almond.kernel.util.JupyterPath
import almond.protocol.KernelSpec

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object Install {

  import com.github.plokhotnyuk.jsoniter_scala.core._

  private def deleteRecursively(f: Path): Unit = {
    if (Files.isDirectory(f))
      Files.list(f)
        .iterator()
        .asScala
        .foreach(deleteRecursively)

    Files.deleteIfExists(f)
  }

  private def readFully(is: => InputStream): Array[Byte] = {

    var is0: InputStream = null

    try {
      is0 = is

      val buffer = new ByteArrayOutputStream
      val data   = Array.ofDim[Byte](16384)

      var nRead = 0
      while ({
        nRead = is0.read(data, 0, data.length)
        nRead != -1
      })
        buffer.write(data, 0, nRead)

      buffer.flush()
      buffer.toByteArray
    }
    finally
      if (is0 != null)
        is0.close()
  }

  /** Installs a Jupyter kernel.
    */
  def installIn(
    kernelId: String,
    spec: KernelSpec,
    jupyterPath: Either[Path, JupyterPath],
    logo64PngOpt: Option[URL] = None,
    force: Boolean = false,
    copyLauncher: Boolean = false
  ): Path = {

    // About the logo, don't know if other formats / sizes are allowed.

    // Ensure we can read that one upfront
    val logoContentOpt = logo64PngOpt.map(url => readFully(url.openStream()))

    val jupyterDir = jupyterPath match {
      case Left(p) => p
      case Right(j) =>
        j.paths.headOption.getOrElse(
          throw new InstallException.JupyterDirectoryTypeNotFound(j.name)
        )
    }

    val dir = jupyterDir.resolve(kernelId)

    if (Files.exists(dir))
      if (force)
        // not deleting dir itself - on Windows, sometimes running into java.nio.file.AccessDeniedException in
        // createDirectories below else
        Files.list(dir)
          .iterator()
          .asScala
          .foreach(deleteRecursively)
      else
        throw new InstallException.InstallDirAlreadyExists(dir)

    Files.createDirectories(dir)

    if (!Files.exists(dir))
      throw new Exception(s"Could not create $dir")

    val spec0 =
      if (copyLauncher)
        launcherPos(spec.argv) match {
          case None =>
            throw new Exception(
              s"Can't copy launcher, launcher argument in command ${spec.argv.mkString(" ")} cannot be found"
            )
          case Some((launcher0, pos)) =>
            val dest   = dir.resolve("launcher.jar")
            val source = Paths.get(launcher0)
            if (!Files.exists(source))
              throw new Exception(s"Launcher $launcher0 in kernel spec command not found")
            if (!Files.isRegularFile(source))
              throw new Exception(
                s"Launcher $launcher0 in kernel spec command is not a regular file"
              )
            try Files.copy(
                Paths.get(launcher0),
                dest,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
              )
            catch {
              case _: IOException =>
                Files.copy(Paths.get(launcher0), dest, StandardCopyOption.REPLACE_EXISTING)
            }
            spec.copy(
              argv = spec.argv.updated(pos, spec.argv(pos).replace(launcher0, dest.toString))
            )
        }
      else
        spec

    val kernelJsonContent = writeToArray(spec0, WriterConfig.withIndentionStep(2))

    Files.write(dir.resolve("kernel.json"), kernelJsonContent)
    for (b <- logoContentOpt)
      Files.write(dir.resolve("logo-64x64.png"), b)

    dir
  }

  private def mainClass(jar: String): String = {
    val zf  = new ZipFile(new File(jar))
    val ent = zf.getEntry("META-INF/MANIFEST.MF")
    if (ent == null)
      sys.error(s"No manifest found in $jar")
    val mf   = new Manifest(zf.getInputStream(ent))
    val attr = mf.getMainAttributes()
    Option(attr.getValue(Attributes.Name.MAIN_CLASS)).getOrElse {
      sys.error(s"No main class found in $jar")
    }
  }

  /** Gets the command that launched the current application if possible.
    *
    * Works if the coursier launcher is involved.
    *
    * @param filterOutArgs:
    *   arguments to filter-out
    */
  def currentAppCommand(
    extraStartupClassPath: Seq[String],
    filterOutArgs: Set[String] = Set.empty
  ): Option[List[String]] =
    for {
      mainJar <- sys.props.get("coursier.mainJar")
      mainJar0 =
        if (mainJar.startsWith("/"))
          // Paths.get throws if given paths like "C:\\foo" on Windows, without
          // this URI stuff
          Paths.get(new URI("file://" + mainJar)).toString
        else
          mainJar // that case shouldn't happen
      mainArgs = Iterator.from(0)
        .map(i => s"coursier.main.arg-$i")
        .map(sys.props.get)
        .takeWhile(_.nonEmpty)
        .collect { case Some(arg) if !filterOutArgs(arg) => arg }
        .toList
      cp = (extraStartupClassPath :+ mainJar0).mkString(File.pathSeparator)
    } yield "java" :: "-cp" :: cp :: mainClass(mainJar0) :: mainArgs

  /** Gets the command that launched the current application if possible.
    *
    * Works if the current app is launched via a single JAR, specifying a main class in its
    * manifest.
    *
    * @param filterOutArgs:
    *   arguments to filter-out
    */
  def fatJarCurrentAppCommand(filterOutArgs: Set[String] = Set.empty): Option[List[String]] =
    for {
      mainJar <- Option(getClass.getProtectionDomain.getCodeSource)
        .flatMap(s => Option(s.getLocation))
        .flatMap(u => scala.util.Try(Paths.get(u.toURI).toFile.getAbsolutePath).toOption)
      command <- sys.props.get("sun.java.command")
      mainArgs = command
        .split("\\s+")
        .drop(1) // drop launcher path (should be mainJar)
        .filter(!filterOutArgs(_))
        .toList
    } yield "java" :: "-jar" :: mainJar :: mainArgs

  def launcherPos(command: List[String]): Option[(String, Int)] =
    command match {
      case h :: t if h == "java" || h.endsWith("/java") =>
        @tailrec
        def helper(l: List[(String, Int)]): Option[(String, Int)] =
          l match {
            case Nil =>
              None
            case (h, _) :: t =>
              if (h == "-cp")
                t.headOption.map { case (arg, n) =>
                  // assuming the launcher is last here…
                  (arg.split(File.pathSeparator).last, n + 1)
                }
              else if (h == "-jar")
                t.headOption.map { case (arg, n) => (arg, n + 1) }
              else if (h.startsWith("-"))
                helper(t)
              else
                None
          }

        helper(t.zipWithIndex)
      case _ =>
        None
    }

  def defaultConnectionFileArgs: Seq[String] =
    Seq("--connection-file", "{connection_file}")

  def install(
    defaultId: String,
    defaultDisplayName: String,
    language: String,
    options: Options,
    extraStartupClassPath: Seq[String] = Nil,
    defaultLogoOpt: Option[URL] = None,
    connectionFileArgs: Seq[String] = defaultConnectionFileArgs,
    interruptMode: Option[String] = None
  ): Path =
    install(
      defaultId,
      defaultDisplayName,
      language,
      options,
      defaultLogoOpt,
      connectionFileArgs,
      interruptMode,
      Map.empty,
      extraStartupClassPath
    )

  def install(
    defaultId: String,
    defaultDisplayName: String,
    language: String,
    options: Options,
    defaultLogoOpt: Option[URL],
    connectionFileArgs: Seq[String],
    interruptMode: Option[String],
    env: Map[String, String],
    extraStartupClassPath: Seq[String]
  ): Path = {

    val path =
      options.jupyterPath match {
        case Some(p) => Left(Paths.get(p))
        case None =>
          Right(
            if (options.global)
              JupyterPath.System
            else
              JupyterPath.User
          )
      }

    val cmd =
      options.command.map(_.trim).filter(_.nonEmpty) match {
        case Some(command) =>
          command
            .split("\\s+")
            .toSeq
        case None =>
          if (options.arg.isEmpty)
            Install.currentAppCommand(
              extraStartupClassPath,
              Set("--install", "--force", "--global").flatMap(s =>
                Seq(s, s"$s=true")
              )
            ).getOrElse {
              throw new InstallException.CannotGetKernelCommand
            }
          else
            options.arg
      }

    val logoOpt =
      options.logo match {
        case None =>
          defaultLogoOpt
        case Some("") =>
          None
        case Some(f) =>
          Some(Paths.get(f).toUri.toURL)
      }

    Install.installIn(
      options.id.getOrElse(defaultId),
      KernelSpec(
        argv = (cmd ++ connectionFileArgs).toList,
        display_name = options.displayName.getOrElse(defaultDisplayName),
        language = language,
        interrupt_mode = interruptMode,
        env = env
      ),
      path,
      logo64PngOpt = logoOpt,
      force = options.force,
      copyLauncher = options.copyLauncher0
    )
  }

  def installOrError(
    defaultId: String,
    defaultDisplayName: String,
    language: String,
    options: Options,
    extraStartupClassPath: Seq[String],
    defaultLogoOpt: Option[URL] = None,
    connectionFileArgs: Seq[String] = defaultConnectionFileArgs,
    interruptMode: Option[String] = None
  ): Either[InstallException, Path] =
    installOrError(
      defaultId,
      defaultDisplayName,
      language,
      options,
      defaultLogoOpt,
      connectionFileArgs,
      interruptMode,
      Map.empty,
      extraStartupClassPath
    )

  def installOrError(
    defaultId: String,
    defaultDisplayName: String,
    language: String,
    options: Options,
    defaultLogoOpt: Option[URL],
    connectionFileArgs: Seq[String],
    interruptMode: Option[String],
    env: Map[String, String],
    extraStartupClassPath: Seq[String]
  ): Either[InstallException, Path] =
    try {
      val dir = install(
        defaultId,
        defaultDisplayName,
        language,
        options,
        defaultLogoOpt,
        connectionFileArgs,
        interruptMode,
        env,
        extraStartupClassPath
      )
      Right(dir)
    }
    catch {
      case e: InstallException =>
        Left(e)
    }

}
