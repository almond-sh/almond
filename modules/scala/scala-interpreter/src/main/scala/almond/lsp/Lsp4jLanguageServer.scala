package almond.lsp

import almond.interpreter.lsp.{LanguageServer => KernelLanguageServer}
import almond.logger.LoggerContext
import almond.protocol.RawJson
import org.eclipse.lsp4j.jsonrpc.json.{JsonRpcMethod, MessageJsonHandler}
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.services.{JsonDelegate, ServiceEndpoints}
import org.eclipse.lsp4j.jsonrpc.{MessageConsumer, MessageIssueException, RemoteEndpoint}
import org.eclipse.lsp4j.services.{LanguageClient, LanguageClientAware, LanguageServer}

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

import scala.util.control.NonFatal

/** Runs lsp4j language servers on kernel LSP sessions
  *
  * @param createServer
  *   creates a server, for each new session
  */
final class Lsp4jLanguageServer(
  createServer: () => LanguageServer with LanguageClientAware,
  logCtx: LoggerContext
) extends KernelLanguageServer {

  private val log = logCtx(getClass)

  def session(client: KernelLanguageServer.Client): KernelLanguageServer.Session = {
    val server = createServer()

    val methods = new LinkedHashMap[String, JsonRpcMethod]
    methods.putAll(ServiceEndpoints.getSupportedMethods(classOf[LanguageServer]))
    methods.putAll(ServiceEndpoints.getSupportedMethods(classOf[LanguageClient]))
    val jsonHandler = new MessageJsonHandler(methods)

    val outgoing: MessageConsumer = (message: Message) =>
      client.send(RawJson(jsonHandler.serialize(message).getBytes(StandardCharsets.UTF_8)))
    val endpoint = new RemoteEndpoint(
      outgoing,
      ServiceEndpoints.toEndpoint(Lsp4jLanguageServer.proxy(classOf[LanguageServer], server))
    )
    // allows to parse responses, using the method of their request
    jsonHandler.setMethodProvider(endpoint)

    server.connect(ServiceEndpoints.toServiceObject(endpoint, classOf[LanguageClient]))

    new KernelLanguageServer.Session {
      def message(message: RawJson): Unit =
        try endpoint.consume(jsonHandler.parseMessage(message.toString))
        catch {
          case e: MessageIssueException =>
            // answers requests with an error
            endpoint.handle(e.getRpcMessage, e.getIssues)
          case NonFatal(e) =>
            log.warn(s"Error processing LSP message $message", e)
        }
      def close(): Unit =
        server.exit()
    }
  }
}

object Lsp4jLanguageServer {

  // java.util.logging only keeps weak references to loggers, so we hold this one, lest it and
  // the handler we add to it get garbage collected before lsp4j creates its own loggers
  private val lsp4jLogger = java.util.logging.Logger.getLogger("org.eclipse.lsp4j")

  /** Sends what lsp4j logs to `logCtx`
    *
    * lsp4j logs via java.util.logging, whose default handler writes to stderr, that ends up in the
    * output of the cell running at that time.
    *
    * Meant to be called once, early on, by the kernel.
    */
  def redirectLogging(logCtx: LoggerContext): Unit = {
    val log = logCtx(getClass)
    lsp4jLogger.setUseParentHandlers(false)
    lsp4jLogger.addHandler(new java.util.logging.Handler {
      def publish(record: java.util.logging.LogRecord): Unit = {
        val message = s"lsp4j: ${record.getMessage}"
        Option(record.getThrown) match {
          case Some(e) => log.warn(message, e)
          case None    => log.debug(message)
        }
      }
      def flush(): Unit = ()
      def close(): Unit = ()
    })
  }

  /** Proxy for `obj`, only exposing the methods of `iface`
    *
    * lsp4j looks for the JSON-RPC annotations of the methods of services via reflection. Scala
    * classes have forwarders for the default methods of the Java interfaces they implement, with
    * the same annotations, which makes lsp4j find these methods twice and fail. So we only pass it
    * proxies, for the service objects it gets from `@JsonDelegate` methods too.
    */
  def proxy[T](iface: Class[T], obj: T): T = {
    val handler: InvocationHandler = (_: Any, method: Method, args: Array[AnyRef]) => {
      val res =
        try method.invoke(obj, Option(args).getOrElse(Array.empty[AnyRef]): _*)
        catch {
          case e: InvocationTargetException =>
            throw e.getCause
        }
      if (res != null && method.isAnnotationPresent(classOf[JsonDelegate]))
        proxy(method.getReturnType.asInstanceOf[Class[AnyRef]], res)
      else
        res
    }
    iface.cast(Proxy.newProxyInstance(iface.getClassLoader, Array[Class[_]](iface), handler))
  }
}
