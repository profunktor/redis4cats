---
layout: docs
title:  "Hashes"
number: 6
---

# Hashes API

Purely functional interface for the [Hashes API](https://redis.io/commands#hash).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.HashCommands
import dev.profunktor.redis4cats.log4cats._
import dev.profunktor.redis4cats.data._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, HashCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[HashCommands[IO, String, String]]
}
```

### Hash Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

val testKey   = "foo"
val testField = "bar"

def putStrLn(str: String): IO[Unit] = IO(println(str))

val showResult: Option[String] => IO[Unit] =
  _.fold(putStrLn(s"Not found key: $testKey | field: $testField"))(s => putStrLn(s))

commandsApi.use { redis => // HashCommands[IO, String, String]
  for {
    x <- redis.hGet(testKey, testField)
    _ <- showResult(x)
    _ <- redis.hSet(testKey, testField, "some value")
    y <- redis.hGet(testKey, testField)
    _ <- showResult(y)
    _ <- redis.hSetNx(testKey, testField, "should not happen")
    w <- redis.hGet(testKey, testField)
    _ <- showResult(w)
    _ <- redis.hDel(testKey, testField)
    z <- redis.hGet(testKey, testField)
    _ <- showResult(z)
  } yield ()
}
```

### Field expiration (`HEXPIRE`) and read-and-modify (`HGETEX`/`HGETDEL`)

Redis 7.4+ supports per-**field** TTLs and atomic read-and-modify reads. Each of these commands acts
on one or more fields and returns one result per field, in the order given.

```scala mdoc:silent
import scala.concurrent.duration._
import java.time.Instant
import dev.profunktor.redis4cats.effects.{ ExpireExistenceArg, HGetExArgs }

commandsApi.use { redis =>
  for {
    _ <- redis.hSet(testKey, Map("f1" -> "v1", "f2" -> "v2"))
    // Set a relative TTL on specific fields (HEXPIRE); one status code per field.
    _ <- redis.hExpire(testKey, 30.seconds, "f1", "f2")
    // Conditional variant — only set the TTL if the field currently has none.
    _ <- redis.hExpire(testKey, 1.minute, ExpireExistenceArg.Nx, "f1")
    // Absolute expiry (HEXPIREAT).
    _ <- redis.hExpireAt(testKey, Instant.now.plusSeconds(60), "f2")
    // Inspect remaining TTL / absolute expiry time per field (None = no TTL or no such field).
    _ <- redis.httl(testKey, "f1", "f2")  // List[Option[FiniteDuration]]
    _ <- redis.hExpireTime(testKey, "f1") // List[Option[Instant]]
    // Remove the TTL again (HPERSIST); one Boolean per field.
    _ <- redis.hPersist(testKey, "f1")
    // Read and (re)set expiry in one step (HGETEX), and read-and-delete (HGETDEL).
    _ <- redis.hGetEx(testKey, HGetExArgs.Ex(10.seconds), "f1", "f2") // List[Option[String]]
    _ <- redis.hGetDel(testKey, "f2")                                 // List[Option[String]]
  } yield ()
}
```

`HGetExArgs` selects what `hGetEx` does to each field's TTL while reading it (`Ex`/`Px` relative,
`ExAt`/`PxAt` absolute, or `Persist` to clear it); `ExpireExistenceArg` (`Nx`/`Xx`/`Gt`/`Lt`) guards
the `hExpire`/`hExpireAt` update. Millisecond-precision variants `hpttl` and `hpExpireTime` mirror
`httl` and `hExpireTime`.
