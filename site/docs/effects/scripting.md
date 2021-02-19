---
layout: docs
title:  "Scripting"
number: 12
---

# Scripting API

Purely functional interface for the [Scripting API](https://redis.io/commands#scripting).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.ScriptCommands
import dev.profunktor.redis4cats.effects.ScriptOutputType
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.unsafeCreate[IO]

val commandsApi: Resource[IO, ScriptCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[ScriptCommands[IO, String, String]]
}
```

### Script Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // ScriptCommands[IO, String, String]
  for {
    // returns a String according the value codec (the last type parameter of ScriptCommands)
    greeting: String <- cmd.eval("return 'Hello World'", ScriptOutputType.Value)
    _ <- putStrLn(s"Greetings from Lua: $greeting")
  } yield ()
}
```

The return type depends on the `ScriptOutputType` you pass and needs to suite the result of the Lua script itself. Possible values are `Integer`, `Value` (for decoding the result using the value codec), `Multi` (for many values) and `Status` (maps to `Unit` in Scala). Scripts can be cached for better performance using `scriptLoad` and then executed via `evalSha`, see the [redis docs]((https://redis.io/commands#scripting)) for details.
