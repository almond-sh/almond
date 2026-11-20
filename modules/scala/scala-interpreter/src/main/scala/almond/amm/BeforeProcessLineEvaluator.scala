package almond.amm

import ammonite.runtime.Evaluator
import ammonite.util.{Evaluated, Imports, Name, Printer, Res, Util}

/** Calls `before` with the name of the wrapper of each input, and the names of the classes it was
  * compiled to, right before it's loaded and run
  */
final case class BeforeProcessLineEvaluator(
  underlying: Evaluator,
  before: (String, Seq[String]) => Unit
) extends Evaluator {
  override def loadClass(wrapperName: String, classFiles: Util.ClassFiles): Res[Class[_]] =
    underlying.loadClass(wrapperName, classFiles)
  override def evalMain(cls: Class[_], contextClassloader: ClassLoader): Any =
    underlying.evalMain(cls, contextClassloader)
  override def processLine(
    output: Util.ClassFiles,
    newImports: Imports,
    usedEarlierDefinitions: Seq[String],
    printer: Printer,
    indexedWrapperName: Name,
    wrapperPath: Seq[Name],
    pkgName: Seq[Name],
    silent: Boolean,
    contextClassLoader: ClassLoader
  ): Res[Evaluated] = {
    val classNames = output.collect {
      case (name, _) if name.endsWith(".class") =>
        name.stripSuffix(".class").replace('/', '.')
    }
    before(indexedWrapperName.raw, classNames)
    underlying.processLine(
      output,
      newImports,
      usedEarlierDefinitions,
      printer,
      indexedWrapperName,
      wrapperPath,
      pkgName,
      silent,
      contextClassLoader
    )
  }
  override def processScriptBlock(
    cls: Class[_],
    newImports: Imports,
    usedEarlierDefinitions: Seq[String],
    wrapperName: Name,
    wrapperPath: Seq[Name],
    pkgName: Seq[Name],
    contextClassLoader: ClassLoader
  ): Res[Evaluated] =
    underlying.processScriptBlock(
      cls,
      newImports,
      usedEarlierDefinitions,
      wrapperName,
      wrapperPath,
      pkgName,
      contextClassLoader
    )
}
