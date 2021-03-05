---
layout: docs
title:  "Strings"
number: 10
---

# Strings API

Purely functional interface for the [Strings API](https://redis.io/commands#string).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, StringCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[StringCommands[IO, String, String]]
}
```

### String Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

val usernameKey = "users"

def putStrLn(str: String): IO[Unit] = IO(println(str))

val showResult: Option[String] => IO[Unit] =
  _.fold(putStrLn(s"Not found key: $usernameKey"))(s => putStrLn(s))

commandsApi.use { cmd => // StringCommands[IO, String, String]
  for {
    x <- cmd.get(usernameKey)
    _ <- showResult(x)
    _ <- cmd.set(usernameKey, "gvolpe")
    y <- cmd.get(usernameKey)
    _ <- showResult(y)
    _ <- cmd.setNx(usernameKey, "should not happen")
    w <- cmd.get(usernameKey)
    _ <- showResult(w)
  } yield ()
}
```

