---
layout: docs
title:  "Server"
number: 12
---

# Server API

Purely functional interface for the [Server API](https://redis.io/commands#server).

```tut:book:invisible
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.ServerCommands
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis

import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)

val commandsApi: Resource[IO, ServerCommands[IO]] = {
  Fs2Redis[IO, String, String](null, null, null).widen[ServerCommands[IO]]
}
```

### Server Commands usage

Once you have acquired a connection you can start using it:

```tut:book:silent
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

