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

  test("subscribe: to same channel should share an underlying subscription") {
    withRedisPubSub { pubSub =>
      val channel = RedisChannel("test-pubsub-shared")

      for {
        sub1 <- pubSub.subscribe(channel).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fiber started.
        _ <- pubSub.shouldHaveNSubs(channel, 1)
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map(channel -> 1L)))
        sub2 <- pubSub.subscribe(channel).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fiber started.
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map(channel -> 2L)))
        _ <- pubSub.shouldHaveNSubs(channel, 1)
        _ <- sub1.cancel
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map(channel -> 1L)))
        _ <- pubSub.shouldHaveNSubs(channel, 1)
        _ <- sub2.cancel
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
        _ <- pubSub.shouldHaveNSubs(channel, 0)
      } yield ()
    }
  }

  test("subscribe: messages should be delivered to all subscribers") {
    withRedisPubSub { pubSub =>
      val channel = RedisChannel("test-pubsub-shared")

      for {
        sub1 <- pubSub.subscribe(channel).compile.toVector.start
        sub2 <- pubSub.subscribe(channel).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fiber started.
        _ <- pubSub.publish(channel, "hello")
        _ <- IO.sleep(200.millis) // Wait to make sure the message is delivered.
        _ <- pubSub.unsubscribe(channel)
        sub1Result <- sub1.joinWith(IO.raiseError(new Exception(s"sub1 should not have been cancelled")))
        _ <- IO(assertEquals(sub1Result, Vector("hello")))
        sub2Result <- sub2.joinWith(IO.raiseError(new Exception(s"sub2 should not have been cancelled")))
        _ <- IO(assertEquals(sub2Result, Vector("hello")))
      } yield ()
    }
  }

  test("unsubscribe: should terminate all listening streams") {
    withRedisPubSub { pubSub =>
      val channel = RedisChannel("test-pubsub-shared")

      for {
        sub1 <- pubSub.subscribe(channel).compile.toVector.start
        sub2 <- pubSub.subscribe(channel).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fibers have started ands streams started processing.
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map(channel -> 2L)))
        _ <- pubSub.shouldHaveNSubs(channel, 1)
        _ <- pubSub.unsubscribe(channel)
        _ <- sub1.joinWith(IO.raiseError(new Exception("sub1 should not have been cancelled")))
        _ <- sub2.joinWith(IO.raiseError(new Exception("sub2 should not have been cancelled")))
        _ <- pubSub.shouldHaveNSubs(channel, 0)
        _ <- pubSub.internalChannelSubscriptions.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
      } yield ()
    }
  }

  test("psubscribe: to same pattern should share an underlying subscription") {
    withRedisPubSub { pubSub =>
      val pattern = RedisPattern("test-pubsub-shared:pattern:*")

      for {
        sub1 <- pubSub.psubscribe(pattern).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fiber started.
        _ <- pubSub.internalPatternSubscriptions.map(assertEquals(_, Map(pattern -> 1L)))
        sub2 <- pubSub.psubscribe(pattern).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fiber started.
        _ <- pubSub.internalPatternSubscriptions.map(assertEquals(_, Map(pattern -> 2L)))
        _ <- sub1.cancel
        _ <- pubSub.internalPatternSubscriptions.map(assertEquals(_, Map(pattern -> 1L)))
        _ <- sub2.cancel
        _ <- pubSub.internalPatternSubscriptions.map(assertEquals(_, Map.empty[RedisPattern[String], Long]))
      } yield ()
    }
  }

  test("punsubscribe: should terminate all streams") {
    withRedisPubSub { pubSub =>
      val pattern = RedisPattern("test-pubsub-shared:pattern:*")

      for {
        sub1 <- pubSub.psubscribe(pattern).compile.toVector.start
        sub2 <- pubSub.psubscribe(pattern).compile.toVector.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fibers have started ands streams started processing.
        _ <- pubSub.internalPatternSubscriptions.map(assertEquals(_, Map(pattern -> 2L)))
        _ <- pubSub.punsubscribe(pattern)
        _ <- sub1.joinWith(IO.raiseError(new Exception("sub1 should not have been cancelled")))
        _ <- sub2.joinWith(IO.raiseError(new Exception("sub2 should not have been cancelled")))
        _ <- pubSub.internalPatternSubscriptions.map(assertEquals(_, Map.empty[RedisPattern[String], Long]))
      } yield ()
    }
  }

  test("subscribing to a silent channel should not fail with RedisCommandTimeoutException") {
    timeoutingOperationTest { (options, _) =>
      fs2.Stream.resource(withRedisPubSubOptionsResource(options)).flatMap { pubSub =>
        pubSub.subscribe(RedisChannel("test-sub-expiration"))
      }
    }
  }

  test("subscribing to a silent pattern should not fail with RedisCommandTimeoutException") {
    timeoutingOperationTest { (options, _) =>
      fs2.Stream.resource(withRedisPubSubOptionsResource(options)).flatMap { pubSub =>
        pubSub.psubscribe(RedisPattern("test-sub-expiration"))
      }
    }
  }
}
