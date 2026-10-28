package almondbuild

/** Generates the widget classes of the `widgets` module, from the model schema of ipywidgets
  *
  * The schema (`jupyterwidgetmodels.latest.json` in the ipywidgets repository) lists the models of
  * the core widgets, along with their attributes, their types, and their default values. We
  * generate a class per model, with a property per attribute, along with a companion object with an
  * `apply` method accepting a value for each attribute.
  *
  * Some widgets get extra behavior from hand-written traits of the `widgets` module (see
  * [[extraParents]] and [[selectionKinds]]).
  */
object WidgetsGenerator {

  private final case class Attribute(
    name: String,
    scalaName: String,
    tpe: String,
    codec: String,
    // the initial value of the property, in the class
    classDefault: String,
    // the default value of the apply parameter, if any
    applyDefault: Option[String],
    help: String,
    echo: Boolean
  )

  /** How selection widgets (whose options are only known in the kernel) select values */
  private final case class SelectionKind(
    parent: String,
    valueType: String,
    valueDefault: String
  )

  private val selectionKinds = Map(
    "DropdownModel"             -> SelectionKind("SingleSelectionWidget", "Option[T]", "None"),
    "RadioButtonsModel"         -> SelectionKind("SingleSelectionWidget", "Option[T]", "None"),
    "SelectModel"               -> SelectionKind("SingleSelectionWidget", "Option[T]", "None"),
    "ToggleButtonsModel"        -> SelectionKind("SingleSelectionWidget", "Option[T]", "None"),
    "SelectionSliderModel"      -> SelectionKind("SliderSelectionWidget", "Option[T]", "None"),
    "SelectMultipleModel"       -> SelectionKind("MultipleSelectionWidget", "Seq[T]", "Nil"),
    "SelectionRangeSliderModel" -> SelectionKind("RangeSelectionWidget", "Option[(T, T)]", "None")
  )

  /** Hand-written traits some widgets extend */
  private val extraParents = Map(
    "ButtonModel" -> Seq("ButtonExtras"),
    "OutputModel" -> Seq("OutputExtras")
  )

  /** Widgets whose companion gets helpers to create them from files or URLs */
  private val mediaModels = Set("ImageModel", "VideoModel", "AudioModel")

  /** Models we don't generate classes for */
  private val skippedModels = Set(
    // abstract base model, only listed for its attributes
    "DOMWidgetModel"
  )

  /** Attributes that don't appear in the parameters of the apply methods */
  private def skippedApplyParams(model: String, attr: String): Boolean =
    attr == "_options_labels" ||
    (selectionKinds.contains(model) && attr == "index") ||
    (model == "OutputModel" && attr == "msg_id")

  /** Attributes not exposed publicly */
  private val protectedAttributes = Set("_options_labels")

  private val identityAttributes = Set(
    "_model_module",
    "_model_module_version",
    "_model_name",
    "_view_module",
    "_view_module_version",
    "_view_name"
  )

  /** Classes of widgets referenced in the schema, when they don't follow the general rule */
  private def referenceClass(model: String, name: String): String =
    (model, name) match {
      case ("ControllerModel", "Axis")   => "ControllerAxis"
      case ("ControllerModel", "Button") => "ControllerButton"
      case _                             => name
    }

  private def className(model: String): String =
    model.stripSuffix("Model")

  private def camelCase(name: String): String = {
    val parts = name.stripPrefix("_").split('_').toSeq.filter(_.nonEmpty)
    (parts.take(1) ++ parts.drop(1).map(_.capitalize)).mkString
  }

  private def stringLiteral(s: String): String = {
    val b = new StringBuilder("\"")
    for (c <- s)
      c match {
        case '"'                     => b.append("\\\"")
        case '\\'                    => b.append("\\\\")
        case '\n'                    => b.append("\\n")
        case c if c < ' ' || c > '~' => b.append("\\u%04x".format(c.toInt))
        case c                       => b.append(c)
      }
    b.append('"')
    b.result()
  }

  private def doubleLiteral(value: ujson.Value): String = {
    val d = value.num
    if (d == math.rint(d)) s"${d.toLong}.0" else d.toString
  }

  private def intLiteral(value: ujson.Value): String =
    value.num.toLong.toString

  private def attribute(model: String, attr: ujson.Obj): Attribute = {
    val name = attr("name").str
    val help = {
      val base = attr.value.get("help").map(_.str).getOrElse("")
      attr.value.get("enum").map(_.arr.map(v => s"`\"${v.str}\"`")) match {
        case Some(values) if values.nonEmpty =>
          val sep = if (base.isEmpty || base.endsWith(".")) "" else "."
          s"$base$sep One of ${values.mkString(", ")}.".trim
        case _ => base
      }
    }
    val default   = attr.value.getOrElse("default", ujson.Null)
    val allowNone = attr.value.get("allow_none").exists(_.bool)

    def simple(tpe: String, codec: String, default: String) =
      Attribute(name, camelCase(name), tpe, codec, default, Some(default), help, echo = true)

    def stringSeq(values: ujson.Value) =
      if (values.arr.isEmpty) "Nil"
      else values.arr.map(v => stringLiteral(v.str)).mkString("Seq(", ", ", ")")

    (model, name) match {
      case ("IntRangeSliderModel", "value") =>
        val Seq(a, b) = default.arr.toSeq
        simple(
          "(Int, Int)",
          "Codec.tuple2(Codec.int, Codec.int)",
          s"(${intLiteral(a)}, ${intLiteral(b)})"
        )
      case ("FloatRangeSliderModel", "value") =>
        val Seq(a, b) = default.arr.toSeq
        simple(
          "(Double, Double)",
          "Codec.tuple2(Codec.double, Codec.double)",
          s"(${doubleLiteral(a)}, ${doubleLiteral(b)})"
        )
      case ("SelectionRangeSliderModel", "index") =>
        val Seq(a, b) = default.arr.toSeq
        simple(
          "(Int, Int)",
          "Codec.tuple2(Codec.int, Codec.int)",
          s"(${intLiteral(a)}, ${intLiteral(b)})"
        )
      case ("LinkModel" | "DirectionalLinkModel", "source" | "target") =>
        Attribute(
          name,
          name,
          "(Widget, String)",
          "Codec.tuple2(Codec.widget[Widget], Codec.string)",
          "(null, \"\")",
          None,
          help,
          echo = true
        )
      case ("ColorsInputModel" | "TagsInputModel", "value" | "allowed_tags") =>
        simple("Seq[String]", "Codec.seq(Codec.string)", stringSeq(default))
      case ("FloatsInputModel", "value" | "allowed_tags") =>
        simple("Seq[Double]", "Codec.seq(Codec.double)", "Nil")
      case ("IntsInputModel", "value" | "allowed_tags") =>
        simple("Seq[Int]", "Codec.seq(Codec.int)", "Nil")
      case ("FileUploadModel", "value") =>
        // like in ipywidgets, the content of uploaded files isn't echoed to front-ends
        simple("Seq[UploadedFile]", "Codec.seq(UploadedFile.codec)", "Nil").copy(echo = false)
      case _ =>
        attr("type") match {
          case ujson.Arr(types) =>
            val unionTypes = types.map(_.str).toSet
            if (unionTypes == Set("int", "string"))
              simple("Option[Int]", "Codec.orAny(Codec.int)", s"Some(${intLiteral(default)})")
            else if (unionTypes == Set("float", "string"))
              simple(
                "Option[Double]",
                "Codec.orAny(Codec.double)",
                s"Some(${doubleLiteral(default)})"
              )
            else
              sys.error(s"Unsupported union type for $model.$name: $types")
          case ujson.Str(tpe) =>
            tpe match {
              case "string" =>
                if (allowNone)
                  simple(
                    "String",
                    "Codec.optionalString",
                    stringLiteral(default.strOpt.getOrElse(""))
                  )
                else
                  simple("String", "Codec.string", stringLiteral(default.str))
              case "bool" =>
                if (allowNone)
                  simple(
                    "Option[Boolean]",
                    "Codec.option(Codec.boolean)",
                    default.boolOpt.fold("None")(b => s"Some($b)")
                  )
                else
                  simple("Boolean", "Codec.boolean", default.bool.toString)
              case "int" =>
                if (allowNone)
                  simple(
                    "Option[Int]",
                    "Codec.option(Codec.int)",
                    if (default.isNull) "None" else s"Some(${intLiteral(default)})"
                  )
                else
                  simple("Int", "Codec.int", intLiteral(default))
              case "float" =>
                if (allowNone)
                  simple(
                    "Option[Double]",
                    "Codec.option(Codec.double)",
                    if (default.isNull) "None" else s"Some(${doubleLiteral(default)})"
                  )
                else
                  simple("Double", "Codec.double", doubleLiteral(default))
              case "bytes" =>
                simple("Array[Byte]", "Codec.bytes", "Array.emptyByteArray")
              case "Date" =>
                simple("Option[java.time.LocalDate]", "Codec.option(Codec.localDate)", "None")
              case "Time" =>
                simple("Option[java.time.LocalTime]", "Codec.option(Codec.localTime)", "None")
              case "Datetime" =>
                if (model == "NaiveDatetimeModel")
                  simple(
                    "Option[java.time.LocalDateTime]",
                    "Codec.option(Codec.localDateTime)",
                    "None"
                  )
                else
                  simple("Option[java.time.Instant]", "Codec.option(Codec.instant)", "None")
              case "reference" =>
                val cls = referenceClass(model, attr("widget").str)
                Attribute(
                  name,
                  camelCase(name),
                  cls,
                  s"Codec.widget[$cls]",
                  "null",
                  Some(s"$cls()"),
                  help,
                  echo = true
                )
              case "array" =>
                val items = attr.value.get("items").getOrElse {
                  sys.error(s"Untyped array attribute $model.$name")
                }
                items("type").str match {
                  case "string" =>
                    simple("Seq[String]", "Codec.seq(Codec.string)", stringSeq(default))
                  case "int" =>
                    simple(
                      "Seq[Int]",
                      "Codec.seq(Codec.int)",
                      if (default.arr.isEmpty) "Nil"
                      else default.arr.map(intLiteral).mkString("Seq(", ", ", ")")
                    )
                  case "object" =>
                    simple("Seq[ujson.Value]", "Codec.seq(Codec.json)", "Nil")
                  case "reference" =>
                    val cls = referenceClass(model, items("widget").str)
                    simple(s"Seq[$cls]", s"Codec.seq(Codec.widget[$cls])", "Nil")
                  case other =>
                    sys.error(s"Unsupported array item type for $model.$name: $other")
                }
              case other =>
                sys.error(s"Unsupported type for $model.$name: $other")
            }
          case other =>
            sys.error(s"Unsupported type for $model.$name: $other")
        }
    }
  }

  private def docLines(text: String): Seq[String] =
    text.replace("*/", "* /").linesIterator.toSeq

  private final case class Model(
    name: String,
    className: String,
    identity: Map[String, ujson.Value],
    attributes: Seq[Attribute]
  ) {
    def isDom: Boolean     = attributes.exists(_.name == "layout")
    def typeParams: String = if (selectionKinds.contains(name)) "[T]" else ""
    def module: String     = identity("_model_module").str
  }

  private def model(json: ujson.Value): Model = {
    val name  = json("model")("name").str
    val attrs = json("attributes").arr.map(_.obj)
    val identity = attrs
      .filter(a => identityAttributes(a("name").str))
      .map(a => a("name").str -> a.value.getOrElse("default", ujson.Null))
      .toMap
    val attributes = attrs
      .filter(a => !identityAttributes(a("name").str))
      .map(a => attribute(name, a))
      .toSeq
    Model(name, className(name), identity, attributes)
  }

  private def parents(model: Model): Seq[String] = {
    val base = if (model.isDom) "DOMWidget" else "Widget"
    val value = model.attributes.find(_.name == "value") match {
      case Some(attr) if !selectionKinds.contains(model.name) => Seq(s"ValueWidget[${attr.tpe}]")
      case _                                                  => Nil
    }
    val selection = selectionKinds.get(model.name).map(k => s"${k.parent}[T]").toSeq
    val description =
      if (model.attributes.exists(a => a.name == "description" && a.tpe == "String"))
        Seq("DescriptionWidget")
      else Nil
    val container =
      if (model.attributes.exists(a => a.name == "children" && a.tpe == "Seq[Widget]"))
        Seq("ContainerWidget")
      else Nil
    Seq(base) ++ value ++ selection ++ description ++ container ++
      extraParents.getOrElse(model.name, Nil)
  }

  // Order of the parameters of the apply methods: these first, then the others in the order of
  // the schema (alphabetical), and the layout and style last
  private val firstParams = Seq("children", "value", "description")
  private val lastParams  = Seq("layout", "style")

  private def classSource(model: Model): String = {
    val b                          = new StringBuilder
    def line(s: String = ""): Unit = b.append(s).append('\n')
    val cls                        = model.className
    val tpe                        = cls + model.typeParams
    val selectionOpt               = selectionKinds.get(model.name)

    def identityValue(key: String): String =
      model.identity.get(key) match {
        case Some(ujson.Str(s)) => stringLiteral(s)
        case other              => sys.error(s"Unexpected value for ${model.name}.$key: $other")
      }

    line("// Generated from the model schema of ipywidgets by the build (see WidgetsGenerator)")
    line("// Do not edit.")
    line()
    line("package almond.widgets")
    line()
    line(s"/** The `${model.name}` widget model of `${model.module}`")
    line("  *")
    line(s"  * Create instances with `$cls(…)`, see [[$cls$$.apply]].")
    line("  */")
    line(
      s"final class $tpe private[widgets] () extends ${parents(model).mkString(" with ")} {"
    )
    line()
    line(s"  def modelName: String          = ${identityValue("_model_name")}")
    line(s"  def modelModule: String        = ${identityValue("_model_module")}")
    line(s"  def modelModuleVersion: String = ${identityValue("_model_module_version")}")
    val viewName = model.identity.get("_view_name") match {
      case Some(ujson.Str(s)) => s"Some(${stringLiteral(s)})"
      case _                  => "None"
    }
    line(s"  def viewName: Option[String]   = $viewName")
    line(s"  def viewModule: String         = ${identityValue("_view_module")}")
    line(s"  def viewModuleVersion: String  = ${identityValue("_view_module_version")}")

    for (attr <- model.attributes) {
      line()
      val help = docLines(attr.help)
      if (help.length == 1)
        line(s"  /** ${help.head} */")
      else if (help.nonEmpty) {
        line(s"  /** ${help.head}")
        for (l <- help.tail)
          line(s"    * $l")
        line("    */")
      }
      val modifier = if (protectedAttributes(attr.name)) "protected " else ""
      val echoArg  = if (attr.echo) "" else ", echo = false"
      line(
        s"  ${modifier}val ${attr.scalaName}: StateProp[${attr.tpe}] =\n" +
          s"    prop[${attr.tpe}](${stringLiteral(attr.name)}, ${attr.classDefault}$echoArg)(${attr.codec})"
      )
    }
    line("}")
    line()

    // companion

    val applyAttrs = {
      val attrs = model.attributes.filter(a => !skippedApplyParams(model.name, a.name))
      val first = firstParams.flatMap(n => attrs.find(_.name == n))
      val last  = lastParams.flatMap(n => attrs.find(_.name == n))
      first ++ attrs.filter(a => !first.contains(a) && !last.contains(a)) ++ last
    }

    val companionParents =
      if (mediaModels(model.name)) s" extends MediaCompanion[$cls]"
      else ""
    line(s"object $cls$companionParents {")
    line()
    line(s"  /** Creates a new `$cls` widget")
    line("    *")
    line(
      "    * The widget is opened (created in the front-end) when it's displayed, or when a widget"
    )
    line("    * referencing it is opened.")
    for (kind <- selectionOpt) {
      line("    *")
      line("    * @param options")
      line("    *   values users can select")
      line("    * @param labels")
      line(
        "    *   labels of the options, as shown to users (defaults to the `toString` of options)"
      )
      line("    * @param value")
      line("    *   initially selected values (the first option is selected by default, if any)")
    }
    for (attr <- applyAttrs) {
      val help = docLines(attr.help)
      if (help.nonEmpty) {
        line(s"    * @param ${attr.scalaName}")
        for (l <- help)
          line(s"    *   $l")
      }
    }
    line("    */")
    val params = selectionOpt.toSeq.flatMap { kind =>
      Seq(
        "options: Seq[T] = Nil",
        "labels: Seq[String] = Nil",
        s"value: ${kind.valueType} = ${kind.valueDefault}"
      )
    } ++ applyAttrs.map { attr =>
      s"${attr.scalaName}: ${attr.tpe}" + attr.applyDefault.fold("")(" = " + _)
    }
    line(s"  def apply${model.typeParams}(")
    line(params.map("    " + _).mkString(",\n"))
    line(s"  ): $tpe = {")
    line(s"    val widget = new $tpe")
    for (attr <- applyAttrs)
      line(s"    widget.${attr.scalaName}() = ${attr.scalaName}")
    if (selectionOpt.nonEmpty)
      line("    widget.initSelection(options, labels, value)")
    line("    widget")
    line("  }")
    if (mediaModels(model.name)) {
      line()
      line(s"  protected def fromBytes(value: Array[Byte], format: String): $cls =")
      line("    apply(value = value, format = format)")
    }
    line("}")

    b.result()
  }

  private def registrySource(models: Seq[Model]): String = {
    val b                          = new StringBuilder
    def line(s: String = ""): Unit = b.append(s).append('\n')
    line("// Generated from the model schema of ipywidgets by the build (see WidgetsGenerator)")
    line("// Do not edit.")
    line()
    line("package almond.widgets.internal")
    line()
    line("import almond.widgets._")
    line()
    line(
      "/** Creates widgets from the name of their model, for widgets created by the front-end */"
    )
    line("private[widgets] object Registry {")
    line("  def create(module: String, modelName: String): Option[Widget] =")
    line("    (module, modelName) match {")
    for (model <- models) {
      val tpe = model.className + (if (model.typeParams.isEmpty) "" else "[Any]")
      line(
        s"      case (${stringLiteral(model.module)}, ${stringLiteral(model.name)}) => Some(new $tpe)"
      )
    }
    line("      case _ => None")
    line("    }")
    line("}")
    b.result()
  }

  /** Generates the sources of the widget classes from `schema`
    *
    * @return
    *   paths of the files to write, relative to a source directory, along with their content
    */
  def generate(schema: ujson.Value): Seq[(os.SubPath, String)] = {
    val models = schema.arr.toSeq
      .map(model)
      .filter(m => !skippedModels(m.name))
      .sortBy(_.name)
    val classes = models.map { model =>
      os.sub / "almond" / "widgets" / s"${model.className}.scala" -> classSource(model)
    }
    val registry =
      os.sub / "almond" / "widgets" / "internal" / "Registry.scala" -> registrySource(models)
    classes :+ registry
  }
}
