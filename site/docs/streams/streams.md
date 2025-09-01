---
layout: docs
title:  "Streams"
number: 2
---

# Streams

High-level, safe and pure functional API on top of [Redis Streams](https://redis.io/topics/streams-intro).

### Establishing a connection

There are two ways of establishing a connection using the `RedisStream` interpreter:

#### Single connection

```scala
def mkStreamingConnection[F[_], K, V](
  client: RedisClient,
  codec: RedisCodec[K, V],
  uri: RedisURI
): Stream[F, Streaming[Stream[F, ?], K, V]]
```

#### Master / Replica connection

```scala
def mkMasterReplicaConnection[F[_], K, V](codec: RedisCodec[K, V], uris: RedisURI*)(
  readFrom: Option[ReadFrom] = None): Stream[F, Streaming[Stream[F, ?], K, V]]
```

#### Cluster connection

Not implemented yet.

#### Custom connection

```scala
Redis[IO].simple("redis://localhost", RedisCodec.Utf8).map { redis =>
  // Wrap any RedisCommands with a RedisStream layer
  RedisStream[IO, String, String](redis)
}
```

### Streaming API

See https://redis.io/docs/latest/develop/data-types/streams/

```scala
trait Streaming[F[_], S[_], K, V] {
  def append: S[XAddMessage[K, V]] => S[MessageId]
  def append(msg: XAddMessage[K, V]): F[MessageId]
  def read(
    streams: Set[XReadOffsets[K]],
    chunkSize: Int,
    block: Option[Duration] = Some(Duration.Zero),
    count: Option[Long] = None,
    restartOnTimeout: RestartOnTimeout = RestartOnTimeout.always
  ): S[StreamMessage[K, V]]
}
```

`S[_]` represents a `Stream[F, *]` or any other effectful type constructor used to model the streaming operations.

See https://redis.io/docs/latest/commands/xadd.
`append` can be used to add a single message or as a `fs2.Pipe[F, XAddMessage[K, V], MessageId]` to a Stream of `Stream[F, XAddMessage[K, V]]`.

See https://redis.io/docs/latest/commands/xread.
`read(...)` is a source stream, `Stream[F, StreamMessage[K, V]`, from multiple Redis `stream`'s.

It's recommend to use a separate connection for reading and writing to the stream. Read operations may block if the
block duration is set.

### Streaming Example

```scala mdoc:silent
import cats.effect.{ IO, IOApp }
import cats.syntax.all._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.streams.RedisStream
import dev.profunktor.redis4cats.effects.XReadOffsets
import dev.profunktor.redis4cats.streams.data._
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._
import scala.util.Random

object StreamingDemo2 extends IOApp.Simple {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  val stringCodec = RedisCodec.Utf8
  val redisURI    = "redis://localhost"

  val streamKey1 = "demo"
  val streamKey2 = "users"

  def randomMessage: Stream[IO, XAddMessage[String, String]] = Stream.evals {
    val rndKey   = IO(Random.nextInt(1000).toString)
    val rndValue = IO(Random.nextString(10))
    (rndKey, rndValue).parMapN { case (k, v) =>
      List(
        XAddMessage(streamKey1, Map(k -> v)),
        XAddMessage(streamKey2, Map(k -> v))
      )
    }
  }

  private val readStream: Stream[IO, Unit] =
    for {
      redis <- Stream.resource(Redis[IO].simple(redisURI, stringCodec))
      streaming = RedisStream[IO, String, String](redis)
      message <- streaming.read(XReadOffsets.all(streamKey1, streamKey2))
      _ <- Stream.eval(IO.println(message))
    } yield ()

  private val writeStream: Stream[IO, Unit] =
    for {
      redis <- Stream.resource(Redis[IO].simple(redisURI, stringCodec))
      streaming = RedisStream[IO, String, String](redis)
      _ <- Stream.awakeEvery[IO](2.seconds)
      _ <- randomMessage.through(streaming.append)
    } yield ()

  def run: IO[Unit] =
    readStream
      .concurrently(writeStream)
      .interruptAfter(5.seconds)
      .compile
      .drain

}
```

