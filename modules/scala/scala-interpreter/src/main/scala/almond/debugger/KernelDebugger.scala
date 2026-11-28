package almond.debugger

import almond.interpreter.Debugger
import almond.logger.LoggerContext
import almond.protocol.RawJson
import ammonite.debugger.{DebugAdapter, Logger, SessionDebugger}

import java.io.File
import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** Implements the Jupyter debugger protocol, on top of the debugger of Ammonite sessions
  * (`ammonite.debugger.SessionDebugger`), that proxies DAP messages to scala-debug-adapter
  *
  * scala-debug-adapter runs in the JVM of the kernel, in a class loader of its own (see
  * `ammonite.debugger.DebugAdapter`), attached via JDWP to the JVM it runs in, which runs with a
  * JDWP agent (see `ammonite.debugger.Jdwp`).
  *
  * Front-ends refer to cells via files, whose names are a hash of the code of the cells (see
  * [[cellPath]]). These are the keys of the breakpoints of the inputs of the session debugger,
  * which takes care of mapping them to the wrapper classes of cells (and back, in stack frames).
  * This class takes care of what's specific to Jupyter (`debugInfo` and `dumpCell` requests, …) and
  * to JupyterLab.
  *
  * @param jdwpAddress
  *   makes the JDWP agent of the kernel listen if it doesn't yet, and returns its address
  * @param pkgName
  *   package of the wrapper classes (like `ammonite.$sess`)
  * @param startAdapter
  *   starts the debug adapter, attached to the JDWP agent of the kernel
  */
final class KernelDebugger(
  jdwpAddress: () => Either[String, (String, Int)],
  pkgName: Seq[String],
  workDir: os.Path,
  startAdapter: (String => Boolean) => DebugAdapter,
  logCtx: LoggerContext
) extends Debugger {

  import KernelDebugger._

  private val log = logCtx(getClass)

  /** Where front-ends expect the code of cells to be, see [[cellPath]]
    *
    * JupyterLab shows the files of cells as `Cell [3]` (with the execution count of the cell)
    * rather than as paths, in the call stack and breakpoints panels of the debugger, but only if
    * their path contains `ipykernel_` (like the files of ipykernel,
    * `/tmp/ipykernel_<pid>/<hash>.py`), hence the name of that directory.
    */
  private val cellsDir = workDir / "ipykernel_cells"

  /** Copies of the code of the wrapper classes of cells, at the paths the debug adapter looks them
    * up (`<package path>/<wrapper name>.sc`, `.sc` being the source file name Ammonite passes to
    * the compiler)
    */
  private val wrappersDir = KernelDebugger.wrappersDir(workDir)

  private val core = new SessionDebugger(
    jdwpAddress,
    pkgName,
    startAdapter,
    () => Seq(wrappersDir.toString),
    KernelDebugger.logger(logCtx)
  )
  import core.ec

  def tmpFilePrefix = cellsDir.toString + File.separator
  def tmpFileSuffix = ".sc"

  /** The file front-ends use to refer to a cell with that code */
  def cellPath(code: String): String =
    tmpFilePrefix + Murmur2.hash(code, Murmur2.defaultSeed) + tmpFileSuffix

  private val lock = new Object

  @volatile private var eventHandler: (RawJson => Unit) = (_: RawJson) => ()

  def setEventHandler(handler: RawJson => Unit): Unit =
    eventHandler = handler

  core.setEventHandler { event =>
    eventHandler(RawJson(ujson.write(event).getBytes(StandardCharsets.UTF_8)))
  }

  /** Called right before the wrapper class of a cell is loaded and run, from the thread running it
    *
    * @param code
    *   code of the cell, as front-ends see it
    * @param lineShift
    *   difference between the line numbers of the TASTy files of the classes of the cell (if any)
    *   and those of their byte code
    */
  def beforeRun(
    wrapperName: String,
    code: String,
    classNames: Seq[String],
    lineShift: Option[Int]
  ): Unit = {
    val path = cellPath(code)
    // Ammonite compiles the wrapper classes so that the line numbers of the cell code in their
    // byte code are those of the cell itself, hence no offset
    val input = SessionDebugger.Input(
      wrapperName,
      path,
      new File(path).getName,
      path,
      0,
      code.count(_ == '\n') + 1,
      classNames.contains((pkgName :+ wrapperName).mkString(".") + "$Helper")
    )
    try {
      os.write.over(os.Path(path), code, createFolders = true)
      // the source the debug adapter finds for the wrapper class, whose lines are those of the
      // cell, like the byte code
      os.write.over(wrappersDir / pkgName / s"$wrapperName.sc", code, createFolders = true)
    }
    catch {
      case NonFatal(e) =>
        log.warn(s"Error writing sources of $wrapperName", e)
    }
    core.beforeRun(input, classNames, lineShift)
  }

  def request(request: RawJson): Future[RawJson] = {
    val req = Try(ujson.read(request.value).obj) match {
      case Success(obj) => ujson.Obj.from(obj)
      case Failure(e)   => return Future.failed(e)
    }
    val command = req.value.get("command").map(_.str).getOrElse("")
    val seq     = req.value.get("seq").map(_.num.toInt).getOrElse(0)
    val args = req.value.get("arguments") match {
      case Some(obj: ujson.Obj) => ujson.Obj.from(obj.value)
      case _                    => ujson.Obj()
    }

    def respond(body: ujson.Value = ujson.Obj(), success: Boolean = true, message: String = "") = {
      val resp = ujson.Obj(
        "seq"         -> seq,
        "type"        -> "response",
        "request_seq" -> seq,
        "success"     -> success,
        "command"     -> command,
        "body"        -> body
      )
      if (message.nonEmpty)
        resp("message") = message
      resp
    }

    val res = Future(command).flatMap {
      case "debugInfo"         => Future.successful(respond(debugInfo()))
      case "dumpCell"          => Future.successful(respond(dumpCell(args)))
      case "initialize"        => core.initialize(args).map(withSeq(_, seq))
      case "attach"            => core.attach().map(r => withSeq(r, seq, command))
      case "configurationDone" =>
        // the session debugger sends its own configurationDone request upon attach
        Future.successful(respond())
      case "setBreakpoints" => setBreakpoints(args).map(respond(_))
      case "stackTrace" =>
        core.stackTrace(args).map(r => withSeq(withLibrarySourcePaths(r), seq))
      case "variables" => core.request(command, args).map(r => withSeq(variables(r), seq))
      case "source"    => source(args, respond(_), seq)
      case "pause" =>
        val threadId = args.value.get("threadId").collect { case n: ujson.Num => n.num.toLong }
        core.pause(threadId).map(withSeq(_, seq))
      case "continue"                    => continueThreads(args).map(withSeq(_, seq))
      case "stepIn" | "next" | "stepOut" => core.step(command, args).map(withSeq(_, seq))
      case "disconnect"                  => core.disconnect(args).map(withSeq(_, seq))
      case "terminate"                   =>
        // Terminating the debuggee would mean terminating the kernel
        Future.successful(respond())
      case "inspectVariables" => Future.successful(respond(ujson.Obj("variables" -> ujson.Arr())))
      case "richInspectVariables" =>
        Future.successful(respond(ujson.Obj("data" -> ujson.Obj(), "metadata" -> ujson.Obj())))
      case "modules" =>
        Future.successful(respond(ujson.Obj("modules" -> ujson.Arr(), "totalModules" -> 0)))
      case "setExceptionBreakpoints" if !core.isStarted =>
        Future.successful(respond())
      case _ =>
        core.request(command, args).map(withSeq(_, seq))
    }

    res
      .recover {
        case NonFatal(e) =>
          log.warn(s"Error processing debug request $command", e)
          respond(success = false, message = Option(e.getMessage).getOrElse(e.toString))
      }
      .map(r => RawJson(ujson.write(r).getBytes(StandardCharsets.UTF_8)))
  }

  private def withSeq(response: ujson.Obj, seq: Int, command: String = ""): ujson.Obj = {
    val r = ujson.Obj.from(response.value)
    r("request_seq") = seq
    r("seq") = seq
    if (command.nonEmpty)
      r("command") = command
    r
  }

  private def debugInfo(): ujson.Obj =
    ujson.Obj(
      "isStarted"     -> core.isStarted,
      "hashMethod"    -> "Murmur2",
      "hashSeed"      -> Murmur2.defaultSeed.toDouble,
      "tmpFilePrefix" -> tmpFilePrefix,
      "tmpFileSuffix" -> tmpFileSuffix,
      "breakpoints" -> ujson.Arr(
        core.breakpoints.map {
          case (path, bps) =>
            ujson.Obj(
              "source"      -> path,
              "breakpoints" -> ujson.Arr(bps.map(bp => ujson.Obj.from(bp.value)): _*)
            )
        }: _*
      ),
      "stoppedThreads" -> ujson.Arr(core.stoppedThreads.map(id => ujson.Num(id.toDouble)): _*),
      "richRendering"  -> false,
      "exceptionPaths" -> ujson.Arr("Java Exceptions"),
      "copyToGlobals"  -> false
    )

  private def dumpCell(args: ujson.Obj): ujson.Obj = {
    val code = args.value.get("code").map(_.str).getOrElse("")
    val path = cellPath(code)
    os.write.over(os.Path(path), code, createFolders = true)
    ujson.Obj("sourcePath" -> path)
  }

  private def setBreakpoints(args: ujson.Obj): Future[ujson.Obj] = {
    val path = args.value.get("source")
      .flatMap(_.obj.get("path"))
      .map(_.str)
      .getOrElse("")
    val bps = args.value.get("breakpoints").toSeq.flatMap(_.arr).collect {
      case o: ujson.Obj => o
    }
    core.setBreakpoints(path, bps).map { verified =>
      // Breakpoints of the latest input of that cell, if it was run, else pending ones
      val responseBps = bps.zipWithIndex.map {
        case (bp, idx) =>
          ujson.Obj(
            "verified" -> verified.flatMap(_.lift(idx)).getOrElse(true),
            "line"     -> bp.value.get("line").getOrElse(ujson.Null),
            "source"   -> ujson.Obj("path" -> path)
          )
      }
      ujson.Obj("breakpoints" -> ujson.Arr(responseBps: _*))
    }
  }

  /** Adapts the variables of the debug adapter to JupyterLab, see
    * [[KernelDebugger.displayVariable]]
    */
  private def variables(resp: ujson.Obj): ujson.Obj = {
    val resp0 = ujson.Obj.from(resp.value)
    for {
      body <- resp0.value.get("body").collect { case o: ujson.Obj => o }
      vars <- body.value.get("variables").collect { case a: ujson.Arr => a }
    } {
      val body0 = ujson.Obj.from(body.value)
      body0("variables") = ujson.Arr.from(vars.value.map {
        case v: ujson.Obj => KernelDebugger.displayVariable(v)
        case other        => other
      })
      resp0("body") = body0
    }
    resp0
  }

  /** Shows the sources of libraries with paths of their own, see [[librarySourcePath]] */
  private def withLibrarySourcePaths(resp: ujson.Obj): ujson.Obj =
    resp.value.get("body").collect { case o: ujson.Obj => o } match {
      case None => resp
      case Some(body) =>
        val frames = body.value.get("stackFrames").toSeq.flatMap(_.arr).map {
          case o: ujson.Obj => withLibrarySourcePath(o)
          case other        => other
        }
        val newBody = ujson.Obj.from(body.value)
        newBody("stackFrames") = ujson.Arr(frames: _*)
        val newResp = ujson.Obj.from(resp.value)
        newResp("body") = newBody
        newResp
    }

  /** Sources of libraries, by the paths we show front-ends for them (see [[librarySourcePath]]) */
  private val librarySources = new mutable.HashMap[String, String]

  /** Path shown to front-ends for a source of a library, like
    * `scala-library-3.10.1-sources.jar/scala/collection/immutable/Range.scala` for
    * `jar:file:///…/scala-library-3.10.1-sources.jar!/scala/collection/immutable/Range.scala`
    *
    * JupyterLab shows these paths as is in its call stack panel, and names the tabs of sources
    * after their last segment (`Range.scala`). It sends them back in `source` requests, that we
    * answer ourselves, so they only need to be unique.
    */
  private def librarySourcePath(path: String): String =
    jarSource(path) match {
      case None => path
      case Some((jarName, entry)) =>
        lock.synchronized {
          val candidates = Iterator.from(1).map { n =>
            if (n == 1) s"$jarName/$entry" else s"$jarName ($n)/$entry"
          }
          // the first one that isn't shown for another source
          val shown = candidates.find(c => librarySources.get(c).forall(_ == path)).get
          librarySources(shown) = path
          shown
        }
    }

  private def withLibrarySourcePath(frame: ujson.Obj): ujson.Obj = {
    val pathOpt = frame.value.get("source")
      .collect { case o: ujson.Obj => o }
      .flatMap(_.value.get("path"))
      .collect { case ujson.Str(p) => p }
    pathOpt.map(p => (p, librarySourcePath(p))) match {
      case Some((path, shown)) if shown != path =>
        val source = ujson.Obj.from(frame("source").obj)
        source("path") = shown
        val frame0 = ujson.Obj.from(frame.value)
        frame0("source") = source
        frame0
      case _ => frame
    }
  }

  private def source(
    args: ujson.Obj,
    respond: ujson.Value => ujson.Obj,
    seq: Int
  ): Future[ujson.Obj] = {
    val path = args.value.get("source")
      .flatMap(_.obj.get("path"))
      .collect { case ujson.Str(s) => s }
      .map(p => lock.synchronized(librarySources.getOrElse(p, p)))
    path.map(p => Try(os.Path(p))) match {
      case Some(Success(p)) if os.isFile(p) =>
        Future.successful(respond(ujson.Obj("content" -> os.read(p))))
      case _ if path.exists(_.startsWith("jar:")) =>
        // sources of libraries, in source JARs
        Future {
          val content = SessionDebugger.readSource(path.get)
            .getOrElse(throw new Exception(s"Could not read ${path.get}"))
          respond(ujson.Obj("content" -> content))
        }
      case _ =>
        core.request("source", args).map(withSeq(_, seq))
    }
  }

  /** JupyterLab sends `continue` requests for thread 1 (it removes the stopped thread from its own
    * list before reading it), and debugpy resumes all threads then. We resume the threads we know
    * are stopped if the requested one isn't one of them.
    *
    * JupyterLab only hides its "Paused in Debugger" overlay upon `continued` events, that the
    * session debugger sends for each resumed thread.
    */
  private def continueThreads(args: ujson.Obj): Future[ujson.Obj] = {
    val requested = args.value.get("threadId").collect { case n: ujson.Num => n.num.toLong }
    val stopped   = core.stoppedThreads
    val threads =
      if (requested.exists(stopped.contains) || stopped.isEmpty) requested.toVector
      else stopped
    if (threads.isEmpty)
      core.request("continue", args)
    else
      core.continueThreads(threads).map { resp =>
        val resp0 = ujson.Obj.from(resp.value)
        resp0("body") =
          ujson.Obj("allThreadsContinued" -> (threads.length > 1 || requested.isEmpty))
        resp0
      }
  }

  def shutdown(): Unit =
    core.shutdown()
}

object KernelDebugger {

  /** A logger for the session debugger, writing to our logs */
  def logger(logCtx: LoggerContext): Logger = {
    val log = logCtx(classOf[SessionDebugger])
    new Logger {
      def error(msg: => String, t: Throwable): Unit = log.error(msg, t)
      def warn(msg: => String, t: Throwable): Unit  = log.warn(msg, t)
      def info(msg: => String, t: Throwable): Unit  = log.info(msg, t)
      def debug(msg: => String, t: Throwable): Unit = log.debug(msg, t)
    }
  }

  /** Adapts a variable of the debug adapter to JupyterLab
    *
    * The variables panel of JupyterLab shows the type of variables rather than their value, unless
    * that type is one of the Python types int, float, bool, or str (it does show the type of
    * variables in the type column of its table view). So we remove types, and make values more
    * readable (see `ammonite.debugger.SessionDebugger.displayValue`).
    */
  def displayVariable(variable: ujson.Obj): ujson.Obj = {
    val variable0 = ujson.Obj.from(variable.value)
    val tpe       = variable0.value.remove("type").collect { case ujson.Str(s) => s }
    for (value <- variable0.value.get("value").collect { case ujson.Str(s) => s })
      variable0("value") = SessionDebugger.displayValue(value, tpe)
    variable0
  }

  /** The name of the JAR and the path of the entry of a `jar:` URI */
  def jarSource(uri: String): Option[(String, String)] =
    SessionDebugger.jarSource(uri)

  /** Name of a frame of the code of a cell, without the names of the wrapper classes of the cell
    * (see `ammonite.debugger.SessionDebugger.inputFrameName`)
    */
  def cellFrameName(name: String, wrapperName: String): String =
    SessionDebugger.inputFrameName(name, wrapperName)

  /** Where the code of the wrapper classes of cells is written, with the same line numbers as their
    * byte code
    */
  def wrappersDir(workDir: os.Path): os.Path =
    workDir / "wrappers"
}
