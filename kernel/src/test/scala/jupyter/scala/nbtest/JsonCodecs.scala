package jupyter.scala.nbtest

object JsonCodecs {
  import argonaut._, Argonaut.{ JsonDecodeJson => _, MapDecodeJson => _, _ }, Shapeless._

  implicit val outputDecodeJson: DecodeJson[Output] =
    DecodeJson { c =>
      c.--\("output_type").as[String].flatMap {
        case "stream" => c.as[StreamOutput].map(x => x: Output)
        case "display_data" => c.as[DisplayDataOutput].map(x => x: Output)
        case "error" => c.as[ErrorOutput].map(x => x: Output)
        case other => DecodeResult.fail(s"Unrecognized output type: $other", c.history)
      }
    }

  implicit val cellDecodeJson: DecodeJson[Cell] =
    DecodeJson { c =>
      c.--\("cell_type").as[String].flatMap {
        case "code" => c.as[CodeCell].map(x => x: Cell)
        case "markdown" => c.as[MarkdownCell].map(x => x: Cell)
        case other => DecodeResult.fail(s"Unrecognized cell type: $other", c.history)
      }
    }

  // Don't know why this one has to be brought here this way (having MapDecodeJson in scope doesn't seem to work)
  implicit def metadataDecodeJson = implicitly[DecodeJson[Map[String, argonaut.Json]]]

  implicit val notebookDecodeJson = DecodeJson.of[NotebookData]
}

