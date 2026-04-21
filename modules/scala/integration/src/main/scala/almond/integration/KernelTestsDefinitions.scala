package almond.integration

import almond.testkit.Dsl._

abstract class KernelTestsDefinitions extends AlmondFunSuite {

  def kernelLauncher: KernelLauncher

  override def mightRetry = true

  lazy val javaMajorVersion = {
    val versionString =
      sys.props.getOrElse("java.version", sys.error("java.version property not set"))
        .stripPrefix("1.") // for Java 8 and below
    versionString.takeWhile(_ != '.').toInt
  }

  test0("jvm-repr") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.jvmRepr()
    }
  }

  test0("updatable display") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.updatableDisplay()
    }
  }

  test0("auto-update Future results upon completion") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureUponCompletion(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("auto-update Future results in background upon completion") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.autoUpdateFutureInBackgroundUponCompletion(
        kernelLauncher.defaultScalaVersion
      )
    }
  }

  test0("toree AddJar file") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(kernelLauncher.defaultScalaVersion, sameCell = false)
    }
  }

  test0("toree AddJar file same cell") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarFile(kernelLauncher.defaultScalaVersion, sameCell = true)
    }
  }

  test0("toree AddJar URL") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(kernelLauncher.defaultScalaVersion, sameCell = false)
    }
  }

  test0("toree AddJar URL same cell") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarURL(kernelLauncher.defaultScalaVersion, sameCell = true)
    }
  }

  test0("toree Html") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeHtml()
    }
  }

  test0("toree AddJar custom protocol") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeAddJarCustomProtocol(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("toree custom cell magic") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.toreeCustomCellMagic()
    }
  }

  test0("compile only") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compileOnly()
    }
  }

  if (kernelLauncher.defaultScalaVersion.startsWith("2."))
    test0("extra class path") { implicit forceVersion =>
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.extraCp(
          kernelLauncher.defaultScalaVersion,
          KernelLauncher.kernelShapelessVersion
        )
      }
    }

  if (kernelLauncher.defaultScalaVersion.startsWith("2.") && javaMajorVersion >= 11)
    test0("inspections") { implicit forceVersion =>
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.inspections(kernelLauncher.defaultScalaVersion)
      }
    }

  test0("compilation error") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.compilationError(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("add dependency") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addDependency(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("add repository") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addRepository(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("add scalac option") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.addScalacOption(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("completion") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.completion(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("simple exception handler") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.exceptionHandler()
    }
  }

  test0("more exception handlers") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.moreExceptionHandlers()
    }
  }

  test0("jackson api") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.almondJackson(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("custom wrapper name") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.customWrapperName()
    }
  }

  test0("jackson api") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.almondJackson(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("package cells") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.packageCells(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("custom package name") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.customPkgName()
    }
  }

  // Disabled in Scala 3 for now, as the output directory isn't
  // write-only there. That needs fixing in Ammonite.
  if (kernelLauncher.defaultScalaVersion.startsWith("2."))
    test0("output directory") { implicit forceVersion =>
      kernelLauncher.withKernel { implicit runner =>
        implicit val sessionId: SessionId = SessionId()
        almond.integration.Tests.outputDirectory()
      }
    }

  // Doesn't pass, might need fixing in Ammonite
  // test0("custom short package name") {
  //   kernelLauncher.withKernel { implicit runner =>
  //     implicit val sessionId: SessionId = SessionId()
  //     almond.integration.Tests.customShortPkgName()
  //   }
  // }

  test0("throwable getMessage throws") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.throwableGetMessageThrows(kernelLauncher.defaultScalaVersion)
    }
  }

  test0("throwable getStackTrace throws") { implicit forceVersion =>
    kernelLauncher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      almond.integration.Tests.throwableGetStackTraceThrows(kernelLauncher.defaultScalaVersion)
    }
  }
}
