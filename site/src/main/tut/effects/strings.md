---
layout: docs
title:  "Strings"
number: 3
---

# Strings API

Purely functional interface for the [Strings API](https://redis.io/commands#string) definition.

### Establishing a connection

You can either operate at the `Effect` level or at the `Stream` level. If your app does not do any streaming the recommended way is to use the former. Both methods `apply` and `stream` are available on `Fs2RedisClient`:

```scala
def apply[F[_]: Concurrent: Log](uri: RedisURI): Resource[F, Fs2RedisClient]
```

```scala
def stream[F[_]: Concurrent: Log](uri: RedisURI): Stream[F, Fs2RedisClient]
```

Here's an example of acquiring a client and a connection to the Strings API:

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

### String Commands usage

Once you have acquired a connection you can start using it:

```tut:book:silent
val usernameKey = "users"

def putStrLn(str: String): IO[Unit] = IO(println(str))

val showResult: Option[String] => IO[Unit] =
  _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

commandsApi.use { cmd =>
  for {
    x <- cmd.get(usernameKey)
    _ <- showResult(x)
    _ <- cmd.set(usernameKey, "gvolpe")
    y <- cmd.get(usernameKey)
    _ <- showResult(y)
    _ <- cmd.setNx(usernameKey, "should not happen")
    w <- cmd.get(usernameKey)
    _ <- showResult(w)
    _ <- cmd.del(usernameKey)
    z <- cmd.get(usernameKey)
    _ <- showResult(z)
  } yield ()
}
```

