---
layout: docs
title:  "Sets"
number: 8
---

# Sets API

Purely functional interface for the [Sets API](https://redis.io/commands#set).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.SetCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, SetCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[SetCommands[IO, String, String]]
}
```

### Set Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

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
