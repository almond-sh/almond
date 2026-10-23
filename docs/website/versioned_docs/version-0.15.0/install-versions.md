---
title: Versions
---

Available Scala versions for each version of almond

Almond version | Scala 2.11 version | Scala 2.12 version | Scala 2.13 version
---------------|--------------------|--------------------|-------------------
`0.1.0` to `0.1.8` | `2.11.12` | `2.12.6`                                          | -
`0.1.9` - `0.1.12` | `2.11.12` | `2.12.6`, `2.12.7`                                | -
`0.1.13` - `0.6.0` | `2.11.12` | `2.12.6`, `2.12.7`, `2.12.8`                      | -
`0.7.0`            | -         | `2.12.6`, `2.12.7`, `2.12.8`                      | `2.13.0`
`0.8.0`            | -         | `2.12.6`, `2.12.7`, `2.12.8`, `2.12.9`            | `2.13.0`
`0.8.1`            | -         | `2.12.6`, `2.12.7`, `2.12.8`, `2.12.9`, `2.12.10` | `2.13.0`
`0.8.2` - `0.8.3`  | -         | `2.12.6`, `2.12.7`, `2.12.8`, `2.12.9`, `2.12.10` | `2.13.0`, `2.13.1`
`0.9.0` - ...      | -         | `2.12.8`, `2.12.9`, `2.12.10`                     | `2.13.0`, `2.13.1`

The versions after `0.14.5` support Scala `2.12.8` to `2.12.21`, `2.13.3` to `2.13.18`, along
with Scala 3 (its LTS series, and the latest ones). Note that the Scala 2.12 versions before
`2.12.18` and the Scala 2.13 versions before `2.13.11` need a JDK at most 17 to run.

See also release notes at https://github.com/almond-sh/almond/releases

To find out which version of almond is installed, from a running notebook, go to the menu bar and select:

- `Help -> About`, if using Jupyter Classic
- `Help -> About the Scala Kernel`, if using JupyterLab
