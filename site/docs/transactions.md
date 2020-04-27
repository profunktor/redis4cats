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

Note that every command has to be forked (`.start`) because the commands need to be sent to the server asynchronously and no response will be received until either an `EXEC` or a `DISCARD` command is sent. Both forking and sending the final command is handled by `RedisTransaction`. Every command has to be atomic and independent of previous results, so it wouldn't make sense to chain commands using `flatMap` (though, it would technically work).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.domain._
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

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
    cmd.get(key1).flatTap(showResult(key1)) *>
      cmd.get(key2).flatTap(showResult(key2))

  val setters = tx.run(
    cmd.set(key1, "foo"),
    cmd.set(key2, "bar"),
    // .. more transactional commands ..
  )

  getters *> setters *> getters.void
}
```

It should be exclusively used to run Redis commands as part of a transaction, not any other computations. Fail to do so, may result in unexpected behavior.

For example, the following transaction will result in a dead-lock:

```scala mdoc:silent
commandsApi.use { cmd =>
  val tx = RedisTransaction(cmd)

  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *>
      cmd.get(key2).flatTap(showResult(key2))

  val setters = tx.run(
    cmd.set(key1, "foo"),
    cmd.set(key2, "bar"),
    cmd.discard
  )

  getters *> setters *> getters.void
}
```

You should never pass a transactional command: `MULTI`, `EXEC` or `DISCARD`.

The following example will result in a successful transaction; the error will be swallowed:

```scala mdoc:silent
commandsApi.use { cmd =>
  val tx = RedisTransaction(cmd)

  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *>
      cmd.get(key2).flatTap(showResult(key2))

  val failedTx = tx.run(
    cmd.set(key1, "foo"),
    cmd.set(key2, "bar"),
    IO.raiseError(new Exception("boom"))
  )

  getters *> failedTx *> getters.void
}
```
