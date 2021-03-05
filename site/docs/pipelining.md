---
layout: docs
title:  "Pipelining"
number: 7
position: 7
---

# Pipelining

Use [pipelining](https://redis.io/topics/pipelining) to speed up your queries by having full control of commands flushing. By default Redis works in autoflush mode but it can be disabled to "pipeline" commands to the server without waiting for a response. And at any point in time you can "flush commands".

`redis4cats` provides a `RedisPipeline` utility that models this behavior with some guarantees described below:

- `acquire`: disable autoflush and send a bunch of commands defined as a custom `HList`.
- `release`: either flush commands on success or log error on failure / cancellation.
- `guarantee`: re-enable autoflush.

## Caveats

⚠️ **Pipelining shares the same asynchronous implementation of transactions, meaning the order of the commands cannot be guaranteed.** ⚠️

This statement means that given the following set of operations.

```scala
val operations =
  cmd.set(key1, "osx") :: cmd.set(key2, "linux") :: cmd.get(key1) ::
    cmd.set(key1, "bar") :: cmd.set(key2, "foo") :: cmd.get(key1) :: HNil
```

The result of those two `get` operations will not be deterministic.

### RedisPipeline usage

The API for disabling / enabling autoflush and flush commands manually is available for you to use but since the pattern is so common it is recommended to just use `RedisPipeline`. You can create a pipeline by passing the commands API as a parameter and invoke the `exec` function (or `filterExec`) given the set of commands you wish to send to the server.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server in an asynchronous way but no response will be received until the commands are successfully flushed. Also, it is not possible to sequence commands (`flatMap`) that are part of a pipeline. Every command has to be atomic and independent of previous results.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
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
import dev.profunktor.redis4cats.pipeline._
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext

implicit val timer = IO.timer(ExecutionContext.global)

def putStrLn(str: String): IO[Unit] = IO(println(str))

val key1 = "testp1"
val key2 = "testp2"
val key3 = "testp3"

val showResult: String => Option[String] => IO[Unit] = key =>
_.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s"$key: $s"))

commandsApi.use { cmd => // RedisCommands[IO, String, String]
  val getters =
    cmd.get(key1).flatTap(showResult(key1)) >>
        cmd.get(key2).flatTap(showResult(key2)) >>
        cmd.get(key3).flatTap(showResult(key3))

   val operations =
      cmd.set(key1, "osx") :: cmd.get(key3) :: cmd.set(key2, "linux") :: cmd.sIsMember("foo", "bar") :: HNil

    val runPipeline =
      RedisPipeline(cmd)
        .filterExec(operations)
        .map {
          case res1 ~: res2 ~: HNil =>
            assert(res1.contains("3"))
            assert(!res2)
        }
        .onError {
          case PipelineError =>
            putStrLn("[Error] - Pipeline failed")
          case _: TimeoutException =>
            putStrLn("[Error] - Timeout")
        }

  val prog =
    for {
      _  <- cmd.set(key3, "3")
      _  <- runPipeline
      v1 <- cmd.get(key1)
      v2 <- cmd.get(key2)
    } yield {
      assert(v1.contains("osx"))
      assert(v2.contains("linux"))
    }

  getters >> prog >> getters >> putStrLn("keep doing stuff...")
}
```

The `filterExec` function filters out values of type `Unit`, which are normally irrelevant. If you find yourself needing the `Unit` types to verify some behavior, use `exec` instead.
