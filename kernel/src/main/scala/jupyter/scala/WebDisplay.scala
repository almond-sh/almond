package jupyter.scala

import ammonite.interpreter.api.DisplayItem
import ammonite.interpreter.api.DisplayItem.{Import, LazyIdentity, Identity, Definition}

object WebDisplay {

  /* For now, identical to ammonite.shell.ShellDisplay */

  def pprintSignature(ident: String) = s"""Iterator(ReplBridge.shell.shellPPrint($$user.$ident, "$ident"))"""

  def apply(d: DisplayItem): String =
    d match {
      case Definition(label, name) =>
        s"""Iterator(ReplBridge.shell.shellPrintDef("$label", "$name"))"""
      case Identity(ident) =>
        pprintSignature(ident) +
          s""" ++ Iterator(" = ") ++ ammonite.pprint.PPrint($$user.$ident)"""
      case LazyIdentity(ident) =>
        s"""${pprintSignature(ident)} ++ Iterator(" = <lazy>")"""
      case Import(imported) =>
        s"""Iterator(ReplBridge.shell.shellPrintImport("$imported"))"""
    }

}
