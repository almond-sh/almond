import json as __almond_scalapy_json


def __almond_scalapy_format_display_data(obj, include):
    repr_methods = ((t, m) for m, t in include if m in set(dir(obj)))
    representations = ((t, getattr(obj, m)()) for t, m in repr_methods)

    display_data = (
        (t, (r[0], r[1]) if isinstance(r, tuple) and len(r) == 2 else (r, None))
        for t, r in representations if r is not None
    )
    display_data = [(t, m, md) for t, (m, md) in display_data if m is not None]

    data = [
        (t, d if isinstance(d, str) else __almond_scalapy_json.dumps(d))
        for t, d, _ in display_data
    ]
    metadata = [
        (t, md if isinstance(md, str) else __almond_scalapy_json.dumps(md))
        for t, _, md in display_data if md is not None
    ]

    return data, metadata
