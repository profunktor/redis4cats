---
layout: docs
title:  "Bloom Filter"
number: 18
---

# Bloom Filter API

Purely functional interface for the [Bloom Filter API](https://redis.io/docs/latest/commands/?group=bf), a
probabilistic data structure for testing set membership: it may report false positives, but never false
negatives.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.BloomFilterCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, BloomFilterCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[BloomFilterCommands[IO, String, String]]
}
```

### Bloom Filter Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
commandsApi.use { redis => // BloomFilterCommands[IO, String, String]
  for {
    _      <- redis.bfReserve("users", 0.001, 10000L)          // BF.RESERVE — 0.1% error rate, 10k capacity
    _      <- redis.bfAdd("users", "alice")                    // BF.ADD
    _      <- redis.bfMAdd("users", "bob", "carol")            // BF.MADD
    exists <- redis.bfMExists("users", "alice", "dave")        // BF.MEXISTS — List(true, false)
    _      <- redis.bfInsert(                                  // BF.INSERT — creates the filter if missing
                "admins",
                BfInsertArgs.Create(capacity = Some(100L), scaling = Some(BfScaling.NonScaling)),
                "alice"
              )
    info   <- redis.bfInfo("users")                            // BF.INFO
  } yield (exists, info)
}
```

`bfMAdd` and `bfInsert` return one element per value: `Some(true)` if it was newly added, `Some(false)` if it
may have existed already, and `None` if adding it failed (e.g. a non-scaling filter reached its capacity).

A filter can be copied chunk by chunk with `bfScanDump` and `bfLoadChunk`, starting from iterator `0` and
stopping once `bfScanDump` returns iterator `0`.
