---
layout: docs
title:  "Streams"
number: 2
---

# Streams (experimental)

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

### Streaming API

At the moment there's only two combinators:

```scala
trait Streaming[F[_], K, V] {
  def append: F[XAddMessage[K, V]] => F[MessageId]
  def read(keys: Set[K], initialOffset: K => StreamingOffset[K] = StreamingOffset.All[K]): F[XReadMessage[K, V]]
}
```

`append` can be used as a `Sink[F, StreamingMessage[K, V]` and `read(keys)` as a source `Stream[F, StreamingMessageWithId[K, V]`. Note that `Redis` allows you to consume from multiple `stream` keys at the same time.

### Streaming Example

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.parallel._
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.streams.RedisStream
import dev.profunktor.redis4cats.streams.data._
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

implicit val timer = IO.timer(ExecutionContext.global)
implicit val cs    = IO.contextShift(ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val stringCodec = RedisCodec.Utf8

def putStrLn[A](a: A): IO[Unit] = IO(println(a))

val streamKey1 = "demo"
val streamKey2 = "users"

def randomMessage: Stream[IO, XAddMessage[String, String]] = Stream.eval {
  val rndKey   = IO(Random.nextInt(1000).toString)
  val rndValue = IO(Random.nextString(10))
  (rndKey, rndValue).parMapN {
    case (k, v) =>
      XAddMessage(streamKey1, Map(k -> v))
  }
}

for {
  client    <- Stream.resource(RedisClient[IO].from("redis://localhost"))
  streaming <- RedisStream.mkStreamingConnection[IO, String, String](client, stringCodec)
  source    = streaming.read(Set(streamKey1, streamKey2))
  appender  = streaming.append
  rs <- Stream(
         source.evalMap(putStrLn(_)),
         Stream.awakeEvery[IO](3.seconds) >> randomMessage.through(appender)
       ).parJoin(2).drain
} yield rs
```

