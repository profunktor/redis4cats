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
import com.github.gvolpe.fs2redis.algebra.ConnectionCommands
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)

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

