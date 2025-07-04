/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.stream

import zio._
import zio.stream.compression.{CompressionException, CompressionLevel, CompressionStrategy, FlushMode}

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io._
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption._
import java.nio.file.{OpenOption, Path, Paths}
import java.nio.{Buffer, ByteBuffer}
import java.util.zip.{DataFormatException, Inflater}
import java.{util => ju}
import scala.annotation.tailrec

private[stream] trait ZStreamPlatformSpecificConstructors {
  self: ZStream.type =>

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The optionality of the error type `E` can be used to signal the end
   * of the stream, by setting it to `None`.
   */
  def async[R, E, A](
    register: ZStream.Emit[R, E, A, Unit] => Unit,
    outputBuffer: => Int = 16
  )(implicit trace: Trace): ZStream[R, E, A] =
    asyncMaybe(
      callback => {
        register(callback)
        None
      },
      outputBuffer
    )

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback returns either a canceler or
   * synchronously returns a stream. The optionality of the error type `E` can
   * be used to signal the end of the stream, by setting it to `None`.
   */
  def asyncInterrupt[R, E, A](
    register: ZStream.Emit[R, E, A, Unit] => Either[URIO[R, Any], ZStream[R, E, A]],
    outputBuffer: => Int = 16
  )(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.unwrapScoped[R](for {
      output  <- ZIO.acquireRelease(Queue.bounded[stream.Take[E, A]](outputBuffer))(_.shutdown)
      runtime <- ZIO.runtime[R]
      eitherStream <-
        ZIO.succeed {
          register { k =>
            try {
              runtime.unsafe
                .run(stream.Take.fromPull(k).flatMap(output.offer))(trace, Unsafe.unsafe)
              ()
            } catch {
              case FiberFailure(c) if c.isInterrupted =>
                ()
            }
          }
        }
    } yield {
      eitherStream match {
        case Right(value) => ZStream.unwrap(output.shutdown as value)
        case Left(canceler) =>
          lazy val loop: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
            ZChannel.unwrap(
              output.take
                .flatMap(_.exit)
                .fold(
                  maybeError =>
                    ZChannel.fromZIO(output.shutdown) *>
                      maybeError
                        .fold[ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit]](ZChannel.unit)(ZChannel.fail(_)),
                  a => ZChannel.write(a) *> loop
                )
            )

          ZStream.fromChannel(loop).ensuring(canceler)
      }
    })

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback itself returns an a scoped
   * resource. The optionality of the error type `E` can be used to signal the
   * end of the stream, by setting it to `None`.
   */
  def asyncScoped[R, E, A](
    register: (ZIO[R, Option[E], Chunk[A]] => Unit) => ZIO[R with Scope, E, Any],
    outputBuffer: => Int = 16
  )(implicit trace: Trace): ZStream[R, E, A] =
    scoped[R] {
      for {
        output  <- ZIO.acquireRelease(Queue.bounded[stream.Take[E, A]](outputBuffer))(_.shutdown)
        runtime <- ZIO.runtime[R]
        _ <- register { k =>
               try {
                 runtime.unsafe.run(stream.Take.fromPull(k).flatMap(output.offer))(trace, Unsafe.unsafe)
                 ()
               } catch {
                 case FiberFailure(c) if c.isInterrupted =>
                   ()
               }
             }
        done <- Ref.make(false)
        pull = done.get.flatMap {
                 if (_)
                   Pull.end
                 else
                   output.take.flatMap(_.exit).onError(_ => done.set(true) *> output.shutdown)
               }
      } yield pull
    }.flatMap(repeatZIOChunkOption(_))

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times The registration of the callback itself returns an effect. The
   * optionality of the error type `E` can be used to signal the end of the
   * stream, by setting it to `None`.
   */
  def asyncZIO[R, E, A](
    register: ZStream.Emit[R, E, A, Unit] => ZIO[R, E, Any],
    outputBuffer: => Int = 16
  )(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.fromChannel(ZChannel.unwrapScoped[R](for {
      output  <- ZIO.acquireRelease(Queue.bounded[stream.Take[E, A]](outputBuffer))(_.shutdown)
      runtime <- ZIO.runtime[R]
      _ <- register { k =>
             try {
               runtime.unsafe.run(stream.Take.fromPull(k).flatMap(output.offer))(trace, Unsafe.unsafe)
               ()
             } catch {
               case FiberFailure(c) if c.isInterrupted =>
                 ()
             }
           }
    } yield {
      lazy val loop: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] = ZChannel.unwrap(
        output.take
          .flatMap(_.exit)
          .fold(
            maybeError =>
              ZChannel.fromZIO(output.shutdown) *>
                maybeError.fold[ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit]](ZChannel.unit)(ZChannel.fail(_)),
            a => ZChannel.write(a) *> loop
          )
      )

      loop
    }))

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback can possibly return the stream
   * synchronously. The optionality of the error type `E` can be used to signal
   * the end of the stream, by setting it to `None`.
   */
  def asyncMaybe[R, E, A](
    register: ZStream.Emit[R, E, A, Unit] => Option[ZStream[R, E, A]],
    outputBuffer: => Int = 16
  )(implicit trace: Trace): ZStream[R, E, A] =
    asyncInterrupt(k => register(k).toRight(ZIO.unit), outputBuffer)

  /**
   * Creates a stream of bytes from the specified file.
   */
  final def fromFile(file: => File, chunkSize: => Int = ZStream.DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromZIO(ZIO.attempt(file.toPath))
      .flatMap(path => self.fromPath(path, chunkSize))

  /**
   * Creates a stream of bytes from a file at the specified path represented by
   * a string.
   */
  final def fromFileName(name: => String, chunkSize: => Int = ZStream.DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromZIO(ZIO.attempt(Paths.get(name)))
      .flatMap(path => self.fromPath(path, chunkSize))

  /**
   * Creates a stream of bytes from a file at the specified uri.
   */
  final def fromFileURI(uri: => URI, chunkSize: => Int = ZStream.DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromZIO(ZIO.attempt(Paths.get(uri)))
      .flatMap(path => self.fromPath(path, chunkSize))

  /**
   * Creates a stream of bytes from a file at the specified path.
   */
  final def fromPath(path: => Path, chunkSize: => Int = ZStream.DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Throwable, Byte] =
    ZStream.blocking {
      ZStream
        .acquireReleaseWith(ZIO.attempt(FileChannel.open(path)))(chan => ZIO.succeed(chan.close()))
        .flatMap { channel =>
          ZStream.fromZIO(ZIO.succeed(ByteBuffer.allocate(chunkSize))).flatMap { reusableBuffer =>
            ZStream.repeatZIOChunkOption(
              for {
                bytesRead <- ZIO.attempt(channel.read(reusableBuffer)).asSomeError
                _         <- Exit.failNone.when(bytesRead == -1)
                chunk <- ZIO.succeed {
                           reusableBuffer.flip()
                           Chunk.fromByteBuffer(reusableBuffer)
                         }
              } yield chunk
            )
          }
        }
    }

  /**
   * Creates a stream from `java.io.Reader`.
   */
  def fromReader(reader: => Reader, chunkSize: => Int = ZStream.DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, IOException, Char] =
    ZStream.succeed((reader, chunkSize)).flatMap { case (reader, chunkSize) =>
      ZStream.repeatZIOChunkOption {
        for {
          bufArray  <- ZIO.succeed(Array.ofDim[Char](chunkSize))
          bytesRead <- ZIO.attemptBlockingIO(reader.read(bufArray)).asSomeError
          chars <- if (bytesRead < 0)
                     Exit.failNone
                   else if (bytesRead == 0)
                     Exit.emptyChunk
                   else if (bytesRead < chunkSize)
                     ZIO.succeed(Chunk.fromArray(bufArray).take(bytesRead))
                   else
                     ZIO.succeed(Chunk.fromArray(bufArray))
        } yield chars
      }
    }

  /**
   * Creates a stream from scoped `java.io.Reader`.
   */
  def fromReaderScoped[R](
    reader: => ZIO[Scope with R, IOException, Reader],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: Trace): ZStream[R, IOException, Char] =
    ZStream.scoped[R](reader).flatMap(fromReader(_, chunkSize))

  /**
   * Creates a stream from an effect producing `java.io.Reader`.
   */
  def fromReaderZIO[R](
    reader: => ZIO[R, IOException, Reader],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: Trace): ZStream[R, IOException, Char] =
    fromReaderScoped[R](ZIO.acquireRelease(reader)(reader => ZIO.succeed(reader.close())), chunkSize)

  /**
   * Creates a stream from a Java stream
   */
  final def fromJavaStream[A](stream: => java.util.stream.Stream[A])(implicit
    trace: Trace
  ): ZStream[Any, Throwable, A] =
    fromJavaStream(stream, ZStream.DefaultChunkSize)

  /**
   * Creates a stream from a Java stream
   */
  final def fromJavaStream[A](
    stream: => java.util.stream.Stream[A],
    chunkSize: Int
  )(implicit trace: Trace): ZStream[Any, Throwable, A] =
    ZStream.fromJavaIteratorScoped(
      ZIO.acquireRelease(ZIO.attempt(stream))(stream => ZIO.succeed(stream.close())).map(_.iterator()),
      chunkSize
    )

  /**
   * Creates a stream from a scoped Java stream
   */
  final def fromJavaStreamScoped[R, A](stream: => ZIO[Scope with R, Throwable, java.util.stream.Stream[A]])(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    fromJavaStreamScoped[R, A](stream, ZStream.DefaultChunkSize)

  /**
   * Creates a stream from a scoped Java stream
   */
  final def fromJavaStreamScoped[R, A](
    stream: => ZIO[Scope with R, Throwable, java.util.stream.Stream[A]],
    chunkSize: Int
  )(implicit trace: Trace): ZStream[R, Throwable, A] =
    ZStream.scoped[R](stream).flatMap(ZStream.fromJavaStream(_, chunkSize))

  /**
   * Creates a stream from a Java stream
   */
  final def fromJavaStreamSucceed[R, A](stream: => java.util.stream.Stream[A])(implicit
    trace: Trace
  ): ZStream[R, Nothing, A] =
    fromJavaStreamSucceed(stream, ZStream.DefaultChunkSize)

  /**
   * Creates a stream from a Java stream
   */
  final def fromJavaStreamSucceed[R, A](
    stream: => java.util.stream.Stream[A],
    chunkSize: Int
  )(implicit
    trace: Trace
  ): ZStream[R, Nothing, A] =
    ZStream.fromJavaIteratorSucceed(stream.iterator(), chunkSize)

  /**
   * Creates a stream from a Java stream
   */
  final def fromJavaStreamZIO[R, A](stream: => ZIO[R, Throwable, java.util.stream.Stream[A]])(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    fromJavaStreamZIO(stream, ZStream.DefaultChunkSize)

  /**
   * Creates a stream from a Java stream
   */
  final def fromJavaStreamZIO[R, A](
    stream: => ZIO[R, Throwable, java.util.stream.Stream[A]],
    chunkSize: Int
  )(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    ZStream.fromZIO(stream).flatMap(ZStream.fromJavaStream(_, chunkSize))

  private[stream] trait ZStreamConstructorPlatformSpecific extends ZStreamConstructorLowPriority1 {

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from a
     * `java.util.stream.Stream[A]`.
     */
    implicit def JavaStreamConstructor[A, StreamLike[A] <: java.util.stream.Stream[A]]
      : WithOut[StreamLike[A], ZStream[Any, Throwable, A]] =
      new ZStreamConstructor[StreamLike[A]] {
        type Out = ZStream[Any, Throwable, A]
        def make(input: => StreamLike[A])(implicit trace: Trace): ZStream[Any, Throwable, A] =
          ZStream.fromJavaStream(input)
      }

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from a `ZIO[R with Scope,
     * Throwable, java.util.stream.Stream[A]]`.
     */
    implicit def JavaStreamScopedConstructor[R, E <: Throwable, A, StreamLike[A] <: java.util.stream.Stream[A]]
      : WithOut[ZIO[Scope with R, E, StreamLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[Scope with R, E, StreamLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[Scope with R, E, StreamLike[A]])(implicit
          trace: Trace
        ): ZStream[R, Throwable, A] =
          ZStream.fromJavaStreamScoped[R, A](input)
      }

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from a `ZIO[R, Throwable,
     * java.util.stream.Stream[A]]`.
     */
    implicit def JavaStreamZIOConstructor[R, E <: Throwable, A, StreamLike[A] <: java.util.stream.Stream[A]]
      : WithOut[ZIO[R, E, StreamLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[R, E, StreamLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[R, E, StreamLike[A]])(implicit trace: Trace): ZStream[R, Throwable, A] =
          ZStream.fromJavaStreamZIO(input)
      }

  }
}

private[stream] trait ZSinkPlatformSpecificConstructors {
  self: ZSink.type =>

  /**
   * Uses the provided `File` to create a [[ZSink]] that consumes byte chunks
   * and writes them to the `File`. The sink will yield count of bytes written.
   */
  final def fromFile(
    file: => File,
    position: => Long = 0L,
    options: => Set[OpenOption] = Set(WRITE, TRUNCATE_EXISTING, CREATE)
  )(implicit
    trace: Trace
  ): ZSink[Any, Throwable, Byte, Byte, Long] =
    ZSink
      .fromZIO(ZIO.attempt(file.toPath))
      .flatMap(path => self.fromPath(path, position, options))

  /**
   * Uses the provided `Path` represented as a string to create a [[ZSink]] that
   * consumes byte chunks and writes them to the `File`. The sink will yield
   * count of bytes written.
   */
  final def fromFileName(
    name: => String,
    position: => Long = 0L,
    options: => Set[OpenOption] = Set(WRITE, TRUNCATE_EXISTING, CREATE)
  )(implicit
    trace: Trace
  ): ZSink[Any, Throwable, Byte, Byte, Long] =
    ZSink
      .fromZIO(ZIO.attempt(Paths.get(name)))
      .flatMap(path => self.fromPath(path, position, options))

  /**
   * Uses the provided `URI` to create a [[ZSink]] that consumes byte chunks and
   * writes them to the `File`. The sink will yield count of bytes written.
   */
  final def fromFileURI(
    uri: => URI,
    position: => Long = 0L,
    options: => Set[OpenOption] = Set(WRITE, TRUNCATE_EXISTING, CREATE)
  )(implicit
    trace: Trace
  ): ZSink[Any, Throwable, Byte, Byte, Long] =
    ZSink
      .fromZIO(ZIO.attempt(Paths.get(uri)))
      .flatMap(path => self.fromPath(path, position, options))

  /**
   * Uses the provided `Path` to create a [[ZSink]] that consumes byte chunks
   * and writes them to the `File`. The sink will yield count of bytes written.
   */
  final def fromPath(
    path: => Path,
    position: => Long = 0L,
    options: => Set[OpenOption] = Set(WRITE, TRUNCATE_EXISTING, CREATE)
  )(implicit
    trace: Trace
  ): ZSink[Any, Throwable, Byte, Byte, Long] = {

    val scopedChannel = ZIO.acquireRelease(
      ZIO
        .attemptBlockingInterrupt(
          FileChannel
            .open(
              path,
              options.foldLeft(
                new ju.HashSet[OpenOption]()
              ) { (acc, op) =>
                acc.add(op)
                acc
              } // for avoiding usage of different Java collection converters for different scala versions
            )
            .position(position)
        )
    )(chan => ZIO.attemptBlocking(chan.close()).orDie)

    ZSink.unwrapScoped {
      scopedChannel.map { chan =>
        ZSink.foldLeftChunksZIO(0L) { (bytesWritten, byteChunk: Chunk[Byte]) =>
          ZIO.attemptBlockingInterrupt {
            val bytes = byteChunk.toArray

            chan.write(ByteBuffer.wrap(bytes))

            bytesWritten + bytes.length.toLong
          }
        }
      }
    }
  }

}

private[stream] trait ZPipelinePlatformSpecificConstructors {
  def deflate(
    bufferSize: => Int = 64 * 1024,
    noWrap: => Boolean = false,
    level: => CompressionLevel = CompressionLevel.DefaultCompression,
    strategy: => CompressionStrategy = CompressionStrategy.DefaultStrategy,
    flushMode: => FlushMode = FlushMode.NoFlush
  )(implicit trace: Trace): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.fromChannel(
      Deflate.makeDeflater(
        bufferSize,
        noWrap,
        level,
        strategy,
        flushMode
      )
    )

  def inflate(
    bufferSize: => Int = 64 * 1024,
    noWrap: => Boolean = false
  )(implicit trace: Trace): ZPipeline[Any, CompressionException, Byte, Byte] =
    ZPipeline.fromChannel(
      Inflate.makeInflater(bufferSize, noWrap)
    )

  def gzip(
    bufferSize: => Int = 64 * 1024,
    level: => CompressionLevel = CompressionLevel.DefaultCompression,
    strategy: => CompressionStrategy = CompressionStrategy.DefaultStrategy,
    flushMode: => FlushMode = FlushMode.NoFlush
  )(implicit trace: Trace): ZPipeline[Any, Nothing, Byte, Byte] =
    ZPipeline.fromChannel(
      Gzip.makeGzipper(bufferSize, level, strategy, flushMode)
    )

  def gunzip[Env](bufferSize: => Int = 64 * 1024)(implicit
    trace: Trace
  ): ZPipeline[Any, CompressionException, Byte, Byte] =
    ZPipeline.fromChannel(
      Gunzip.makeGunzipper(bufferSize)
    )

  def gunzipAuto[Env](
    bufferSize: => Int = 64 * 1024
  )(implicit trace: Trace): ZPipeline[Any, CompressionException, Byte, Byte] = {
    val GZIP_PREFIX: Chunk[Byte] = Chunk(0x1f.toByte, 0x8b.toByte)
    ZPipeline.branchAfter[Any, CompressionException, Byte, Byte](2) { chunk =>
      if (chunk == GZIP_PREFIX) {
        ZPipeline.prepend(chunk) >>> ZPipeline.gunzip(bufferSize)
      } else {
        ZPipeline.prepend(chunk)
      }
    }
  }
}
