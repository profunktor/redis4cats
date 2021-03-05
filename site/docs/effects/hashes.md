---
layout: docs
title:  "Hashes"
number: 6
---

# Hashes API

Purely functional interface for the [Hashes API](https://redis.io/commands#hash).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.HashCommands
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.data._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, HashCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[HashCommands[IO, String, String]]
}
```

### Hash Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

val testKey   = "foo"
val testField = "bar"

def putStrLn(str: String): IO[Unit] = IO(println(str))

val showResult: Option[String] => IO[Unit] =
  _.fold(putStrLn(s"Not found key: $testKey | field: $testField"))(s => putStrLn(s))

commandsApi.use { cmd => // HashCommands[IO, String, String]
  for {
    x <- cmd.hGet(testKey, testField)
    _ <- showResult(x)
    _ <- cmd.hSet(testKey, testField, "some value")
    y <- cmd.hGet(testKey, testField)
    _ <- showResult(y)
    _ <- cmd.hSetNx(testKey, testField, "should not happen")
    w <- cmd.hGet(testKey, testField)
    _ <- showResult(w)
    _ <- cmd.hDel(testKey, testField)
    z <- cmd.hGet(testKey, testField)
    _ <- showResult(z)
  } yield ()
}
```
