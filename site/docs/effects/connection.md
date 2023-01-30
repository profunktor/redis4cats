---
layout: docs
title:  "Connection"
number: 11
---

# Connection API

Purely functional interface for the [Connection API](https://redis.io/commands#connection).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.ConnectionCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, ConnectionCommands[IO, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[ConnectionCommands[IO, String]]
}
```

### Connection Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { redis => // ConnectionCommands[IO, String]
  val clientName = "client_x"
  for {
    _ <- redis.ping.flatMap(putStrLn) // "pong"
    _ <- redis.setClientName(clientName) // true
    retrievedClientName <- redis.getClientName()
    _ <- putStrLn(retrievedClientName.getOrElse("")) // "client_x"
  } yield ()
}
```

