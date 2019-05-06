---
layout: docs
title:  "Connection"
number: 11
---

# Connection API

Purely functional interface for the [Connection API](https://redis.io/commands#connection).

```tut:book:invisible
import cats.effect.{IO, Resource}
import cats.syntax.all._
import dev.profunktor.fs2redis.algebra.ConnectionCommands
import dev.profunktor.fs2redis.interpreter.Fs2Redis
import dev.profunktor.fs2redis.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, ConnectionCommands[IO]] = {
  Fs2Redis[IO, String, String](null, null, null).widen[ConnectionCommands[IO]]
}
```

### Connection Commands usage

Once you have acquired a connection you can start using it:

```tut:book:silent
import cats.effect.IO
import cats.syntax.all._

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // ConnectionCommands[IO]
  for {
    pong <- cmd.ping
    _ <- putStrLn(pong) //"pong"
  } yield ()
}
```

