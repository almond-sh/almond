package almond.util

import almond.logger.LoggerContext

import java.lang.reflect.{InvocationHandler, Method, Proxy}

import scala.util.control.NonFatal

/** Handles SIGINT, that Jupyter sends to kernels to interrupt them, unless their kernel spec asks
  * for interrupt messages
  *
  * This relies on `sun.misc.Signal`, that we access via reflection, as it's not part of the Java 8
  * API our modules are compiled against.
  */
object SigInt {

  private final class Api(
    signalClass: Class[_],
    handlerClass: Class[_],
    signal: AnyRef,
    handleMethod: Method
  ) {
    def newHandler(f: () => Unit): AnyRef =
      Proxy.newProxyInstance(
        handlerClass.getClassLoader,
        Array(handlerClass),
        new InvocationHandler {
          def invoke(proxy: Object, method: Method, args: Array[Object]): Object =
            method.getName match {
              case "handle" =>
                f()
                null
              case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"   => java.lang.Boolean.valueOf(proxy eq args(0))
              case "toString" => "almond.util.SigInt handler"
              case _          => null
            }
        }
      )
    def handle(handler: AnyRef): AnyRef =
      handleMethod.invoke(null, signal, handler)
  }

  private lazy val apiOpt: Either[Throwable, Api] =
    try {
      val signalClass  = Class.forName("sun.misc.Signal")
      val handlerClass = Class.forName("sun.misc.SignalHandler")
      val signal =
        signalClass.getConstructor(classOf[String]).newInstance("INT").asInstanceOf[AnyRef]
      Right(new Api(
        signalClass,
        handlerClass,
        signal,
        signalClass.getMethod("handle", signalClass, handlerClass)
      ))
    }
    catch {
      case NonFatal(e) =>
        Left(e)
    }

  /** Installs a SIGINT handler
    *
    * @return
    *   the handler that was there before, to pass to [[restore]]
    */
  def install(f: () => Unit): Either[Throwable, AnyRef] =
    apiOpt.flatMap { api =>
      try Right(api.handle(api.newHandler(f)))
      catch {
        case NonFatal(e) =>
          // the JVM uses SIGINT itself (-Xrs, …)
          Left(e)
      }
    }

  /** Re-installs a handler that [[install]] returned */
  def restore(previousHandler: AnyRef): Unit =
    apiOpt.foreach(_.handle(previousHandler))

  /** Runs `t` with `f` handling SIGINT, then re-installs the handler that was there before
    *
    * Unlike `ammonite.repl.Signaller`, that installs the default handler (exiting the JVM) once
    * done, rather than the former one.
    */
  def scoped[T](f: () => Unit)(t: => T): T =
    install(f) match {
      case Left(_) =>
        t
      case Right(previous) =>
        try t
        finally restore(previous)
    }

  /** Makes the JVM log SIGINTs, rather than exit upon them
    *
    * The JVM exits upon SIGINT by default. The handler that interrupts cells is only installed
    * while cells run, and the one installed here handles SIGINTs the rest of the time. Without it,
    * interrupting a kernel that isn't running a cell, or that just finished running one, would kill
    * it.
    */
  def installIdleHandler(logCtx: LoggerContext): Unit = {
    val log = logCtx(getClass)
    val res = install { () =>
      log.warn("Received SIGINT, but no cell is running, ignoring it")
    }
    res match {
      case Left(e)  => log.warn("Cannot install SIGINT handler", e)
      case Right(_) =>
    }
  }
}
