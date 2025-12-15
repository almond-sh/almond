package almond.amm

import ammonite.runtime.Evaluator
import ammonite.util.{Evaluated, Imports, Name, Printer, Res, Util}

case class HookEvaluator(
  underlying: Evaluator,
  hook: HookEvaluator.Hook
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
  ): Res[Evaluated] =
    hook.wrap {
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
    hook.wrap {
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
}

object HookEvaluator {
  trait Hook {
    def wrap[T](f: => T): T
  }
}
