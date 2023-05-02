package almond.logger

import java.util.Locale

sealed abstract class Level(
  val index: Int,
  val name: String,
  val coloredName: String
) extends Product with Serializable with Ordered[Level] {

  def this(index: Int, name: String) =
    this(index, name, name)

  def compare(that: Level): Int =
    index.compare(that.index)

  def errorEnabled: Boolean =
    this >= Level.Error
  def warningEnabled: Boolean =
    this >= Level.Warning
  def infoEnabled: Boolean =
    this >= Level.Info
  def debugEnabled: Boolean =
    this >= Level.Debug

}

object Level {

  case object None    extends Level(0, "NONE")
  case object Error   extends Level(1, "ERROR", Console.RED + "ERROR" + Console.RESET)
  case object Warning extends Level(2, "WARN", Console.YELLOW + "WARN" + Console.RESET)
  case object Info    extends Level(3, "INFO", Console.BLUE + "INFO" + Console.RESET)
  case object Debug   extends Level(4, "DEBUG", Console.MAGENTA + "DEBUG" + Console.RESET)

  def fromString(s: String): Either[String, Level] =
    s.toLowerCase(Locale.ROOT) match {
      case "none" =>
        Right(None)
      case "error" =>
        Right(Error)
      case "warn" | "warning" =>
        Right(Warning)
      case "info" =>
        Right(Info)
      case "debug" =>
        Right(Debug)
      case _ =>
        Left(s"Unrecognized logging level: $s")
    }

}
