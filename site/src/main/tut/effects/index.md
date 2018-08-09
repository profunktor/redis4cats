---
layout: docs
title:  "Effects-based API"
number: 3
position: 2
---

# Effects-based API

- **[Geo API](./geo)**: Purely functional `Geo API`.
- **[Strings API](./strings)**: Purely functional `Strings API`.

### Acquiring client and connection

For all the effects-based APIs a client and a commands connection is exactly the same. There are basically two options: to either operate at the `Effect` level or at the `Stream` level. If your app does not do any streaming the recommended way is to use the former. Both methods `apply` and `stream` are available on `Fs2RedisClient`:

```scala
def apply[F[_]: Concurrent: Log](uri: RedisURI): Resource[F, Fs2RedisClient]
```

```scala
def stream[F[_]: Concurrent: Log](uri: RedisURI): Stream[F, Fs2RedisClient]
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

import scala.concurrent.ExecutionContext.Implicits.global

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
