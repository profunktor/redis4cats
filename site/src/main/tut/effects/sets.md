---
layout: docs
title:  "Sets"
number: 8
---

# Sets API

Purely functional interface for the [Sets API](https://redis.io/commands#set).

```tut:book:invisible
import cats.effect.{IO, Resource}
import cats.syntax.all._
import dev.profunktor.fs2redis.algebra.SetCommands
import dev.profunktor.fs2redis.interpreter.Fs2Redis
import dev.profunktor.fs2redis.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, SetCommands[IO, String, String]] = {
  Fs2Redis[IO, String, String](null, null, null).map(_.asInstanceOf[SetCommands[IO, String, String]])
}
```

### Set Commands usage

Once you have acquired a connection you can start using it:

```tut:book:silent
import cats.effect.IO
import cats.syntax.all._

val testKey = "foos"

def putStrLn(str: String): IO[Unit] = IO(println(str))

val showResult: Set[String] => IO[Unit] = x => putStrLn(s"$testKey members: $x")

commandsApi.use { cmd => // SetCommands[IO, String, String]
  for {
    x <- cmd.sMembers(testKey)
    _ <- showResult(x)
    _ <- cmd.sAdd(testKey, "set value")
    y <- cmd.sMembers(testKey)
    _ <- showResult(y)
    _ <- cmd.sCard(testKey).flatMap(s => putStrLn(s"size: ${s.toString}"))
    _ <- cmd.sRem("non-existing", "random")
    w <- cmd.sMembers(testKey)
    _ <- showResult(w)
    _ <- cmd.sRem(testKey, "set value")
    z <- cmd.sMembers(testKey)
    _ <- showResult(z)
    _ <- cmd.sCard(testKey).flatMap(s => putStrLn(s"size: ${s.toString}"))
  } yield ()
}
```
