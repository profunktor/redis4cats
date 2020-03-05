---
layout: docs
title:  "Effects API"
number: 1
position: 1
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
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection.{RedisClient, RedisURI}
import dev.profunktor.redis4cats.domain.RedisCodec
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8

val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(RedisURI.make[IO]("redis://localhost"))
    client <- RedisClient[IO](uri)
    redis  <- Redis[IO, String, String](client, stringCodec)
  } yield redis
```

The only difference with other APIs will be the `Commands` type. For the `Strings API` is `StringCommands`, for `Sorted Sets API` is `SortedSetCommands` and so on. For a complete list please take a look at the
[algebras](https://github.com/profunktor/redis4cats/tree/master/modules/effects/src/main/scala/dev/profunktor/redis4cats/algebra).

### Standalone, Sentinel or Cluster

You can connect in any of these modes by either using `JRedisURI.create` or `JRedisURI.Builder`. More information
[here](https://github.com/lettuce-io/lettuce-core/wiki/Redis-URI-and-connection-details).

### Cluster connection

The process looks mostly like standalone connection but with small differences:

```scala
val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(RedisURI.make[IO]("redis://localhost:30001"))
    client <- RedisClusterClient[IO](uri)
    redis  <- Redis.cluster[IO, String, String](client, stringCodec)
  } yield redis
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
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection.RedisMasterReplica
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.domain.{ ReadFrom}

// Already Imported Above, but if copying from this block is necessary
// val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8

val connection: Resource[IO, RedisMasterReplica[String, String]] =
  Resource.liftF(RedisURI.make[IO]("redis://localhost")).flatMap { uri =>
    RedisMasterReplica[IO, String, String](stringCodec, uri)(Some(ReadFrom.MasterPreferred))
  }

connection.use { conn =>
  Redis.masterReplica[IO, String, String](conn).flatMap { cmd =>
    cmd.set("foo", "123") >> IO.unit  // do something
  }
}
```

Find more information [here](https://github.com/lettuce-io/lettuce-core/wiki/Master-Replica#examples).
