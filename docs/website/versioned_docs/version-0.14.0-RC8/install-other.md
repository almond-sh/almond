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
    almond:0.14.0-RC8 --scala 2.13.11 \
    -o almond
$ ./almond --install
$ rm -f almond # the generated launcher can be removed after install, it copied itself in the kernel installation directory
```

