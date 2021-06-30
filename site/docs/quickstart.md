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

object QuickStart extends IOApp.Simple {

  def run: IO[Unit] =
    Redis[IO].utf8("redis://localhost").use { redis =>
      for {
        _ <- redis.set("foo", "123")
        x <- redis.get("foo")
        _ <- redis.setNx("foo", "should not happen")
        y <- redis.get("foo")
        _ <- IO(println(x === y)) // true
      } yield ()
    }

}
```

This is the simplest way to get up and running with a single-node Redis connection. To learn more about commands, clustering, pipelining and transactions, please have a look at the extensive documentation.

You can continue reading about the different ways of acquiring a client and a connection [here](./client.html).
