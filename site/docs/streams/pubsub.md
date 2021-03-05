---
layout: docs
title:  "PubSub"
number: 1
---

# PubSub

Simple, safe and pure functional streaming client to interact with [Redis PubSub](https://redis.io/topics/pubsub).

### Establishing a connection

There are three options available in the `PubSub` interpreter:

- `mkPubSubConnection`: Whenever you need one or more subscribers and publishers / stats.
- `mkSubscriberConnection`: When all you need is one or more subscribers but no publishing / stats.
- `mkPublisherConnection`: When all you need is to publish / stats.

```scala mdoc:invisible
trait RedisChannel[K] { def value: K }
case class Subscription[K](channel: RedisChannel[K], number: Long)
```

Note: cluster support is not implemented yet.

### Subscriber

```scala mdoc:silent
trait SubscribeCommands[F[_], K, V] {
  def subscribe(channel: RedisChannel[K]): F[V]
  def unsubscribe(channel: RedisChannel[K]): F[Unit]
}
```

When using the `PubSub` interpreter the types will be `Stream[F, V]` and `Stream[F, Unit]` respectively.

### Publisher / PubSubStats

```scala mdoc:silent
trait PubSubStats[F[_], K] {
  def pubSubChannels: F[List[K]]
  def pubSubSubscriptions(channel: RedisChannel[K]): F[Subscription[K]]
  def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]
}

trait PublishCommands[F[_], K, V] extends PubSubStats[F, K] {
  def publish(channel: RedisChannel[K]): F[V] => F[Unit]
}
```

When using the `PubSub` interpreter the `publish` function will be defined as a `Sink[F, V]` that can be connected to a `Stream[F, ?]` source.

### PubSub example

```scala mdoc:silent
import cats.effect.{ExitCode, IO, IOApp}
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.log4cats._
import fs2.{Pipe, Stream}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._
import scala.util.Random

object PubSubDemo extends IOApp {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private val stringCodec = RedisCodec.Utf8

  private val eventsChannel = RedisChannel("events")
  private val gamesChannel  = RedisChannel("games")

  def sink(name: String): Pipe[IO, String, Unit] = _.evalMap(x => IO(println(s"Subscriber: $name >> $x")))

  val program: Stream[IO, Unit] =
    for {
      client <- Stream.resource(RedisClient[IO].from("redis://localhost"))
      pubSub <- Stream.resource(PubSub.mkPubSubConnection[IO, String, String](client, stringCodec))
      sub1   = pubSub.subscribe(eventsChannel)
      sub2   = pubSub.subscribe(gamesChannel)
      pub1   = pubSub.publish(eventsChannel)
      pub2   = pubSub.publish(gamesChannel)
      rs  <- Stream(
             sub1.through(sink("#events")),
             sub2.through(sink("#games")),
             Stream.awakeEvery[IO](3.seconds) >> Stream.eval(IO(Random.nextInt(100).toString)).through(pub1),
             Stream.awakeEvery[IO](5.seconds) >> Stream.emit("Pac-Man!").through(pub2),
             Stream.awakeDelay[IO](11.seconds) >> pubSub.unsubscribe(gamesChannel),
             Stream.awakeEvery[IO](6.seconds) >> pubSub
               .pubSubSubscriptions(List(eventsChannel, gamesChannel))
               .evalMap(x => IO(println(x)))
           ).parJoin(6).drain
    } yield rs

  override def run(args: List[String]): IO[ExitCode] =
    program.compile.drain.as(ExitCode.Success)

}
```
