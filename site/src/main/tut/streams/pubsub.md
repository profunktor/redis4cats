---
layout: docs
title:  "PubSub"
number: 1
---

# PubSub

Simple, safe and pure functional streaming client to interact with [Redis PubSub](https://redis.io/topics/pubsub).

### Establishing a connection

There are three options available in the `Fs2PubSub` interpreter:

- `mkPubSubConnection`: Whenever you need one or more subscribers and publishers / stats.
- `mkSubscriberConnection`: When all you need is one or more subscribers but no publishing / stats.
- `mkPublisherConnection`: When all you need is to publish / stats.

```tut:invisible
trait Fs2RedisChannel[K] { def value: K }
case class Subscription[K](channel: Fs2RedisChannel[K], number: Long)
```

### Subscriber

```tut:silent
trait SubscribeCommands[F[_], K, V] {
  def subscribe(channel: Fs2RedisChannel[K]): F[V]
  def unsubscribe(channel: Fs2RedisChannel[K]): F[Unit]
}
```

When using the `Fs2PubSub` interpreter the types will be `Stream[F, V]` and `Stream[F, Unit]` respectively.

### Publisher / PubSubStats

```tut:silent
trait PubSubStats[F[_], K] {
  def pubSubChannels: F[List[K]]
  def pubSubSubscriptions(channel: Fs2RedisChannel[K]): F[Subscription[K]]
  def pubSubSubscriptions(channels: List[Fs2RedisChannel[K]]): F[List[Subscription[K]]]
}

trait PublishCommands[F[_], K, V] extends PubSubStats[F, K] {
  def publish(channel: Fs2RedisChannel[K]): F[V] => F[Unit]
}
```

When using the `Fs2PubSub` interpreter the `publish` function will be defined as a `Sink[F, V]` that can be connected to a `Stream[F, ?]` source.

### PubSub example

```tut:book:silent
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.apply._
import com.github.gvolpe.fs2redis.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.interpreter.pubsub.Fs2PubSub
import com.github.gvolpe.fs2redis.domain.{DefaultChannel, DefaultRedisCodec}
import fs2.{Sink, Stream}
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

import scala.concurrent.duration._
import scala.util.Random

object Fs2PubSubDemo extends IOApp {

  private val redisURI    = RedisURI.create("redis://localhost")
  private val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  private val eventsChannel = DefaultChannel("events")
  private val gamesChannel  = DefaultChannel("games")

  def sink(name: String): Sink[IO, String] = _.evalMap(x => IO(println(s"Subscriber: $name >> $x")))

  def stream(args: List[String]): Stream[IO, Unit] =
    for {
      client <- Stream.resource(Fs2RedisClient[IO](redisURI))
      pubSub <- Fs2PubSub.mkPubSubConnection[IO, String, String](client, stringCodec, redisURI)
      sub1   = pubSub.subscribe(eventsChannel)
      sub2   = pubSub.subscribe(gamesChannel)
      pub1   = pubSub.publish(eventsChannel)
      pub2   = pubSub.publish(gamesChannel)
      _  <- Stream(
             sub1 to sink("#events"),
             sub2 to sink("#games"),
             Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)) to pub1,
             Stream.awakeEvery[IO](5.seconds) >> Stream.emit("Pac-Man!") to pub2,
             Stream.awakeDelay[IO](11.seconds) >> pubSub.unsubscribe(gamesChannel),
             Stream.awakeEvery[IO](6.seconds) >> pubSub
               .pubSubSubscriptions(List(eventsChannel, gamesChannel))
               .evalMap(x => IO(println(x)))
           ).parJoin(6).drain
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    stream(args).compile.drain *> IO.pure(ExitCode.Success)

}
```
