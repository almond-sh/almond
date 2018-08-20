package almond.interpreter.comm

import almond.protocol.CommInfo
import cats.effect.IO

/**
  * Manages targets for comm messages from frontends.
  *
  * See https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#custom-messages.
  *
  * Adding a target with name `"target_name"` allows to receive messages from frontends.
  * From the Jupyter classic UI, one can send messages to this target via code like
  * {{{
  *   var comm = Jupyter.notebook.kernel.comm_manager.new_comm("target_name", '{"a": 2, "b": false}');
  *   comm.open();
  *   comm.send('{"c": 2, "d": {"foo": [1, 2]}}');
  * }}}
  */
trait CommManager {
  def addTarget(name: String, target: IOCommTarget): Unit
  def target(name: String): Option[IOCommTarget]
  def removeTarget(name: String): Unit

  def addId(target: IOCommTarget, id: String): Unit
  def fromId(id: String): Option[IOCommTarget]
  def removeId(id: String): Option[IOCommTarget]

  def allInfos: IO[Map[String, CommInfo.Info]]
  def allIds(targetName: String): IO[Seq[String]]
}

object CommManager {

  def create(): CommManager =
    new CommManagerImpl

}
