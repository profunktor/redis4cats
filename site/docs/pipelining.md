---
layout: docs
title:  "Pipelining"
number: 5
position: 5
---

# Pipelining

Use [pipelining](https://redis.io/topics/pipelining) to speed up your queries by having full control of commands flushing. By default Redis works in autoflush mode but it can be disabled to "pipeline" commands to the server without waiting for a response. And at any point in time you can "flush commands".

`redis4cats` provides a `RedisPipeline` utility that models this behavior with some guarantees described below:

- `acquire`: disable autoflush and send a bunch of commands defined as a custom `HList`.
- `release`: either flush commands on success or log error on failure / cancellation.
- `guarantee`: re-enable autoflush.

### RedisPipeline usage

The API for disabling / enabling autoflush and flush commands manually is available for you to use but since the pattern is so common it is recommended to just use `RedisPipeline`. You can create a pipeline by passing the commands API as a parameter and invoke the `exec` function (or `filterExec`) given the set of commands you wish to send to the server.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server in an asynchronous way but no response will be received until the commands are successfully flushed. Also, it is not possible to sequence commands (`flatMap`) that are part of a pipeline. Every command has to be atomic and independent of previous results.

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

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

val showResult: String => Option[String] => IO[Unit] = key =>
_.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s"$key: $s"))

commandsApi.use { cmd => // RedisCommands[IO, String, String]
  val getters =
    cmd.get(key1).flatTap(showResult(key1)) *>
        cmd.get(key2).flatTap(showResult(key2))

  val operations =
    cmd.set(key1, "noop") :: cmd.set(key2, "windows") :: cmd.get(key1) ::
        cmd.set(key1, "nix") :: cmd.set(key2, "linux") :: cmd.get(key1) :: HNil

  val prog =
    RedisPipeline(cmd)
      .filterExec(operations)
      .flatMap {
        case res1 ~: res2 ~: HNil =>
          putStrLn(s"res1: $res1, res2: $res2")
      }
      .onError {
        case PipelineError =>
          putStrLn("[Error] - Pipeline failed")
        case _: TimeoutException =>
          putStrLn("[Error] - Timeout")
      }

  getters >> prog >> getters >> putStrLn("keep doing stuff...")
}
```

The `filterExec` function filters out values of type `Unit`, which are normally irrelevant. If you find yourself needing the `Unit` types to verify some behavior, use `exec` instead.
