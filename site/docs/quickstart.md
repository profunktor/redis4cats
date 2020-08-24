---
layout: docs
title:  "Quick Start"
number: 1
position: 1
---

# Quick Start

```scala mdoc:silent
import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout._

object QuickStart extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Redis[IO].utf8("redis://localhost").use { cmd =>
      for {
        _ <- cmd.set("foo", "123")
        x <- cmd.get("foo")
        _ <- cmd.setNx("foo", "should not happen")
        y <- cmd.get("foo")
        _ <- IO(println(x === y)) // true
      } yield ExitCode.Success
    }

}
```

This is the simplest way to get up and running with a single-node Redis connection. Beware that `.use` will open a connection for you to use in the block and then ensure it gets closed at the end of the block.

If you would like to have a more imperative approach you can use the `allocated` function of `cats.effect.Resource`.

```scala mdoc:silent
import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout._

object QuickStartImperative {

  def run(args: Array[String]): Unit = {
    implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
    val resource = Redis[IO].utf8("redis://localhost")

    val (cmd, cleanUp) = resource.allocated.unsafeRunSync()

    try {
      val io = for {
        _ <- cmd.set("foo", "123")
        x <- cmd.get("foo")
        _ <- cmd.setNx("foo", "should not happen")
        y <- cmd.get("foo")
        _ <- IO(println(x === y)) // true
      } yield ()

      // Run our Redis commands
      io.unsafeRunSync()
    }
    finally {
      // Close the redis connection.
      cleanUp.unsafeRunSync()
    }
  }
  
}
```

To learn more about commands, clustering, pipelining and transactions, please have a look at the extensive documentation.

You can continue reading about the different ways of acquiring a client and a connection [here](./client.html).
