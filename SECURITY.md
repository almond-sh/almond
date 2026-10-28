# Security policy

## Supported versions

Security fixes go in the next release. Only the latest almond release gets them: there are no
backports to older release lines. The [releases page](https://github.com/almond-sh/almond/releases)
lists the latest version.

## Reporting a vulnerability

Please don't open a public issue or pull request for a vulnerability. Report it privately instead,
from the [Security tab](https://github.com/almond-sh/almond/security) of the repository ("Report a
vulnerability"), which opens a
[private security advisory](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
that only the maintainers can see.

Please include:

- the almond version (or commit) and the Scala version of the kernel;
- how the kernel was started, and the relevant settings of its connection file (`ip`, `key`,
  `signature_scheme`);
- where the attacker sits: on the network, another user of the same host, data passed to a cell…;
- the steps to reproduce, ideally as a test or a script using `jupyter_client`.

almond is maintained by volunteers. We aim to acknowledge reports within a week, and will keep you
updated as we work on a fix. Once a fix is released, we publish an advisory, and credit you in it
unless you'd rather not be.

## Scope

almond is a Jupyter kernel: running the code it is sent is what it is for. Whoever holds the key of
its connection file can run any code as the user running the kernel. The main security boundary is
the Jupyter protocol itself: the ZeroMQ sockets of the connection file, and the HMAC signature of
the messages.

In scope, for example:

- running code in the kernel, or reading the messages it exchanges, without the key;
- making the key or the connection file readable by other users of the host;
- crashing or hanging the kernel without the key;
- HTML or JavaScript injection in outputs, from data (not code) that a cell passes to a display
  helper that isn't meant to render it as HTML;
- a network attacker reading or replacing the dependencies a cell fetches (downgrade to http,
  skipped checksums).

Out of scope:

- anything the key holder can do by design: cell code, `//> using` directives, magics, predef
  files, the dependencies and repositories cells ask for, HTML or JavaScript that a cell outputs on
  purpose (`Html(...)`, `Javascript(...)`);
- options passed on the command line of the kernel or the launcher, and the content of the kernel
  spec, which come from whoever installs or starts the kernel;
- behaviour the Jupyter protocol defines: the heartbeat socket answering unsigned messages, an
  empty `key` disabling signatures (unless almond itself writes or uses an empty key where Jupyter
  didn't ask for one);
- vulnerabilities in dependencies (Ammonite, coursier, JeroMQ, the Scala compiler…) that no almond
  code reaches. Please report these to the projects concerned;
- the build, the documentation, the examples, and the test modules.

[`.oss-scanner/threat_model.md`](.oss-scanner/threat_model.md) describes the threat model in more
detail.
