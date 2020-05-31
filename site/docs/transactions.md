---
layout: docs
title:  "Transactions"
number: 6
position: 6
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
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)
implicit val timer = IO.timer(ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, RedisCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]])
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
  _.fold(Log[IO].info(s"Key not found: $key"))(s => Log[IO].info(s"$key: $s"))

commandsApi.use { cmd => // RedisCommands[IO, String, String]
  val tx = RedisTransaction(cmd)

  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *>
      cmd.get(key2).flatTap(showResult(key2))

  // the commands type is fully inferred
  // IO[Unit] :: IO[Option[String]] :: IO[Unit] :: HNil
  val commands = cmd.set(key1, "foo") :: cmd.get(key1) :: cmd.set(key2, "bar") :: HNil

  // the result type is inferred as well
  // Option[String] :: HNil
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

### Optimistic locking

Redis provides an [optimistic locking using check-and-set](https://redis.io/topics/transactions#optimistic-locking-using-check-and-set) mechanism by using the `WATCH` command. Quoting the Redis documentation:

> `WATCH`ed keys are monitored in order to detect changes against them. If at least one watched key is modified before the `EXEC` command, the whole transaction aborts, and `EXEC` returns a `Null` reply to notify that the transaction failed.

This library translates the `Null` reply as a `TransactionDiscarded` error raised in the effect type. E.g.:

```scala mdoc:silent
val mkClient: Resource[IO, RedisClient] =
  Resource.liftF(RedisURI.make[IO]("redis://localhost")).flatMap(RedisClient[IO](_))

val mkRedis: Resource[IO, RedisCommands[IO, String, String]] =
  mkClient.flatMap(cli => Redis[IO].fromClient(cli, RedisCodec.Utf8))

def txProgram(v1: String, v2: String) =
  mkRedis
    .use { cmd =>
      val getters =
        cmd.get(key1).flatTap(showResult(key1)) *>
            cmd.get(key2).flatTap(showResult(key2))

      val operations =
        cmd.set(key1, "sad") :: cmd.set(key2, "windows") :: cmd.get(key1) ::
            cmd.set(key1, v1) :: cmd.set(key2, v2) :: cmd.get(key1) :: HNil

      val prog: IO[Unit] =
        RedisTransaction(cmd)
          .exec(operations)
          .flatMap {
            case _ ~: _ ~: res1 ~: _ ~: _ ~: res2 ~: HNil =>
              Log[IO].info(s"res1: $res1, res2: $res2")
          }
          .onError {
            case TransactionAborted =>
              Log[IO].error("[Error] - Transaction Aborted")
            case TransactionDiscarded =>
              Log[IO].error("[Error] - Transaction Discarded")
            case _: TimeoutException =>
              Log[IO].error("[Error] - Timeout")
          }

      val watching =
        cmd.watch(key1, key2)

      getters >> watching >> prog >> getters >> Log[IO].info("keep doing stuff...")
    }
```

Note that if we want to run transactions concurrently, we need to acquire a connection per transaction (`RedisCommands`), as `MULTI` can not be called concurrently within the same connection, reason why it is recommended to share the same `RedisClient`.

Now before executing the transaction, we invoke `cmd.watch(key1, key2)`. Next, let's run two concurrent transactions:

```scala mdoc:silent
IO.race(txProgram("nix", "linux"), txProgram("foo", "bar")).void
```

In this case, only the first transaction will be successful. The second one will be discarded. However, we want to eventually succeed most of the time, in which case we can retry a transaction until it succeeds (optimistic locking).

```scala mdoc:silent
def retriableTx: IO[Unit] =
  txProgram("foo", "bar").handleErrorWith {
    case TransactionDiscarded => retriableTx
  }.uncancelable

IO.race(txProgram("nix", "linux"), retriableTx).void
```

The first transaction will be successful, but ultimately, the second transaction will retry and set the values "foo" and "bar".

All these examples can be found under [RedisTransactionsDemo](https://github.com/profunktor/redis4cats/blob/master/modules/examples/src/main/scala/dev/profunktor/redis4cats/RedisTransactionsDemo.scala) and [ConcurrentTransactionsDemo](https://github.com/profunktor/redis4cats/blob/master/modules/examples/src/main/scala/dev/profunktor/redis4cats/ConcurrentTransactionsDemo.scala), respectively.
