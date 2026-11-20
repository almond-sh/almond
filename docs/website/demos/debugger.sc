// Front page demo: "Debugger"
// Each "// %%" line starts a cell, typed then run by record.mjs (see README.md).
// The debugger needs a kernel installed with --debugger, from a version of Almond supporting it
// (record.sh installs the scala3 kernel that way). Scala 3 kernels show nicer names in the call
// stack. The function isn't tail recursive, so that the call stack grows at each stop.

// %demo kernel=scala3 debugger=true width=820 height=540

// %% breakpoints=2 debug=continue,continue,continue pause=2500
def initials(xs: List[String]): String =
  if (xs.isEmpty) ""
  else xs.head.take(1) + initials(xs.tail)

initials(List("Ada", "Lovelace"))
