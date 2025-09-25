package almond.integration

import almond.testkit.Dsl._
import scala.concurrent.duration.{Duration, DurationInt}

object LifecycleCheck {
  def main(args: Array[String]): Unit = {

    val launcher = new KernelLauncher(KernelLauncher.LauncherType.Legacy, "2.13.16") {
      override def lingerDuration: Duration = 1.second
    }

    launcher.withKernel { implicit runner =>
      implicit val sessionId: SessionId = SessionId()
      runner.withManySessions(50, "--debug-multi-kernels") { sessions =>
        sessions.reverse.foreach { implicit session =>
          execute(
            "val n = 2",
            "n: Int = 2"
          )
          execute("exit", "")
        }
      }
    }
  }
}
