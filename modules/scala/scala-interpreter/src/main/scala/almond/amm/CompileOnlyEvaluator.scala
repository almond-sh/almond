package almond.amm

import ammonite.runtime.{Evaluator, Frame}
import ammonite.util.Util.ClassFiles
import ammonite.util.{Catching, Evaluated, Imports, Name, Printer, Res}

// FIXME We're basically vendoring most of the Evaluator.apply implementation here :/
// Only evalMain differs

/** Same as `ammonite.runtime.Evaluator`, except we don't actually run the user code here
  */
class CompileOnlyEvaluator(
  headFrame: () => Frame,
  baseEval: Evaluator
) extends Evaluator {

  def loadClass(wrapperName: String, classFiles: ClassFiles): Res[Class[_]] =
    baseEval.loadClass(wrapperName, classFiles)
  def evalMain(cls: Class[_], contextClassloader: ClassLoader): Any =
    Iterator.empty

  def processLine(
    classFiles: ClassFiles,
    newImports: Imports,
    usedEarlierDefinitions: Seq[String],
    printer: Printer,
    indexedWrapperName: Name,
    wrapperPath: Seq[Name],
    silent: Boolean,
    contextClassLoader: ClassLoader
  ): Res[Evaluated] =
    // https://github.com/com-lihaoyi/Ammonite/blob/62705f474dbcd8a99d71070a9851522b1bf5b67d/amm/runtime/src/main/scala/ammonite/runtime/Evaluator.scala#LL119C7-L138C8
    for {
      cls <- loadClass("ammonite.$sess." + indexedWrapperName.backticked, classFiles)
      _   <- Catching(Evaluator.userCodeExceptionHandler)
    } yield {
      headFrame().usedEarlierDefinitions = usedEarlierDefinitions

      // Exhaust the printer iterator now, before exiting the `Catching`
      // block, so any exceptions thrown get properly caught and handled
      val iter = evalMain(cls, contextClassLoader).asInstanceOf[Iterator[String]]

      if (!silent)
        Evaluator.evaluatorRunPrinter(iter.foreach(printer.resultStream.print))
      else Evaluator.evaluatorRunPrinter(iter.foreach(_ => ()))

      // "" Empty string as cache tag of repl code
      evaluationResult(
        Seq(Name("ammonite"), Name("$sess"), indexedWrapperName),
        wrapperPath,
        newImports
      )
    }

  def processScriptBlock(
    cls: Class[_],
    newImports: Imports,
    usedEarlierDefinitions: Seq[String],
    wrapperName: Name,
    wrapperPath: Seq[Name],
    pkgName: Seq[Name],
    contextClassLoader: ClassLoader
  ): Res[Evaluated] =
    // https://github.com/com-lihaoyi/Ammonite/blob/62705f474dbcd8a99d71070a9851522b1bf5b67d/amm/runtime/src/main/scala/ammonite/runtime/Evaluator.scala#LL149C7-L156C8
    for {
      _ <- Catching(Evaluator.userCodeExceptionHandler)
    } yield {
      headFrame().usedEarlierDefinitions = usedEarlierDefinitions
      evalMain(cls, contextClassLoader)
      val res = evaluationResult(pkgName :+ wrapperName, wrapperPath, newImports)
      res
    }

  // https://github.com/com-lihaoyi/Ammonite/blob/62705f474dbcd8a99d71070a9851522b1bf5b67d/amm/runtime/src/main/scala/ammonite/runtime/Evaluator.scala#L159-L203
  def evaluationResult(
    wrapperName: Seq[Name],
    internalWrapperPath: Seq[Name],
    imports: Imports
  ) =
    Evaluated(
      wrapperName,
      Imports(
        for (id <- imports.value) yield {
          val filledPrefix =
            if (internalWrapperPath.isEmpty) {
              val filledPrefix =
                if (id.prefix.isEmpty)
                  // For some reason, for things not-in-packages you can't access
                  // them off of `_root_`
                  wrapperName
                else
                  id.prefix

              if (filledPrefix.headOption.exists(_.backticked == "_root_"))
                filledPrefix
              else Seq(Name("_root_")) ++ filledPrefix
            }
            else if (id.prefix.isEmpty)
              // For some reason, for things not-in-packages you can't access
              // them off of `_root_`
              Seq(Name("_root_")) ++ wrapperName ++ internalWrapperPath
            else if (id.prefix.startsWith(wrapperName))
              if (id.prefix.lift(wrapperName.length).contains(Name("Helper")))
                Seq(Name("_root_")) ++ wrapperName ++
                  internalWrapperPath ++
                  id.prefix.drop(wrapperName.length + 1)
              else
                Seq(Name("_root_")) ++ wrapperName.init ++
                  Seq(id.prefix.apply(wrapperName.length)) ++
                  internalWrapperPath ++
                  id.prefix.drop(wrapperName.length + 1)
            else if (id.prefix.headOption.exists(_.backticked == "_root_"))
              id.prefix
            else
              Seq(Name("_root_")) ++ id.prefix

          id.copy(prefix = filledPrefix)
        }
      )
    )
}
