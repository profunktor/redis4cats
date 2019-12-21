---
layout: docs
title:  "Transactions"
number: 4
position: 3
---

# Transactions

Redis supports [transactions](https://redis.io/topics/transactions) via the `MULTI`, `EXEC` and `DISCARD` commands. `redis4cats` provides a `RedisTransaction` utility that models a transaction as a resource via the primitive `bracketCase`.

- `acquire`: begin transaction
- `use`: send transactional commands
- `release`: either commit on success or rollback on failure / cancellation.

### Working with transactions

The most common way is to create a `RedisTransaction` once by passing the commands API as a parameter and invoke the `run` function every time you want to run the given commands as part of a new transaction.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server in an asynchronous way but no response will be received until either an `EXEC` or a `DISCARD` command is sent, which is handled by `RedisTransaction`. Also, it is not possible to sequence commands (`flatMap`) that are part of a transaction. Every command has to be atomic and independent of previous results.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.domain._
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, RedisCommands[IO, String, String]] = {
  Redis[IO, String, String](null, null.asInstanceOf[RedisCodec[String, String]])
}
```

```scala mdoc:silent
import cats.effect.IO
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.transactions._

def putStrLn(str: String): IO[Unit] = IO(println(str))

val key1 = "test1"
val key2 = "test2"

val showResult: String => Option[String] => IO[Unit] = key =>
  _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s))

commandsApi.use { cmd => // RedisCommands[IO, String, String]
  val tx = RedisTransaction(cmd)

  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *> cmd.get(key2).flatTap(showResult(key2))

  val setters =
    List(
      cmd.set(key1, "foo"),
      cmd.set(key2, "bar")
    ).traverse_(_.start)

  val failedSetters =
    List(
      cmd.set(key1, "qwe"),
      cmd.set(key2, "asd")
    ).traverse(_.start) *> IO.raiseError(new Exception("boom"))

  val tx1 = tx.run(setters)
  val tx2 = tx.run(failedSetters)

  getters *> tx1 *> tx2.attempt *> getters.void
}
```

