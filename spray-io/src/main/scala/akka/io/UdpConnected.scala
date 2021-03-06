/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.InetSocketAddress
import scala.collection.immutable
import akka.io.Inet.SocketOption
import akka.io.Udp.UdpSettings
import akka.util.ByteString
import akka.actor._

/**
 * UDP Extension for Akka’s IO layer.
 *
 * <b>All contents of the `akka.io` package is marked “experimental”.</b>
 *
 * This marker signifies that APIs may still change in response to user feedback
 * through-out the 2.2 release cycle. The implementation itself is considered
 * stable and ready for production use.
 *
 * This extension implements the connectionless UDP protocol with
 * calling `connect` on the underlying sockets, i.e. with restricting
 * from whom data can be received. For “unconnected” UDP mode see [[akka.io.Udp]].
 *
 * For a full description of the design and philosophy behind this IO
 * implementation please refer to <a href="http://doc.akka.io/">the Akka online documentation</a>.
 */
object UdpConnected extends ExtensionKey[UdpConnectedExt] {
  /**
   * Java API: retrieve the UdpConnected extension for the given system.
   */
  override def get(system: ActorSystem): UdpConnectedExt = super.get(system)

  /**
   * The common interface for [[akka.io.Udp.Command]] and [[akka.io.Udp.Event]].
   */
  sealed trait Message

  /**
   * The common type of all commands supported by the UDP implementation.
   */
  trait Command extends SelectionHandler.HasFailureMessage with Message {
    def failureMessage = CommandFailed(this)
  }

  /**
   * Each [[akka.io.Udp.Send]] can optionally request a positive acknowledgment to be sent
   * to the commanding actor. If such notification is not desired the [[akka.io.Udp.Send#ack]]
   * must be set to an instance of this class. The token contained within can be used
   * to recognize which write failed when receiving a [[akka.io.Udp.CommandFailed]] message.
   */
  case class NoAck(token: Any) extends Event

  /**
   * Default [[akka.io.Udp.NoAck]] instance which is used when no acknowledgment information is
   * explicitly provided. Its “token” is `null`.
   */
  object NoAck extends NoAck(null)

  /**
   * This message is understood by the connection actors to send data to their
   * designated destination. The connection actor will respond with
   * [[akka.io.Udp.CommandFailed]] if the send could not be enqueued to the O/S kernel
   * because the send buffer was full. If the given `ack` is not of type [[akka.io.Udp.NoAck]]
   * the connection actor will reply with the given object as soon as the datagram
   * has been successfully enqueued to the O/S kernel.
   */
  case class Send(payload: ByteString, ack: Any) extends Command {
    require(ack
      != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }
  object Send {
    def apply(data: ByteString): Send = Send(data, NoAck)
  }

  /**
   * Send this message to the [[akka.io.UdpExt#manager]] in order to bind to a local
   * port (optionally with the chosen `localAddress`) and create a UDP socket
   * which is restricted to sending to and receiving from the given `remoteAddress`.
   * All received datagrams will be sent to the designated `handler` actor.
   */
  case class Connect(handler: ActorRef,
                     remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil) extends Command

  /**
   * Send this message to a connection actor (which had previously sent the
   * [[akka.io.Udp.Connected]] message) in order to close the socket. The connection actor
   * will reply with a [[akka.io.Udp.Disconnected]] message.
   */
  case object Disconnect extends Command

  /**
   * Send this message to a listener actor (which sent a [[akka.io.Udp.Bound]] message) to
   * have it stop reading datagrams from the network. If the O/S kernel’s receive
   * buffer runs full then subsequent datagrams will be silently discarded.
   * Re-enable reading from the socket using the [[akka.io.Udp.ResumeReading]] command.
   */
  case object SuspendReading extends Command

  /**
   * This message must be sent to the listener actor to re-enable reading from
   * the socket after a [[akka.io.Udp.SuspendReading]] command.
   */
  case object ResumeReading extends Command

  /**
   * The common type of all events emitted by the UDP implementation.
   */
  trait Event extends Message

  /**
   * When a connection actor receives a datagram from its socket it will send
   * it to the handler designated in the [[akka.io.Udp.Bind]] message using this message type.
   */
  case class Received(data: ByteString) extends Event

  /**
   * When a command fails it will be replied to with this message type,
   * wrapping the failing command object.
   */
  case class CommandFailed(cmd: Command) extends Event

  /**
   * This message is sent by the connection actor to the actor which sent the
   * [[akka.io.Udp.Connect]] message when the UDP socket has been bound to the local and
   * remote addresses given.
   */
  sealed trait Connected extends Event
  case object Connected extends Connected

  /**
   * This message is sent by the connection actor to the actor which sent the
   * [[akka.io.Udp.Disconnect]] message when the UDP socket has been closed.
   */
  sealed trait Disconnected extends Event
  case object Disconnected extends Disconnected

}

class UdpConnectedExt(system: ExtendedActorSystem) extends IO.Extension {

  val settings: UdpSettings = new UdpSettings(system.settings.config.getConfig("akka.io.udp-connected"))

  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new UdpConnectedManager(this)),
      name = "IO-UDP-CONN")
  }

  /**
   * Java API: retrieve the UDP manager actor’s reference.
   */
  def getManager: ActorRef = manager

  val bufferPool: BufferPool = new DirectByteBufferPool(settings.DirectBufferSize, settings.MaxDirectBufferPoolSize)

}