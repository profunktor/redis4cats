---
layout: docs
title:  "Effect-based API"
number: 3
position: 2
---

# Effect-based API

The API that operates at the effect level `F[_]` on top of `cats-effect`.

- **[Connection API](./connection)**
- **[Geo API](./geo)**
- **[Hashes API](./hashes)**
- **[Lists API](./lists)**
- **[Server API](./server)**
- **[Sets API](./sets)**
- **[Sorted SetsAPI](./sortedsets)**
- **[Strings API](./strings)**

### Acquiring client and connection

For all the effect-based APIs the process of acquiring a client and a commands connection is via the `apply` method that returns a `Resource`:

```scala
def apply[F[_]](uri: RedisURI): Resource[F, Fs2RedisClient]
```

### Logger

In order to create a client and/or connection you must provide a `Log` instance that the library uses for internal logging. You could either create your own or use `log4cats` (recommended). `fs2-redis` can derive an instance of `Log[F]` if there is an instance of `Logger[F]` in scope, just need to add the extra dependency `fs2-redis-log4cats` and `import com.github.gvolpe.fs2redis.log4cats._`.

Take a look at the [examples](https://github.com/gvolpe/fs2-redis/blob/master/modules/examples/src/main/scala/com/github/gvolpe/fs2redis/LoggerIOApp.scala) to find out more.

### Establishing connection

Here's an example of acquiring a client and a connection to the `Strings API`:

```tut:book:silent
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.StringCommands
import com.github.gvolpe.fs2redis.connection.{Fs2RedisClient, Fs2RedisURI}
import com.github.gvolpe.fs2redis.domain.{DefaultRedisCodec, Fs2RedisCodec}
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.log4cats._
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.{RedisCodec, StringCodec}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val stringCodec: Fs2RedisCodec[String, String] = DefaultRedisCodec(StringCodec.UTF8)

val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(Fs2RedisURI.make[IO]("redis://localhost"))
    client <- Fs2RedisClient[IO](uri)
    redis  <- Fs2Redis[IO, String, String](client, stringCodec, uri)
  } yield redis
```

The only difference with other APIs will be the `Commands` type. For the `Strings API` is `StringCommands`, for `Sorted Sets API` is `SortedSetCommands` and so on. For a complete list please take a look at the
[algebras](https://github.com/gvolpe/fs2-redis/tree/master/modules/core/src/main/scala/com/github/gvolpe/fs2redis/algebra).

### Standalone, Sentinel or Cluster

You can connect in any of these modes by either using `RedisURI.create` or `RedisURI.Builder`. More information
[here](https://github.com/lettuce-io/lettuce-core/wiki/Redis-URI-and-connection-details).

### Cluster connection

The process looks mostly like standalone connection but with small differences:

```scala
val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(Fs2RedisURI.make[IO]("redis://localhost:30001"))
    client <- Fs2RedisClusterClient[IO](uri)
    redis  <- Fs2Redis.cluster[IO, String, String](client, stringCodec, uri)
  } yield redis
```

### Master / Slave connection

The process is a bit different. First of all, you don't need to create a `Fs2RedisClient`, it'll be created for you. All you need is `Fs2RedisMasterSlave` that exposes in a similar way one method `apply` that returns a `Resource`.

```scala
def apply[F[_], K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
  readFrom: Option[ReadFrom] = None): Resource[F, Fs2RedisMasterSlaveConnection[K, V]]
```

#### Example using the Strings API

```tut:book:silent
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.StringCommands
import com.github.gvolpe.fs2redis.connection.Fs2RedisMasterSlave
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.domain.Fs2RedisMasterSlaveConnection
import io.lettuce.core.{ReadFrom, RedisURI}
import io.lettuce.core.codec.{RedisCodec, StringCodec}

val stringCodec: Fs2RedisCodec[String, String] = DefaultRedisCodec(StringCodec.UTF8)

val connection: Resource[IO, Fs2RedisMasterSlaveConnection[String, String]] =
  Resource.liftF(Fs2RedisURI.make[IO]("redis://localhost")).flatMap { uri =>
    Fs2RedisMasterSlave[IO, String, String](stringCodec, uri)(Some(ReadFrom.MASTER_PREFERRED))
  }

connection.use { conn =>
  Fs2Redis.masterSlave[IO, String, String](conn).flatMap { cmd =>
    IO.unit  // do something
  }
}
```

Find more information [here](https://github.com/lettuce-io/lettuce-core/wiki/Master-Slave#examples).
