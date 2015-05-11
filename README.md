# Jupyter Scala

Jupyter Scala is a Scala kernel for [Jupyter / IPython](http://ipython.org/).
It's a lightweight alternative to [IScala](https://github.com/mattpap/IScala),
being easily embeddable in other apps / libraries and being based
on the great wrapper around the scalac internals from
[Ammonite](https://github.com/lihaoyi/Ammonite/) (my [fork of it](https://github.com/alexarchambault/ammonite-shell) actually).

[![Build Status](https://travis-ci.org/alexarchambault/jupyter-scala.svg?branch=master)](https://travis-ci.org/alexarchambault/jupyter-scala)

## Quick start

Ensure you have [IPython](http://ipython.org/) 3 installed.
`ipython --version` should return a value >= 3.0. If it's
not the case, a quick way of setting it up consists
in installing the [Anaconda](http://continuum.io/downloads) Python
distribution, and then running

    $ pip install --upgrade "ipython[all]"

`ipython --version` should then return a value >= 3.0.

Download the Jupyter Scala binaries for
[Scala 2.10](https://oss.sonatype.org/content/repositories/snapshots/com/github/alexarchambault/jupyter/jupyter-scala-cli_2.10/0.2.0-SNAPSHOT/jupyter-scala-cli_2.10-0.2.0-SNAPSHOT.tar.xz) or
[Scala 2.11](https://oss.sonatype.org/content/repositories/snapshots/com/github/alexarchambault/jupyter/jupyter-scala-cli_2.11/0.2.0-SNAPSHOT/jupyter-scala-cli_2.11-0.2.0-SNAPSHOT.tar.xz), and unpack them in a safe place, `~/opt`
for example.
Then run the `jupyter-scala` command-line program it contains with
the `--kernel-spec` option, like

    $ ~/opt/jupyter-scala-0.2.0-SNAPSHOT/bin/jupyter-scala --kernel-spec

That will set-up the Jupyter Scala kernel for the current user.

Check that IPython knows about Jupyter Scala by running

    $ ipython kernelspec list

This should print, among others, a line like
```
scala-2.11
```
(or `2.10` dependending on the Scala version you chose).

Then run either IPython console with

    $ ipython console --kernel scala-2.11

and start using the Jupyter Scala kernel straightaway,
or run IPython Notebook with

    $ ipython notebook

and create `Scala 2.11` notebooks by choosing `Scala 2.11` in the
dropdown in the upper right of the IPython Notebook start page.

**More detailed explanations and examples to come**

## Internals

Jupyter Scala is a bridge between
(my [fork](https://github.com/alexarchambault/ammonite-shell) of the)
[Ammonite](https://github.com/lihaoyi/Ammonite)'s wrappers around the Scala compiler,
and the Jupyter kernel facilities put in
[Jupyter kernel](https://github.com/alexarchambault/jupyter-kernel).

## Compiling it

Build your own binaries from the sources with

    $ git clone https://github.com/alexarchambault/jupyter-scala.git
    $ cd jupyter-scala
    $ sbt cli/packArchive

This will generate a tar archive like `jupyter-scala-cli-0.2.0-SNAPSHOT.tar.gz` in `cli/target/`. See the instructions above for how to set it up then.

Released under the Apache 2.0 license, see LICENSE for more details.
