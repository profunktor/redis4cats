---
layout: docs
title:  "Sorted Sets"
number: 9
---

# Sorted Sets API

Purely functional interface for the [Sorted Sets API](https://redis.io/commands#sorted_set).

```tut:book:invisible
import cats.effect.{IO, Resource}
import cats.syntax.all._
import dev.profunktor.fs2redis.algebra.SortedSetCommands
import dev.profunktor.fs2redis.interpreter.Fs2Redis
import dev.profunktor.fs2redis.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, SortedSetCommands[IO, String, Long]] = {
  Fs2Redis[IO, String, String](null, null, null).map(_.asInstanceOf[SortedSetCommands[IO, String, Long]])
}
```

### Sorted Set Commands usage

Once you have acquired a connection you can start using it:

```tut:book:silent
import cats.effect.IO
import cats.syntax.all._
import dev.profunktor.fs2redis.effects.{Score, ScoreWithValue, ZRange}

val testKey = "zztop"

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // SortedSetCommands[IO, String, Long]
  for {
    _ <- cmd.zAdd(testKey, args = None, ScoreWithValue(Score(1), 1), ScoreWithValue(Score(3), 2))
    x <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
    _ <- putStrLn(s"Score: $x")
    y <- cmd.zCard(testKey)
    _ <- putStrLn(s"Size: $y")
    z <- cmd.zCount(testKey, ZRange(0, 1))
    _ <- putStrLn(s"Count: $z")
  } yield ()
}
```

