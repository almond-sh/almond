package org.apache.spark.sql.almondinternals

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ScheduledFuture, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}

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

  private val updater = new ProgressBarUpdater()

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

        updater.asyncPollUpdatesFor(elem)
      }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit =
    if (progress)
      Try {
        val elem = stageElem(stageCompleted.stageInfo.stageId)
        elem.allDone()
      }

  override def onTaskStart(taskStart: SparkListenerTaskStart): Unit =
    if (progress)
      Try {
        val elem = stageElem(taskStart.stageId)
        elem.taskStart()
      }

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit =
    if (progress)
      Try {
        val elem = stageElem(taskEnd.stageId)
        elem.taskDone()
      }

}

private[almondinternals] class ProgressBarUpdater(
  implicit publish: OutputHandler
) {
  private val threadFactory: ThreadFactory = {
    val threadNumber = new AtomicInteger(1)
    (r: Runnable) => {
      val threadNumber0 = threadNumber.getAndIncrement()
      val t = new Thread(r, s"almond-spark-listener-update-$threadNumber0")
      t.setDaemon(true)
      t.setPriority(Thread.NORM_PRIORITY)
      t
    }
  }

  private val pool = {
    val executor = new ScheduledThreadPoolExecutor(1, threadFactory)
    executor.setKeepAliveTime(1L, TimeUnit.MINUTES)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  def asyncPollUpdatesFor(elem: StageElem): Unit = {
    lazy val polling: ScheduledFuture[_] = pool.scheduleAtFixedRate(
      () => {
        elem.update()
        if (elem.allDone0)
          polling.cancel(false)
      },
      0,
      1,
      TimeUnit.SECONDS
    )
    polling
  }
}