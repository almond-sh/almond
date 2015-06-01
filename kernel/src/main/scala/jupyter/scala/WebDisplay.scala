package jupyter.scala

import ammonite.api.DisplayItem, DisplayItem._

object WebDisplay {

  /* For now, identical to ammonite.shell.ShellDisplay */

  def apply(d: DisplayItem): String =
    d match {
      case Definition(label, name) =>
        s"""ReplBridge.shell.Internal.printDef("$label", "$name")"""
      case Identity(ident) =>
        s"""ReplBridge.shell.Internal.print($$user.$ident, "$ident", None)"""
      case LazyIdentity(ident) =>
        s"""ReplBridge.shell.Internal.print($$user.$ident, "$ident", Some("<lazy>"))"""
      case Import(imported) =>
        s"""ReplBridge.shell.Internal.printImport("$imported")"""
    }

}
