package almond.interpreter

final case class Completion(from: Int, to: Int, completions: Seq[String])
