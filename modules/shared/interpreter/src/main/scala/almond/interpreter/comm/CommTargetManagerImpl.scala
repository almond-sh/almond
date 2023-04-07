package almond.interpreter.comm

import java.util.concurrent.ConcurrentHashMap

import almond.protocol.CommInfo
import cats.effect.IO

import scala.jdk.CollectionConverters._

final class CommTargetManagerImpl extends CommTargetManager {

  private val targets       = new ConcurrentHashMap[String, IOCommTarget]
  private val commIdTargets = new ConcurrentHashMap[String, IOCommTarget]

  def addTarget(name: String, target: IOCommTarget): Unit = {

    val previous = targets.putIfAbsent(name, target)

    if (previous != null)
      throw new Exception(s"Target $name already registered")
  }

  def target(name: String): Option[IOCommTarget] =
    Option(targets.get(name))

  def removeTarget(name: String): Unit = {
    val target = targets.remove(name)
    if (target != null)
      for ((id, t) <- commIdTargets.asScala.iterator if t == target)
        targets.remove(id)
  }

  def addId(target: IOCommTarget, id: String): Unit = {
    val previous = commIdTargets.put(id, target)
    if (previous != null) {
      // TODO Log error
    }
  }

  def fromId(id: String): Option[IOCommTarget] =
    Option(commIdTargets.get(id))

  def removeId(id: String): Option[IOCommTarget] =
    Option(commIdTargets.remove(id))

  // small and unlikely chance of discrepancies between targets and commIdTargets, as we query them at different times…
  val allInfos: IO[Map[String, CommInfo.Info]] =
    IO {
      val map = targets.asScala.iterator.map { case (k, v) => v -> k }.toMap
      commIdTargets
        .asScala
        .iterator
        .map {
          case (id, target) =>
            id -> map.get(target)
        }
        .collect {
          case (id, Some(name)) =>
            id -> CommInfo.Info(name)
        }
        .toMap
    }

  // same as above
  def allIds(targetName: String): IO[Seq[String]] =
    IO {
      target(targetName) match {
        case Some(target) =>
          commIdTargets
            .asScala
            .iterator
            .collect {
              case (id, `target`) =>
                id
            }
            .toSeq
        case None =>
          Nil
      }
    }
}
