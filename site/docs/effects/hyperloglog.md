---
layout: docs
title:  "HyperLogLog"
number: 17
---

# HyperLogLog API

Purely functional interface for the [HyperLogLog API](https://redis.io/commands#hyperloglog), a
probabilistic data structure for estimating the cardinality of a set using a small, fixed amount of
memory.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.HyperLogLogCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, HyperLogLogCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[HyperLogLogCommands[IO, String, String]]
}
```

### HyperLogLog Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
commandsApi.use { redis => // HyperLogLogCommands[IO, String, String]
  for {
    _     <- redis.pfAdd("visitors", "alice", "bob", "alice") // PFADD — duplicates are ignored
    count <- redis.pfCount("visitors")                        // PFCOUNT — approximate cardinality (~2)
    _     <- redis.pfAdd("today", "carol")
    _     <- redis.pfMerge("all-visitors", "visitors", "today") // PFMERGE — union into a new key
  } yield count
}
```

`pfCount` returns an estimate (with a standard error of ~0.81%), not an exact count — that is the
trade-off that lets a HyperLogLog track huge sets in a constant ~12 KB.
