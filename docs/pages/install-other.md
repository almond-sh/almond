---
title: Other
---

## All-included launcher

When launched by other users, the kernel installed by the default command
downloads its dependencies in the user-specific [coursier cache](https://get-coursier.io/docs/cache.html#location)
upon first launch.

You may prefer to have the launcher embed all these JARs,
so that nothing needs to be downloaded or picked from a cache upon launch. Passing
`--standalone` to the `coursier bootstrap` command generates such a launcher,
```bash
$ coursier bootstrap --standalone \
    almond:@VERSION@ --scala @SCALA_VERSION@ \
    -o almond
$ ./almond --install
$ rm -f almond # the generated launcher can be removed after install, it copied itself in the kernel installation directory
```


Kernels run from such a launcher read the JARs it embeds through `jar:` URLs, rather
than from the file system. Up to almond 0.15.0, this makes them noticeably slower
than kernels installed by the default command whenever the Scala compiler is
(re-)created - when the first cell runs, and each time dependencies are added - and
makes them use more memory. With Scala 3, these versions can't compile any cell from
such a launcher. This should be fixed in almond 0.16.0 and later, whose kernels read
these JARs about as fast as those of the file system. See
[#1244](https://github.com/almond-sh/almond/issues/1244).

Such launchers also embed the source JARs of the kernel dependencies, that
[inspections](usage-completion.md#inspections) read documentation from. Kernels
copy the ones they need to a temporary directory, deleted when they exit. Up to
almond 0.15.0, inspections from such launchers show no documentation from these.
