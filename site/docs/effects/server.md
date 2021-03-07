---
layout: docs
title:  "Server"
number: 12
---

# Server API

Purely functional interface for the [Server API](https://redis.io/commands#server).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.ServerCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, ServerCommands[IO, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[ServerCommands[IO, String]]
}
```

### Server Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // ServerCommands[IO]
  for {
    _ <- cmd.flushAll
    _ <- cmd.flushAllAsync
  } yield ()
}
```

