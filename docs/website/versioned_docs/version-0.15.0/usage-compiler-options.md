---
title: Compiler options
---

Almond compiles each cell with the Scala compiler of the session's Scala version.
By default, that compiler runs with `-deprecation` and `-feature` enabled.
This page describes how to pass extra options to it.

There are three ways to do so:
- [`//> using option` directives](#using-directives) in cells, with the options as strings.
  This is the simplest way, and works the same way in Scala 2 and Scala 3.
- [The `interp.preConfigureCompiler` method of the Ammonite API](#ammonite-api),
  either with a typed API (setting fields of the compiler settings),
  or by passing options as strings.
- [At kernel startup](#at-kernel-startup), via a predef.

In all cases, options are added to the ones already in effect, and they stay in effect
for the rest of the session. There is currently no way to remove an option once it has been
added. Note that options such as `-Werror` / `-Xfatal-warnings` apply to the whole session:
once they are enabled, any warning makes later cells fail to compile.

## Using directives

Options can be added as strings, with `//> using option` directives at the beginning of a
cell, before any code:
```scala
//> using option "-Xfatal-warnings"

@deprecated("use bar instead", "0.1")
def foo() = 2

foo() // fails to compile, since -deprecation is enabled by default
```

Several options can be passed at once, either with several `//> using option` lines or
with a single `//> using options` line (`-Wunused:imports` requires Scala 2.13 or Scala 3,
its Scala 2.12 counterpart is `-Ywarn-unused:imports`):
```scala
//> using option "-unchecked"
//> using option "-Wunused:imports"
```
```scala
//> using options "-unchecked", "-Wunused:imports"
```

Options passed this way are taken into account before the cell gets compiled,
so that they apply to the cell that contains the directives, and to all the cells run after it.
Unlike the `//> using scala` or `//> using jvm` directives of the
[newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher),
these directives don't need to be in the first cells of the notebook: they can be used at any
point in the session.

Options are passed as strings, and any option accepted by the compiler of the current Scala
version can be used. Run `scalac -help` or `scalac -X` / `scalac -W` / `scalac -V` with the
Scala version of the session to list them, or see the
[Scala 2 compiler options reference](https://docs.scala-lang.org/overviews/compiler-options/index.html)
and the
[Scala 3 compiler options reference](https://docs.scala-lang.org/scala3/guides/migration/options-intro.html).
In Scala 2, options that the compiler doesn't recognize make the cell fail. In Scala 3,
they are currently silently ignored.

## Ammonite API

The [Ammonite `InterpAPI`](api-ammonite.md), available as `interp` in notebook sessions,
allows to configure the compiler with `interp.preConfigureCompiler`. It accepts a function
that receives the settings of the compiler, which the function can update. Almond runs this
function each time it creates a compiler instance, and requests a fresh instance after
a call to `interp.preConfigureCompiler`. Options set this way apply to the cells run _after_
the one calling `interp.preConfigureCompiler`.

What that function receives depends on the Scala version of the session:
- in Scala 2, it receives a
  [`scala.tools.nsc.Settings`](https://github.com/scala/scala/blob/2.13.x/src/compiler/scala/tools/nsc/settings/MutableSettings.scala),
- in Scala 3, it receives a
  [`dotty.tools.dotc.core.Contexts.FreshContext`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/core/Contexts.scala),
  whose `settings` field gives access to the typed settings, and whose `setSetting` and
  `setSettings` methods allow to update them.

These are the compiler's own APIs, so code written against them isn't portable across
Scala 2 and Scala 3. The `//> using option` directives above are preferred when portability
matters.

### Typed API

Each compiler option has a corresponding field in the compiler settings. These fields are
typed: boolean options such as `-deprecation` are `Boolean` settings, options accepting a
list of values such as `-language` are `List[String]` settings, and so on. The compiler
rejects invalid values at compile time, in the cell that sets them, rather than at the
next compiler creation.

In Scala 2, the settings are mutable, and each one has a `value` field:
```scala
// Scala 2

// -Xfatal-warnings / -Werror
interp.preConfigureCompiler(_.fatalWarnings.value = true)

// -unchecked
interp.preConfigureCompiler(_.unchecked.value = true)

// disable -deprecation, that almond enables by default
interp.preConfigureCompiler(_.deprecation.value = false)

// several options at once
interp.preConfigureCompiler { settings =>
  settings.unchecked.value = true
  settings.fatalWarnings.value = true
}
```

In Scala 3, the settings are immutable, and get updated via the context that
`interp.preConfigureCompiler` receives:
```scala
// Scala 3

// -Xfatal-warnings / -Werror
interp.preConfigureCompiler { ctx =>
  ctx.setSetting(ctx.settings.XfatalWarnings, true)
}

// disable -deprecation, that almond enables by default
interp.preConfigureCompiler { ctx =>
  ctx.setSetting(ctx.settings.deprecation, false)
}

// -language:implicitConversions
interp.preConfigureCompiler { ctx =>
  ctx.setSetting(ctx.settings.language, List("implicitConversions"))
}

// several options at once
interp.preConfigureCompiler { ctx =>
  ctx
    .setSetting(ctx.settings.unchecked, true)
    .setSetting(ctx.settings.XfatalWarnings, true)
}
```

The names of the settings fields are those of the
[Scala 2 `ScalaSettings`](https://github.com/scala/scala/blob/2.13.x/src/compiler/scala/tools/nsc/settings/ScalaSettings.scala)
and
[Scala 3 `ScalaSettings`](https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala)
classes. Some options don't have a public field, or have a field whose type is
cumbersome to use: those are easier to set as strings, as below.

### Options as strings

`interp.preConfigureCompiler` can also be used to pass options as strings, with the
same parser the compiler uses for its command-line arguments. This accepts any option
of the compiler, like the `//> using option` directives.

In Scala 2, `processArguments` parses a list of options and updates the settings in place.
It returns whether the parsing succeeded, along with the arguments it didn't consume:
```scala
// Scala 2
interp.preConfigureCompiler { settings =>
  val (success, remaining) =
    settings.processArguments(List("-Xfatal-warnings", "-Wunused:imports"), processAll = true)
  assert(success && remaining.isEmpty, s"Invalid compiler options, remaining: $remaining")
}
```

In Scala 3, `ScalacCommand.distill` parses the options against the current settings state
and returns a new state, that can be set on the context. Invalid values are reported in
`errors`, while unrecognized options are reported in `warnings`:
```scala
// Scala 3
import dotty.tools.dotc.ScalacCommand

interp.preConfigureCompiler { ctx =>
  val options = Array("-Werror", "-Wunused:imports")
  val summary = ScalacCommand.distill(options, ctx.settings)(ctx.settingsState)(using ctx)
  val issues  = summary.errors ++ summary.warnings
  assert(issues.isEmpty, s"Invalid compiler options: ${issues.mkString(", ")}")
  ctx.setSettings(summary.sstate)
}
```

Note that a failure in the function passed to `interp.preConfigureCompiler` surfaces
when the next compiler instance gets created, that is, when the next cell gets compiled.

## At kernel startup

To enable options for all sessions of an installed kernel, put one of the
`interp.preConfigureCompiler` calls above in a predef, passed to almond at installation time.
Predef code can be passed inline with [`--predef-code`](install-options.md#--predef-code),
or from a file with `--predef`:
```bash
# Scala 2 kernel
$ ./almond --install \
    --predef-code 'interp.preConfigureCompiler(_.fatalWarnings.value = true)'
```
```bash
# Scala 3 kernel
$ ./almond --install \
    --predef-code 'interp.preConfigureCompiler(ctx => ctx.setSetting(ctx.settings.XfatalWarnings, true))'
```

The predef is kept in the kernel spec, and runs before the first cell of each session,
so that these options are in effect from the first cell onwards.
