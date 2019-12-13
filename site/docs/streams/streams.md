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
  uri: JRedisURI
): Stream[F, Streaming[Stream[F, ?], K, V]]
```

#### Master / Replica connection

```scala
def mkMasterReplicaConnection[F[_], K, V](codec: RedisCodec[K, V], uris: JRedisURI*)(
  readFrom: Option[ReadFrom] = None): Stream[F, Streaming[Stream[F, ?], K, V]]
```

#### Cluster connection

Not implemented yet.

### Streaming API

At the moment there's only two combinators:

```scala
trait Streaming[F[_], K, V] {
  def append: F[StreamingMessage[K, V]] => F[Unit]
  def read(keys: Set[K], initialOffset: K => StreamingOffset[K] = StreamingOffset.All[K]): F[StreamingMessageWithId[K, V]]
}
```

`append` can be used as a `Sink[F, StreamingMessage[K, V]` and `read(keys)` as a source `Stream[F, StreamingMessageWithId[K, V]`. Note that `Redis` allows you to consume from multiple `stream` keys at the same time.

### Streaming Example

```tut:silent
import cats.effect.IO
import cats.syntax.parallel._
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisURI }
import dev.profunktor.redis4cats.domain._
import dev.profunktor.redis4cats.interpreter.streams.RedisStream
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.streams._
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

implicit val timer = IO.timer(ExecutionContext.global)
implicit val cs    = IO.contextShift(ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val stringCodec = RedisCodec.Utf8

def putStrLn(str: String): IO[Unit] = IO(println(str))

val streamKey1 = "demo"
val streamKey2 = "users"

def randomMessage: Stream[IO, StreamingMessage[String, String]] = Stream.eval {
  val rndKey   = IO(Random.nextInt(1000).toString)
  val rndValue = IO(Random.nextString(10))
  (rndKey, rndValue).parMapN {
    case (k, v) =>
      StreamingMessage(streamKey1, Map(k -> v))
  }
}

for {
  redisURI  <- Stream.eval(RedisURI.make[IO]("redis://localhost"))
  client    <- Stream.resource(RedisClient[IO](redisURI))
  streaming <- RedisStream.mkStreamingConnection[IO, String, String](client, stringCodec, redisURI)
  source    = streaming.read(Set(streamKey1, streamKey2))
  appender  = streaming.append
  rs <- Stream(
         source.evalMap(x => putStrLn(x.toString)),
         Stream.awakeEvery[IO](3.seconds) >> randomMessage.to(appender)
       ).parJoin(2).drain
} yield rs
```

