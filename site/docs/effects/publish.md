---
layout: docs
title:  "Publish"
number: 11
---

# Publish API

Purely functional interface for the [Redis Publish commands](https://redis.io/commands#pubsub).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.PublishCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, PublishCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[PublishCommands[IO, String, String]]
}
```

### Publish Commands usage

The Publish API provides simple, non-streaming methods to publish messages to Redis channels:

```scala mdoc:silent
import cats.effect.IO
import dev.profunktor.redis4cats.data.RedisChannel

val eventsChannel = RedisChannel("events")

commandsApi.use { redis => // PublishCommands[IO, String, String]
  for {
    // Publish a message to a channel
    // Returns the number of clients that received the message
    count <- redis.publish(eventsChannel, "hello world")
    _ <- IO.println(s"Message delivered to $count subscribers")

    // Publish to a shard channel (Redis Cluster)
    shardChannel = RedisChannel("shard-events")
    count2 <- redis.spublish(shardChannel, "shard message")
    _ <- IO.println(s"Shard message delivered to $count2 subscribers")
  } yield ()
}
```

### Relationship with Streams PubSub Module

The **effects module** provides the foundation that the **streams module** builds upon:

#### Effects Module (this API)
- **Traits**: `Publish[F, K, V]` and `PubSubStats[F, K]` (combined as `PublishAndStats`)
- **Publishing**: Simple, effect-based `publish` and `spublish` methods
- **Statistics**: Query Redis server pub/sub state (active channels, subscriber counts, etc.)
- **No streaming**: Returns direct `F[Long]` or `F[List[...]]` results
- **Part of standard RedisCommands**: Available alongside all other Redis commands
- **Use case**: Fire-and-forget messaging, notifications, checking pub/sub state
- **Connection**: Uses standard `StatefulRedisConnection`

```scala
// Example: Simple publish and stats
val redis: RedisCommands[IO, String, String] = ???

// Publish
redis.publish(RedisChannel("events"), "message"): IO[Long]

// Check statistics
redis.pubSubChannels: IO[List[RedisChannel[String]]]
redis.pubSubSubscriptions(RedisChannel("events")): IO[Option[Subscription[String]]]
```

#### Streams Module (PubSubCommands)
- **Extends effects traits**: `PublishCommands[F, S, K, V]` extends `algebra.Publish[F, K, V]` and `algebra.PubSubStats[F, K]`
- **Adds subscription functionality**: `subscribe`, `unsubscribe`, `psubscribe`, `punsubscribe`
- **FS2 Streams integration**: Streaming variants of publish, reactive subscription streams
- **Reactive**: Subscribe to channels and process messages as they arrive
- **Use case**: Real-time message processing, event-driven architectures with FS2
- **Connection**: Requires dedicated `StatefulRedisPubSubConnection`

```scala
// Example: Stream-based pub/sub (inherits all effects methods!)
val pubSub: PubSubCommands[IO, Stream[IO, *], String, String] = ???

// Subscribe (returns a stream of messages)
val subscriber: Stream[IO, String] = pubSub.subscribe(RedisChannel("events"))

// Publish - streaming variant
val publisher: Stream[IO, String] => Stream[IO, Long] = pubSub.publish(RedisChannel("events"))

// Publish - non-streaming variant (inherited from effects!)
pubSub.publish(RedisChannel("events"), "single message"): IO[Long]

// Statistics (inherited from effects!)
pubSub.pubSubChannels: IO[List[RedisChannel[String]]]
pubSub.numPat: IO[Long]
```

### When to use which?

**Use the Effects Publish API** when:
- You only need to publish messages (no subscription needed)
- You want simple, straightforward message publishing
- You're already using `RedisCommands` for other Redis operations
- You don't need streaming capabilities

**Use the Streams PubSub module** when:
- You need to subscribe to channels and process incoming messages
- You want reactive, stream-based message handling
- You're building event-driven systems with FS2
- You need pattern-based subscriptions (`psubscribe`)

### Available Commands

The Pub/Sub API is organized into three traits in the `dev.profunktor.redis4cats.algebra` package:

#### Publish

```scala
trait Publish[F[_], K, V] {

  /** Publishes a message to the given channel.
    *
    * @param channel the Redis channel to publish to
    * @param message the message value to publish
    * @return the number of clients that received the message
    */
  def publish(channel: RedisChannel[K], message: V): F[Long]

  /** Publishes a message to the given shard channel.
    *
    * @param channel the Redis shard channel to publish to
    * @param message the message value to publish
    * @return the number of clients that received the message
    */
  def spublish(channel: RedisChannel[K], message: V): F[Long]
}
```

#### PubSubStats

```scala
import dev.profunktor.redis4cats.data.{ RedisChannel, Subscription }

trait PubSubStats[F[_], K] {

  /** Returns the total number of pattern subscriptions across all clients. */
  def numPat: F[Long]

  /** Returns the number of subscribers for all channels. */
  def numSub: F[List[Subscription[K]]]

  /** Lists all currently active channels. */
  def pubSubChannels: F[List[RedisChannel[K]]]

  /** Lists all currently active shard channels. */
  def pubSubShardChannels: F[List[RedisChannel[K]]]

  /** Returns the subscription information for a specific channel. */
  def pubSubSubscriptions(channel: RedisChannel[K]): F[Option[Subscription[K]]]

  /** Returns the subscription information for the specified channels. */
  def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]

  /** Returns the number of subscribers for the specified shard channels. */
  def shardNumSub(channels: List[RedisChannel[K]]): F[List[Subscription[K]]]
}
```

**Note:** `Subscription[K]` is defined in `dev.profunktor.redis4cats.data`:
```scala
final case class Subscription[K](channel: RedisChannel[K], number: Long)
```

#### PublishAndStats

```scala
trait PublishAndStats[F[_], K, V] extends Publish[F, K, V] with PubSubStats[F, K]
```

The `RedisCommands` trait extends `PublishAndStats`, so all publish and statistics methods are available on the standard Redis connection.

**Important:** This trait does NOT include subscribe functionality. For full pub/sub with subscriptions, see the [streams module's PubSubCommands](../streams/pubsub.html).

### Complete Example

```scala mdoc:silent
import cats.effect._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisCodec }
import dev.profunktor.redis4cats.log4cats._

object PublishExample extends IOApp.Simple {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  def run: IO[Unit] = {
    RedisClient[IO].from("redis://localhost").use { client =>
      Redis[IO].fromClient(client, RedisCodec.Utf8).use { redis =>
        val channel = RedisChannel("notifications")

        for {
          // Publish a notification
          count <- redis.publish(channel, "System update available")
          _ <- IO.println(s"Notification sent to $count subscribers")

          // If no one is subscribed, count will be 0
          count2 <- redis.publish(RedisChannel("empty-channel"), "nobody listening")
          _ <- IO.println(s"Subscribers: $count2") // Will print "Subscribers: 0"

          // Check pub/sub statistics
          activeChannels <- redis.pubSubChannels
          _ <- IO.println(s"Active channels: ${activeChannels.map(_.underlying).mkString(", ")}")

          // Get subscriber information for specific channels
          subscriptions <- redis.pubSubSubscriptions(List(
            RedisChannel("notifications"),
            RedisChannel("alerts"),
            RedisChannel("events")
          ))
          _ <- subscriptions.traverse { sub =>
            IO.println(s"Channel '${sub.channel.underlying}' has ${sub.number} subscribers")
          }

          // Get total number of pattern subscriptions
          patternCount <- redis.numPat
          _ <- IO.println(s"Total pattern subscriptions: $patternCount")

          // Get all subscriptions across all channels
          allSubs <- redis.numSub
          _ <- IO.println(s"Total active subscriptions: ${allSubs.size}")

          // Check a specific channel's subscription
          maybeSub <- redis.pubSubSubscriptions(channel)
          _ <- maybeSub match {
            case Some(sub) => IO.println(s"Channel has ${sub.number} subscribers")
            case None => IO.println("Channel has no subscribers")
          }

          // Shard channels (for Redis Cluster)
          shardChannels <- redis.pubSubShardChannels
          _ <- IO.println(s"Active shard channels: ${shardChannels.size}")
        } yield ()
      }
    }
  }
}
```
