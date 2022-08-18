The examples in this directory are there mostly for test purposes.

See the [examples repository](https://github.com/almond-sh/examples) for
more detailed and user-friendly examples.

To generate `requirements.txt`, do something along those lines:
```text
$ docker run -it --rm python /bin/bash
# pip install nbconvert==6.5.3 jupyter-console==6.4.4
# pip freeze
```

(Copy the output of `pip freeze` to `requirements.txt`.)
