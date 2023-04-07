package almond

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

import almond.api.JupyterApi
import almond.interpreter.api.DisplayData
import almond.protocol.VariableInspector.{IVariable, iVariableListCodec}
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import pprint.{PPrinter, TPrint, TPrintColors}

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.reflect.{ClassTag, classTag}

trait VariableInspectorApiImpl extends JupyterApi {

  import VariableInspectorApiImpl._

  protected def variableInspectorImplPPrinter(): PPrinter
  protected def allowVariableInspector: Option[Boolean]

  private var variableTrackingEnabled = new AtomicBoolean(allowVariableInspector.getOrElse(false))
  private val variables               = new ConcurrentLinkedQueue[Variable[_]]

  protected def declareVariable[T](
    name: String,
    value: => T,
    strValueOpt: Option[String]
  )(implicit
    tprint: TPrint[T],
    tcolors: TPrintColors,
    classTagT: ClassTag[T]
  ): Unit =
    if (variableInspectorEnabled() && classTagT != classTag[Unit]) {
      val strValue = strValueOpt.getOrElse {
        variableInspectorImplPPrinter()
          .tokenize(
            value,
            height = 1,
            initialOffset = 0
          )
          .map(_.render)
          .mkString
      }
      val variable = Variable(name, () => strValueOpt.toLeft(value), tprint)
      variables.add(variable)
    }

  protected def variableInspectorEnabled(): Boolean =
    variableTrackingEnabled.get()

  protected def variableInspectorInit(): Unit =
    if (allowVariableInspector.forall(identity))
      variableTrackingEnabled.set(true)

  protected def variableInspectorDictList(): Unit = {
    val pprinter = PPrinter.BlackWhite.copy(
      defaultWidth = 60,
      defaultHeight = 1,
      additionalHandlers = variableInspectorImplPPrinter().additionalHandlers
    )
    val retainedVariables =
      VariableInspectorApiImpl.removeDuplicatesBy(variables.iterator().asScala.toVector)(_.name)
    val list = retainedVariables.map(_.iVariable(pprinter))
    val str  = writeToString(list)(iVariableListCodec)
    publish.display(DisplayData.text(str))
  }
}

private object VariableInspectorApiImpl {

  // keeps the last element for each key
  private def removeDuplicatesBy[T, U](l: Seq[T])(key: T => U): List[T] = {
    val b           = List.newBuilder[T]
    val alreadySeen = new mutable.HashSet[U]
    l.reverseIterator.foreach { t =>
      val u = key(t)
      if (!alreadySeen(u)) {
        alreadySeen += u
        b += t
      }
    }
    b.result().reverse
  }

  private final case class Variable[T](
    name: String,
    value: () => Either[String, T],
    tprint: TPrint[T]
  ) {
    def iVariable(pprinter: PPrinter): IVariable =
      IVariable(
        varName = name,
        varSize = "",
        varShape = "",
        varContent =
          value() match {
            case Left(str) => str
            case Right(t) =>
              pprinter
                .tokenize(
                  t,
                  height = 5,
                  width = 25
                )
                .map(_.render)
                .mkString
                .replaceAll(java.util.regex.Pattern.quote("(") + "\n\\s+", "(")
                .replaceAll("\n\\s+", " ")
                .replaceAll("\n", " ")
          },
        varType = tprint.render(TPrintColors.BlackWhite).render,
        isMatrix = false,
        isWidget = None
      )
  }
}
