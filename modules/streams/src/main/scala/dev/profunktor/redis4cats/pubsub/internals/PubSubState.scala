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

package dev.profunktor.redis4cats.pubsub.internals

import cats.{ Applicative, Monad }
import cats.syntax.all._
import cats.effect.kernel.{ Concurrent, MonadCancelThrow, Resource }
import cats.effect.std.AtomicCell
import cats.effect.kernel.syntax.monadCancel._
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisPattern, RedisPatternEvent }
import fs2.Stream
import fs2.concurrent.Topic

/** We use `AtomicCell` instead of `Ref` because we need locking while side-effecting. */
private[pubsub] trait PubSubState[F[_], K, V] {
  def channelSubs: PubSubState.SubscriptionMap[F, RedisChannel[K], V]
  def patternSubs: PubSubState.SubscriptionMap[F, RedisPattern[K], RedisPatternEvent[K, V]]
}

private[pubsub] object PubSubState {

  trait SubscriptionMap[F[_], K, V] {
    def counts: F[Map[K, Long]]

    def subscribe(key: K)(create: Resource[F, Topic[F, Option[V]]]): Stream[F, V]

    def unsubscribe(key: K): F[Unit]
  }

  private object SubscriptionMap {
    def fromCell[F[_]: MonadCancelThrow, K, V](
        cell: AtomicCell[F, Map[K, Redis4CatsSubscription[F, V]]]
    ): SubscriptionMap[F, K, V] =
      new SubscriptionMap[F, K, V] {
        override def counts: F[Map[K, Long]] =
          cell.get.map(_.iterator.map { case (k, v) => k -> v.subscribers }.toMap)

        override def subscribe(key: K)(create: Resource[F, Topic[F, Option[V]]]): Stream[F, V] =
          Stream.eval(addSubscription(key)(create)).flatMap(_.stream(remove(key)))

        private def addSubscription(key: K)(create: Resource[F, Topic[F, Option[V]]]): F[Redis4CatsSubscription[F, V]] =
          cell.evalModify { subscribers =>
            val getSubscription = subscribers.get(key) match {
              case Some(subscription) =>
                // We have an existing subscription, mark that it has one more subscriber.
                subscription.addSubscriber.pure[F]
              case None =>
                // No existing subscription, create a new one.
                create.allocated.map { case (topic, cleanup) =>
                  Redis4CatsSubscription(topic, subscribers = 1, cleanup)
                }
            }
            getSubscription.map(s => (subscribers.updated(key, s), s))
          }

        private def remove(key: K): F[Unit] =
          cell
            .modify { subscribers =>
              subscribers.get(key) match {
                case Some(sub) =>
                  if (sub.isLastSubscriber) (subscribers - key, sub.cleanup)
                  else (subscribers.updated(key, sub.removeSubscriber), Applicative[F].unit)
                case None =>
                  // We were notified about stream termination but we don't have a subscription, this would be a bug
                  (subscribers, Applicative[F].unit)
              }
            }
            .flatten
            .uncancelable

        override def unsubscribe(key: K): F[Unit] =
          cell.get.map(_.get(key)).flatMap {
            // No subscription = nothing to do
            case None => Applicative[F].unit
            // Publish `None` which will terminate all streams, which will perform cleanup once the last stream
            // terminates.
            case Some(sub) => sub.topic.publish1(None).void
          }
      }

    def fromShards[F[_]: Monad, K, V](shards: Vector[SubscriptionMap[F, K, V]]): SubscriptionMap[F, K, V] =
      new SubscriptionMap[F, K, V] {
        override def counts: F[Map[K, Long]] = shards.foldMapM(_.counts)

        override def subscribe(key: K)(create: Resource[F, Topic[F, Option[V]]]): Stream[F, V] =
          getKeyShard(key).subscribe(key)(create)

        override def unsubscribe(key: K): F[Unit] =
          getKeyShard(key).unsubscribe(key)

        private def getKeyShard(key: K): SubscriptionMap[F, K, V] = {
          val location = Math.abs(key.## % shards.size)
          shards(location)
        }
      }
  }

  def make[F[_]: Concurrent, K, V](shards: Option[Int]): F[PubSubState[F, K, V]] =
    shards.filter(_ > 1) match {
      case None    => single[F, K, V]
      case Some(n) => sharded[F, K, V](n)
    }

  private def single[F[_]: Concurrent, K, V]: F[PubSubState[F, K, V]] =
    for {
      channelSubs0 <- AtomicCell[F].of(Map.empty[RedisChannel[K], Redis4CatsSubscription[F, V]])
      patternSubs0 <- AtomicCell[F].of(Map.empty[RedisPattern[K], Redis4CatsSubscription[F, RedisPatternEvent[K, V]]])
    } yield new PubSubState[F, K, V] {
      override val channelSubs: PubSubState.SubscriptionMap[F, RedisChannel[K], V] =
        SubscriptionMap.fromCell(channelSubs0)
      override val patternSubs: PubSubState.SubscriptionMap[F, RedisPattern[K], RedisPatternEvent[K, V]] =
        SubscriptionMap.fromCell(patternSubs0)
    }

  private def sharded[F[_]: Concurrent, K, V](number: Int): F[PubSubState[F, K, V]] = {
    assert(number > 1)
    for {
      channelShards <- AtomicCell[F]
                         .of(Map.empty[RedisChannel[K], Redis4CatsSubscription[F, V]])
                         .map(SubscriptionMap.fromCell[F, RedisChannel[K], V])
                         .replicateA(number)
      patternShards <- AtomicCell[F]
                         .of(Map.empty[RedisPattern[K], Redis4CatsSubscription[F, RedisPatternEvent[K, V]]])
                         .map(SubscriptionMap.fromCell[F, RedisPattern[K], RedisPatternEvent[K, V]])
                         .replicateA(number)
    } yield new PubSubState[F, K, V] {
      override val channelSubs: PubSubState.SubscriptionMap[F, RedisChannel[K], V] =
        SubscriptionMap.fromShards(channelShards.toVector)
      override val patternSubs: PubSubState.SubscriptionMap[F, RedisPattern[K], RedisPatternEvent[K, V]] =
        SubscriptionMap.fromShards(patternShards.toVector)
    }
  }

}
