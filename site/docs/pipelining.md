---
layout: docs
title:  "Pipelining"
number: 7
position: 7
---

# Pipelining

Use [pipelining](https://redis.io/topics/pipelining) to speed up your queries by having full control of commands flushing. By default Redis works in autoflush mode but it can be disabled to "pipeline" commands to the server without waiting for a response. And at any point in time you can "flush commands".

`RedisCommands` provides two methods: `pipeline` and `pipeline_`, which are very similar to the transactional commands (see [Transactions](./transactions.html)). The behavior is modeled as a resource described below:

- `acquire`: disable autoflush and send a bunch of commands defined as a `List[F[Unit]]`.
- `release`: either flush commands on success or log error on failure / cancellation.
- `guarantee`: re-enable autoflush.

⚠️ **Pipelining shares the same asynchronous implementation of transactions, so you can only run sequential pipelines from a single `RedisCommands` instance.** ⚠️

### RedisPipeline usage

The API for disabling / enabling autoflush and flush commands manually is available for you to use but since the pattern is so common it is recommended to use `RedisPipe`, because it shares the same implementation of `RedisTx`, which can be tricky to get right.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server in an asynchronous way but no response will be received until the commands are successfully flushed. Also, it is not possible to sequence commands (`flatMap`) that are part of a pipeline. Every command has to be atomic and independent of previous results.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, RedisCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]])
}
```

```scala mdoc:silent
import cats.effect.IO
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.tx.TxStore

val key1 = "testp1"
val key2 = "testp2"
val key3 = "testp3"

val showResult: String => Option[String] => IO[Unit] = key =>
  _.fold(IO.println(s"Not found key: $key"))(s => IO.println(s"$key: $s"))

commandsApi.use { redis => // RedisCommands[IO, String, String]
  val getters =
    redis.get(key1).flatTap(showResult(key1)) >>
        redis.get(key2).flatTap(showResult(key2)) >>
        redis.get(key3).flatTap(showResult(key3))

  val ops = (store: TxStore[IO, String, Option[String]]) =>
    List(
      redis.set(key1, "osx"),
      redis.get(key3).flatMap(store.set(key3)),
      redis.set(key2, "linux")
    )

  val runPipeline =
    redis.pipeline(ops)
      .flatMap(kv => IO.println(s"KV: $kv"))
      .recoverWith {
        case e =>
          IO.println(s"[Error] - ${e.getMessage}")
      }

  val prog =
    for {
      _  <- redis.set(key3, "3")
      _  <- runPipeline
      v1 <- redis.get(key1)
      v2 <- redis.get(key2)
    } yield {
      assert(v1.contains("osx"))
      assert(v2.contains("linux"))
    }

  getters >> prog >> getters >> IO.println("keep doing stuff...")
}
```

The `pipeline` method provides a `TxStore` we can use to store values we run within the pipeline for later retrieval, same as we do with transactions. If you don't need the store, prefer to use `pipeline_` instead.
