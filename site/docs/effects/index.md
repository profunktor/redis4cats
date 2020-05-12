---
layout: docs
title:  "Effects API"
number: 2
position: 2
---

# Effects API

The API that operates at the effect level `F[_]` on top of `cats-effect`.

- **[Connection API](./connection.html)**
- **[Geo API](./geo.html)**
- **[Hashes API](./hashes.html)**
- **[Lists API](./lists.html)**
- **[Scripting API](./scripting.html)**
- **[Server API](./server.html)**
- **[Sets API](./sets.html)**
- **[Sorted SetsAPI](./sortedsets.html)**
- **[Strings API](./strings.html)**

### Acquiring client and connection

For all the effect-based APIs the process of acquiring a client and a commands connection is via the `apply` method that returns a `Resource`:

```scala
def apply[F[_]](uri: RedisURI): Resource[F, RedisClient]
```

### Logger

In order to create a client and/or connection you must provide a `Log` instance that the library uses for internal logging. You could either create your own or use `log4cats` (recommended). `redis4cats` can derive an instance of `Log[F]` if there is an instance of `Logger[F]` in scope, just need to add the extra dependency `redis4cats-log4cats` and `import dev.profunktor.redis4cats.log4cats._`.

Take a look at the [examples](https://github.com/profunktor/redis4cats/blob/master/modules/examples/src/main/scala/dev/profunktor/redis4cats/LoggerIOApp.scala) to find out more.

### Establishing connection

Here's an example of acquiring a client and a connection to the `Strings API`:

```scala mdoc:silent
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8

val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(RedisURI.fromClient[IO]("redis://localhost"))
    client <- RedisClient[IO](uri)
    redis  <- Redis[IO].fromClient(client, stringCodec)
  } yield redis
```

The only difference with other APIs will be the `Commands` type. For the `Strings API` is `StringCommands`, for `Sorted Sets API` is `SortedSetCommands` and so on. For a complete list please take a look at the [algebras](https://github.com/profunktor/redis4cats/tree/master/modules/effects/src/main/scala/dev/profunktor/redis4cats/algebra).

Doing it this way, you can share the same `RedisClient` to establish many different connections. If your use case is simple, have a look at the section below.

### Single node connection

For those who only need a simple API access to Redis commands, there are a few ways to acquire a connection:

```scala mdoc:silent
val simpleApi: Resource[IO, StringCommands[IO, String, String]] =
  Redis[IO].simple("redis://localhost", RedisCodec.Ascii)
```

Or the most common one:

```scala mdoc:silent
val utf8Api: Resource[IO, StringCommands[IO, String, String]] =
  Redis[IO].utf8("redis://localhost")
```

### Standalone, Sentinel or Cluster

You can connect in any of these modes by either using `JRedisURI.create` or `JRedisURI.Builder`. More information
[here](https://github.com/lettuce-io/lettuce-core/wiki/Redis-URI-and-connection-details).

### Cluster connection

The process looks mostly like standalone connection but with small differences:

```scala mdoc:silent
val clusterApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(RedisURI.fromClient[IO]("redis://localhost:30001"))
    client <- RedisClusterClient[IO](uri)
    redis  <- Redis[IO].fromClusterClient(client, stringCodec)
  } yield redis
```

You can also make it simple if you don't need to reuse the client:

```scala mdoc:silent
val clusterUtf8Api: Resource[IO, StringCommands[IO, String, String]] =
  Redis[IO].clusterUtf8("redis://localhost:30001")
```

### Master / Replica connection

The process is a bit different. First of all, you don't need to create a `RedisClient`, it'll be created for you. All you need is `RedisMasterReplica` that exposes in a similar way one method `apply` that returns a `Resource`.

```scala
def apply[F[_], K, V](codec: RedisCodec[K, V], uris: RedisURI*)(
  readFrom: Option[ReadFrom] = None): Resource[F, RedisMasterReplica[K, V]]
```

#### Example using the Strings API

```scala mdoc:silent
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection.RedisMasterReplica
import dev.profunktor.redis4cats.data.ReadFrom

// Already imported above, but if copying from this block is necessary
// val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8

val commands: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri <- Resource.liftF(RedisURI.fromClient[IO]("redis://localhost"))
    conn <- RedisMasterReplica[IO].fromClient(stringCodec, uri)(Some(ReadFrom.MasterPreferred))
    cmds <- Redis[IO].masterReplica(conn)
  } yield cmds

commands.use { cmd =>
  cmd.set("foo", "123") >> IO.unit  // do something
}
```

Find more information [here](https://github.com/lettuce-io/lettuce-core/wiki/Master-Replica#examples).
