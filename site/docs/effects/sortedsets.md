---
layout: docs
title:  "Sorted Sets"
number: 9
---

# Sorted Sets API

Purely functional interface for the [Sorted Sets API](https://redis.io/commands#sorted_set).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, SortedSetCommands[IO, String, Long]] = {
  Redis[IO].fromClient[String, Long](null, null.asInstanceOf[RedisCodec[String, Long]]).widen[SortedSetCommands[IO, String, Long]]
}
```

### Sorted Set Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange}

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

