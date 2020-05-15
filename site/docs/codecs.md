---
layout: docs
title:  "Codecs"
number: 6
position: 6
---

# Codecs

Redis is a key-value store, and as such, it is commonly used to store simple values in a "stringy" form. Redis4Cats parameterizes the type of keys and values, allowing you to provide the desired `RedisCodec`. The most common one is `RedisCodec.Utf8` but there's also a `RedisCodec.Ascii` and a `RedisCodec.Bytes` as well.

You can also manipulate existing codecs. The `RedisCodec` object exposes a few functions for this purpose.

### Compression

There are two functions available: `deflate` and `gzip`. Here's an example using the latter:

```scala mdoc:silent
import dev.profunktor.redis4cats.data.RedisCodec

RedisCodec.gzip(RedisCodec.Utf8)
```

It manipulates an existing codec to add compression support.

### Encryption

In the same spirit, there's another function `secure`, which takes two extra arguments for encryption and decryption, respectively. These two extra arguments are of type `CipherSupplier`. You can either create your own or use the provided functions, which are effectful.

```scala mdoc:silent
import cats.effect._
import javax.crypto.spec.SecretKeySpec

def mkCodec(key: SecretKeySpec): IO[RedisCodec[String, String]] =
  for {
    e <- RedisCodec.encryptSupplier[IO](key)
    d <- RedisCodec.decryptSupplier[IO](key)
  } yield RedisCodec.secure(RedisCodec.Utf8, e, d)
```

### Deriving codecs

Redis4Cats defines a `SplitEpi` datatype, which stands for [Split Epimorphism](https://ncatlab.org/nlab/show/split+epimorphism), as explained by Rob Norris at [Scala eXchange 2018](https://skillsmatter.com/skillscasts/11626-keynote-pushing-types-and-gazing-at-the-stars). It sounds more complicated than it actually is. Here's its definition:

```scala
final case class SplitEpi[A, B](
    get: A => B,
    reverseGet: B => A
) extends (A => B)
```

Under the `dev.profunktor.redis4cats.codecs.splits._` package, you will find useful `SplitEpi` implementations for codecs.

```scala
val stringDoubleEpi: SplitEpi[String, Double] =
  SplitEpi(s => Try(s.toDouble).getOrElse(0), _.toString)

val stringLongEpi: SplitEpi[String, Long] =
  SplitEpi(s => Try(s.toLong).getOrElse(0), _.toString)

val stringIntEpi: SplitEpi[String, Int] =
  SplitEpi(s => Try(s.toInt).getOrElse(0), _.toString)
```

Given a `SplitEpi`, we can derive a `RedisCodec` from an existing one. For example:

```scala mdoc:silent
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits._

val longCodec: RedisCodec[String, Long] =
  Codecs.derive(RedisCodec.Utf8, stringLongEpi)
```

This is the most common kind of derivation. That is, the one that operates on the value type `V` since keys are most of the time treated as strings. However, if you wish to derive a codec that also modifies the key type `K`, you can do it by supplying another `SplitEpi` instance for keys.

```scala mdoc:silent
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits._
import dev.profunktor.redis4cats.data.RedisCodec

case class Keys(value: String)

val keysSplitEpi: SplitEpi[String, Keys] =
  SplitEpi(Keys.apply, _.value)

val newCodec: RedisCodec[Keys, Long] =
  Codecs.derive(RedisCodec.Utf8, keysSplitEpi, stringLongEpi)
```

### Json codecs

In the same way we derived simple codecs, we could have one for Json, in case we are only storing values of a single type. For example, say we have the following algebraic data type (ADT):

```scala mdoc:silent
sealed trait Event

object Event {
  case class Ack(id: Long) extends Event
  case class Message(id: Long, payload: String) extends Event
  case object Unknown extends Event
}
```

We can define a `SplitEpi[String, Event]` that handles the Json encoding and decoding in the following way:

```scala mdoc:silent
import dev.profunktor.redis4cats.codecs.splits.SplitEpi
import dev.profunktor.redis4cats.effect.Log.NoOp._
import io.circe.generic.auto._
import io.circe.parser.{ decode => jsonDecode }
import io.circe.syntax._

val eventSplitEpi: SplitEpi[String, Event] =
  SplitEpi[String, Event](
    str => jsonDecode[Event](str).getOrElse(Event.Unknown),
    _.asJson.noSpaces
  )
```

We can then proceed to derive a `RedisCodec[String, Event]` from an existing one.

```scala mdoc:silent
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.data.RedisCodec

val eventsCodec: RedisCodec[String, Event] =
  Codecs.derive(RedisCodec.Utf8, eventSplitEpi)
```

Finally, we can put all the pieces together to acquire a `RedisCommands[IO, String, Event]`.

```scala mdoc:silent
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.NoOp._
import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)

val eventsKey = "events"

Redis[IO].simple("redis://localhost", eventsCodec)
  .use { cmd =>
    for {
      x <- cmd.sCard(eventsKey)
      _ <- IO(println(s"Number of events: $x"))
      _ <- cmd.sAdd(eventsKey, Event.Ack(1), Event.Message(23, "foo"))
      y <- cmd.sMembers(eventsKey)
      _ <- IO(println(s"Events: $y"))
    } yield ()
  }
```

The full compiling example can be found [here](https://github.com/profunktor/redis4cats/blob/master/modules/examples/src/main/scala/dev/profunktor/redis4cats/JsonCodecDemo.scala).

Although it is possible to derive a Json codec in this way, it is mainly preferred to use a simple codec like `RedisCodec.Utf8` and manage the encoding / decoding yourself (separation of concerns). In this way, you can have a single active `Redis` connection for more than one type of message.
