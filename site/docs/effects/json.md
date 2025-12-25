---
layout: docs
title:  "JSON"
number: 15
---

# RedisJSON API

Purely functional interface for the [RedisJSON API](https://redis.io/docs/latest/develop/data-types/json/).

## Prerequisites

**Important:** RedisJSON support requires the [RedisJSON module](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/json/) to be installed and loaded in your Redis server. This is a Redis Stack feature and is not available in standard Redis installations.

To use RedisJSON, you can:
- Use [Redis Stack](https://redis.io/docs/latest/operate/oss_and_stack/install/install-stack/) which includes RedisJSON
- Install the [RedisJSON module](https://github.com/RedisJSON/RedisJSON) separately
- Use Redis Cloud which includes RedisJSON support

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.JsonCommands
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.data._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.lettuce.core.json.{JsonPath, JsonValue}

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, JsonCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[JsonCommands[IO, String, String]]
}
```

## JSON Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO
import io.lettuce.core.json.{JsonPath, JsonValue}

val userKey = "user:1"
val rootPath = JsonPath.of("$")

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { redis => // JsonCommands[IO, String, String]
  for {
    // Set a JSON document
    _ <- redis.jSet(userKey, rootPath, JsonValue.of("""{"name":"Alice","age":30}"""))

    // Get the JSON document
    result <- redis.jGet(userKey, rootPath)
    _ <- putStrLn(s"User: $result")

    // Get raw JSON string
    raw <- redis.jGetRaw(userKey, rootPath)
    _ <- putStrLn(s"Raw JSON: $raw")

    // Set using raw JSON string (convenient for simple cases)
    _ <- redis.jSetStr(userKey, JsonPath.of("$.city"), """"New York"""")

    // Increment a numeric field
    _ <- redis.numIncrBy(userKey, JsonPath.of("$.age"), 1)

    // Append to a JSON string
    _ <- redis.strAppend(userKey, JsonPath.of("$.name"), JsonValue.of(""" Smith"""))

    // Delete a field
    _ <- redis.jDel(userKey, JsonPath.of("$.city"))

    // Get the type of values at path
    types <- redis.jsonType(userKey, JsonPath.of("$.age"))
    _ <- putStrLn(s"Age type: $types")
  } yield ()
}
```

## Working with JSON Arrays

RedisJSON provides comprehensive array manipulation commands:

```scala mdoc:silent
val listKey = "shopping:list"
val itemsPath = JsonPath.of("$.items")

commandsApi.use { redis =>
  for {
    // Initialize with an empty array
    _ <- redis.jSetStr(listKey, rootPath, """{"items":[]}""")

    // Append items to array (using JsonValue)
    _ <- redis.arrAppend(listKey, itemsPath,
      JsonValue.of(""""milk""""),
      JsonValue.of(""""bread"""")
    )

    // Append using raw JSON strings (more convenient)
    _ <- redis.arrAppendStr(listKey, itemsPath, """"eggs"""", """"butter"""")

    // Get array length
    length <- redis.arrLen(listKey, itemsPath)
    _ <- putStrLn(s"Items count: $length")

    // Find index of an item
    idx <- redis.arrIndexStr(listKey, itemsPath, """"milk"""")
    _ <- putStrLn(s"Milk at index: $idx")

    // Insert at specific position
    _ <- redis.arrInsertStr(listKey, itemsPath, 0, """"coffee"""")

    // Pop an item from the array
    popped <- redis.arrPop(listKey, itemsPath)
    _ <- putStrLn(s"Popped: $popped")
  } yield ()
}
```

## String vs JsonValue Methods

The RedisJSON API provides two variants for many methods:

- **JsonValue variants**: Work with Lettuce's `JsonValue` wrapper (e.g., `jSet`, `arrAppend`)
- **String variants** (with `Str` suffix): Accept raw JSON strings for convenience (e.g., `jSetStr`, `arrAppendStr`)

String variants are more convenient when working with literal JSON or serialized data:

```scala mdoc:silent
// Using JsonValue
redis.jSet(userKey, rootPath, JsonValue.of("""{"name":"Bob"}"""))

// Using raw string (more convenient)
redis.jSetStr(userKey, rootPath, """{"name":"Bob"}""")
```

## Conditional Operations

Use `JsonSetArgs` for conditional SET operations:

```scala mdoc:silent
import io.lettuce.core.json.arguments.JsonSetArgs

commandsApi.use { redis =>
  for {
    // Set only if path does not exist (NX)
    _ <- redis.jSet(userKey, JsonPath.of("$.email"),
      JsonValue.of(""""alice@example.com""""),
      JsonSetArgs.Builder.nx()
    )

    // Set only if path exists (XX)
    _ <- redis.jSet(userKey, JsonPath.of("$.email"),
      JsonValue.of(""""newemail@example.com""""),
      JsonSetArgs.Builder.xx()
    )
  } yield ()
}
```

## Available Commands

The `JsonCommands` trait provides the following command groups:

- **JSON Document Operations**: `jSet`, `jSetStr`, `jGet`, `jGetRaw`, `jMget`, `jDel`, `jClear`, `jsonType`
- **JSON Array Operations**: `arrAppend`, `arrAppendStr`, `arrInsert`, `arrInsertStr`, `arrLen`, `arrPop`, `arrPopRaw`, `arrIndex`, `arrIndexStr`, `arrTrim`
- **JSON Number Operations**: `numIncrBy`
- **JSON String Operations**: `strAppend`, `strAppendStr`, `jsonStrLen`
- **JSON Boolean Operations**: `toggle`
- **JSON Object Operations**: `jObjKeys`, `jObjLen`
- **JSON Merge**: `jsonMerge`, `jsonMergeStr`

For complete method signatures, see the `JsonCommands` trait in the algebra package.
