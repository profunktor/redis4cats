---
layout: docs
title:  "Effect-based API"
number: 3
position: 2
---

# Effect-based API

The API that operates at the effect level `F[_]` on top of `cats-effect`.

- **[Geo API](./geo)**
- **[Hashes API](./hashes)**
- **[Lists API](./lists)**
- **[Sets API](./sets)**
- **[Sorted SetsAPI](./sortedsets)**
- **[Strings API](./strings)**

### Acquiring client and connection

For all the effect-based APIs the process of acquiring a client and a commands connection is exactly the same. There are basically two options: to either operate at the `Effect` level or at the `Stream` level. If your app does not do any streaming the recommended way is to use the former. Both methods `apply` and `stream` are available on `Fs2RedisClient`:

```scala
def apply[F[_]](uri: RedisURI): Resource[F, Fs2RedisClient]
```

```scala
def stream[F[_]](uri: RedisURI): Stream[F, Fs2RedisClient]
```

### Establishing connection

Here's an example of acquiring a client and a connection to the `Strings API`:

```tut:book:silent
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.StringCommands
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisClient
import com.github.gvolpe.fs2redis.model.{DefaultRedisCodec, Fs2RedisCodec}
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.{RedisCodec, StringCodec}

import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)

val redisURI: RedisURI                         = RedisURI.create("redis://localhost")
val stringCodec: Fs2RedisCodec[String, String] = DefaultRedisCodec(StringCodec.UTF8)

val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    client <- Fs2RedisClient[IO](redisURI)
    redis  <- Fs2Redis[IO, String, String](client, stringCodec, redisURI)
  } yield redis
```

The only difference with other APIs will be the `Commands` type. For the `Strings API` is `StringCommands`, for `Sorted Sets API` is `SortedSetCommands` and so on. For a complete list please take a look at the
[algebras](https://github.com/gvolpe/fs2-redis/tree/master/core/src/main/scala/com/github/gvolpe/fs2redis/algebra).

### Standalone, Sentinel or Cluster

You can connect in any of these modes by either using `RedisURI.create` or `RedisURI.Builder`. More information
[here](https://github.com/lettuce-io/lettuce-core/wiki/Redis-URI-and-connection-details).

### Master / Slave connection

The process is a bit different. First of all, you don't need to create a `Fs2RedisClient`, it'll be created for you. All you need is `Fs2RedisMasterSlave` that exposes in a similar way one method `apply` to operate at the effect level and another method `stream` to operate at the stream level.

```scala
def apply[F[_], K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
  readFrom: Option[ReadFrom] = None): Resource[F, Fs2RedisMasterSlaveConnection[K, V]]
```

```scala
def stream[F[_], K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
  readFrom: Option[ReadFrom] = None): Stream[F, Fs2RedisMasterSlaveConnection[K, V]]
```

#### Example using the Strings API

```tut:book:silent
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.StringCommands
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.interpreter.connection.Fs2RedisMasterSlave
import com.github.gvolpe.fs2redis.model.Fs2RedisMasterSlaveConnection
import io.lettuce.core.{ReadFrom, RedisURI}
import io.lettuce.core.codec.{RedisCodec, StringCodec}

val redisURI: RedisURI                         = RedisURI.create("redis://localhost")
val stringCodec: Fs2RedisCodec[String, String] = DefaultRedisCodec(StringCodec.UTF8)

val connection: Resource[IO, Fs2RedisMasterSlaveConnection[String, String]] =
  Fs2RedisMasterSlave[IO, String, String](stringCodec, redisURI)(Some(ReadFrom.MASTER_PREFERRED))

connection.use { conn =>
  Fs2Redis.masterSlave[IO, String, String](conn).flatMap { cmd =>
    IO.unit  // do something
  }
}
```

Find more information [here](https://github.com/lettuce-io/lettuce-core/wiki/Master-Slave#examples).
