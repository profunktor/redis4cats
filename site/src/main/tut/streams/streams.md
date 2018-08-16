---
layout: docs
title:  "Streams"
number: 2
---

# Streams (experimental)

High-level, safe and pure functional API on top of [Redis Streams](https://redis.io/topics/streams-intro).

### Establishing a connection

There are two ways of establishing a connection using the `Fs2Streaming` companion object:

#### Single connection

```scala
def mkStreamingConnection[F[_], K, V](
  client: Fs2RedisClient,
  codec: Fs2RedisCodec[K, V],
  uri: RedisURI
): Stream[F, Streaming[Stream[F, ?], K, V]]
```

#### Master / Slave connection

```scala
def mkMasterSlaveConnection[F[_], K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
  readFrom: Option[ReadFrom] = None): Stream[F, Streaming[Stream[F, ?], K, V]]
```

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
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.streams.Fs2Streaming
import com.github.gvolpe.fs2redis.model._
import fs2.Stream
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

val redisURI    = RedisURI.create("redis://localhost")
val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

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
  client    <- Fs2RedisClient.stream[IO](redisURI)
  streaming <- Fs2Streaming.mkStreamingConnection[IO, String, String](client, stringCodec, redisURI)
  source    = streaming.read(Set(streamKey1, streamKey2))
  appender  = streaming.append
  rs <- Stream(
         source.evalMap(x => putStrLn(x.toString)),
         Stream.awakeEvery[IO](3.seconds) >> randomMessage.to(appender)
       ).join(2).drain
} yield rs
```

