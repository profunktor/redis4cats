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
      // A single listener for all channels and patterns: since we have a map of all subscriptions, we can
      // dispatch messages to the right topic directly. Lettuce calls listeners one by one for every subscribe,
      // unsubscribe, message, etc., so using one listener per subscription when we can look up the right one
      // directly is wasted work on every message.
      dispatcher <- Dispatcher.sequential[F] // no parallelism needed: onMessage below never blocks on I/O
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

    // Note that this blocks (the calling Lettuce/Netty thread) when `onMessage` semantically blocks - the
    // `Topic`-backed `State` implementation semantically blocks when one of the topic's subscribers is behind.
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

  // Scala 3 doesn't like value classes in generic trait implementations (https://github.com/scala/scala3/issues/11264),
  // so this is a plain case class of functions rather than a trait with abstract methods.
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

    // Representing subscription states, so we can handle subscribing and unsubscribing without a global lock.
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

      /** `topic` starts absent (the very first, synchronous placeholder set before anything effectful runs) and is
        * filled in immediately once created - before the real Redis SUBSCRIBE is issued, not after. That ordering is
        * the whole point: it closes a message-drop window. Lettuce can dispatch a message via the listener as soon as
        * Redis acks the subscription, independent of when the calling fiber's own `subscribe()` future resolves. If the
        * topic only appeared once `subscribe()` had fully completed, any message arriving in between would have nowhere
        * to land and would be silently dropped by `onMessage`'s `Subscribing` case. Since `topic` is populated before
        * the real network call happens, that window no longer exists - while
        * `unsubscribe`/`SubscriptionMap.unsubscribe`'s own "wait for subscribing to finish before touching Redis"
        * behavior (see below) is untouched, since it doesn't inspect the topic at all.
        */
      final case class Subscribing[F[_], V](topic: Option[Topic[F, V]], done: F[Unit]) extends SubscriptionState[F, V]
      final case class Unsubscribing[F[_], V](done: F[Unit]) extends SubscriptionState[F, V]
      // The previous implementation leaves a Redis4CatsSubscription with
      // `subscriber` set to `1` even when there are no subscribers to the Topic
      // anymore.
      final case class FailedToUnsubscribe[F[_], V]() extends SubscriptionState[F, V]

      def description[F[_], A](s: Option[SubscriptionState[F, A]]): String =
        s match {
          case None                        => "no subscription"
          case Some(Active(_, _))          => "active subscription"
          case Some(Subscribing(_, _))     => "subscribing"
          case Some(Unsubscribing(_))      => "unsubscribing"
          case Some(FailedToUnsubscribe()) => "failed to unsubscribe"
        }
    }

    /** What `addSubscription` hands back: either a brand-new subscription's already-registered first consumer stream
      * (`Fresh` - eagerly obtained via `topic.subscribeAwait` before the real Redis SUBSCRIBE, for the same reason
      * `Subscribing` carries its topic early - see its doc comment), or the existing topic to join with a fresh
      * `topic.subscribeAwait` call (`Joined` - Redis already knows about this key, so there's no network round trip to
      * protect against here, only the same narrow, purely-local join window every subscriber accepts).
      */
    private sealed trait Registration[F[_], V]
    private object Registration {
      final case class Fresh[F[_], V](stream: Stream[F, V], release: F[Unit]) extends Registration[F, V]
      final case class Joined[F[_], V](topic: Topic[F, V]) extends Registration[F, V]
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
            .flatMap {
              case Registration.Fresh(stream, releaseFirst) =>
                // Eagerly registered below, before the real Redis SUBSCRIBE was even issued - just hand it
                // back, tying its release to this Resource's lifecycle.
                Resource.make(Applicative[F].pure(stream))(_ => releaseFirst)
              case Registration.Joined(topic) =>
                topic.subscribeAwait(500)
            }

        private def addSubscription(key: K): F[Registration[F, V]] =
          Deferred[F, Unit].flatMap { d =>
            val keyRef = mapRef(key)
            // returning an `F[F[Registration[F, V]]]` so we can wait
            // on subcribing/unsubscribing to end outside of the uncancelable
            // region.
            // This means that there is a subtle but very important difference
            // between `fa.pure[F]` and `fa.map(_.pure[F])` in the code below:
            // - in the first one `fa` will not be part the uncancelable region
            // - in the second `fa` will be uncancelable
            keyRef
              .flatModify[F[Registration[F, V]]] {
                case Some(subscription @ Active(topic, _)) =>
                  // We have an existing subscription, mark that it has one more subscriber.
                  val newSubscription = subscription.addSubscriber
                  val log = Log[F].debug(
                    s"Returning existing subscription for $key, " +
                      s"subscribers: ${subscription.subscribers} -> ${newSubscription.subscribers}"
                  )
                  (Some(newSubscription), log.as(Registration.Joined(topic): Registration[F, V]).pure[F])
                case s @ Some(Unsubscribing(wait)) =>
                  // an existing subscription is getting shut down, wait and try again
                  // note we want to wait and retry outside of the uncancelable scope
                  (s, (wait >> addSubscription(key)).pure[F])
                case s @ Some(Subscribing(_, wait)) =>
                  // an existing subscription is getting created, wait and try again
                  // note we want to wait and retry outside of the uncancelable scope
                  (s, (wait >> addSubscription(key)).pure[F])
                case Some(FailedToUnsubscribe()) =>
                  // unsubscribe failed, but we resubscribe to be sure
                  val action = subscribeStateChange(key, keyRef, d)
                  (Some(Subscribing(None, d.get)), action.map(_.pure[F]))
                case None =>
                  // No existing subscription, create a new one.
                  val action = subscribeStateChange(key, keyRef, d)
                  (Some(Subscribing(None, d.get)), action.map(_.pure[F]))
              }
              .flatten
          }

        // subscribe with redis
        // move to Subscribing(topic) and then to Active
        private def subscribeStateChange(
            key: K,
            keyRef: Ref[F, Option[SubscriptionState[F, V]]],
            d: Deferred[F, Unit]
        ): F[Registration.Fresh[F, V]] = {
          val complete = d.complete(()).void
          val subscribe = Topic[F, V].flatMap { topic =>
            // Register the topic under `Subscribing` - so `onMessage` has somewhere to route a message to -
            // *before* issuing the real Redis SUBSCRIBE below. See the comment on `Subscribing` for why.
            keyRef
              .flatModify {
                case Some(Subscribing(None, _)) => (Some(Subscribing(Some(topic), d.get)), Applicative[F].unit)
                case other                      => (other, unexpectedState[Unit](other, "before subscribing"))
              }
              .flatMap { _ =>
                // Eagerly register *this first subscriber's* fs2-level Topic consumer too, before the real
                // Redis SUBSCRIBE - registering the topic above isn't enough on its own: fs2's Topic doesn't
                // buffer a publish for a consumer that hasn't called subscribeAwait yet, so without this, a
                // message arriving right after Redis acks the subscription would still have nowhere to land.
                topic.subscribeAwait(500).allocated.flatMap { case (firstStream, releaseFirst) =>
                  commands
                    .subscribe(key)
                    .onError { case _ =>
                      keyRef.flatModify {
                        case Some(Subscribing(Some(t), _)) if t eq topic =>
                          (None, releaseFirst.attempt.void *> t.close.void *> complete)
                        case other => (other, unexpectedState(other, "after failing to subscribe"))
                      }
                    }
                    .flatMap { _ =>
                      keyRef.flatModify {
                        case Some(Subscribing(Some(t), _)) if t eq topic =>
                          val subscription = Active(t, subscribers = 1)
                          val registration = Registration.Fresh(firstStream, releaseFirst)
                          (Some(subscription), complete.as(registration))
                        case other =>
                          // unexpected state, but we still try to unsubscribe
                          unsubscribeStateChange(key, keyRef, d).map(
                            _.voidError >>
                              releaseFirst.attempt.void >>
                              unexpectedState[Registration.Fresh[F, V]](other, "after subscribe succeeded")
                          )
                      }
                    }
                }
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
            // wait until the subscription has started and unsubscribe - deliberately not inspecting the
            // topic here: even though `Subscribing` may already carry one (see the comment on `Subscribing`),
            // we still don't touch Redis until the in-flight `subscribe()` call actually resolves, so a
            // concurrent `unsubscribe` can never race ahead of our own not-yet-issued SUBSCRIBE on the wire.
            case Some(Subscribing(_, wait)) => wait >> unsubscribe(key)
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
            case Some(Subscribing(Some(topic), _)) =>
              // The real Redis SUBSCRIBE hasn't been confirmed yet, but the topic already exists (see the
              // comment on `Subscribing`), so a message Lettuce dispatches this early still has somewhere to
              // land instead of being silently dropped.
              topic.publish1(message).void
            case Some(Subscribing(None, _)) =>
              // Genuinely unreachable in practice: Redis can't have forwarded a message for a subscription it
              // doesn't know about yet, and the topic is filled in before the real SUBSCRIBE is ever issued.
              // Kept as a safe fallback rather than an assertion failure.
              Log[F].debug(s"Received message for $key before the subscription topic was created")
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
