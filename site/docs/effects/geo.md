---
layout: docs
title:  "Geo"
number: 4
---

# Geo API

Purely functional interface for the [Geo API](https://redis.io/commands#geo).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.GeoCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, GeoCommands[IO, String, String]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[GeoCommands[IO, String, String]]
}
```

### Geo Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import cats.effect.IO
import dev.profunktor.redis4cats.effects._
import io.lettuce.core.GeoArgs

val testKey = "location"

def putStrLn(str: String): IO[Unit] = IO(println(str))

val _BuenosAires  = GeoLocation(Longitude(-58.3816), Latitude(-34.6037), "Buenos Aires")
val _RioDeJaneiro = GeoLocation(Longitude(-43.1729), Latitude(-22.9068), "Rio de Janeiro")
val _Montevideo   = GeoLocation(Longitude(-56.164532), Latitude(-34.901112), "Montevideo")
val _Tokyo        = GeoLocation(Longitude(139.6917), Latitude(35.6895), "Tokyo")

commandsApi.use { cmd => // GeoCommands[IO, String, String]
  for {
    _ <- cmd.geoAdd(testKey, _BuenosAires)
    _ <- cmd.geoAdd(testKey, _RioDeJaneiro)
    _ <- cmd.geoAdd(testKey, _Montevideo)
    _ <- cmd.geoAdd(testKey, _Tokyo)
    x <- cmd.geoDist(testKey, _BuenosAires.value, _Tokyo.value, GeoArgs.Unit.km)
    _ <- putStrLn(s"Distance from ${_BuenosAires.value} to Tokyo: $x km")
    y <- cmd.geoPos(testKey, _RioDeJaneiro.value)
    _ <- putStrLn(s"Geo Pos of ${_RioDeJaneiro.value}: ${y.headOption}")
    z <- cmd.geoRadius(testKey, GeoRadius(_Montevideo.lon, _Montevideo.lat, Distance(10000.0)), GeoArgs.Unit.km)
    _ <- putStrLn(s"Geo Radius in 1000 km: $z")
  } yield ()
}
```
