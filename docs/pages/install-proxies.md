---
title: Proxies and mirrors
---

Almond downloads what it needs from Maven Central, with [coursier](https://get-coursier.io).
When Maven Central can't be reached directly from your machine, coursier has to go through the
proxy or the repository mirror of your network, and it has to do so at every point Almond fetches
something:

- **when installing Almond**: the `cs` commands of the installation pages generate a launcher, by
  resolving the kernel and downloading its JARs;
- **when Jupyter starts the kernel**: the launcher checks the JARs it needs against the coursier
  cache, and downloads missing ones. The [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
  also resolves the actual kernel for the Scala version of the notebook at that point;
- **in notebooks**: `import $ivy` and `//> using dep` directives resolve and download the
  libraries they pull.

The configuration described below is read at all of these points, by the `cs` command, by the
launchers it generates, and by the coursier that runs inside the kernel. It lives in files under
your home directory, so that nothing needs to be passed to Jupyter when it starts the kernel.

## Proxies

Put the proxy in the Maven settings file, `~/.m2/settings.xml`, like
```xml
<settings>
  <proxies>
    <proxy>
      <id>company-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>proxy.company.com</host>
      <port>3128</port>
      <username>user</username>
      <password>password</password>
      <nonProxyHosts>localhost|*.company.com</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
```

Leave `username` and `password` out if the proxy doesn't require authentication. `protocol` is the
protocol used to talk to the proxy itself (usually `http`), not the protocol of the requests going
through it: the proxy is used for both HTTP and HTTPS requests.

The `cs` command, the launchers it generates, and the kernel all read that file: they set the
[proxy properties of the JVM](https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html)
from it, and register an authenticator sending the credentials to the proxy. They look for it under
the directory that the `CS_MAVEN_HOME` environment variable points to first, then under
`MAVEN_HOME`, then under `~/.m2`.

You can then install Almond like the other installation pages say to, without further options:
```text
$ cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- --scala @SCALA213_VERSION@ --install
```

Alternatively, the proxy can be set in the [Scala CLI configuration file](https://get-coursier.io/docs/other-proxy)
with `cs config httpProxy.address`, `cs config httpProxy.user --password` and
`cs config httpProxy.password --password`. The launchers read that file by running the `cs` command,
which then needs to be in the `PATH` when Jupyter starts the kernel. The Maven settings file has no
such requirement.

Lastly, the JVM proxy properties can be passed as usual, with `-Dhttps.proxyHost=…` options:
for the kernel, they can be set in the `JAVA_TOOL_OPTIONS` environment variable, that all JVMs pick
up. See below for [environment variables](#environment-variables).

## Repository mirrors

Organizations that make Maven Central available on their network through a repository manager
(Nexus, Artifactory, …) can point coursier at it with a `mirror.properties` file, in the coursier
configuration directory:

- `~/.config/coursier/mirror.properties` on Linux (or under `$XDG_CONFIG_HOME` if set),
- `~/Library/Preferences/Coursier/mirror.properties` on macOS,
- `%LOCALAPPDATA%\Coursier\config\mirror.properties` on Windows.

The file lists the repositories to replace, and what to replace them with, like
```properties
central.from=https://repo1.maven.org/maven2
central.to=https://nexus.company.com/repository/maven-central
central.type=maven
```

Each mirror has a name (`central` above, any name works), a `.from` entry with the repository (or
several, separated by `;`) it stands for, a `.to` entry with where to get it from instead, and
a `.type`, `maven` or `tree`. A `maven` mirror replaces a Maven repository whose root is exactly
one of the `.from` addresses. A `tree` mirror replaces a URL prefix, keeping the rest of the path,
which also works for Ivy repositories. Say the repository manager hosts several repositories
under the same prefix:
```properties
company.from=https://repo1.maven.org/maven2;https://jitpack.io
company.to=https://nexus.company.com/repository/maven-central
company.type=maven

snapshots.from=https://central.sonatype.com/repository/maven-snapshots
snapshots.to=https://nexus.company.com/repository/maven-snapshots
snapshots.type=maven
```

Like the proxy settings above, the mirrors are used everywhere: by the `cs` command when installing
Almond, by the launchers when the kernel starts, and by the kernel when notebooks pull libraries.
The `COURSIER_MIRRORS` environment variable can point to that file if it lives elsewhere, and
`COURSIER_EXTRA_MIRRORS` to a second one.

Mirrors can also be set in the [Scala CLI configuration file](https://get-coursier.io/docs/reference-mirrors)
with `cs config repositories.mirrors`. Like for proxies, the launchers then need the `cs` command
in the `PATH` when Jupyter starts the kernel.

If the mirror requires credentials, see the [credentials](https://get-coursier.io/docs/other-credentials)
page of the coursier documentation: the `COURSIER_CREDENTIALS` environment variable and the
`~/.config/coursier/credentials.properties` file are read at the same points as the mirrors.

## Custom repositories

Rather than mirroring Maven Central, one can also replace the repositories coursier resolves from
altogether, with the `COURSIER_REPOSITORIES` environment variable, like
```text
$ export COURSIER_REPOSITORIES="ivy2Local|https://nexus.company.com/repository/maven-public"
```
See [using custom Maven repositories](install-advanced.md#using-custom-maven-repositories).

## Environment variables

The proxy and mirror configurations above are files, so that they are found regardless of how
Jupyter starts the kernel. Environment variables like `COURSIER_MIRRORS`, `COURSIER_REPOSITORIES`,
`CS_MAVEN_HOME` or `JAVA_TOOL_OPTIONS` need more care: the kernel gets the environment of the
Jupyter server that starts it, which is fine when you start Jupyter from a shell that has them
set, but not necessarily when Jupyter is started as a service, from a desktop launcher, or in a
container.

To be on the safe side, pin them in the kernel spec when installing Almond, with `--env`:
```text
$ cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- \
    --scala @SCALA213_VERSION@ \
    --install \
    --env "COURSIER_MIRRORS=/etc/coursier/mirror.properties"
```

`--env` can be repeated. The variables end up in the `env` section of the `kernel.json` file of
the kernel, that Jupyter sets when it starts the kernel.

## Checking that it works

`import $ivy` in a notebook is the quickest way to check that the configuration is picked up by
the kernel:
```scala
import $ivy.`org.typelevel::cats-core:2.12.0`
```

If Almond fails to start, or the import fails, run the `cs` command with the same configuration
to check it on its own, like
```text
$ cs fetch org.typelevel::cats-core:2.12.0
```
When `cs` works but Almond doesn't, the configuration probably doesn't reach the kernel: check the
environment Jupyter starts the kernel with, or pin the variables the configuration relies on in the
kernel spec, as described above.

## How this is tested

The Almond repository has Docker-based tests of these instructions, that you can run with
```text
$ ./mill -i -j 1 scala.proxy-tests.test
```

They install the kernel and run a notebook with it, from containers on a Docker network that
can't reach the outside: the only way to Maven Central is an authenticated HTTP proxy, configured
through `~/.m2/settings.xml`, or a mirror (an nginx reverse proxy of Maven Central), configured
through `~/.config/coursier/mirror.properties`. They exercise both the newer launcher and the
former one, and check that a wrongly configured proxy doesn't let the kernel through.
