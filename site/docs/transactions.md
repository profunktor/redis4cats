---
layout: docs
title:  "Transactions"
number: 4
position: 3
---

# Transactions

Redis supports [transactions](https://redis.io/topics/transactions) via the `MULTI`, `EXEC` and `DISCARD` commands. `redis4cats` provides a `RedisTransaction` utility that models a transaction as a `Resource`.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server asynchronously and no response will be received until either an `EXEC` or a `DISCARD` command is sent. Both forking and sending the final command is handled by `RedisTransaction`.

These are internals, though. All you need to care about is what commands you want to run as part of a transaction
and handle the possible errors and retry logic.

### Working with transactions

The most common way is to create a `RedisTransaction` once by passing the commands API as a parameter and invoke the `exec` function (or `filterExec`) every time you want to run the given commands as part of a new transaction.

Every command has to be atomic and independent of previous Redis results, so it is not recommended to chain commands using `flatMap`.

Below you can find a first example of transactional commands.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)
implicit val timer = IO.timer(ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, RedisCommands[IO, String, String]] = {
  Redis[IO, String, String](null, null.asInstanceOf[RedisCodec[String, String]])
}
```

```scala mdoc:silent
import cats.effect.IO
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.transactions._
import java.util.concurrent.TimeoutException

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

  // the commands type is fully inferred
  // IO[Unit] :: IO[Option[String]] :: IO[Unit] :: HNil
  val commands = cmd.set(key1, "foo") :: cmd.get(key1) :: cmd.set(key2, "bar") :: HNil

  // the result type is inferred as well
  // Unit :: Option[String] :: Unit :: HNil
  val prog =
    tx.filterExec(commands)
      .flatMap {
        case res1 ~: HNil =>
          putStrLn(s"Key1 result: $res1")
      }
      .onError {
        case TransactionAborted =>
          putStrLn("[Error] - Transaction Aborted")
        case TransactionDiscarded =>
          putStrLn("[Error] - Transaction Discarded")
        case _: TimeoutException =>
          putStrLn("[Error] - Timeout")
      }

  getters >> prog >> getters.void
}
```

It should be exclusively used to run Redis commands as part of a transaction, not any other computations. Fail to do so, may result in unexpected behavior.

Transactional commands may be discarded if something went wrong in between. The possible errors you may get are:

- `TransactionDiscarded`: The `EXEC` command failed and the transactional commands were discarded.
- `TransactionAborted`: The `DISCARD` command was triggered due to cancellation or other failure within the transaction.
- `TimeoutException`: The transaction timed out due to some unknown error.

The `filterExec` function filters out values of type `Unit`, which are normally irrelevant. If you find yourself needing the `Unit` types to verify some behavior, use `exec` instead.

### How NOT to use transactions

For example, the following transaction will result in a dead-lock:

```scala mdoc:silent
commandsApi.use { cmd =>
  val tx = RedisTransaction(cmd)

  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *>
      cmd.get(key2).flatTap(showResult(key2))

  val setters = tx.exec(
    cmd.set(key1, "foo") :: cmd.set(key2, "bar") :: cmd.discard :: HNil
  )

  getters *> setters.void *> getters.void
}
```

You should never pass a transactional command: `MULTI`, `EXEC` or `DISCARD`.

The following example will result in a successful transaction on Redis. Yet, the operation will end up raising the error passed as a command.

```scala mdoc:silent
commandsApi.use { cmd =>
  val tx = RedisTransaction(cmd)

  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *>
      cmd.get(key2).flatTap(showResult(key2))

  val failedTx = tx.exec(
    cmd.set(key1, "foo") :: cmd.set(key2, "bar") :: IO.raiseError(new Exception("boom")) :: HNil
  )

  getters *> failedTx.void *> getters.void
}
```
