---
title: deepnote
---

[Deepnote](https://deepnote.com) offers hosted notebooks with real-time collaboration capabilities.
Almond can be used there via a custom environment image, published on
[dockerhub](https://hub.docker.com/r/almondsh/almond) upon each release, along with the
[other Docker images](try-docker.md):
```
almondsh/almond:@LATEST_RELEASE@-deepnote
```
(or `almondsh/almond:latest-deepnote` for the latest version). Set it as a custom image in the
environment settings of a Deepnote project (see the
[Deepnote documentation](https://deepnote.com/docs/custom-environment)).

This image is based on Deepnote's own Python image, and has kernels for the Scala versions of the
`almondsh/almond:@LATEST_RELEASE@` image. New notebooks use the Scala 2.13 kernel (`scala213`).

You can also try Almond very quickly by cloning [this Deepnote project with Almond kernel and examples](https://deepnote.com/project/9970220b-94df-4e6e-9695-85dda6b14c59#%2Fexamples%2Fnotebooks%2Findex.ipynb).


[![Launch in Deepnote](https://deepnote.com/buttons/launch-in-deepnote.svg)](https://deepnote.com/project/9970220b-94df-4e6e-9695-85dda6b14c59#%2Fexamples%2Fnotebooks%2Findex.ipynb)
