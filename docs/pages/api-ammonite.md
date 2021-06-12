---
title: Ammonite API
---

The Ammonite API consists in instances of several classes, that are created
by Ammonite or almond, and allow you to interact with the REPL. These instances
are accessible either by their name, from the REPL, like `interp`,
or via implicits, like `implicitly[ammonite.interp.api.InterpAPI]`.

The following instances are available:
- `interp`, which is a [`ammonite.interp.api.InterpAPI`](#interpapi),
- `repl`, which is a [`ammonite.repl.api.ReplAPI`](#replapi).

## `InterpAPI`

[`InterpAPI`](https://github.com/lihaoyi/Ammonite/blob/master/amm/interp/src/main/scala/ammonite/interp/InterpAPI.scala) allows to
- [load new dependencies](#load-dependencies) or [compiler plugins](#load-compiler-plugins),
- [add repositories](#add-repositories) for dependencies,
- [add exit hooks](#add-exit-hooks),
- [configure compiler options](#configure-compiler-options).

### Load dependencies

`interp.load.ivy` accepts one or several
[`coursier.Dependency`](https://github.com/coursier/coursier/blob/ac5a6efa3e13925f0fb1409ea45d6b9a29865deb/modules/coursier/shared/src/main/scala/coursier/package.scala#L19).

Load simple dependencies
```scala
interp.load.ivy("org.platanios" %% "tensorflow-data" % "0.4.1")
```

Load dependencies while adjusting some parameters
```scala
interp.load.ivy(
  // replace with linux-gpu-x86_64 on linux with nvidia gpu or with darwin-cpu-x86_64 on macOS 
  ("org.platanios" %% "tensorflow" % "0.4.1").withClassifier("linux-cpu-x86_64")
)
```

The dependencies can then be used in the cell right _after_ the one calling
`interp.load.ivy`.

Note that in the case of simple dependencies, when directly entering code
in a notebook,
the following syntax is preferred
and allows to use the dependency in the current cell rather than the next one:
```scala
import $ivy.`org.platanios::tensorflow-data:0.4.1`
```

### Load compiler plugins

`interp.load.plugin.ivy` accepts one or several
[`coursier.Dependency`](https://github.com/coursier/coursier/blob/ac5a6efa3e13925f0fb1409ea45d6b9a29865deb/modules/coursier/shared/src/main/scala/coursier/package.scala#L19).

```scala
interp.load.plugin.ivy("org.spire-math" %% "kind-projector" % "0.9.9")
```
The plugins can then be used in the cell right _after_ the one calling
`interp.load.plugin.ivy`.


Note that when directly entering code in a notebook, the following syntax
is more concise, and allows to use the compiler plugin in the current cell:
```scala
import $plugin.$ivy.`org.spire-math::kind-projector:0.9.9`

// example of use
trait T[F[_]]
type T2 = T[Either[String, ?]]
```

### Add repositories

One can add extra
[`coursier.Repository`](https://github.com/coursier/coursier/blob/ac5a6efa3e13925f0fb1409ea45d6b9a29865deb/modules/coursier/shared/src/main/scala/coursier/package.scala#L69) via

```scala
import coursierapi._

interp.repositories() ++= Seq(
  MavenRepository.of("https://nexus.corp.com/content/repositories/releases")
    .withCredentials(Credentials.of("user", "pass"))
)
```

### Add exit hooks

```scala
interp.beforeExitHooks += { _ =>
  // called before the kernel exits
}
```

### Configure compiler options

```scala
// enable warnings
interp.configureCompiler(_.settings.nowarn.value = false)
```

## `ReplAPI`

[`ReplAPI`](https://github.com/lihaoyi/Ammonite/blob/master/amm/repl/src/main/scala/ammonite/repl/ReplAPI.scala) allows to
- [access the pretty-printer and customize its behavior](#pretty-printer),
- [access the latest thrown exception](#latest-thrown-exception),
- [access the command history](#command-history),
- [request the compiler instance to be re-created](#refresh-compiler-instance),
- [get the current compiler instance](#get-compiler-instance),
- [get the current imports](#get-current-imports),
- [evaluate code from strings or files](#evaluate-code), and lastly
- [get the class names and byte code](#byte-code-of-repl-inputs) of the code entered during the current
session.

### Pretty-printer

```scala
class Foo(val x: Int)

repl.pprinter() = {
  val p = repl.pprinter()
  p.copy(
    additionalHandlers = p.additionalHandlers.orElse {
      case f: Foo =>
        pprint.Tree.Lazy(_ => Iterator(fansi.Color.Yellow(s"foo: ${f.x}").render))
    }
  )
}
```

### Latest thrown exception

```scala
repl.lastException // last thrown exception, or null if none were thrown
```

### Command history

```scala
repl.history // current session history
repl.fullHistory // shared history
```

### Refresh compiler instance

```scala
repl.newCompiler()
```

### Get compiler instance

```scala
repl.compiler // has type scala.tools.nsc.Global
```

### Get current imports

```scala
repl.imports
```

### Evaluate code

```scala
repl.load("val a = 2")
```

### Byte code of REPL inputs

```scala
repl.sess.frames.flatMap(_.classloader.newFileDict).toMap
// Map[String, Array[Byte]], keys: class names, values: byte code
```

