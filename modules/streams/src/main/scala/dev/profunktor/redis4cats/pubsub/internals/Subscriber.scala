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

import cats.{ Applicative, ApplicativeThrow, FlatMap, Functor }
import cats.effect.kernel._
import cats.effect.std.{ Dispatcher, MapRef }
import cats.syntax.all._
import dev.profunktor.redis4cats.data.{ RedisChannel, RedisPattern, RedisPatternEvent }
import dev.profunktor.redis4cats.effect.{ FutureLift, Log }
import fs2.Stream
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.{ RedisPubSubAdapter, RedisPubSubListener, StatefulRedisPubSubConnection }

private[internals] class Subscriber[F[_]: MonadCancelThrow, K, V] private (
    private val state: Subscriber.State[F, K, V]
) extends SubscribeCommands[F, Stream[F, *], K, V] {

  override def subscribe(channel: RedisChannel[K]): Stream[F, V] =
    Stream.resource(state.channelSubs.subscribeAwait(channel)).flatten

  override def unsubscribe(channel: RedisChannel[K]): F[Unit] =
    state.channelSubs.unsubscribe(channel)

  override def psubscribe(
      pattern: RedisPattern[K]
  ): Stream[F, RedisPatternEvent[K, V]] =
    Stream.resource(state.patternSubs.subscribeAwait(pattern)).flatten

  override def punsubscribe(pattern: RedisPattern[K]): F[Unit] =
    state.patternSubs.unsubscribe(pattern)

  override def internalChannelSubscriptions: F[Map[RedisChannel[K], Long]] =
    state.channelSubs.counts

  override def internalPatternSubscriptions: F[Map[RedisPattern[K], Long]] =
    state.patternSubs.counts
}

private[pubsub] object Subscriber {

  def make[F[_]: Async: FutureLift: Log, K, V](
      subConnection: StatefulRedisPubSubConnection[K, V]
  ): Resource[F, SubscribeCommands[F, Stream[F, *], K, V]] =
    for {
      state <- Resource.eval(
                 State.fromMapRefs[F, K, V](
                   channelCommands = SubscriptionCommands.channel(subConnection),
                   patternCommands = SubscriptionCommands.pattern(subConnection)
                 )
               )
      // We only use a single listener for all channels and patterns.
      // Since we have a map of all subscriptions, we can dispatch messages to
      // the right topic directly.
      // Lettuce calls the listeners one by one (for every subscribe,
      // unsubscribe, message, ...), so using multiple listeners when we can
      // find the right subscription easily, is inefficient.
      dispatcher <- Dispatcher.sequential[F] // we have no parallelism in the listener
      _ <- Resource.make {
             val listener = State.listener(state, dispatcher)
             Sync[F].delay(subConnection.addListener(listener)).as(listener)
           }(listener => Sync[F].delay(subConnection.removeListener(listener)))
    } yield new Subscriber(state)

  private final case class State[F[_], K, V](
      channelSubs: SubscriptionMap[F, RedisChannel[K], V],
      patternSubs: SubscriptionMap[F, RedisPattern[K], RedisPatternEvent[K, V]]
  )

  private object State {
    def fromMapRefs[F[_]: Async: Log, K, V](
        channelCommands: SubscriptionCommands[F, RedisChannel[K]],
        patternCommands: SubscriptionCommands[F, RedisPattern[K]]
    ): F[State[F, K, V]] =
      (
        SubscriptionMap.makeMapRef[F, RedisChannel[K], V](channelCommands),
        SubscriptionMap.makeMapRef[F, RedisPattern[K], RedisPatternEvent[K, V]](patternCommands)
      ).mapN(apply)

    // Note that this blocks when `onMessage` semantically blocks.
    // The current `State` implementation using `Topic` semantically blocks when
    // one of the subscribers of the `Topic` is behind.
    def listener[F[_], K, V](
        state: State[F, K, V],
        dispatcher: Dispatcher[F]
    ): RedisPubSubListener[K, V] =
      new RedisPubSubAdapter[K, V] {
        override def message(ch: K, msg: V): Unit =
          try
            dispatcher.unsafeRunSync(state.channelSubs.onMessage(RedisChannel(ch), msg))
          catch {
            case _: IllegalStateException => throw PubSubInternals.DispatcherAlreadyShutdown()
          }
        override def message(pattern: K, channel: K, message: V): Unit =
          try
            dispatcher.unsafeRunSync(
              state.patternSubs.onMessage(RedisPattern(pattern), RedisPatternEvent(pattern, channel, message))
            )
          catch {
            case _: IllegalStateException => throw PubSubInternals.DispatcherAlreadyShutdown()
          }
      }
  }

  // scala 3 doesn't like value classes in generic trait implementations:
  // https://github.com/scala/scala3/issues/11264
  // private[internals] trait SubscriptionCommands[F[_], K] {
  //   def subscribe(key: K): F[Unit]
  //   def unsubscribe(key: K): F[Unit]
  // }
  private[internals] final case class SubscriptionCommands[F[_], K](
      subscribe: K => F[Unit],
      unsubscribe: K => F[Unit]
  )

  private[internals] object SubscriptionCommands {
    def channel[F[_]: FutureLift: Functor, K, V](
        subConnection: StatefulRedisPubSubConnection[K, V]
    ): SubscriptionCommands[F, RedisChannel[K]] =
      SubscriptionCommands[F, RedisChannel[K]](
        subscribe = key => FutureLift[F].lift(subConnection.async().subscribe(key.underlying)).void,
        unsubscribe = key => FutureLift[F].lift(subConnection.async().unsubscribe(key.underlying)).void
      )

    def pattern[F[_]: FutureLift: Functor, K, V](
        subConnection: StatefulRedisPubSubConnection[K, V]
    ): SubscriptionCommands[F, RedisPattern[K]] =
      SubscriptionCommands[F, RedisPattern[K]](
        subscribe = key => FutureLift[F].lift(subConnection.async().psubscribe(key.underlying)).void,
        unsubscribe = key => FutureLift[F].lift(subConnection.async().punsubscribe(key.underlying)).void
      )

    def withLogs[F[_]: FlatMap: Log, K](base: SubscriptionCommands[F, K]): SubscriptionCommands[F, K] =
      SubscriptionCommands[F, K](
        subscribe = key => base.subscribe(key) >> Log[F].debug(s"Subscribed to $key"),
        unsubscribe = key => base.unsubscribe(key) >> Log[F].debug(s"Unsubscribed from $key")
      )
  }

  private[internals] trait SubscriptionMap[F[_], K, V] {
    def counts: F[Map[K, Long]]

    def subscribeAwait(key: K): Resource[F, Stream[F, V]]

    def unsubscribe(key: K): F[Unit]

    def onMessage(key: K, message: V): F[Unit]
  }

  private[internals] object SubscriptionMap {

    // Representing subscription states, so we can handling subscribing and
    // unsubscribing without locking.
    //
    // State changes:
    //
    // None => Subscribing (subscribe)
    // Subscribing -> Active (subscribe)
    // Active -> Unsubscribing (remove)
    // Unsubscribing -> None (remove, unsubscribe)
    //
    // Unsubscribing -> FailedToUnsubscribe (remove)
    // FailedToUnsubscribe -> Subscribing (subscribe)
    // FailedToUnsubscribe -> Unsubscribing (unsubscribe)
    private sealed trait SubscriptionState[F[_], V]
    private object SubscriptionState {
      final case class Active[F[_], V](
          topic: Topic[F, V],
          subscribers: Long
      ) extends SubscriptionState[F, V] {
        assert(subscribers > 0, s"subscribers must be > 0, was $subscribers")

        def addSubscriber: Active[F, V]    = copy(subscribers = subscribers + 1)
        def removeSubscriber: Active[F, V] = copy(subscribers = subscribers - 1)
        def isLastSubscriber: Boolean      = subscribers == 1
      }
      final case class Subscribing[F[_], V](done: F[Unit]) extends SubscriptionState[F, V]
      final case class Unsubscribing[F[_], V](done: F[Unit]) extends SubscriptionState[F, V]
      // The previous implementation leaves a Redis4CatsSubscription with
      // `subscriber` set to `1` even when there are no subscribers to the Topic
      // anymore.
      final case class FailedToUnsubscribe[F[_], V]() extends SubscriptionState[F, V]

      def description[F[_], A](s: Option[SubscriptionState[F, A]]): String =
        s match {
          case None                        => "no subscription"
          case Some(Active(_, _))          => "active subscription"
          case Some(Subscribing(_))        => "subscribing"
          case Some(Unsubscribing(_))      => "unsubscribing"
          case Some(FailedToUnsubscribe()) => "failed to unsubscribe"
        }
    }

    def makeMapRef[F[_]: Async: Log, K, V](
        commands: SubscriptionCommands[F, K]
    ): F[SubscriptionMap[F, K, V]] =
      Sync[F]
        .delay {
          // cats-effect defaults
          val initialCapacity  = 16
          val loadFactor       = 0.75f
          val concurrencyLevel = 16
          new java.util.concurrent.ConcurrentHashMap[K, SubscriptionState[F, V]](
            initialCapacity,
            loadFactor,
            concurrencyLevel
          )
        }
        .map { chm =>
          import scala.jdk.CollectionConverters._
          val mapRef = MapRef.fromConcurrentHashMap[F, K, SubscriptionState[F, V]](chm)
          val values = Sync[F].delay(chm.entrySet().iterator.asScala.map(entry => entry.getKey -> entry.getValue).toMap)
          fromMapRef[F, K, V](mapRef, values, commands)
        }

    def singleRef[F[_]: Concurrent: Log, K, V](
        commands: SubscriptionCommands[F, K]
    ): F[SubscriptionMap[F, K, V]] =
      Ref[F]
        .of(Map.empty[K, SubscriptionState[F, V]])
        .map { ref =>
          fromMapRef[F, K, V](MapRef.fromSingleImmutableMapRef(ref), ref.get, commands)
        }

    private def fromMapRef[F[_]: Concurrent: Log, K, V](
        mapRef: MapRef[F, K, Option[SubscriptionState[F, V]]],
        values: F[Map[K, SubscriptionState[F, V]]],
        commands: SubscriptionCommands[F, K]
    ): SubscriptionMap[F, K, V] =
      new SubscriptionMap[F, K, V] {
        import SubscriptionState._

        override def counts: F[Map[K, Long]] =
          values.map(_.collect {
            case (k, Active(_, subscribers)) => (k, subscribers)
            case (k, FailedToUnsubscribe())  => (k, 0L)
          })

        override def subscribeAwait(key: K): Resource[F, Stream[F, V]] =
          Resource
            .make(addSubscription(key))(_ => remove(key))
            .flatMap(_.topic.subscribeAwait(500))

        private def addSubscription(key: K): F[SubscriptionState.Active[F, V]] =
          Deferred[F, Unit].flatMap { d =>
            val keyRef = mapRef(key)
            // returning an `F[F[SubscriptionState.Active[F, V]]]]` so we can wait
            // on subcribing/unsubscribing to end outside of the uncancelable
            // region.
            // This means that there is a subtle but very important difference
            // between `fa.pure[F]` and `fa.map(_.pure[F])` in the code below:
            // - in the first one `fa` will not be part the uncancelable region
            // - in the second `fa` will be uncancelable
            keyRef
              .flatModify[F[SubscriptionState.Active[F, V]]] {
                case Some(subscription @ Active(_, _)) =>
                  // We have an existing subscription, mark that it has one more subscriber.
                  val newSubscription = subscription.addSubscriber
                  val log = Log[F].debug(
                    s"Returning existing subscription for $key, " +
                      s"subscribers: ${subscription.subscribers} -> ${newSubscription.subscribers}"
                  )
                  (Some(newSubscription), log.as(newSubscription).pure[F])
                case s @ Some(Unsubscribing(wait)) =>
                  // an existing subscription is getting shut down, wait and try again
                  // note we want to wait and retry outside of the uncancelable scope
                  (s, (wait >> addSubscription(key)).pure[F])
                case s @ Some(Subscribing(wait)) =>
                  // an existing subscription is getting created, wait and try again
                  // note we want to wait and retry outside of the uncancelable scope
                  (s, (wait >> addSubscription(key)).pure[F])
                case Some(FailedToUnsubscribe()) =>
                  // unsubscribe failed, but we resubscribe to be sure
                  val action = subscribeStateChange(key, keyRef, d)
                  (Some(Subscribing(d.get)), action.map(_.pure[F]))
                case None =>
                  // No existing subscription, create a new one.
                  val action = subscribeStateChange(key, keyRef, d)
                  (Some(Subscribing(d.get)), action.map(_.pure[F]))
              }
              .flatten
          }

        // subscribe with redis
        // move to Subscribing and then to Active
        private def subscribeStateChange(
            key: K,
            keyRef: Ref[F, Option[SubscriptionState[F, V]]],
            d: Deferred[F, Unit]
        ): F[SubscriptionState.Active[F, V]] = {
          val complete = d.complete(()).void
          val subscribe = (Topic[F, V] <* commands.subscribe(key))
            .onError { case _ =>
              keyRef.flatModify {
                case Some(Subscribing(_)) => (None, complete)
                case other                => (other, unexpectedState(other, "after failing to subscribe"))
              }
            }
            .flatMap { topic =>
              keyRef.flatModify {
                case Some(Subscribing(_)) =>
                  val subscription = Active(topic, subscribers = 1)
                  (Some(subscription), complete.as(subscription))
                case other =>
                  // unexpected state, but we still try to unsubscribe
                  unsubscribeStateChange(key, keyRef, d).map(
                    _.voidError >> unexpectedState[Active[F, V]](
                      other,
                      "after subscribe succeeded"
                    )
                  )
              }
            }
          Log[F].info(s"Creating subscription for $key") *> subscribe <* Log[F].debug(
            s"Created subscription for $key"
          )
        }

        private def remove(key: K): F[Unit] =
          Deferred[F, Unit].flatMap { d =>
            val keyRef = mapRef(key)
            keyRef.flatModify {
              case Some(sub @ Active(_, _)) =>
                if (sub.isLastSubscriber) unsubscribeStateChange(key, keyRef, d)
                else (Some(sub.removeSubscriber), Applicative[F].unit)
              case Some(FailedToUnsubscribe()) =>
                unsubscribeStateChange(key, keyRef, d)
              case other =>
                // `remove` is only called from `subscribe` after we have an active subscription,
                // so we shouldn't get a `remove` for `None` or `Subscribing`.
                // We can only end up in `Unsubscribing` after the last `remove` for a subscription
                // so we shouldn't get a `remove` for `Unsubscribing`.
                val log = Log[F].error(
                  s"We were notified about stream termination for $key but we don't have an active subscription, " +
                    s"this is a bug in redis4cats!"
                )
                (other, log)
            }
          }

        override def unsubscribe(key: K): F[Unit] =
          mapRef(key).get.flatMap {
            // No subscription = nothing to do
            case None => Log[F].debug(s"Not unsubscribing from $key because we don't have a subscription")
            // Subscription already shutting down = nothing to do
            case Some(Unsubscribing(_)) => Applicative[F].unit
            // `close` will terminate all streams, which will unsubscribe
            // once the last stream terminates.
            case Some(Active(topic, subscribers)) =>
              // TODO: Should we unsubscribe here already?
              // `Topic#publish` after closing is a no op, any new messages
              // won't be observed.
              Log[F].info(s"Unsubscribing from $key with ${subscribers} subscribers") >>
                topic.close.void
            // wait until the subscription has started and unsubscribe
            case Some(Subscribing(wait)) => wait >> unsubscribe(key)
            // retry to unsubscribe
            case Some(FailedToUnsubscribe()) =>
              // unlike with the previous implementation we can retry to
              // unsubscribe. We could call `unsubscribe` before, but we would
              // never try to actually unsubscribe, since there are no topic
              // subscribers to call `remove`.
              Deferred[F, Unit].flatMap { d =>
                val keyRef = mapRef(key)
                keyRef.flatModify {
                  case Some(FailedToUnsubscribe()) => unsubscribeStateChange(key, keyRef, d)
                  case other                       => (other, d.complete(()).void)
                }
              }
          }

        // unsubscribe with redis
        // move to Unsubscribing and then to None (or FailedToUnsubscribe)
        private def unsubscribeStateChange(
            key: K,
            keyRef: Ref[F, Option[SubscriptionState[F, V]]],
            d: Deferred[F, Unit]
        ): (Option[SubscriptionState[F, V]], F[Unit]) = {
          val complete = d.complete(()).void
          val action = commands
            .unsubscribe(key)
            .onError { case _ =>
              keyRef.flatModify {
                case Some(Unsubscribing(_)) => (Some(FailedToUnsubscribe()), complete)
                case other => (other, complete >> unexpectedState(other, "after unsubscribing unsuccessfully"))
              }
            }
            .>>(
              keyRef.flatModify {
                case Some(Unsubscribing(_)) => (None, complete)
                case other => (other, complete >> unexpectedState[Unit](other, "after unsubscribing successfully"))
              }
            )
          (Some(Unsubscribing(d.get)), action)
        }

        override def onMessage(key: K, message: V): F[Unit] =
          mapRef(key).get.flatMap {
            case Some(Active(topic, _)) =>
              // if one of the topics subscriptions is behind, we wiil block
              // other messages
              topic.publish1(message).void
            case Some(Subscribing(_)) =>
              // we should only get this when we already successfully subscribed
              // to redis, but the state hasn't been updated yet.
              // The previous implementation would publish to the topic, but
              // there would be no subecribers to the topic yet. So dropping the
              // message is equivalent.
              // We could wait until the we are done subscribing, but that would
              // block other messages:
              // wait >> onMessage(key, message)
              Log[F].debug(s"Received message for $key before the subscription stream has started")
            case Some(Unsubscribing(_))      => Applicative[F].unit
            case Some(FailedToUnsubscribe()) =>
              // TODO should we spawn an unsubscribe here?
              Applicative[F].unit
            case None =>
              // We expect that all SUBSCRIBE commands are made through
              // `subscribe`. so we should never receive message without
              // subscriptions
              Log[F].info(s"Received message for $key without subscription")
          }

        private def unexpectedState[A](state: Option[SubscriptionState[F, V]], msg: String): F[A] =
          ApplicativeThrow[F].raiseError(
            new IllegalStateException(
              s"Unexpected subscription state (${SubscriptionState.description(state)}) $msg. This is a bug in redis4cats!"
            )
          )
      }

  }

}
