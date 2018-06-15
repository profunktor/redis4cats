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

package com.github.gvolpe.fs2redis.interpreter.pubsub.internals

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.Ref
import cats.effect.syntax.effect._
import cats.syntax.all._
import com.github.gvolpe.fs2redis.model.Fs2RedisChannel
import fs2.async.mutable
import io.lettuce.core.pubsub.{RedisPubSubListener, StatefulRedisPubSubConnection}

object Fs2PubSubInternals {

  private[fs2redis] def defaultListener[F[_]: ConcurrentEffect, K, V](
      fs2RedisChannel: Fs2RedisChannel[K],
      topic: mutable.Topic[F, Option[V]]): RedisPubSubListener[K, V] =
    new RedisPubSubListener[K, V] {
      override def message(channel: K, message: V): Unit =
        if (channel == fs2RedisChannel.value) {
          topic.publish1(Option(message)).toIO.unsafeRunAsync(_ => ())
        }
      override def message(pattern: K, channel: K, message: V): Unit = this.message(channel, message)
      override def psubscribed(pattern: K, count: Long): Unit        = ()
      override def subscribed(channel: K, count: Long): Unit         = ()
      override def unsubscribed(channel: K, count: Long): Unit       = ()
      override def punsubscribed(pattern: K, count: Long): Unit      = ()
    }

  private[fs2redis] def apply[F[_], K, V](state: Ref[F, PubSubState[F, K, V]],
                                          subConnection: StatefulRedisPubSubConnection[K, V])(
      implicit F: ConcurrentEffect[F]): GetOrCreateTopicListener[F, K, V] = { channel => st =>
    st.get(channel.value)
      .fold {
        for {
          topic    <- fs2.async.topic[F, Option[V]](None)
          listener = defaultListener(channel, topic)
          _        <- F.delay(println(s"Adding listener for channel: $channel"))
          _        <- F.delay(subConnection.addListener(listener))
          _        <- state.update(_.updated(channel.value, topic))
        } yield topic
      }(F.pure)
  }

}
