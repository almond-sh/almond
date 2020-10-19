package almond.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object VariableInspector {

  final case class IVariable(
    varName: String,
    varSize: String,
    varShape: String,
    varContent: String,
    varType: String,
    isMatrix: Boolean,
    isWidget: Option[Boolean]
  )

  implicit val iVariableCodec: JsonValueCodec[IVariable] =
    JsonCodecMaker.makeWithRequiredCollectionFields
  implicit val iVariableListCodec: JsonValueCodec[List[IVariable]] =
    JsonCodecMaker.makeWithRequiredCollectionFields

}
