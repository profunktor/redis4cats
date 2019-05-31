---
layout: docs
title:  "Pipelining"
number: 5
position: 4
---

# Pipelining

Use [pipelining](https://redis.io/topics/pipelining) to speed up your queries by having full control of commands flushing. By default Redis works in autoflush mode but it can be disabled to "pipeline" commands to the server without waiting for a response. And at any point in time you can "flush commands".

`redis4cats` provides a `RedisPipeline` utility that models this behavior with some guarantees described below:

- `acquire`: disable autoflush.
- `use`: send a bunch of commands defined as `F[A]`.
- `release`: either flush commands on success or log error on failure / cancellation.
- `guarantee`: re-enable autoflush.

### RedisPipeline usage

The API for disabling / enabling autoflush and flush commands manually is available for you to use but since the pattern
is so common it is recommended to just use `RedisPipeline`. You can create a pipeline by passing the commands API as a parameter and invoke the `run` function given the set of commands you wish to send to the server.

Note that every command has to be forked (`.start`) because the commands need to be sent to the server in an asynchronous way but no response will be received until the commands are successfully flushed. Also, it is not possible to sequence commands (`flatMap`) that are part of a pipeline. Every command has to be atomic and independent of previous results.

```tut:book:invisible
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.log4cats._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, RedisCommands[IO, String, String]] = {
  Redis[IO, String, String](null, null, null)
}
```

```tut:book:silent
import cats.effect.IO
import cats.implicits._
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.pipeline._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

implicit val timer = IO.timer(ExecutionContext.global)

def putStrLn(str: String): IO[Unit] = IO(println(str))

val key = "testp"

val showResult: Int => Option[String] => IO[Unit] = n =>
  _.fold(putStrLn(s"Not found key $key-$n"))(s => putStrLn(s))

commandsApi.use { cmd => // RedisCommands[IO, String, String]
  def traversal(f: Int => IO[Unit]): IO[Unit] =
    List.range(0, 50).traverse(f).void

  val setters: IO[Unit] =
    traversal(n => cmd.set(s"$key-$n", (n * 2).toString).start.void)

  val getters: IO[Unit] =
    traversal(n => cmd.get(s"$key-$n").flatMap(showResult(n)))

  RedisPipeline(cmd).run(setters) *> IO.sleep(2.seconds) *> getters
}
```

