---
layout: docs
title:  "Lists"
number: 7
---

# Lists API

Purely functional interface for the [Lists API](https://redis.io/commands#list).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.ListCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, ListCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[ListCommands[IO, String, String]]
}
```

### List Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

val testKey = "listos"

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // ListCommands[IO, String, String]
  for {
    _ <- cmd.rPush(testKey, "one", "two", "three")
    x <- cmd.lRange(testKey, 0, 10)
    _ <- putStrLn(s"Range: $x")
    y <- cmd.lLen(testKey)
    _ <- putStrLn(s"Length: $y")
    a <- cmd.lPop(testKey)
    _ <- putStrLn(s"Left Pop: $a")
    b <- cmd.rPop(testKey)
    _ <- putStrLn(s"Right Pop: $b")
    z <- cmd.lRange(testKey, 0, 10)
    _ <- putStrLn(s"Range: $z")
  } yield ()
}
```
