# Threat model

## What this project does and where untrusted input enters

almond is a Scala kernel for Jupyter. A Jupyter frontend (JupyterLab, Notebook, a Jupyter client
library) starts it with a connection file, and sends it code to compile and run. Its main parts are:

- `modules/shared/kernel`: the kernel framework, which implements the Jupyter messaging protocol
  over ZeroMQ (JeroMQ), with message signing, and the `--install` command writing kernel specs;
- `modules/scala/scala-interpreter`: the interpreter, based on a fork of Ammonite, that compiles
  and runs the code of the cells, and fetches the dependencies they ask for with coursier;
- `modules/scala/scala-kernel` and `modules/scala/scala-kernel-api`: the kernel's entry point, and
  the API that code in cells uses (display helpers, comms, input);
- `modules/api`: display helpers that turn values into Jupyter outputs (HTML, Markdown, images...);
- `modules/scala/launcher`: a small kernel that reads the `//> using` directives of the first
  cells (Scala version, JVM, options), then starts the real kernel with the same connection file;
- `modules/scala/kernel-helpers`: code shared by the launcher and the kernel (directives...).

Running code is what the kernel is for. Whoever holds the key of the connection file can run any
code as the user running the kernel, read and write their files, and start processes. So the main
security boundary is the Jupyter protocol itself: the ZeroMQ sockets bound on the `ip` and the
ports of the connection file, and the HMAC signature (`key`, `signature_scheme`) of the messages.
These sockets often listen on non-loopback interfaces: kernels in containers, in Kubernetes pods
started by Jupyter Enterprise Gateway, on shared hosts of JupyterHub deployments.

Untrusted input comes from:

- anyone who can reach the ZeroMQ ports without holding the key: other users of a shared host,
  other containers or pods, the network;
- the files almond writes that hold the key or the ports: the connection file it rewrites with
  the ports it picked, the connection and option files the launcher passes to the kernel it
  starts, its logs;
- data that code in cells passes to almond's display API without expecting it to be interpreted:
  strings, URLs, file names, exception messages. JupyterLab trusts the outputs of the cells the
  user runs, and runs the scripts in their HTML, so HTML or JavaScript injected in such outputs
  runs in the JupyterLab page, which can run code on the server;
- the repositories that dependencies come from, when code in cells fetches some (`import $ivy`,
  `//> using dep`, `//> using repository`), and the URLs that display helpers like `Html.from(url)`
  or `Image.from(url)` read.

## Components that matter most / least

Most:

- message reading, signing and checking: `modules/shared/kernel/src/main/scala/almond/channels/`
  (in particular `zeromq/ZeromqSocketImpl.scala`), `almond/protocol/` (`Connection`,
  `Registration`, the JSON codecs), and the handlers that run before any user code in
  `almond/interpreter/messagehandlers/`;
- anything that handles the connection file, the registration file (kernel startup handshake of
  Jupyter protocol 5.6), and the files the launcher hands over to the kernel: `almond/kernel/Kernel.scala`,
  `modules/scala/launcher`;
- the display helpers that produce HTML, Markdown, SVG or JavaScript outputs out of values:
  `modules/api/src/main/scala/almond/display/`, `almond/api/helpers/`, and the rendering of
  values and errors in `modules/scala/scala-interpreter`;
- dependency and repository handling in `modules/scala/scala-interpreter` and
  `modules/scala/kernel-helpers` (repository parsing, http vs https, checksums).

Less: `--install` and the kernel spec it writes (`almond/kernel/install/`), whose input comes from
the person installing the kernel; the Toree compatibility magics (`almond/toree/`), only enabled
with `--toree-magics`.

Least, out of scope: the build (`mill-build/`, `build.mill`, `mill`), the `dev.*` commands that
start JupyterLab for development, `docs/`, `examples/`, `.github/`, the test modules
(`modules/shared/test`, `modules/shared/test-kit`, `modules/scala/test-definitions`,
`modules/scala/integration`, `modules/scala/proxy-tests`, `modules/scala/examples`), the
`modules/echo` demo kernel, and `modules/scala/almond-rx`, which isn't published.

## How to exercise it

The repository is at `/src`, built with Mill. Run Mill without its daemon (`./mill -i ...`). The
image has no network: `COURSIER_MODE=offline` makes coursier only use its cache.

Modules are cross-built: the first cross value is a binary Scala version (the oldest full Scala
version of each binary one, that the modules are compiled with), the second one, for the
interpreter and kernel tests, the full Scala version the tests run with. The image holds the
dependencies of the modules and tests for the latest Scala 2.13, 3 and 2.12 versions:
`./mill -i --ticker false oss-scanner.scalaVersions` prints them, one per line, as the full
Scala version followed by its binary one. Below, `<full>` and `<binary>` stand for these, and
`./mill -i resolve 'scala.scala-interpreter[_].test[_]'` lists the actual module names.

- Unit tests (utest), one class at a time:
  - protocol, signing, sockets:
    `./mill -i 'shared.kernel[<binary>].test.testOnly' almond.channels.zeromq.ZeromqSocketTests`
    (also `almond.kernel.KernelTests`, `almond.kernel.RegistrationTests`,
    `almond.protocol.ProtocolTests`);
  - interpreter and whole kernel, run in-process:
    `./mill -i 'scala.scala-interpreter[<binary>].test[<full>].testOnly' almond.ScalaKernelTests`.
    `TestUtil.kernelRunner` there drives a whole kernel, and is the easiest way to write a
    reproducer;
  - all the tests for a Scala version: `./mill -i '__[<binary>].test' + '__.test[<full>].testForked'`,
    or `./mill -i oss-scanner.test` for those of the latest Scala 2.13 and 3 versions.
- A kernel started on its own: `almond-kernel --connection-file <file>` runs the kernel of the
  latest Scala 2.13 version. `python3` has `jupyter_client`, to talk to it, or to send it
  hand-crafted ZeroMQ frames (with `zmq`).
- Tests that fetch dependencies the image doesn't have in its cache fail. The integration tests
  (`scala.integration.test`) need artifacts published locally and aren't prepared in the image.

## How you rate severity

- Critical: running code in the kernel, or reading what flows through it, without the key, with
  the kernel started the default way (by Jupyter, with a connection file using HMAC-SHA256).
- High: the same, but needing a setting that is common in deployments (kernel bound to a
  non-loopback interface); making the key readable by other users of the host; JavaScript
  injection in outputs, from data (not code) that a cell passes to a display helper that is not
  meant to render it as HTML.
- Medium: crashing or hanging the kernel without the key; reading or replacing the dependencies a
  cell fetches by being on the network path (downgrade to http, skipped checksums); the same
  injections as above that need an unusual call.
- Low: information in local logs; hardening without a demonstrated attack (a timing difference in
  a comparison with no practical exploit, for example).

Each report states its preconditions: the almond and Scala versions, the connection file
settings, where the attacker sits (network, other local user, data passed to a cell), and each
action the user takes.

## Reports and patches

Give the almond commit first, then the input and the steps. Include a utest test that fails
before the fix, in the module of the code it fixes (`shared.kernel[...].test`,
`scala.scala-interpreter[...].test[...]`...). Keep patches small, in the style of the
surrounding code.

## Anything to leave alone

- Code the key holder runs, by design: cell code, `//> using` directives (including `javaCmd`,
  `javaOpt`, `jvm`), magics, predef files, the dependencies and repositories it asks for.
- Options passed on the command line of the kernel or the launcher, and the content of the kernel
  spec, which come from whoever installs or starts the kernel.
- The heartbeat socket answering unsigned messages: the Jupyter protocol defines it that way.
- An empty `key` in the connection file disabling signatures: the Jupyter protocol defines it that
  way too. Report it if almond itself writes or uses an empty key where Jupyter didn't ask for one.
- JavaScript and HTML that a cell outputs on purpose (`Html(...)`, `Javascript(...)`).
- Defects in Ammonite, coursier, JeroMQ, the Scala compiler and other dependencies that no almond
  code reaches.
- Everything listed as out of scope above.
