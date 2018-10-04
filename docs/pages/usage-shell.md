---
title: Shell API
---

Almond mostly wraps the [Ammonite](http://ammonite.io/) scala shell in a Jupyter kernel. Most
of its features (but for the terminal-specific ones) should work as is in almond.

We just give examples of some of those here.

## Adding dependencies

Use Ammonite's `import $ivy` syntax. Example
```scala
import $ivy.`io.circe::circe-core:0.9.3`
import $ivy.`io.circe::circe-parser:0.9.3`

import $ivy.`org.yaml:snakeyaml:1.23`
```

Note the use of single `:` for Java dependencies (last one), and double `::`
in first position for the Scala ones.

## Adding custom repositories

Example:

```scala
import coursier.core.Authentication, coursier.MavenRepository

interp.repositories() ++= Seq(MavenRepository(
  "https://nexus.corp.com/content/repositories/releases",
  authentication = Some(Authentication("user", "pass"))
))
```

## Custom pretty-printing
