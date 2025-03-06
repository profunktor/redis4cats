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
package pubsub
package internals

import cats.effect.IO
import dev.profunktor.redis4cats.data.RedisChannel
import scala.concurrent.duration._

class SubscriberSuite extends IOSuite {

  private val waitOnFiber: IO[Unit] = IO.sleep(200.millis)

  private val channel1 = RedisChannel("a")
  private val channel2 = RedisChannel("b")

  test("subscribe and unsubscribe") {
    for {
      subRef <- IO.ref(0)
      unsubRef <- IO.ref(0)
      map <- subscriptionMap(subRef.update(_ + 1), unsubRef.update(_ + 1))
      subscription <- map.subscribe(channel1).compile.toList.start
      _ <- waitOnFiber
      _ <- map.unsubscribe(channel1)
      _ <- subscription.joinWith(notCanceled).map(assertEquals(_, Nil))
      _ <- subRef.get.map(assertEquals(_, 1))
      _ <- unsubRef.get.map(assertEquals(_, 1))
    } yield ()
  }

  test("subscribe and unsubscribe automatically") {
    for {
      subRef <- IO.ref(0)
      unsubRef <- IO.ref(0)
      interrupt <- IO.deferred[Either[Throwable, Unit]]
      map <- subscriptionMap(subRef.update(_ + 1), unsubRef.update(_ + 1))
      subscription <- map.subscribe(channel1).interruptWhen(interrupt).compile.toList.start
      _ <- waitOnFiber
      _ <- interrupt.complete(Right(()))
      _ <- subscription.joinWith(notCanceled).map(assertEquals(_, Nil))
      _ <- subRef.get.map(assertEquals(_, 1))
      _ <- unsubRef.get.map(assertEquals(_, 1))
    } yield ()
  }

  test("receive messages") {
    for {
      map <- subscriptionMap(IO.unit, IO.unit)
      subscription <- map.subscribe(channel1).compile.toList.start
      _ <- waitOnFiber
      _ <- map.onMessage(channel1, "one")
      _ <- map.onMessage(channel1, "two")
      _ <- map.unsubscribe(channel1)
      _ <- subscription.joinWith(notCanceled).map(assertEquals(_, List("one", "two")))
    } yield ()
  }

  test("subscription count") {
    for {
      map <- subscriptionMap(IO.unit, IO.unit)
      subscription <- map.subscribe(channel1).compile.toList.start
      _ <- waitOnFiber
      _ <- map.counts.map(assertEquals(_, Map(channel1 -> 1L)))
      _ <- map.unsubscribe(channel1)
      _ <- subscription.joinWith(notCanceled)
      _ <- map.counts.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
    } yield ()
  }

  test("handle multiple subscriptions for the same key") {
    for {
      subRef <- IO.ref(0)
      unsubRef <- IO.ref(0)
      map <- subscriptionMap(subRef.update(_ + 1), unsubRef.update(_ + 1))
      subscription1 <- map.subscribe(channel1).take(1).compile.toList.start
      subscription2 <- map.subscribe(channel1).take(2).compile.toList.start
      _ <- waitOnFiber
      _ <- map.onMessage(channel1, "one")
      _ <- map.onMessage(channel1, "two")
      _ <- subscription1.joinWith(notCanceled).map(assertEquals(_, List("one")))
      _ <- subscription2.joinWith(notCanceled).map(assertEquals(_, List("one", "two")))
      _ <- subRef.get.map(assertEquals(_, 1))
      _ <- unsubRef.get.map(assertEquals(_, 1))
    } yield ()
  }

  test("handle subscriptions to multiple keys") {
    for {
      subRef <- IO.ref(List.empty[RedisChannel[String]])
      unsubRef <- IO.ref(List.empty[RedisChannel[String]])
      map <- subscriptionMap(c => subRef.update(_ :+ c), c => unsubRef.update(_ :+ c))
      subscription1 <- map.subscribe(channel1).compile.toList.start
      subscription2 <- map.subscribe(channel2).take(1).compile.toList.start
      _ <- waitOnFiber
      _ <- map.counts.map(assertEquals(_, Map(channel1 -> 1L, channel2 -> 1L)))
      _ <- map.onMessage(channel1, "one")
      _ <- map.onMessage(channel2, "two")
      _ <- map.unsubscribe(channel1)
      _ <- subscription1.joinWith(notCanceled).map(assertEquals(_, List("one")))
      _ <- subscription2.joinWith(notCanceled).map(assertEquals(_, List("two")))
      _ <- map.counts.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
      _ <- subRef.get.map(channels => assertEquals(channels.sortBy(_.underlying), List(channel1, channel2)))
      _ <- unsubRef.get.map(channels => assertEquals(channels.sortBy(_.underlying), List(channel1, channel2)))
    } yield ()
  }

  test("handle subscribe failure") {
    // state changes: None -> Subscribing -> None
    for {
      unsubRef <- IO.ref(0)
      map <- subscriptionMap(IO.raiseError(new RuntimeException("fail subscribe")), unsubRef.update(_ + 1))
      subscription <- map.subscribe(channel1).compile.toList.start
      _ <- waitOnFiber
      _ <- subscription.join.map(outcome => assert(outcome.isError))
      _ <- map.counts.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
      _ <- unsubRef.get.map(assertEquals(_, 0))
    } yield ()
  }

  test("handle unsubscribe failure") {
    // state changes: (None -> Subscribing ->) Active -> FailedToUnsubscribe -> Unsubscribing -> None
    for {
      unsubRef <- IO.ref(0)
      map <- subscriptionMap(
               IO.unit,
               unsubRef.flatModify {
                 case 0 => (1, IO.raiseError[Unit](new RuntimeException("failed")))
                 case n => (n + 1, IO.unit)
               }
             )
      subscription <- map.subscribe(channel1).compile.toList.start
      _ <- waitOnFiber
      _ <- map.onMessage(channel1, "one")
      _ <- map.unsubscribe(channel1)
      _ <- subscription.join.map(outcome => assert(outcome.isError))
      _ <- map.counts.map(assertEquals(_, Map(channel1 -> 0L)))
      _ <- map.unsubscribe(channel1)
      _ <- map.counts.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
    } yield ()
  }

  private def subscriptionMap(
      sub: IO[Unit],
      unsub: IO[Unit]
  ): IO[Subscriber.SubscriptionMap[IO, RedisChannel[String], String]] =
    subscriptionMap(_ => sub, _ => unsub)

  private def subscriptionMap(
      sub: RedisChannel[String] => IO[Unit],
      unsub: RedisChannel[String] => IO[Unit]
  ): IO[Subscriber.SubscriptionMap[IO, RedisChannel[String], String]] = {
    // import effect.Log.Stdout._
    import effect.Log.NoOp._
    Subscriber.SubscriptionMap.singleRef[IO, RedisChannel[String], String](
      Subscriber.SubscriptionCommands.withLogs(
        new Subscriber.SubscriptionCommands[IO, RedisChannel[String]] {
          override def subscribe(key: RedisChannel[String]): IO[Unit]   = sub(key)
          override def unsubscribe(key: RedisChannel[String]): IO[Unit] = unsub(key)
        }
      )
    )
  }

  private def notCanceled[A]: IO[A] = IO.raiseError(new RuntimeException("should not be canceled"))

}
