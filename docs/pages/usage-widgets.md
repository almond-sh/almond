---
title: Widgets
---

Almond supports [Jupyter widgets](https://ipywidgets.readthedocs.io): sliders, buttons,
text fields, drop-downs, and the like, that users can interact with, and whose state is synced
with the kernel. The `almond.widgets` package provides Scala counterparts of the widgets of
[ipywidgets](https://ipywidgets.readthedocs.io) 8.

```scala
import almond.widgets._

val slider = IntSlider(value = 5, min = 0, max = 10, description = "n")
val label  = Label()
slider.value.observe { n =>
  label.value() = s"n² = ${n * n}"
}

VBox(Seq(slider, label))
```

## Requirements

Widgets are rendered by the front-end, that needs to have the Jupyter widgets extension installed:
- in JupyterLab or Jupyter Notebook 7, install the `jupyterlab_widgets` Python package (or
`ipywidgets`, that depends on it) in the environment of Jupyter, like
```text
$ pip install jupyterlab_widgets
```
- VS Code renders widgets out of the box.

## Creating and displaying widgets

Widgets are created via the `apply` methods of their companion objects, that accept a value for
each of their properties, like the constructors of ipywidgets do:
```scala
val text = Text(value = "Hello", placeholder = "Type something", description = "Text:")
```

Like in ipywidgets, a widget is displayed when it's the result of a cell (or of the last
expression of a cell). Widgets assigned to variables aren't displayed, as they're typically
assembled in containers before being displayed:
```scala
val a = IntSlider(description = "a")
val b = IntSlider(description = "b")
HBox(Seq(a, b)) // displays a and b side by side
```

Call `display()` on a widget to display it from anywhere in a cell. A widget can be displayed
several times: all its views stay in sync.

## Properties

Each property of a widget is a `Prop`, that can be read with `()`, and changed with `() =`:
```scala
val slider = FloatSlider(value = 0.5, max = 1.0)
slider.value()       // the current value, updated when users move the slider
slider.value() = 0.8 // moves the slider in the front-end
slider.max() = 2.0
```

The names of properties are the camel case versions of the names of their ipywidgets
counterparts (`readout_format` becomes `readoutFormat`, for example).

Properties that can be `None` in ipywidgets are `Option`s, except properties for CSS values or
tooltips, where an empty string means "no value".

`observe` registers a callback, called each time a property changes, either because of users
interacting with the widget, or because of the kernel changing it:
```scala
val sub = slider.value.observe(v => println(s"new value: $v"))
sub.cancel() // stops calling the callback
```
`observeChange` gives both the former and the new values. Several changes can be sent in a single
message with `holdSync`:
```scala
slider.holdSync {
  slider.max() = 10.0
  slider.value() = 5.0
}
```

## Buttons

```scala
val button = Button(description = "Click me", buttonStyle = "success", icon = "check")
button.onClick {
  println("clicked")
}
button
```

## Selection widgets

The options of `Dropdown`, `RadioButtons`, `Select`, `ToggleButtons`, `SelectionSlider`,
`SelectMultiple`, and `SelectionRangeSlider` can be any values. Only their labels are sent to the
front-end, which are the `toString` of the options, unless custom labels are passed:
```scala
val dropdown = Dropdown(
  options = Seq(1, 2, 3),
  labels = Seq("one", "two", "three"),
  value = Some(2),
  description = "Number:"
)
dropdown.value() // Some(2), an Option[Int]
dropdown.label() // Some("two")
dropdown.setOptions(Seq(4, 5)) // changes the options, selecting the first one
```

## Containers and layout

`HBox`, `VBox`, `Box`, `GridBox`, `Tab`, `Accordion`, and `Stack` contain other widgets. The
`Layout` of a widget gives access to its CSS properties:
```scala
val buttons = (1 to 4).map(i => Button(description = s"Button $i"))
GridBox(
  children = buttons,
  layout = Layout(gridTemplateColumns = "repeat(2, 150px)", gridGap = "4px")
)
```
```scala
Tab(
  children = Seq(IntSlider(), Text()),
  titles = Seq("Slider", "Text")
)
```

## Output widgets

`Output` widgets show outputs, like the output area of cells. `capture` sends what's printed to
them. This is especially handy in callbacks, that run while no cell is running:
```scala
val button = Button(description = "Click me")
val out    = Output()
button.onClick {
  out.capture {
    println("Button clicked")
  }
}
VBox(Seq(button, out))
```
Like `with out:` blocks in Python, `capture` shows exceptions thrown in its block in the widget,
rather than re-throwing them. Rich outputs (HTML, images, …) can be sent to output widgets via
their `outputHandler`:
```scala
Html("<b>Bold</b>").display()(out.outputHandler)
```
Note that `capture` only captures what's printed via `println`, `Console.out`, or `Console.err`,
by the current thread.

## Interact

`interact` calls a function each time the values of some widgets change, and shows its output and
result below them:
```scala
interact(IntSlider(value = 5, max = 20), Checkbox(description = "double")) { (n, double) =>
  if (double) 2 * n else n
}
```

## Linking widgets

`jslink` links two properties in the front-end, so that the link keeps working even if the kernel
is busy, or gone. `link` links properties in the kernel. Their `jsdlink` and `dlink` counterparts
only propagate the changes of their first argument:
```scala
val slider = IntSlider()
val text   = IntText()
jslink(slider.value, text.value)
HBox(Seq(slider, text))
```

## Binary data

`Image`, `Video`, and `Audio` widgets take binary data, sent to the front-end as binary buffers:
```scala
Image.fromFile("plot.png")
```
```scala
Image(value = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("plot.png")), format = "png")
```

`FileUpload` widgets give access to the files users upload:
```scala
val upload = FileUpload(accept = ".csv", multiple = true)
upload.value.observe { files =>
  for (file <- files)
    println(s"${file.name}: ${file.size} bytes")
}
upload
```

## Custom widgets

### anywidget

Custom widgets can be written with [anywidget](https://anywidget.dev), by extending `AnyWidget`
with a JavaScript module, and adding properties synced with the front-end with `prop`:
```scala
class Counter extends AnyWidget(
  esm = """
    function render({ model, el }) {
      const button = document.createElement("button");
      const update = () => { button.textContent = `count is ${model.get("count")}`; };
      button.addEventListener("click", () => {
        model.set("count", model.get("count") + 1);
        model.save_changes();
      });
      model.on("change:count", update);
      update();
      el.appendChild(button);
    }
    export default { render };
  """
) {
  val count = prop("count", 0)
}

val counter = new Counter
counter.count.observe(n => println(s"count: $n"))
counter
```
The `anywidget` Python package needs to be installed in the environment of Jupyter, for its
front-end extension to be available.

### Other widget libraries

Widgets of other libraries (whose front-end extension is installed) can be used via
`GenericWidget`, or by extending `DOMWidget`, and declaring the properties of the widget with
`prop`.

## Things to know

Widget callbacks (property observers, `onClick` handlers, …) are run by a thread of the kernel
processing messages from the front-end. Unlike in the IPython kernel, this thread can run them
while a cell is running. This lets users interact with widgets while a long computation is going
on, but means callbacks may need to synchronize their access to things shared with cells.

The front-end restores widgets after a page reload, by asking the kernel for their latest state.

## Available widgets

Accordion, Audio, BoundedFloatText, BoundedIntText, Box, Button, Checkbox, ColorPicker,
ColorsInput, Combobox, Controller, DatePicker, Datetime, Dropdown, FileUpload, FloatLogSlider,
FloatProgress, FloatRangeSlider, FloatSlider, FloatText, FloatsInput, GridBox, HBox, HTML,
HTMLMath, Image, IntProgress, IntRangeSlider, IntSlider, IntText, IntsInput, Label, Layout,
NaiveDatetime, Output, Password, Play, RadioButtons, Select, SelectMultiple, SelectionRangeSlider,
SelectionSlider, Stack, Tab, TagsInput, Text, Textarea, Time, ToggleButton, ToggleButtons, VBox,
Valid, Video, along with the style widgets (`SliderStyle`, `ButtonStyle`, …), and `Link` /
`DirectionalLink`.

Their classes are generated from the
[model schema of ipywidgets](https://github.com/jupyter-widgets/ipywidgets/blob/main/packages/schema/jupyterwidgetmodels.latest.md),
whose documentation details their properties.
