package org.apache.spark.sql.almondinternals

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import almond.interpreter.api.{CommHandler, CommTarget, OutputHandler}
import org.apache.spark.scheduler._
import org.apache.spark.sql.SparkSession

import scala.collection.JavaConverters._
import scala.util.Try

final class ProgressSparkListener(
  session: SparkSession,
  keep: Boolean,
  progress: Boolean
)(implicit
  publish: OutputHandler,
  commHandler: CommHandler
) extends SparkListener {

  import ProgressSparkListener._

  private val elems = new ConcurrentHashMap[Int, StageElem]

  private val commTargetName = s"cancel-stage-${UUID.randomUUID()}"

  private var sentInitCode = false

  commHandler.registerCommTarget(
    commTargetName,
    CommTarget { (_, data) =>

      Try(com.github.plokhotnyuk.jsoniter_scala.core.readFromArray(data)(CancelStageReq.codec)).toEither match {
        case Left(err) =>
          publish.stderr(s"Error decoding message: $err" + '\n')
        case Right(req) =>
          val stageId = req.stageId
          if (elems.containsKey(stageId)) {
            publish.stderr(s"Cancelling stage $stageId" + '\n')
            session.sparkContext.cancelStage(stageId)
          } else {
            publish.stderr(s"Stage $stageId not found (only have ${elems.asScala.toVector.map(_._1).sorted})" + '\n')
          }
      }
    }
  )

  def newStageElem(stageId: Int, numTasks: Int, name: String, details: String): StageElem = {

    if (!elems.contains(stageId))
      elems.putIfAbsent(stageId, new StageElem(stageId, numTasks, keep, name, details))

    elems.get(stageId)
  }

  def stageElem(stageId: Int): StageElem =
    elems.get(stageId)

  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit =
    if (progress)
      Try {
        val elem = newStageElem(
          stageSubmitted.stageInfo.stageId,
          stageSubmitted.stageInfo.numTasks,
          stageSubmitted.stageInfo.name,
          stageSubmitted.stageInfo.details
        )
        elem.init(commTargetName, !sentInitCode)
        sentInitCode = true
      }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit =
    if (progress)
      Try {
        val elem = stageElem(stageCompleted.stageInfo.stageId)
        elem.allDone()
        elem.update()
      }

  override def onTaskStart(taskStart: SparkListenerTaskStart): Unit =
    if (progress)
      Try {
        val elem = stageElem(taskStart.stageId)
        elem.taskStart()
        elem.update()
      }

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit =
    if (progress)
      Try {
        val elem = stageElem(taskEnd.stageId)
        elem.taskDone()
        elem.update()
      }

}

object ProgressSparkListener {

  final case class CancelStageReq(stageId: Int)

  object CancelStageReq {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.macros._
    implicit val codec: JsonValueCodec[CancelStageReq] =
      JsonCodecMaker.make(CodecMakerConfig)
  }


}
