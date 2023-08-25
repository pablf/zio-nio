package zio.nio.channels

import zio._
import zio.nio.{BaseSpec, EffectOps, InetSocketAddress}
import zio.test.Assertion._
import zio.test._

import java.io.{EOFException, FileNotFoundException, IOException}
import java.nio.channels
import java.{nio => jnio}

object ChannelSpec extends BaseSpec {

  override def spec =
    suite("Channel")(
      test("localAddress") {
        SocketChannel.open.flatMap { con =>
          for {
            _            <- con.bindAuto
            localAddress <- con.localAddress
          } yield assert(localAddress)(isSome(isSubtype[InetSocketAddress](anything)))
        }
      },
      suite("explicit end-of-stream")(
        test("converts EOFException to None") {
          assertZIO(ZIO.fail(new EOFException).eofCheck.exit)(fails(isNone))
        },
        test("converts non EOFException to Some") {
          val e: IOException = new FileNotFoundException()
          assertZIO(ZIO.fail(e).eofCheck.exit)(fails(isSome(equalTo(e))))
        },
        test("passes through success") {
          assertZIO(ZIO.succeed(42).eofCheck.exit)(succeeds(equalTo(42)))
        }
      ),
      suite("blocking operations")(
        test("read can be interrupted") {
          live {
            for {
              promise <- Promise.make[Nothing, Unit]
              fiber <- ZIO.scoped {
                         Pipe.open
                           .flatMap(_.source)
                           .flatMapNioBlockingOps(ops => promise.succeed(()) *> ops.readChunk(1))

                       }.fork
              _    <- promise.await
              _    <- ZIO.sleep(500.milliseconds)
              exit <- fiber.interrupt
            } yield assert(exit)(isInterrupted)
          }
        },
        test("write can be interrupted") {
          val hangingOps: GatheringByteOps = new GatheringByteOps {
            override protected[channels] val channel = new jnio.channels.GatheringByteChannel {

              @volatile private var _closed = false

              private def hang(): Nothing = {
                while (!_closed)
                  Thread.sleep(10L)
                throw new jnio.channels.AsynchronousCloseException()
              }

              override def write(srcs: Array[jnio.ByteBuffer], offset: Int, length: Int): Long = hang()

              override def write(srcs: Array[jnio.ByteBuffer]): Long = hang()

              override def write(src: jnio.ByteBuffer): Int = hang()

              override def isOpen: Boolean = !_closed

              override def close(): Unit = _closed = true
            }
          }
          val hangingChannel = new BlockingChannel {
            override type BlockingOps = GatheringByteOps

            override def flatMapBlocking[R, E >: IOException, A](
              f: GatheringByteOps => ZIO[R, E, A]
            )(implicit trace: Trace): ZIO[R, E, A] = nioBlocking(f(hangingOps))

            override protected val channel: channels.Channel = hangingOps.channel
          }

          live {
            for {
              promise <- Promise.make[Nothing, Unit]
              fiber <-
                hangingChannel
                  .flatMapBlocking(ops => promise.succeed(()) *> ops.writeChunk(Chunk.single(42.toByte)))
                  .fork
              _    <- promise.await
              _    <- ZIO.sleep(500.milliseconds)
              exit <- fiber.interrupt
            } yield assert(exit)(isInterrupted)
          }
        },
        test("accept can be interrupted") {
          live {
            ZIO.scoped {
              ServerSocketChannel.open.tap(_.bindAuto()).flatMap { serverChannel =>
                for {
                  promise <- Promise.make[Nothing, Unit]
                  fiber   <- serverChannel.flatMapBlocking(ops => promise.succeed(()) *> ZIO.scoped(ops.accept)).fork
                  _       <- promise.await
                  _       <- ZIO.sleep(500.milliseconds)
                  exit    <- fiber.interrupt
                } yield assert(exit)(isInterrupted)
              }
            }
          }
        }
      )
    )
}
