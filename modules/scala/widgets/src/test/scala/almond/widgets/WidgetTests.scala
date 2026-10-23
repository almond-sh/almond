package almond.widgets

import almond.display.Html
import almond.widgets.TestCommHandler.Sent
import utest._

import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, LocalTime}

import scala.collection.mutable

object WidgetTests extends TestSuite {

  private final class Env {
    val comm    = new TestCommHandler
    val manager = new WidgetManager(comm, echo = true)
    manager.registerTargets()
    def apply[T](f: => T): T =
      WidgetManager.withCurrent(manager)(f)
  }

  private def withEnv[T](f: Env => T): T =
    f(new Env)

  private def str(s: String): JsonValue = JsonValue.Str(s)
  private def num(d: Double): JsonValue = JsonValue.Num(d)
  private def ref(w: Widget): JsonValue = JsonValue.Str("IPY_MODEL_" + w.id)
  private def update(fields: (String, JsonValue)*): JsonValue =
    JsonValue.Obj(
      "method"       -> str("update"),
      "state"        -> JsonValue.Obj(fields: _*),
      "buffer_paths" -> JsonValue.Arr()
    )

  val tests = Tests {

    test("json") {
      // characters that need escaping in JSON, and non-ASCII ones (e acute, line separator)
      val eAcute  = new String(Character.toChars(0xe9))
      val lineSep = new String(Character.toChars(0x2028))
      val input =
        "{\"a\":[1,2.5,-3e2,true,false,null],\"b\":\"q\\\"\\\\\\n\\t\\u00e9" + lineSep +
          "\",\"c\":{}}"
      val value = JsonValue.parse(input)
      val expected = JsonValue.Obj(
        "a" -> JsonValue.Arr(
          num(1),
          num(2.5),
          num(-300),
          JsonValue.Bool(true),
          JsonValue.Bool(false),
          JsonValue.Null
        ),
        "b" -> str("q\"\\\n\t" + eAcute + lineSep),
        "c" -> JsonValue.Obj.empty
      )
      assert(value == expected)
      assert(JsonValue.parse(value.render) == value)
      // the line separator is escaped, the e acute isn't
      val expectedRender =
        "{\"a\":[1,2.5,-300,true,false,null],\"b\":\"q\\\"\\\\\\n\\t" + eAcute +
          "\\u2028\",\"c\":{}}"
      assert(value.render == expectedRender)

      val invalid = Seq("{", "[1,]", "{\"a\" 1}", "tru", "\"abc", "1 2")
      for (s <- invalid) {
        val res = scala.util.Try(JsonValue.parse(s))
        assert(res.isFailure)
      }
    }

    test("buffers") {
      val a = Array[Byte](1, 2, 3)
      val b = Array[Byte](4, 5)
      val value = JsonValue.Obj(
        "x" -> JsonValue.Bytes(a),
        "y" -> JsonValue.Arr(num(1), JsonValue.Obj("z" -> JsonValue.Bytes(b)), JsonValue.Bytes(a))
      )
      val (stripped, paths, buffers) = internal.Buffers.split(value)
      assert(stripped == JsonValue.Obj("y" -> JsonValue.Arr(
        num(1),
        JsonValue.Obj.empty,
        JsonValue.Null
      )))
      assert(
        paths == JsonValue.Arr(
          JsonValue.Arr(str("x")),
          JsonValue.Arr(str("y"), num(1), str("z")),
          JsonValue.Arr(str("y"), num(2))
        )
      )
      assert(buffers.map(_.toSeq) == Seq(a.toSeq, b.toSeq, a.toSeq))
      val restored = internal.Buffers.put(stripped, paths.values, buffers)
      // keys of objects may be re-ordered
      val restoredY = restored.asInstanceOf[JsonValue.Obj].get("y")
      assert(restoredY == value.get("y"))
      assert(restored.asInstanceOf[JsonValue.Obj].get("x") == value.get("x"))
    }

    test("lazy open") {
      withEnv { env =>
        val slider = env(IntSlider(value = 3, min = 1, max = 5, description = "n"))
        slider.value() = 4
        assert(env.comm.take().isEmpty)

        val data   = slider.displayData()
        val opened = env.comm.take()
        // layout and style first, then the slider referencing them
        assert(opened.map(_.kind) == List("open", "open", "open"))
        assert(opened.last.id == slider.id)
        assert(opened.map(_.id).take(2).toSet == Set(slider.layout().id, slider.style().id))
        val state = opened.last.state
        assert(state.get("_model_name") == Some(str("IntSliderModel")))
        assert(state.get("_view_name") == Some(str("IntSliderView")))
        assert(state.get("_model_module") == Some(str("@jupyter-widgets/controls")))
        assert(state.get("value") == Some(num(4)))
        assert(state.get("min") == Some(num(1)))
        assert(state.get("description") == Some(str("n")))
        assert(state.get("layout") == Some(ref(slider.layout())))
        assert(state.get("tooltip") == Some(JsonValue.Null))
        assert(opened.last.metadata == JsonValue.Obj("version" -> str("2.1.0")))

        val viewData = JsonValue.parse(data.detailedData(Widget.viewMimeType) match {
          case almond.interpreter.api.DisplayData.Value.Json(s) => s
          case other                                            => sys.error(s"Unexpected $other")
        })
        assert(
          viewData == JsonValue.Obj(
            "version_major" -> num(2),
            "version_minor" -> num(0),
            "model_id"      -> str(slider.id)
          )
        )

        // already open
        slider.displayData()
        assert(env.comm.take().isEmpty)
      }
    }

    test("kernel-side updates") {
      withEnv { env =>
        val text = env(Text(value = "a")).open()
        env.comm.take()
        text.value() = "b"
        text.value() = "b" // no change, nothing sent
        val sent = env.comm.take()
        assert(sent.length == 1)
        assert(sent.head.method == Some("update"))
        assert(sent.head.state == JsonValue.Obj("value" -> str("b")))

        text.holdSync {
          text.value() = "c"
          text.placeholder() = "type here"
          text.value() = "d"
        }
        val held = env.comm.take()
        assert(held.length == 1)
        assert(
          held.head.state == JsonValue.Obj("value" -> str("d"), "placeholder" -> str("type here"))
        )
      }
    }

    test("front-end updates") {
      withEnv { env =>
        val slider = env(FloatSlider(value = 1.5)).open()
        env.comm.take()
        val seen = new mutable.ListBuffer[(Double, Double)]
        slider.value.observeChange((former, value) => seen += former -> value)

        env.comm.frontendMessage(slider.id, update("value" -> num(2.5)))
        assert(slider.value() == 2.5)
        assert(seen.toList == List(1.5 -> 2.5))
        val echo = env.comm.take()
        assert(echo.map(_.method) == List(Some("echo_update")))
        assert(echo.head.state == JsonValue.Obj("value" -> num(2.5)))

        // observers changing other properties
        slider.value.observe(v => slider.description() = s"at $v")
        env.comm.frontendMessage(slider.id, update("value" -> num(3)))
        assert(slider.description() == "at 3.0")
        val sent = env.comm.take()
        assert(sent.map(_.method) == List(Some("echo_update"), Some("update")))
        assert(sent(1).state == JsonValue.Obj("description" -> str("at 3.0")))

        env.comm.frontendMessage(slider.id, JsonValue.Obj("method" -> str("request_state")))
        val full = env.comm.take()
        assert(full.map(_.method) == List(Some("update")))
        assert(full.head.state.get("value") == Some(num(3)))
        assert(full.head.state.get("_model_name") == Some(str("FloatSliderModel")))
      }
    }

    test("no echo") {
      val comm    = new TestCommHandler
      val manager = new WidgetManager(comm, echo = false)
      manager.registerTargets()
      val box = WidgetManager.withCurrent(manager)(Checkbox()).open()
      comm.take()
      comm.frontendMessage(box.id, update("value" -> JsonValue.Bool(true)))
      assert(box.value())
      assert(comm.take().isEmpty)
    }

    test("button") {
      withEnv { env =>
        val button = env(Button(description = "Click me")).open()
        var clicks = 0
        val sub    = button.onClick(clicks += 1)
        val click = JsonValue.Obj(
          "method"  -> str("custom"),
          "content" -> JsonValue.Obj("event" -> str("click"))
        )
        env.comm.frontendMessage(button.id, click)
        env.comm.frontendMessage(button.id, click)
        assert(clicks == 2)
        sub.cancel()
        env.comm.frontendMessage(button.id, click)
        assert(clicks == 2)
      }
    }

    test("exceptions in callbacks") {
      withEnv { env =>
        val button = env(Button()).open()
        var clicks = 0
        button.onClick(throw new Exception("foo"))
        button.onClick(clicks += 1)
        val err      = new java.io.ByteArrayOutputStream
        val ps       = new java.io.PrintStream(err, true, "UTF-8")
        val previous = System.err
        try {
          System.setErr(ps)
          button.click()
        }
        finally System.setErr(previous)
        assert(clicks == 1)
        assert(err.toString("UTF-8").contains("java.lang.Exception: foo"))
      }
    }

    test("selection") {
      withEnv { env =>
        val dropdown = env(Dropdown(options = Seq(1, 2, 3), labels = Seq("one", "two", "three")))
        assert(dropdown.value() == Some(1))
        assert(dropdown.label() == Some("one"))
        assert(dropdown.index() == Some(0))
        dropdown.value() = Some(3)
        assert(dropdown.index() == Some(2))
        dropdown.open()
        val opened = env.comm.take().last
        assert(opened.state.get("index") == Some(num(2)))
        assert(
          opened.state.get("_options_labels") == Some(
            JsonValue.Arr(str("one"), str("two"), str("three"))
          )
        )

        val values = new mutable.ListBuffer[Option[Int]]
        dropdown.value.observe(values += _)
        env.comm.frontendMessage(dropdown.id, update("index" -> num(1)))
        assert(dropdown.value() == Some(2))
        assert(values.toList == List(Some(2)))

        val failure = scala.util.Try(dropdown.value() = Some(4))
        assert(failure.isFailure)

        env.comm.take()
        dropdown.setOptions(Seq(10, 20))
        assert(dropdown.labels == Seq("10", "20"))
        assert(dropdown.value() == Some(10))
        val sent = env.comm.take()
        assert(sent.length == 1)
        assert(sent.head.state.get("_options_labels") == Some(JsonValue.Arr(str("10"), str("20"))))
        assert(sent.head.state.get("index") == Some(num(0)))

        val none = env(Dropdown[String]())
        assert(none.value() == None)

        val multiple = env(SelectMultiple(options = Seq("a", "b", "c"), value = Seq("a", "c")))
        assert(multiple.index() == Seq(0, 2))
        assert(multiple.label() == Seq("a", "c"))

        val range =
          env(SelectionRangeSlider(options = Seq('a', 'b', 'c'), value = Some(('b', 'c'))))
        assert(range.index() == (1, 2))

        val slider = env(SelectionSlider(options = Seq("x", "y")))
        assert(slider.value() == "x")
      }
    }

    test("containers") {
      withEnv { env =>
        val a   = env(Label(value = "a"))
        val b   = env(HTML(value = "<b>b</b>"))
        val box = env(VBox(Seq(a, b)))
        box.open()
        val opened = env.comm.take()
        assert(opened.last.id == box.id)
        val openedIds = opened.map(_.id).toSet
        assert(openedIds.contains(a.id))
        assert(openedIds.contains(b.id))
        assert(opened.last.state.get("children") == Some(JsonValue.Arr(ref(a), ref(b))))
        env(assert(box.children() == Seq(a, b)))

        val formerChildren = new mutable.ListBuffer[Seq[Widget]]
        box.children.observeChange((former, _) => formerChildren += former)

        val c = env(Button())
        box.children() = box.children() :+ c
        val sent = env.comm.take()
        // the new child (and its layout and style) gets opened before being referenced
        assert(sent.map(_.kind) == List("open", "open", "open", "msg"))
        assert(sent(2).id == c.id)
        assert(sent.last.state.get("children") == Some(JsonValue.Arr(ref(a), ref(b), ref(c))))
        assert(c.isOpen)
        assert(formerChildren.toList == List(Seq(a, b)))

        // references to widgets that aren't open
        val d         = env(Label())
        val e         = env(Label())
        val unopened  = env(HBox(Seq(d)))
        val formerOne = new mutable.ListBuffer[Seq[Widget]]
        unopened.children.observeChange((former, _) => formerOne += former)
        unopened.children() = Seq(e)
        assert(unopened.children() == Seq(e))
        assert(formerOne.toList == List(Seq(d)))
        assert(!d.isOpen && !e.isOpen)
      }
    }

    test("binary values") {
      withEnv { env =>
        val bytes  = Array[Byte](1, 2, 3, 4)
        val image  = env(Image(value = bytes, format = "png")).open()
        val opened = env.comm.take().last
        assert(opened.id == image.id)
        assert(!opened.state.fields.contains("value"))
        assert(opened.bufferPaths == JsonValue.Arr(JsonValue.Arr(str("value"))))
        assert(opened.buffers.map(_.toSeq) == Seq(bytes.toSeq))

        image.value() = Array[Byte](5)
        val sent = env.comm.take()
        assert(sent.length == 1)
        assert(sent.head.bufferPaths == JsonValue.Arr(JsonValue.Arr(str("value"))))
        assert(sent.head.buffers.map(_.toSeq) == Seq(Seq[Byte](5)))

        val url = env(Image.fromUrl("https://example.com/a.png"))
        assert(url.format() == "url")
        assert(new String(url.value(), StandardCharsets.UTF_8) == "https://example.com/a.png")
      }
    }

    test("file upload") {
      withEnv { env =>
        val upload = env(FileUpload(multiple = true)).open()
        env.comm.take()
        val files = new mutable.ListBuffer[Seq[UploadedFile]]
        upload.value.observe(files += _)
        val content = "hello".getBytes(StandardCharsets.UTF_8)
        val msg = JsonValue.Obj(
          "method" -> str("update"),
          "state" -> JsonValue.Obj(
            "value" -> JsonValue.Arr(
              JsonValue.Obj(
                "name"          -> str("hello.txt"),
                "type"          -> str("text/plain"),
                "size"          -> num(5),
                "last_modified" -> num(1700000000000.0)
              )
            )
          ),
          "buffer_paths" -> JsonValue.Arr(JsonValue.Arr(str("value"), num(0), str("content")))
        )
        env.comm.frontendMessage(upload.id, msg, Seq(content))
        val value = upload.value()
        assert(value.length == 1)
        assert(value.head.name == "hello.txt")
        assert(value.head.mimeType == "text/plain")
        assert(value.head.size == 5L)
        assert(new String(value.head.content, StandardCharsets.UTF_8) == "hello")
        assert(value.head.lastModified == Instant.ofEpochMilli(1700000000000L))
        assert(files.length == 1)
        // file contents aren't echoed
        assert(env.comm.take().isEmpty)
      }
    }

    test("output") {
      withEnv { env =>
        val out = env(Output()).open()
        env.comm.take()
        out.capture {
          println("a")
          print("b")
          Console.err.println("c")
        }
        val outputs = out.outputs()
        assert(
          outputs == Seq(
            JsonValue.Obj(
              "output_type" -> str("stream"),
              "name"        -> str("stdout"),
              "text"        -> str("a" + System.lineSeparator() + "b")
            ),
            JsonValue.Obj(
              "output_type" -> str("stream"),
              "name"        -> str("stderr"),
              "text"        -> str("c" + System.lineSeparator())
            )
          )
        )
        assert(env.comm.take().forall(_.method == Some("update")))

        out.clearOutput()
        out.capture {
          Html("<b>hi</b>").display()(out.outputHandler)
          throw new Exception("boom")
        }
        val outputs0 = out.outputs()
        assert(outputs0.length == 2)
        val display = outputs0.head.asInstanceOf[JsonValue.Obj]
        assert(display.get("output_type") == Some(str("display_data")))
        assert(display.get("data") == Some(JsonValue.Obj("text/html" -> str("<b>hi</b>"))))
        val error = outputs0(1).asInstanceOf[JsonValue.Obj]
        assert(error.get("output_type") == Some(str("error")))
        assert(error.get("evalue") == Some(str("boom")))
      }
    }

    test("links") {
      withEnv { env =>
        val a        = env(IntSlider())
        val b        = env(IntText())
        val jsLink   = env(jslink(a.value, b.value))
        val sent     = env.comm.take()
        val linkOpen = sent.find(_.id == jsLink.id).get
        assert(linkOpen.state.get("source") == Some(JsonValue.Arr(ref(a), str("value"))))
        assert(linkOpen.state.get("target") == Some(JsonValue.Arr(ref(b), str("value"))))
        assert(a.isOpen && b.isOpen)

        val c   = env(IntSlider(value = 2))
        val d   = env(IntSlider())
        val sub = link(c.value, d.value)
        assert(d.value() == 2)
        d.value() = 5
        assert(c.value() == 5)
        sub.cancel()
        d.value() = 6
        assert(c.value() == 5)

        val e = env(FloatText())
        dlink(c.value, e.value, (n: Int) => n * 1.5)
        assert(e.value() == 7.5)
        c.value() = 2
        assert(e.value() == 3.0)
      }
    }

    test("interact") {
      withEnv { env =>
        val slider = env(IntSlider(value = 3))
        val box    = env(interact(slider)(n => n * 2))
        val out    = box.children().last.asInstanceOf[Output]
        def text() =
          out.outputs().collect {
            case obj: JsonValue.Obj => obj.get("text")
          }
        assert(text() == Seq(Some(str("6" + System.lineSeparator()))))
        slider.value() = 4
        assert(text() == Seq(Some(str("8" + System.lineSeparator()))))
      }
    }

    test("dates") {
      withEnv { env =>
        val picker = env(DatePicker(value = Some(LocalDate.of(2026, 10, 10)))).open()
        val state  = env.comm.take().last.state
        assert(
          state.get("value") == Some(
            JsonValue.Obj("year" -> num(2026), "month" -> num(9), "date" -> num(10))
          )
        )
        env.comm.frontendMessage(
          picker.id,
          update("value" -> JsonValue.Obj(
            "year"  -> num(2020),
            "month" -> num(0),
            "date"  -> num(31)
          ))
        )
        assert(picker.value() == Some(LocalDate.of(2020, 1, 31)))

        val time = env(Time(value = Some(LocalTime.of(13, 45, 10, 500000000))))
        assert(
          time.stateValue("value") == JsonValue.Obj(
            "hours"        -> num(13),
            "minutes"      -> num(45),
            "seconds"      -> num(10),
            "milliseconds" -> num(500)
          )
        )
        assert(time.step() == Some(60.0))
        time.step() = None
        assert(time.stateValue("step") == str("any"))
      }
    }

    test("close") {
      withEnv { env =>
        val w = env(Label()).open()
        env.comm.take()
        w.close()
        assert(env.comm.take().map(_.kind) == List("close"))
        assert(env.manager.widget(w.id).isEmpty)
        w.value() = "ignored"
        assert(env.comm.take().isEmpty)
      }
    }

    test("control comm") {
      withEnv { env =>
        val image = env(Image(value = Array[Byte](1, 2))).open()
        val label = env(Label(value = "l")).open()
        env.comm.take()
        env.comm.frontendOpen(WidgetManager.controlTargetName, "control", JsonValue.Obj.empty)
        env.comm.frontendMessage("control", JsonValue.Obj("method" -> str("request_states")))
        val reply = env.comm.take()
        assert(reply.length == 1)
        assert(reply.head.id == "control")
        assert(reply.head.method == Some("update_states"))
        val states = reply.head.data.asInstanceOf[JsonValue.Obj].get("states").get
          .asInstanceOf[JsonValue.Obj]
        assert(states.fields.contains(label.id))
        assert(states.fields.contains(image.id))
        val labelState = states.get(label.id).get.asInstanceOf[JsonValue.Obj]
        assert(labelState.get("model_name") == Some(str("LabelModel")))
        assert(
          labelState.get("state").get.asInstanceOf[JsonValue.Obj].get("value") == Some(str("l"))
        )
        assert(
          reply.head.bufferPaths == JsonValue.Arr(JsonValue.Arr(
            str(image.id),
            str("state"),
            str("value")
          ))
        )
        assert(reply.head.buffers.map(_.toSeq) == Seq(Seq[Byte](1, 2)))
      }
    }

    test("widgets created by the front-end") {
      withEnv { env =>
        val state = JsonValue.Obj(
          "_model_module"         -> str("@jupyter-widgets/controls"),
          "_model_module_version" -> str("2.0.0"),
          "_model_name"           -> str("ControllerButtonModel"),
          "_view_module"          -> str("@jupyter-widgets/controls"),
          "_view_module_version"  -> str("2.0.0"),
          "_view_name"            -> str("ControllerButtonView"),
          "pressed"               -> JsonValue.Bool(true),
          "value"                 -> num(0.5)
        )
        env.comm.frontendOpen(
          WidgetManager.targetName,
          "frontend-id",
          JsonValue.Obj("state" -> state, "buffer_paths" -> JsonValue.Arr())
        )
        val w = env.manager.widget("frontend-id")
        assert(w.exists(_.isInstanceOf[ControllerButton]))
        val button = w.get.asInstanceOf[ControllerButton]
        assert(button.pressed())
        assert(button.value() == 0.5)
        env.comm.frontendMessage("frontend-id", update("pressed" -> JsonValue.Bool(false)))
        assert(!button.pressed())

        env.comm.frontendOpen(
          WidgetManager.targetName,
          "other-id",
          JsonValue.Obj(
            "state" -> JsonValue.Obj(
              "_model_module" -> str("custom"),
              "_model_name"   -> str("CustomModel"),
              "count"         -> num(2)
            )
          )
        )
        val generic = env.manager.widget("other-id").get.asInstanceOf[GenericWidget]
        assert(generic.modelName == "CustomModel")
        assert(generic.field[Int]("count").apply() == 2)
      }
    }

    test("anywidget") {
      withEnv { env =>
        class Counter extends AnyWidget("export default { render() {} }") {
          val count = prop("count", 0)
        }
        val counter = env(new Counter)
        counter.count() = 2
        counter.open()
        val state = env.comm.take().last.state
        assert(state.get("_model_name") == Some(str("AnyModel")))
        assert(state.get("_model_module") == Some(str("anywidget")))
        assert(state.get("_esm") == Some(str("export default { render() {} }")))
        assert(state.get("count") == Some(num(2)))
        assert(!state.fields.contains("_css"))
      }
    }

    test("all widgets") {
      withEnv { env =>
        // creating and opening all the generated widgets, checking their state is valid JSON
        val widgets = env {
          Seq[Widget](
            Accordion(),
            Audio(),
            BoundedFloatText(),
            BoundedIntText(),
            Box(),
            Button(),
            Checkbox(),
            ColorPicker(),
            ColorsInput(),
            Combobox(),
            Controller(),
            DatePicker(),
            Datetime(value = Some(Instant.EPOCH)),
            Dropdown(options = Seq("a")),
            FileUpload(),
            FloatLogSlider(),
            FloatProgress(),
            FloatRangeSlider(),
            FloatSlider(),
            FloatText(),
            FloatsInput(),
            GridBox(),
            HBox(),
            HTML(),
            HTMLMath(),
            Image(),
            IntProgress(),
            IntRangeSlider(),
            IntSlider(),
            IntText(),
            IntsInput(),
            Label(),
            NaiveDatetime(),
            Output(),
            Password(),
            Play(),
            RadioButtons(options = Seq(1, 2)),
            Select(options = Seq(1, 2)),
            SelectMultiple(options = Seq(1, 2)),
            SelectionRangeSlider(options = Seq(1, 2)),
            SelectionSlider(options = Seq(1, 2)),
            Stack(),
            Tab(),
            TagsInput(),
            Text(),
            Textarea(),
            Time(),
            ToggleButton(),
            ToggleButtons(options = Seq(1, 2)),
            VBox(),
            Valid(),
            Video()
          )
        }
        widgets.foreach(_.open())
        val sent = env.comm.take()
        assert(sent.forall(_.kind == "open"))
        for (msg <- sent)
          assert(JsonValue.parse(msg.data.render) == msg.data)
        val ids = sent.map(_.id).toSet
        assert(widgets.forall(w => ids.contains(w.id)))
      }
    }
  }
}
