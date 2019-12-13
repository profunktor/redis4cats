---
layout: docs
title:  "Server"
number: 12
---

# Server API

Purely functional interface for the [Server API](https://redis.io/commands#server).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.ServerCommands
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, ServerCommands[IO, String]] = {
  Redis[IO, String, String](null, null, null).widen[ServerCommands[IO, String]]
}
```

### Server Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // ServerCommands[IO]
  for {
    _ <- cmd.flushAll
    _ <- cmd.flushAllAsync
  } yield ()
}
```

