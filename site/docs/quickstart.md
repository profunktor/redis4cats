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

This is the simplest way to get up and running with a single-node Redis connection. To learn more about commands, clustering, pipelining and transactions, please have a look at the extensive documentation.

You can continue reading about the different ways of acquiring a client and a connection [here](./effects/).
