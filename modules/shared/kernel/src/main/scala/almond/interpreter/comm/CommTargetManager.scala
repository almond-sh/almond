package almond.interpreter.comm

import almond.protocol.CommInfo
import cats.effect.IO

trait CommTargetManager {
  def addTarget(name: String, target: IOCommTarget): Unit
  def target(name: String): Option[IOCommTarget]
  def removeTarget(name: String): Unit

  def addId(target: IOCommTarget, id: String): Unit
  def fromId(id: String): Option[IOCommTarget]
  def removeId(id: String): Option[IOCommTarget]

  def allInfos: IO[Map[String, CommInfo.Info]]
  def allIds(targetName: String): IO[Seq[String]]
}

object CommTargetManager {

  def create(): CommTargetManager =
    new CommTargetManagerImpl

}
