---
layout: docs
title:  "Strings"
number: 10
---

# Strings API

Purely functional interface for the [Strings API](https://redis.io/commands#string).

```tut:book:invisible
import cats.effect.{IO, Resource}
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, StringCommands[IO, String, String]] = {
  Redis[IO, String, String](null, null, null).map(_.asInstanceOf[StringCommands[IO, String, String]])
}
```

### String Commands usage

Once you have acquired a connection you can start using it:

```tut:book:silent
import cats.effect.IO
import cats.syntax.all._

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

