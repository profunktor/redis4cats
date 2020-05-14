---
layout: docs
title:  "Codecs"
number: 6
position: 6
---

# Codecs

Redis is a key-value store, and as such, it is commonly used to store simple values in a "stringy" form. Redis4Cats parameterizes the type of keys and values, allowing you to provide the desired `RedisCodec`. The most common one is `RedisCodec.Utf8` but there's also `RedisCodec.Ascii`, in case you need it.

### Deriving codecs

Under the `dev.profunktor.redis4cats.codecs.splits._` package, you will find standard `SplitEpi` definitions.

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
import dev.profunktor.redis4cats.data.RedisCodec

val longCodec: RedisCodec[String, Long] =
  Codecs.derive[String, Long](RedisCodec.Utf8, stringLongEpi)
```

Notice that you can only derive codecs that modify the value type `V` but not the key type `K`. This is because keys are strings 99% of the time. However, you can roll out your own codec if you wish (look at how the implementation of `Codecs.derive`), but that's not recommended.

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
import cats.effect._
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
