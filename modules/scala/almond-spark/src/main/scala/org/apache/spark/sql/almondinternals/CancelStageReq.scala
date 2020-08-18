package org.apache.spark.sql.almondinternals

final case class CancelStageReq(stageId: Int)

object CancelStageReq {
  import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
  import com.github.plokhotnyuk.jsoniter_scala.macros._
  implicit val codec: JsonValueCodec[CancelStageReq] =
    JsonCodecMaker.make
}
