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

import dev.profunktor.redis4cats.data.{ RedisChannel, RedisPattern, RedisPatternEvent }
import cats.effect.IO
import cats.effect.kernel.Deferred
import scala.concurrent.duration._

class RedisPubSubSpec extends Redis4CatsFunSuite(isCluster = false) {
  test("subscribing to channel and pattern should not double messages") {
    // Test for a bug where subscribing to a channel and pattern matching the same channel would send a message twice
    // to the channel listener.

    val channel = RedisChannel("pubsub-spec-channel-and-pattern:1")
    val pattern = RedisPattern("pubsub-spec-channel-and-pattern:*")

    case class Results(
        channel: Vector[String],
        pattern: Vector[RedisPatternEvent[String, String]]
    )

    withRedisPubSub { pubSub =>
      val actualIO = for {
        finished <- Deferred[IO, Either[Throwable, Unit]]
        s1 <- pubSub.subscribe(channel).interruptWhen(finished).compile.to(Vector).start
        s2 <- pubSub.psubscribe(pattern).interruptWhen(finished).compile.to(Vector).start
        _ <- IO.sleep(200.millis) // wait for the subscription to start
        _ <- fs2.Stream.emit("hello").through(pubSub.publish(channel)).compile.drain
        _ <- IO.sleep(200.millis) // wait for the message to arrive
        _ <- finished.complete(Right(()))
        channelResults <- s1.joinWith(IO.raiseError(new RuntimeException("s1 should not be cancelled")))
        patternResults <- s2.joinWith(IO.raiseError(new RuntimeException("s2 should not be cancelled")))
      } yield Results(channelResults, patternResults)

      val expected = Results(
        channel = Vector("hello"),
        pattern = Vector(RedisPatternEvent(pattern.underlying, channel.underlying, "hello"))
      )

      actualIO.map(assertEquals(_, expected))
    }
  }
}
