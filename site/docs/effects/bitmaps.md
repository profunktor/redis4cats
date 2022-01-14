---
layout: docs
title:  "Bitmaps"
number: 14
---

# Bitmaps API

Purely functional interface for the [Bitmaps API](https://redis.io/commands#bitmap).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.BitCommands
import dev.profunktor.redis4cats.algebra.BitCommandOperation.{ IncrUnsignedBy, SetUnsigned }
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, BitCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[BitCommands[IO, String, String]]
}
```

### List Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO

val testKey  = "foo"
val testKey2 = "bar"
val testKey3 = "baz"

def putStrLn(str: String): IO[Unit] = IO(println(str))

commandsApi.use { cmd => // BitCommands[IO, String, String]
  for {
    a <- cmd.setBit(testKey, 7, 1)
    _ <- cmd.setBit(testKey2, 7, 0)
    _ <- putStrLn(s"Set as $a")
    b <- cmd.getBit(testKey, 6)
    _ <- putStrLn(s"Bit at offset 6 is $b")
    _ <- cmd.bitOpOr(testKey3, testKey, testKey2)
    _ <- for {
      s1 <- cmd.setBit("bitmapsarestrings", 2, 1)
      s2 <- cmd.setBit("bitmapsarestrings", 3, 1)
      s3 <- cmd.setBit("bitmapsarestrings", 5, 1)
      s4 <- cmd.setBit("bitmapsarestrings", 10, 1)
      s5 <- cmd.setBit("bitmapsarestrings", 11, 1)
      s6 <- cmd.setBit("bitmapsarestrings", 14, 1)
    } yield s1 + s2 + s3 + s4 + s5 + s6
    bf <- cmd.bitField(
      "inmap",
      SetUnsigned(2, 1),
      SetUnsigned(3, 1),
      SetUnsigned(5, 1),
      SetUnsigned(10, 1),
      SetUnsigned(11, 1),
      SetUnsigned(14, 1),
      IncrUnsignedBy(14, 1)
    )
    _ <- putStrLn(s"Via bitfield $bf")
  } yield ()
}
```
