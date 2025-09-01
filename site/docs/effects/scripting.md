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

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, ScriptCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[ScriptCommands[IO, String, String]]
}
```

### Script Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { redis => // ScriptCommands[IO, String, String]
  for {
    // returns a String according the value codec (the last type parameter of ScriptCommands)
    greeting <- redis.eval("return 'Hello World'", ScriptOutputType.Value)
    _ <- putStrLn(s"Greetings from Lua: $greeting")
  } yield ()
}
```

The return type depends on the `ScriptOutputType` you pass and needs to suite the result of the Lua script itself. Possible values are `Integer`, `Value` (for decoding the result using the value codec), `Multi` (for many values) and `Status` (maps to `Unit` in Scala). Scripts can be cached for better performance using `scriptLoad` and then executed via `evalSha`, see the [redis docs]((https://redis.io/commands#scripting)) for details.

### Lua Scripting Extensions

Redis4cats provides useful extensions to the Lua scripting; methods for loading scripts and executing them by their SHA1 are provided.

Suppose you have the following Lua script saved under the project's `resources` folder, `mymodule/src/main/resources/lua/hsetAndExpire.lua)`:

```lua
local key = KEYS[1]
local field = ARGV[1]
local value = ARGV[2]
local ttl = tonumber(ARGV[3])

local numFieldsSet = redis.call('hset', key, field, value)
redis.call('expire', key, ttl)
return numFieldsSet
```

Then you can load it into Redis and execute via:

```scala mdoc:silent
import dev.profunktor.redis4cats.extensions.luaScripting._

commandsApi.use { redis => // ScriptCommands[IO, String, String]
  for {
    hsetAndExpire <- LuaScript.loadFromResources[IO](redis)("hsetAndExpire.lua")
    value = "42"
    ttl = "10"
    _ <- redis.evalLua(
      hsetAndExpire,
      ScriptOutputType.Integer[String],
      keys = List("mySetKey"),
      values = List("myField", value, ttl)
    )
  } yield ()
}
```

The extension api provides the following methods:
- `LuaScript.make` to create a `LuaScript` instance from a string
- `LuaScript.loadFromResources` to load a Lua script from resources, path is configurable and defaults to `lua/`
- `evalLua` method as a shortcut for `evalSha` then falling back to `eval` if the script is not loaded yet

