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
import com.github.gvolpe.fs2redis.algebra.StringCommands
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

import scala.concurrent.ExecutionContext.Implicits.global

val commandsApi: Resource[IO, StringCommands[IO, String, String]] = {
  Fs2Redis[IO, String, String](null, null, null).map(_.asInstanceOf[StringCommands[IO, String, String]])
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
    _ <- cmd.del(usernameKey)
    z <- cmd.get(usernameKey)
    _ <- showResult(z)
  } yield ()
}
```

