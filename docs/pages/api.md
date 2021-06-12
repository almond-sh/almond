---
title: Intro
hide_title: true
---

# API

The API of almond is two-fold:
- the [Ammonite API](api-ammonite.md) gives access to the REPL internals
(loading dependencies, evaluating code, getting the full classpath),
- the [almond Jupyter API](api-jupyter.md) allows to communicate with
Jupyter front-ends.

Both of these APIs can be used:
- [directly from notebook sessions](api-access-instances.md#from-the-repl),
- [as libraries](api-access-instances.md#from-a-library), that your own libraries can depend on.

In the latter case, your libraries can be loaded during an almond session,
and then interact with the Jupyter front-end on their own.
