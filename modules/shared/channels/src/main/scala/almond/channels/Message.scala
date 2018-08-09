package almond.channels

final case class Message(
  idents: Seq[Seq[Byte]],
  header: String,
  parentHeader: String,
  metadata: String,
  content: String
)
