---
layout: docs
title:  "Transactions"
number: 4
position: 3
---

# Transactions

Redis supports [transactions](https://redis.io/topics/transactions) via the `MULTI`, `EXEC` and `DISCARD` commands. `fs2-redis` models transactions as resources:

- `acquire`: begin transaction
- `use`: send transactional commands
- `release`: either commit on success or rollback on failure / cancellation.

### Working with transactions

The most common way is to create a `Transaction` by passing the commands API as a parameter followed by the commands you
want to run in a transactional way.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server in an asynchronous way but no response will be received until either an `EXEC` or a `DISCARD` command is sent, which is handled by `Transaction`.

```tut:book:invisible
import cats.effect.{IO, Resource}
import com.github.gvolpe.fs2redis.algebra._
import com.github.gvolpe.fs2redis.interpreter.Fs2Redis
import com.github.gvolpe.fs2redis.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, RedisCommands[IO, String, String]] = {
  Fs2Redis[IO, String, String](null, null, null)
}
```

```tut:book:silent
import cats.effect.IO
import cats.implicits._
import com.github.gvolpe.fs2redis.transactions._

def putStrLn(str: String): IO[Unit] = IO(println(str))

val key1 = "test1"
val key2 = "test2"

val showResult: String => Option[String] => IO[Unit] = key =>
  _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s))

commandsApi.use { cmd => // RedisCommands[IO, String, String]
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

  val tx1 = Transaction(cmd)(setters)
  val tx2 = Transaction(cmd)(failedSetters)
  // `apply` is syntax sugar for `resource(cmd).use(_ => fa)`
  val tx3 = Transaction.resource(cmd).use(_ => failedSetters)

  getters *> tx1 *> tx2.attempt *> tx3.attempt *> getters.void
}
```

### Transaction Pool

As demonstrated in the example above the easiest way to operate with transactions is by using the `Transaction.apply` method but you might also find convenient to create a `TransactionPool` to avoid passing the `RedisCommands` every time you need a transaction and then just use `run` to execute the given commands within a new transaction:

```tut:book:silent
commandsApi.use { cmd => // RedisCommands[IO, String, String]
  TransactionPool(cmd).use { txs => // TransactionPool[IO, String, String])
    val getters =
      cmd.get(key1).flatTap(showResult(key1)) *>
        cmd.get(key2).flatTap(showResult(key2))

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

    val tx1 = txs.run(setters) // Creates a new transaction and runs `setters`
    val tx2 = txs.run(failedSetters) // Creates a new transaction and runs `failedSetters`

    getters *> tx1 *> tx2.attempt *> getters.void
  }
}
```

Note that a `TransactionPool` is created as a `Resource` so it can be composed with the commands and clients on startup.

