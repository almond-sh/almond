package almond.interpreter.util

import argonaut._
import argonaut.PrettyParams.nospace._
import argonaut.PrettyParams.vectorMemo

object BetterPrinter {

  private def addIndentation(s: String): Int => String = {
    val lastNewLineIndex = s.lastIndexOf("\n")
    if (lastNewLineIndex < 0) {
      _ => s
    } else {
      val afterLastNewLineIndex = lastNewLineIndex + 1
      val start = s.substring(0, afterLastNewLineIndex)
      val end = s.substring(afterLastNewLineIndex)
      n => start + indent * n + end
    }
  }

  private val openBraceText = "{"
  private val closeBraceText = "}"
  private val openArrayText = "["
  private val closeArrayText = "]"
  private val commaText = ","
  private val colonText = ":"
  private val nullText = "null"
  private val trueText = "true"
  private val falseText = "false"
  private val stringEnclosureText = "\""

  private val _lbraceLeft = addIndentation(lbraceLeft)
  private val _lbraceRight = addIndentation(lbraceRight)
  private val _rbraceLeft = addIndentation(rbraceLeft)
  private val _rbraceRight = addIndentation(rbraceRight)
  private val _lbracketLeft = addIndentation(lbracketLeft)
  private val _lbracketRight = addIndentation(lbracketRight)
  private val _rbracketLeft = addIndentation(rbracketLeft)
  private val _rbracketRight = addIndentation(rbracketRight)
  private val _lrbracketsEmpty = addIndentation(lrbracketsEmpty)
  private val _arrayCommaLeft = addIndentation(arrayCommaLeft)
  private val _arrayCommaRight = addIndentation(arrayCommaRight)
  private val _objectCommaLeft = addIndentation(objectCommaLeft)
  private val _objectCommaRight = addIndentation(objectCommaRight)
  private val _colonLeft = addIndentation(colonLeft)
  private val _colonRight = addIndentation(colonRight)

  private val lbraceMemo = vectorMemo{depth: Int => "%s%s%s".format(_lbraceLeft(depth), openBraceText, _lbraceRight(depth + 1))}
  private val rbraceMemo = vectorMemo{depth: Int => "%s%s%s".format(_rbraceLeft(depth), closeBraceText, _rbraceRight(depth + 1))}

  private val lbracketMemo = vectorMemo{depth: Int => "%s%s%s".format(_lbracketLeft(depth), openArrayText, _lbracketRight(depth + 1))}
  private val rbracketMemo = vectorMemo{depth: Int => "%s%s%s".format(_rbracketLeft(depth), closeArrayText, _rbracketRight(depth))}
  private val lrbracketsEmptyMemo = vectorMemo{depth: Int => "%s%s%s".format(openArrayText, _lrbracketsEmpty(depth), closeArrayText)}
  private val arrayCommaMemo = vectorMemo{depth: Int => "%s%s%s".format(_arrayCommaLeft(depth + 1), commaText, _arrayCommaRight(depth + 1))}
  private val objectCommaMemo = vectorMemo{depth: Int => "%s%s%s".format(_objectCommaLeft(depth + 1), commaText, _objectCommaRight(depth + 1))}
  private val colonMemo = vectorMemo{depth: Int => "%s%s%s".format(_colonLeft(depth + 1), colonText, _colonRight(depth + 1))}

  /**
    * Returns a string representation of a pretty-printed JSON value.
    */
  final def noSpaces(j: Json): String = {

    import Json._
    import StringEscaping._

    def appendJsonString(builder: StringBuilder, jsonString: String): StringBuilder = {
      for (c <- jsonString) {
        if (isNormalChar(c))
          builder += c
        else
          builder.append(escape(c))
      }

      builder
    }

    def encloseJsonString(builder: StringBuilder, jsonString: JsonString): StringBuilder = {
      appendJsonString(builder.append(stringEnclosureText), jsonString).append(stringEnclosureText)
    }

    def trav(builder: StringBuilder, depth: Int, k: Json): StringBuilder = {

      def lbrace(builder: StringBuilder): StringBuilder = {
        builder.append(lbraceMemo(depth))
      }
      def rbrace(builder: StringBuilder): StringBuilder = {
        builder.append(rbraceMemo(depth))
      }
      def lbracket(builder: StringBuilder): StringBuilder = {
        builder.append(lbracketMemo(depth))
      }
      def rbracket(builder: StringBuilder): StringBuilder = {
        builder.append(rbracketMemo(depth))
      }
      def lrbracketsEmpty(builder: StringBuilder): StringBuilder = {
        builder.append(lrbracketsEmptyMemo(depth))
      }
      def arrayComma(builder: StringBuilder): StringBuilder = {
        builder.append(arrayCommaMemo(depth))
      }
      def objectComma(builder: StringBuilder): StringBuilder = {
        builder.append(objectCommaMemo(depth))
      }
      def colon(builder: StringBuilder): StringBuilder = {
        builder.append(colonMemo(depth))
      }

      k.fold[StringBuilder](
        builder.append(nullText)
        , bool => builder.append(if (bool) trueText else falseText)
        , n => n match {
          case JsonLong(x) => builder append x.toString
          case JsonDecimal(x) => builder append x
          case JsonBigDecimal(x) => builder append x.toString
        }
        , s => encloseJsonString(builder, s)
        , e => if (e.isEmpty) {
          lrbracketsEmpty(builder)
        } else {
          rbracket(e.foldLeft((true, lbracket(builder))){case ((firstElement, builder), subElement) =>
            val withComma = if (firstElement) builder else arrayComma(builder)
            val updatedBuilder = trav(withComma, depth + 1, subElement)
            (false, updatedBuilder)
          }._2)
        }
        , o => {
          rbrace((if (preserveOrder) o.toList else o.toMap).foldLeft((true, lbrace(builder))){case ((firstElement, builder), (key, value)) =>
            val ignoreKey = dropNullKeys && value.isNull
            if (ignoreKey) {
              (firstElement, builder)
            } else {
              val withComma = if (firstElement) builder else objectComma(builder)
              (false, trav(colon(encloseJsonString(withComma, key)), depth + 1, value))
            }
          }._2)
        }
      )
    }

    trav(new StringBuilder(), 0, j).toString()
  }

}
