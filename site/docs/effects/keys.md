---
layout: docs
title:  "Keys"
number: 10
---

# Keys API

Purely functional interface for the [Keys API](https://redis.io/commands#generic).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.KeyCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration._

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, KeyCommands[IO, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[KeyCommands[IO, String]]
}
```

### key Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

val key = "users"

commandsApi.use { cmd => // KeyCommands[IO, String]
  for {
    _ <- cmd.del(key)
    _ <- cmd.exists(key)
    _ <- cmd.expire(key, Duration(5, SECONDS))
  } yield ()
}
```

