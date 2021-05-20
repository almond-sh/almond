---
title: Plotting
---
Whilst there are scala plotting libraries, here is a pragmatic, flexible workflow without them. Play each part of the stack to it's strengths - use the vega editor to get your "spec" right, then scala to obatin and pipe the data into the spec. Any JSON library would do... 

# Sketch
1. Wheel in javascript to obtain vega(-lite) embed
2. Customise spec on vega webiste using "fake" dataset
3. Inject data into spec
4. Display

# Code
Cell 1 - embed libraries
```
Javascript(
"""var script = document.createElement('script');
    
    script.type = 'text/javascript';
    script.src = '//cdn.jsdelivr.net/npm/vega@5';
    document.head.appendChild(script);
    
    var script = document.createElement('script');
    script.type = 'text/javascript';
    script.src = '//cdn.jsdelivr.net/npm/vega-embed@6';
    document.head.appendChild(script);
""")
```
Cell 2 - get spec, overwrite with custom data
```
import $ivy.`com.lihaoyi::ujson:1.3.12`
import $ivy.`com.lihaoyi::requests:0.6.5`
val spec = ujson.read(requests.get("https://vega.github.io/vega/examples/bar-chart.vg.json").text)

spec("data")(0)("values") = ujson.Arr(
  ujson.Obj("category" -> "Epic", "amount" -> 50),
  ujson.Obj("category" -> "amounts", "amount" -> 100)
)
```
Cell 3 - Display
```
Javascript(s"""
vegaEmbed(element, $spec).then(function(result) {
  }).catch(console.error)
""")
```

