package almond.proxytests

import java.net.{InetSocketAddress, Socket}
import java.security.MessageDigest
import java.util.UUID

import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.control.NonFatal

/** The bits of Docker the tests need: networks that can't reach the outside, servers bridging them
  * with the outside, and images built from the files under `docker/`.
  */
object Docker {

  /** Whether a Docker daemon can be reached from here */
  lazy val available: Boolean =
    try
      os.proc("docker", "version")
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode == 0
    catch {
      case NonFatal(_) => false
    }

  /** Runs `docker` with the passed arguments, and returns its output. Its stderr goes to ours. */
  private def docker(args: os.Shellable*): String =
    os.proc("docker", args).call(stdin = os.Inherit, stderr = os.Inherit).out.trim()

  /** Two networks the tests run their containers on
    *
    * @param internal
    *   a network with no route to the outside: the containers Almond runs in are only on this one
    * @param external
    *   a regular network, that the proxy or mirror is on too, for it to reach the outside
    */
  final case class Networks(internal: String, external: String)

  def withNetworks[T](f: Networks => T): T = {
    val prefix   = "almond-proxy-tests-" + UUID.randomUUID().toString.take(8)
    val networks = Networks(s"$prefix-internal", s"$prefix-external")
    docker("network", "create", "--internal", networks.internal)
    try {
      docker("network", "create", networks.external)
      try f(networks)
      finally docker("network", "rm", networks.external)
    }
    finally
      docker("network", "rm", networks.internal)
  }

  final case class Container(id: String) {

    /** IP address of this container on the passed network */
    def ipOn(network: String): String = {
      val ip = docker(
        "inspect",
        "--format",
        s"{{ (index .NetworkSettings.Networks \"$network\").IPAddress }}",
        id
      )
      if (ip.isEmpty)
        sys.error(s"Container $id has no address on network $network")
      ip
    }

    /** Port of the host the passed port of this container is published on */
    def publishedPort(port: Int): Int = {
      val out = docker("port", id, port.toString)
      // one line per address family, like "0.0.0.0:32768" and ":::32768"
      out.linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_.split(':').last.toInt)
        .toSeq
        .headOption
        .getOrElse(sys.error(s"Port $port of container $id is not published (got '$out')"))
    }

    def remove(): Unit =
      os.proc("docker", "rm", "-f", id)
        .call(check = false, stdin = os.Inherit, stdout = os.Pipe, stderr = os.Inherit)
  }

  /** Starts a server container, and waits for it to listen on its port `port`.
    *
    * The container is on both networks: it is started on the external one, and connected to the
    * internal one afterwards. `port` is also published on the host, on a random port, for us to
    * check that the server is up - the host can't reach the container networks on every platform.
    */
  def startServer(
    image: String,
    networks: Networks,
    port: Int,
    extraArgs: os.Shellable*
  ): Container = {
    val id = docker(
      "run",
      "-d",
      "--rm",
      "--network",
      networks.external,
      "-p",
      s"127.0.0.1::$port",
      extraArgs,
      image
    )
    val container = Container(id)
    try {
      docker("network", "connect", networks.internal, id)
      waitForPort(container.publishedPort(port), s"$image container $id", 2.minutes)
    }
    catch {
      case NonFatal(e) =>
        container.remove()
        throw e
    }
    container
  }

  private def waitForPort(port: Int, what: String, timeout: Duration): Unit = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def listening(): Boolean = {
      val socket = new Socket
      try {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 1000)
        true
      }
      catch {
        case _: java.io.IOException => false
      }
      finally socket.close()
    }
    var up = listening()
    while (!up && System.currentTimeMillis() < deadline) {
      Thread.sleep(500L)
      up = listening()
    }
    if (!up)
      sys.error(s"Timeout waiting for $what to listen on port $port")
    // The socket above got accepted, but the server may not process requests yet
    Thread.sleep(2000L)
  }

  def withServer[T](image: String, networks: Networks, port: Int, extraArgs: os.Shellable*)(
    f: Container => T
  ): T = {
    val container = startServer(image, networks, port, extraArgs*)
    try f(container)
    finally container.remove()
  }

  /** Builds the image of the Dockerfile under `contextDir` if needed, and returns its tag.
    *
    * The tag includes a hash of the build context and of the build arguments, so that the image is
    * rebuilt when any of them changes, and re-used as is otherwise.
    */
  def ensureImage(name: String, contextDir: os.Path, buildArgs: Map[String, String]): String = {
    val md = MessageDigest.getInstance("SHA-1")
    for (f <- os.walk(contextDir).filter(os.isFile(_)).sortBy(_.toString)) {
      md.update(f.relativeTo(contextDir).toString.getBytes("UTF-8"))
      md.update(os.read.bytes(f))
    }
    for ((k, v) <- buildArgs.toSeq.sorted)
      md.update(s"$k=$v".getBytes("UTF-8"))
    val hash = md.digest().map(b => f"$b%02x").mkString.take(12)
    val tag  = s"$name:$hash"
    val exists = os.proc("docker", "image", "inspect", tag)
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      .exitCode == 0
    if (!exists) {
      System.err.println(s"Building image $tag from $contextDir")
      os.proc(
        "docker",
        "build",
        "-t",
        tag,
        buildArgs.toSeq.sorted.flatMap { case (k, v) => Seq("--build-arg", s"$k=$v") },
        contextDir
      ).call(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
    }
    tag
  }

  /** Runs a container on the internal network, until its command exits.
    *
    * Its output goes to ours as it comes, and is returned along with its exit code.
    */
  def runIsolated(
    networks: Networks,
    dockerArgs: Seq[os.Shellable],
    image: String,
    command: Seq[os.Shellable]
  ): (Int, String) = {
    val output = new StringBuilder
    val res =
      os.proc("docker", "run", "--rm", "--network", networks.internal, dockerArgs, image, command)
        .call(
          check = false,
          stdin = os.Inherit,
          stdout = os.ProcessOutput.Readlines { line =>
            System.err.println(line)
            output.append(line).append(System.lineSeparator())
          },
          mergeErrIntoOut = true
        )
    (res.exitCode, output.result())
  }
}
