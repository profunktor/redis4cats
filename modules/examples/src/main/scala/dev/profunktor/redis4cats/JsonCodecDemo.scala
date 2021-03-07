/*
 * Copyright 2018-2021 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.redis4cats

import cats.effect.{ IO, Resource }
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits.SplitEpi
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.NoOp._
import io.circe.generic.auto._
import io.circe.parser.{ decode => jsonDecode }
import io.circe.syntax._

object JsonCodecDemo extends LoggerIOApp {

  import Demo._

  sealed trait Event

  object Event {
    case class Ack(id: Long) extends Event
    case class Message(id: Long, payload: String) extends Event
    case object Unknown extends Event
  }

  val program: IO[Unit] = {
    val eventsKey = "events"

    val eventSplitEpi: SplitEpi[String, Event] =
      SplitEpi[String, Event](
        str => jsonDecode[Event](str).getOrElse(Event.Unknown),
        _.asJson.noSpaces
      )

    val eventsCodec: RedisCodec[String, Event] =
      Codecs.derive(RedisCodec.Utf8, eventSplitEpi)

    val commandsApi: Resource[IO, RedisCommands[IO, String, Event]] =
      Redis[IO].simple(redisURI, eventsCodec)

    commandsApi
      .use { cmd =>
        for {
          x <- cmd.sCard(eventsKey)
          _ <- putStrLn(s"Number of events: $x")
          _ <- cmd.sAdd(eventsKey, Event.Ack(1), Event.Message(23, "foo"))
          y <- cmd.sMembers(eventsKey)
          _ <- putStrLn(s"Events: $y")
        } yield ()
      }
  }

}
