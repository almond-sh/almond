package jupyter.scala

import ammonite.api.DisplayItem, DisplayItem._

object WebDisplay {

  /* For now, identical to ammonite.shell.ShellDisplay */

  def pprintSignature(ident: String) = s"""_root_.scala.collection.Iterator(ReplBridge.shell.shellPPrint($$user.$ident, "$ident"))"""

  def apply(d: DisplayItem): String =
    d match {
      case Definition(label, name) =>
        s"""_root_.scala.collection.Iterator(ReplBridge.shell.shellPrintDef("$label", "$name"))"""
      case Identity(ident) =>
        pprintSignature(ident) +
          s""" ++ _root_.scala.collection.Iterator(" = ") ++ ammonite.pprint.PPrint($$user.$ident)"""
      case LazyIdentity(ident) =>
        s"""${pprintSignature(ident)} ++ _root_.scala.collection.Iterator(" = <lazy>")"""
      case Import(imported) =>
        s"""_root_.scala.collection.Iterator(ReplBridge.shell.shellPrintImport("$imported"))"""
    }

}
