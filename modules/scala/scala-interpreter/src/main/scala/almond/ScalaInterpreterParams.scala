package almond

import java.nio.file.Path

import almond.directives.KernelOptions
import almond.launcher.directives.CustomGroup
import almond.protocol.KernelInfo
import ammonite.compiler.iface.CodeWrapper
import ammonite.compiler.CodeClassWrapper
import ammonite.util.Colors
import coursierapi.{Dependency, Module}

import scala.concurrent.ExecutionContext

final case class ScalaInterpreterParams(
  updateBackgroundVariablesEcOpt: Option[ExecutionContext] = None,
  extraRepos: Seq[String] = Nil,
  extraBannerOpt: Option[String] = None,
  extraLinks: Seq[KernelInfo.Link] = Nil,
  predefCode: String = "",
  predefFiles: Seq[Path] = Nil,
  automaticDependencies: Map[Module, Seq[Dependency]] = Map(),
  automaticVersions: Map[Module, String] = Map(),
  forceMavenProperties: Map[String, String] = Map(),
  mavenProfiles: Map[String, Boolean] = Map(),
  codeWrapper: CodeWrapper = CodeClassWrapper,
  initialColors: Colors = Colors.Default,
  initialClassLoader: ClassLoader = Thread.currentThread().getContextClassLoader,
  metabrowse: Boolean = false,
  metabrowseHost: String = "localhost",
  metabrowsePort: Int = -1,
  lazyInit: Boolean = false,
  trapOutput: Boolean = false,
  quiet: Boolean = true,
  disableCache: Boolean = false,
  autoUpdateLazyVals: Boolean = true,
  autoUpdateVars: Boolean = true,
  useNotebookCoursierLogger: Boolean = false,
  silentImports: Boolean = false,
  allowVariableInspector: Option[Boolean] = None,
  useThreadInterrupt: Boolean = false,
  outputDir: Either[os.Path, Boolean] = Right(true),
  toreeMagics: Boolean = false,
  toreeApiCompatibility: Boolean = false,
  compileOnly: Boolean = false,
  extraClassPath: List[os.Path] = Nil,
  initialCellCount: Int = 0,
  upfrontKernelOptions: KernelOptions = KernelOptions(),
  ignoreLauncherDirectivesIn: Set[String] = Set.empty,
  launcherDirectiveGroups: Seq[CustomGroup] = Nil,
  initialSettings: Seq[String] = Seq("-deprecation", "-feature")
)
