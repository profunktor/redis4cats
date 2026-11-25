/*
 * Copyright 2018-2025 ProfunKtor
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
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.syntax.all._

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

  test("subscribe: ending the subscriptions to some channels does not affect other channels") {
    withRedisPubSub { pubSub =>
      val cancelled    = RedisChannel("test-pubsub-independent:cancelled")
      val unsubscribed = RedisChannel("test-pubsub-independent:unsubscribed")
      val other        = RedisChannel("test-pubsub-independent:other")

      for {
        cancelledSub <- pubSub.subscribe(cancelled).compile.drain.start
        unsubscribedSub <- pubSub.subscribe(unsubscribed).compile.drain.start
        otherSub <- pubSub.subscribe(other).take(1).compile.toVector.start
        _ <- List(cancelled, unsubscribed, other).traverse_(pubSub.shouldHaveNSubs(_, 1, waitFor = 1.second))
        _ <- cancelledSub.cancel
        _ <- pubSub.unsubscribe(unsubscribed)
        _ <- unsubscribedSub.joinWith(IO.raiseError(new Exception("unsubscribedSub should not have been cancelled")))
        _ <- pubSub.shouldHaveNSubs(cancelled, 0)
        _ <- pubSub.shouldHaveNSubs(unsubscribed, 0, waitFor = 1.second)
        _ <- pubSub.publish(other, "hello")
        otherResult <- otherSub
                         .joinWith(IO.raiseError(new Exception("otherSub should not have been cancelled")))
                         .timeout(5.seconds)
        _ <- IO(assertEquals(otherResult, Vector("hello")))
        // Re-subscribing to the channels whose subscriptions ended delivers messages again.
        resubscribed <-
          List(cancelled, unsubscribed).traverse(ch => pubSub.subscribe(ch).take(1).compile.toVector.start)
        _ <- List(cancelled, unsubscribed).traverse_(pubSub.shouldHaveNSubs(_, 1, waitFor = 1.second))
        _ <- List(cancelled, unsubscribed).traverse_(ch => pubSub.publish(ch, ch.underlying))
        results <- resubscribed.traverse(
                     _.joinWith(IO.raiseError(new Exception("resubscribed sub should not have been cancelled")))
                       .timeout(5.seconds)
                   )
        _ <- IO(assertEquals(results, List(Vector(cancelled.underlying), Vector(unsubscribed.underlying))))
      } yield ()
    }
  }

  test("subscribe: a stalled subscriber ending releases delivery to other channels") {
    withRedisPubSub { pubSub =>
      val stalled = RedisChannel("test-pubsub-stalled:stalled")
      val other   = RedisChannel("test-pubsub-stalled:other")

      for {
        // Takes the first message and never pulls again, so its queue fills up and blocks delivery on the connection.
        stalledSub <- pubSub.subscribe(stalled).evalMap(_ => IO.never[Unit]).compile.drain.start
        otherSub <- pubSub.subscribe(other).take(1).compile.toVector.start
        _ <- pubSub.shouldHaveNSubs(stalled, 1, waitFor = 1.second)
        _ <- pubSub.shouldHaveNSubs(other, 1, waitFor = 1.second)
        _ <- fs2.Stream.range(0, 1000).map(_.toString).through(pubSub.publish(stalled)).compile.drain
        _ <- IO.sleep(200.millis) // Wait for the queue to fill up.
        _ <- stalledSub.cancel.timeout(5.seconds)
        _ <- pubSub.publish(other, "hello")
        result <- otherSub
                    .joinWith(IO.raiseError(new Exception("otherSub should not have been cancelled")))
                    .timeout(5.seconds)
        _ <- IO(assertEquals(result, Vector("hello")))
      } yield ()
    }
  }

  test("subscribe/psubscribe: messages on many channels and a pattern are all delivered") {
    withRedisPubSub { pubSub =>
      val channels = (1 to 50).toList.map(i => RedisChannel(s"test-pubsub-many:$i"))
      val pattern  = RedisPattern("test-pubsub-many:*")

      for {
        channelSubs <- channels.traverse(ch => pubSub.subscribe(ch).take(1).compile.toVector.start)
        patternSub <- pubSub.psubscribe(pattern).take(channels.size.toLong).compile.toVector.start
        _ <- channels.traverse_(pubSub.shouldHaveNSubs(_, 1, waitFor = 1.second))
        _ <- IO.sleep(200.millis) // Wait for the pattern subscription to start.
        _ <- channels.traverse_(ch => pubSub.publish(ch, ch.underlying))
        channelResults <- channelSubs.traverse(
                            _.joinWith(IO.raiseError(new Exception("channel sub should not have been cancelled")))
                              .timeout(5.seconds)
                          )
        patternResults <- patternSub
                            .joinWith(IO.raiseError(new Exception("pattern sub should not have been cancelled")))
                            .timeout(5.seconds)
        _ <- IO(assertEquals(channelResults, channels.map(ch => Vector(ch.underlying))))
        _ <- IO(assertEquals(patternResults.map(_.channel).toSet, channels.map(_.underlying).toSet))
        _ <-
          IO(
            assertEquals(patternResults.map(e => e.channel -> e.data), patternResults.map(e => e.channel -> e.channel))
          )
      } yield ()
    }
  }

  test("numSub: returns subscriber counts for the queried channels") {
    withRedisPubSub { pubSub =>
      val subscribedChannel   = RedisChannel("test-pubsub-numsub-subscribed")
      val unsubscribedChannel = RedisChannel("test-pubsub-numsub-unsubscribed")

      for {
        sub <- pubSub.subscribe(subscribedChannel).compile.drain.start
        _ <- IO.sleep(200.millis) // Wait to make sure the fiber started.
        result <- pubSub.numSub(NonEmptyList.of(subscribedChannel, unsubscribedChannel))
        _ <- IO(assertEquals(result.find(_.channel == subscribedChannel).map(_.number), Some(1L)))
        _ <- IO(assertEquals(result.find(_.channel == unsubscribedChannel).map(_.number), Some(0L)))
        _ <- sub.cancel
      } yield ()
    }
  }

  test("numSub/pubSubSubscriptions/shardNumSub: an empty channel list returns an empty list locally") {
    withRedisPubSub { pubSub =>
      for {
        a <- pubSub.numSub(List.empty)
        _ <- IO(assertEquals(a, List.empty))
        b <- pubSub.pubSubSubscriptions(List.empty)
        _ <- IO(assertEquals(b, List.empty))
        c <- pubSub.shardNumSub(List.empty)
        _ <- IO(assertEquals(c, List.empty))
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
