package almond.internals

import almond.logger.Logger

import java.nio.file.{Files, Path}
import java.util.concurrent.{
  ExecutorService,
  Executors,
  Future,
  RejectedExecutionException,
  ThreadFactory,
  TimeUnit,
  TimeoutException
}

import scala.collection.mutable
import scala.meta.Dialect
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.io.AbsolutePath
import scala.meta.pc.ContentType
import scala.meta.pc.reports.EmptyReportContext
import scala.util.control.NonFatal

/** The mtags index of the source JARs of a session, that Scala 2 inspections read docs from
  *
  * Indexing all the source JARs of a class path takes several seconds (the kernel alone has a few
  * hundred of them in its class path). So it's done in the background, on a thread of its own,
  * rather than when the first inspection needs it. [[update]] schedules the indexing of the source
  * JARs that haven't been indexed yet: it's called once the interpreter is initialized, and after
  * each cell, so that the source JARs of the dependencies that cells add get indexed too.
  *
  * @param basePath
  *   the source JARs of the class path the kernel was started with, computed once, on the indexing
  *   thread (looking for them can mean opening all the JARs of the class path)
  */
final class SourceIndex(dialect: Dialect, basePath: () => Seq[Path], log: Logger) {

  private implicit val reportContext: EmptyReportContext = new EmptyReportContext

  private val index: OnDemandSymbolIndex = OnDemandSymbolIndex.empty()
  private val scaladocs                  = new Scaladocs(index)

  // only accessed from the indexing thread
  private val indexed        = new mutable.HashSet[Path]
  private lazy val basePath0 = basePath()

  // Re-created each time new JARs are indexed, as Docstrings caches the absence of docs for the
  // symbols it couldn't find
  @volatile private var docs0 = new Docstrings(index)

  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor(
      new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, "almond-source-index")
          t.setDaemon(true)
          t
        }
      }
    )

  // the last indexing task submitted, which runs after all the previous ones
  private var latest = Option.empty[Future[Unit]]

  /** Schedules the indexing of the source JARs of the session that haven't been indexed yet
    *
    * @param sessionPath
    *   the source JARs added to the session so far (see [[SourcePath.session]])
    */
  def update(sessionPath: Seq[Path]): Unit =
    synchronized {
      try latest = Some(executor.submit[Unit](() => indexNew(sessionPath)))
      catch {
        case _: RejectedExecutionException => // shut down
      }
    }

  private def indexNew(sessionPath: Seq[Path]): Unit = {
    val start   = System.nanoTime()
    val toIndex = (sessionPath ++ basePath0).distinct.filterNot(indexed)
    if (toIndex.nonEmpty) {
      for (p <- toIndex if !Thread.currentThread().isInterrupted) {
        indexed += p
        try
          if (Files.isDirectory(p))
            // like the resource or class directories of build tools
            index.addSourceDirectory(AbsolutePath(p), dialect)
          else
            // mtags only reads source JARs from the default file system, see
            // SourcePath.localFile
            index.addSourceJar(AbsolutePath(SourcePath.localFile(p)), dialect)
        catch {
          case NonFatal(e) =>
            if (!Thread.currentThread().isInterrupted)
              log.warn(s"Ignoring exception while indexing $p", e)
        }
      }
      docs0 = new Docstrings(index)
      val durationMs = (System.nanoTime() - start) / 1000000L
      log.info(s"Indexed ${toIndex.length} source JAR(s) in $durationMs ms")
    }
  }

  /** Waits for the source JARs passed to [[update]] so far to be indexed, for up to `maxWait`
    *
    * @return
    *   whether these were all indexed
    */
  def await(maxWait: Long, unit: TimeUnit): Boolean =
    synchronized(latest) match {
      case None => true
      case Some(f) =>
        try {
          f.get(maxWait, unit)
          true
        }
        catch {
          case _: TimeoutException =>
            log.info("Source JARs still being indexed, docs of some symbols may be missing")
            false
          case NonFatal(e) =>
            log.warn("Error while indexing source JARs", e)
            false
        }
    }

  /** The rendered doc of a symbol, read from the source JARs indexed so far
    *
    * Uses mtags for Javadoc, and [[Scaladocs]] for scaladoc.
    *
    * @param symbol
    *   the semanticdb symbol whose doc is rendered
    * @param siteClasses
    *   see [[Scaladocs.documentation]]
    */
  def documentation(symbol: String, siteClasses: Seq[String]): Option[String] =
    if (scaladocs.isJava(symbol)) {
      val doc = docs0.documentation(
        symbol,
        () => java.util.Collections.emptyList[String](),
        ContentType.MARKDOWN
      )
      if (doc.isPresent) Some(doc.get().docstring).filter(_.nonEmpty) else None
    }
    else
      scaladocs.documentation(symbol, siteClasses)

  def shutdown(): Unit =
    executor.shutdownNow()
}
