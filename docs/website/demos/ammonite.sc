// Front page demo: "All the Ammonite niceties"
// Each "// %%" line starts a cell, typed then run by record.mjs (see README.md).

// %demo height=640 warmup=true

// %%
//> using dep "com.lihaoyi::ujson:4.4.3"

// %%
val json = ujson.read("""{"content": "Hello", "version": 2}""")

// %%
json("version") = 3

// %% pause=2500
println(json.render(indent = 2))
