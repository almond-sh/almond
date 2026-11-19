package almond.internals

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** Drops the debug messages that the Scala 3 compiler writes straight to `System.err` when the
  * Scala 2 pickles it reads reference classes that aren't on the class path, like
  * {{{
  * ***** missing reference, looking for HNil/T in package shapeless
  * decls = Scope{}
  * }}}
  * followed by more lines when the `Scope{…}` isn't empty, and sometimes by a `**** slow search
  * found: …` line (see `dotty.tools.dotc.core.unpickleScala2.Scala2Unpickler`).
  *
  * The compiler reads these pickles when it looks for imports to suggest in error messages, with
  * Scala 2.13 libraries, like plotly-scala, on the class path. Users can't do anything about these
  * messages.
  *
  * Other lines are passed to `forward` as is, line separators included.
  */
final class MissingReferenceFilter(forward: String => Unit) {
  import MissingReferenceFilter._

  private var state: State = State.Normal

  def line(line: String): Unit =
    if (keep(stripAnsi(line).stripLineEnd))
      forward(line)

  private def keep(content: String): Boolean =
    state match {
      case State.Normal =>
        if (content.startsWith(missingReferencePrefix)) {
          state = State.ExpectDecls
          false
        }
        else
          true
      case State.ExpectDecls =>
        if (content.startsWith(declsPrefix)) {
          state = if (content.endsWith("}")) State.AfterDecls else State.InDecls
          false
        }
        else {
          state = State.Normal
          keep(content)
        }
      case State.InDecls =>
        // the declarations are indented, and the closing brace isn't
        if (content == "}") {
          state = State.AfterDecls
          false
        }
        else if (content.nonEmpty && content.head.isWhitespace)
          false
        else {
          state = State.Normal
          keep(content)
        }
      case State.AfterDecls =>
        state = State.Normal
        !content.startsWith(slowSearchPrefix) && keep(content)
    }

  /** An output stream, passing the lines written to it to [[line]] */
  def outputStream(): MissingReferenceFilter.LineOutputStream =
    new MissingReferenceFilter.LineOutputStream(this)
}

object MissingReferenceFilter {

  private sealed abstract class State extends Product with Serializable
  private object State {
    case object Normal      extends State
    case object ExpectDecls extends State
    case object InDecls     extends State
    case object AfterDecls  extends State
  }

  private val missingReferencePrefix = "***** missing reference, looking for "
  private val declsPrefix            = "decls = Scope{"
  private val slowSearchPrefix       = "**** slow search found: "

  private val ansiPattern                  = "\u001b\\[[0-9;]*m".r
  private def stripAnsi(s: String): String = ansiPattern.replaceAllIn(s, "")

  final class LineOutputStream(filter: MissingReferenceFilter) extends OutputStream {
    private val buffer = new ByteArrayOutputStream

    private def flushLine(): Unit =
      if (buffer.size() > 0) {
        val line = new String(buffer.toByteArray, StandardCharsets.UTF_8)
        buffer.reset()
        filter.line(line)
      }

    override def write(b: Int): Unit = {
      buffer.write(b)
      if (b == '\n')
        flushLine()
    }

    /** Passes what's left of the last line, if it doesn't end with a line separator */
    def flushPartialLine(): Unit =
      flushLine()
  }

  /** Runs `f` with these messages filtered out of `System.err` */
  def filteringSystemErr[T](f: => T): T = {
    val previous = System.err
    val filter   = new MissingReferenceFilter(s => previous.print(s))
    val stream   = filter.outputStream()
    val err      = new PrintStream(stream, true, StandardCharsets.UTF_8.name())
    System.setErr(err)
    try f
    finally {
      err.flush()
      stream.flushPartialLine()
      System.setErr(previous)
      previous.flush()
    }
  }
}
