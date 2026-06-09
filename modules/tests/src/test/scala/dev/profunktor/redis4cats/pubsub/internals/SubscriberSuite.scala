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
package pubsub
package internals

import cats.effect.IO
import dev.profunktor.redis4cats.data.RedisChannel

class SubscriberSuite extends IOSuite {

  private val channel1 = RedisChannel("a")
  private val channel2 = RedisChannel("b")

  test("subscribe and unsubscribe") {
    for {
      subRef <- IO.ref(0)
      unsubRef <- IO.ref(0)
      map <- subscriptionMap(subRef.update(_ + 1), unsubRef.update(_ + 1))
      _ <- map
             .subscribeAwait(channel1)
             .flatMap(_.compile.toList.background)
             .use { getMessages =>
               map.unsubscribe(channel1) >>
                 getMessages.flatMap(_.embedError).map(assertEquals(_, Nil))
             }
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
      _ <- map
             .subscribeAwait(channel1)
             .flatMap(_.interruptWhen(interrupt).compile.toList.background)
             .use { getMessages =>
               for {
                 _ <- interrupt.complete(Right(()))
                 _ <- getMessages.flatMap(_.embedError).map(assertEquals(_, Nil))
               } yield ()
             }
      _ <- subRef.get.map(assertEquals(_, 1))
      _ <- unsubRef.get.map(assertEquals(_, 1))
    } yield ()
  }

  test("receive messages") {
    for {
      map <- subscriptionMap(IO.unit, IO.unit)
      _ <- map
             .subscribeAwait(channel1)
             .flatMap(_.compile.toList.background)
             .use(messages =>
               map.onMessage(channel1, "one") >>
                 map.onMessage(channel1, "two") >>
                 map.unsubscribe(channel1) >>
                 messages.flatMap(_.embedError).map(assertEquals(_, List("one", "two")))
             )
    } yield ()
  }

  test("subscription count") {
    for {
      map <- subscriptionMap(IO.unit, IO.unit)
      _ <- map
             .subscribeAwait(channel1)
             .use(_ =>
               map.counts.map(assertEquals(_, Map(channel1 -> 1L)))
               // map.unsubscribe(channel1) >>
             )
      _ <- map.counts.map(assertEquals(_, Map.empty[RedisChannel[String], Long]))
    } yield ()
  }

  test("handle multiple subscriptions for the same key") {
    for {
      subRef <- IO.ref(0)
      unsubRef <- IO.ref(0)
      map <- subscriptionMap(subRef.update(_ + 1), unsubRef.update(_ + 1))
      subscription1 = map
                        .subscribeAwait(channel1)
                        .flatMap(_.take(1).compile.toList.background)
      subscription2 = map
                        .subscribeAwait(channel1)
                        .flatMap(_.take(2).compile.toList.background)
      _ <- subscription1.both(subscription2).use { case (messages1, messages2) =>
             map.onMessage(channel1, "one") >>
               map.onMessage(channel1, "two") >>
               messages1.flatMap(_.embedError).map(assertEquals(_, List("one"))) >>
               messages2.flatMap(_.embedError).map(assertEquals(_, List("one", "two")))
           }
      _ <- subRef.get.map(assertEquals(_, 1))
      _ <- unsubRef.get.map(assertEquals(_, 1))
    } yield ()
  }

  test("handle subscriptions to multiple keys") {
    for {
      subRef <- IO.ref(List.empty[RedisChannel[String]])
      unsubRef <- IO.ref(List.empty[RedisChannel[String]])
      map <- subscriptionMap(c => subRef.update(_ :+ c), c => unsubRef.update(_ :+ c))
      subscription1 = map
                        .subscribeAwait(channel1)
                        .flatMap(_.compile.toList.background)
      subscription2 = map
                        .subscribeAwait(channel2)
                        .flatMap(_.take(1).compile.toList.background)
      _ <- subscription1.both(subscription2).use { case (messages1, messages2) =>
             map.counts.map(assertEquals(_, Map(channel1 -> 1L, channel2 -> 1L))) >>
               map.onMessage(channel1, "one") >>
               map.onMessage(channel2, "two") >>
               map.unsubscribe(channel1) >>
               messages1.flatMap(_.embedError).map(assertEquals(_, List("one"))) >>
               messages2.flatMap(_.embedError).map(assertEquals(_, List("two")))
           }
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
      _ <- map.subscribeAwait(channel1).use_.attempt.map(attempt => assert(attempt.isLeft))
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
      t <- map.subscribeAwait(channel1).allocated
      (subscription, unsubscribe) = t
      _ <- subscription.compile.toList.background.use { messages =>
             map.onMessage(channel1, "one") >>
               map.unsubscribe(channel1) >>
               messages.flatMap(_.embedError).map(assertEquals(_, List("one")))
           }
      _ <- unsubscribe.attempt.map { attempt => println(attempt); assert(attempt.isLeft) }
      _ <- map.counts.map(assertEquals(_, Map(channel1 -> 0L)))
      _ <- unsubscribe
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
    import effect.Log.NoOp._
    Subscriber.SubscriptionMap.singleRef[IO, RedisChannel[String], String](
      Subscriber.SubscriptionCommands.withLogs(
        Subscriber.SubscriptionCommands[IO, RedisChannel[String]](sub, unsub)
      )
    )
  }

}
