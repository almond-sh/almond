package jupyter.scala

import ammonite.interpreter.Preprocessor.Display

trait WebDisplay extends Display {

  def definition(definitionLabel: String, name: String) =
    s"""Iterator(ReplBridge.shell.shellPrintDef("$definitionLabel", "$name"))"""

  def pprintSignature(ident: String) = s"""Iterator(ReplBridge.shell.shellPPrint($$user.$ident, "$ident"))"""

  def identity(ident: String) = {
    pprintSignature(ident) +
      s""" ++ Iterator(" = ") ++ ammonite.pprint.PPrint($$user.$ident)"""
  }

  def lazyIdentity(ident: String) =
    s"""${pprintSignature(ident)} ++ Iterator(" = <lazy>")"""

  def displayImport(imported: String) =
    s"""Iterator(ReplBridge.shell.shellPrintImport("$imported"))"""

}
