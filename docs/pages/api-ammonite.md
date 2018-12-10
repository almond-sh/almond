---
title: Ammonite API
---

The Ammonite API consists in instances of several classes, that are created
by Ammonite or almond, and allow you to interact with the REPL. These instances
are accessible either by their name, from the REPL, like `interp`,
or via implicits, like `implicitly[ammonite.interp.InterpAPI]`.

The following instances are available:
- `interp`, which is a [`ammonite.interp.InterpAPI`](#interpapi),
- `repl`, which is a [`ammonite.repl.ReplAPI`](#replapi).

## `InterpAPI`

[`InterpAPI`](https://github.com/lihaoyi/Ammonite/blob/master/amm/interp/src/main/scala/ammonite/interp/InterpAPI.scala) allows to
- [load new dependencies](#load-dependencies) or [compiler plugins](#load-compiler-plugins),
- [add repositories](#add-repositories) for dependencies,
- [add exit hooks](#add-exit-hooks),
- [configure compiler options](#configure-compiler-options).

### Load dependencies

???

### Load compiler plugins

???

### Add repositories

???

### Add exit hooks

???

### Configure compiler options

???

## `ReplAPI`

[`ReplAPI`](https://github.com/lihaoyi/Ammonite/blob/master/amm/repl/src/main/scala/ammonite/repl/ReplAPI.scala) allows to
- [access the latest thrown exception](#latest-thrown-exception),
- [access the command history](#command-history),
- [request the compiler instance to be re-created](#refresh-compiler-instance),
- [get the current compiler instance](#get-compiler-instance),
- [get the current imports](#get-current-imports),
- [evaluate code from strings or files](#evaluate-code), and lastly
- [get the class names and byte code](#byte-code-of-repl-inputs) of the code entered during the current
session.

### Latest thrown exception

???

### Command history

???

### Refresh compiler instance

???

### Get compiler instance

???

### Get current imports

???

### Evaluate code

???

### Byte code of REPL inputs

???
