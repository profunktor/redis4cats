/*
 * Copyright 2018 Fs2 Redis
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

package com.github.gvolpe.fs2redis.interpreter.pubsub

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.SubscribeCommands
import com.github.gvolpe.fs2redis.interpreter.pubsub.internals.{Fs2PubSubInternals, PubSubState}
import com.github.gvolpe.fs2redis.model.Fs2RedisChannel
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class Fs2Subscriber[F[_], K, V](state: Ref[F, PubSubState[F, K, V]], subConnection: StatefulRedisPubSubConnection[K, V])(
    implicit F: ConcurrentEffect[F])
    extends SubscribeCommands[Stream[F, ?], K, V] {

  override def subscribe(channel: Fs2RedisChannel[K]): Stream[F, V] = {
    val getOrCreateTopicListener = Fs2PubSubInternals[F, K, V](state, subConnection)
    val setup: F[Topic[F, Option[V]]] =
      for {
        st    <- state.get
        topic <- getOrCreateTopicListener(channel)(st)
        _     <- JRFuture(F.delay(subConnection.async().subscribe(channel.value)))
      } yield topic

    Stream.eval(setup).flatMap(_.subscribe(500).unNone)
  }

  override def unsubscribe(channel: Fs2RedisChannel[K]): Stream[F, Unit] =
    Stream.eval {
      JRFuture(F.delay(subConnection.async().unsubscribe(channel.value))).void
    }

}
