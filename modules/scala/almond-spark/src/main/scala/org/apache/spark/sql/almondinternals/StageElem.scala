package org.apache.spark.sql.almondinternals

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import almond.interpreter.api.OutputHandler

final class StageElem(stageId: Int, numTasks: Int, keep: Boolean, name: String, details: String) {

  val displayId      = s"stage-info-${UUID.randomUUID()}"
  val titleDisplayId = s"$displayId-title"

  val startedTasks = new AtomicInteger
  val doneTasks    = new AtomicInteger

  @volatile var allDone0 = false

  def taskStart(): Unit = {
    startedTasks.incrementAndGet()
  }

  def taskDone(): Unit = {
    doneTasks.incrementAndGet()
  }

  def allDone(): Unit = {
    allDone0 = true
  }

  val extraStyle = Seq(
    "word-wrap: normal",
    "white-space: nowrap",
    "text-align: center"
  )

  def init(cancelStageCommTargetName: String, sendInitCode: Boolean)(implicit
    publish: OutputHandler
  ): Unit = {

    if (sendInitCode)
      publish.html(
        s"""<script>
           |var comm = Jupyter.notebook.kernel.comm_manager.new_comm('$cancelStageCommTargetName', {});
           |
           |function cancelStage(stageId) {
           |  console.log('Cancelling stage ' + stageId);
           |  comm.send({ 'stageId': stageId });
           |}
           |</script>
          """.stripMargin
      )

    publish.html(
      s"""<div>
         |  <span style="float: left; ${extraStyle.mkString("; ")}">$name</span>
         |  <span style="float: right; ${
          extraStyle.mkString("; ")
        }"><a href="#" onclick="cancelStage($stageId);">(kill)</a></span>
         |</div>
         |<br>
         |""".stripMargin,
      id = titleDisplayId
    )
    // <br> above seems required put both divs on different lines in nteract
    publish.html(
      s"""<div class="progress">
         |  <div class="progress-bar bg-success" role="progressbar" style="width: 0%; ${
          extraStyle.mkString("; ")
        }; color: white" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100">
         |    0 / $numTasks
         |  </div>
         |</div>
         |""".stripMargin,
      id = displayId
    )
  }

  def update()(implicit publish: OutputHandler): Unit = {

    if (allDone0)
      publish.updateHtml(
        s"""<div>
           |  <span style="float: left;">$name</span>
           |</div>
           |""".stripMargin,
        id = titleDisplayId
      )

    if (allDone0 && !keep)
      publish.updateHtml("", id = displayId)
    else {
      val doneTasks0    = doneTasks.get()
      val startedTasks0 = startedTasks.get()

      val diff = startedTasks0 - doneTasks0

      val donePct    = math.round(100.0 * doneTasks0.toDouble / numTasks).toInt
      val startedPct = math.round(100.0 * (startedTasks0 - doneTasks0).toDouble / numTasks).toInt

      publish.updateHtml(
        s"""<div class="progress">
           |  <div class="progress-bar" role="progressbar" style="background-color: blue; width: $donePct%; ${
            extraStyle.mkString("; ")
          }; color: white" aria-valuenow="$donePct" aria-valuemin="0" aria-valuemax="100">
           |    $doneTasks0${if (diff == 0) "" else s" + $diff"} / $numTasks
           |  </div>
           |  <div class="progress-bar" role="progressbar" style="background-color: red; width: $startedPct%" aria-valuenow="$startedPct" aria-valuemin="0" aria-valuemax="100"></div>
           |</div>
           |""".stripMargin,
        id = displayId
      )
    }
  }

}
