/*
 * Copyright 2018-2020 ProfunKtor
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

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.Ref
import cats.effect.syntax.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.effect.Log
import fs2.concurrent.Topic
import io.lettuce.core.pubsub.RedisPubSubListener

object PubSubInternals {

  private[redis4cats] def defaultListener[F[_]: ConcurrentEffect, K, V](
      state: Ref[F, PubSubState[F, K, V]]
  ): RedisPubSubListener[K, V] =
    new RedisPubSubListener[K, V] {
      override def message(ch: K, msg: V): Unit =
        state.get
          .flatMap(
            _.get(ch).traverse(_.publish1(Option(msg)))
          )
          .toIO
          .unsafeRunAsync(_ => ())

      override def message(pattern: K, channel: K, message: V): Unit = this.message(channel, message)
      override def psubscribed(pattern: K, count: Long): Unit        = ()
      override def subscribed(channel: K, count: Long): Unit         = ()
      override def unsubscribed(channel: K, count: Long): Unit       = ()
      override def punsubscribed(pattern: K, count: Long): Unit      = ()
    }

  private[redis4cats] def apply[F[_]: ConcurrentEffect: Log, K, V](
      state: Ref[F, PubSubState[F, K, V]]
  ): GetOrCreateTopicListener[F, K, V] = { channels =>
    Topic[F, Option[V]](None).flatTap { topic =>
      F.info(s"Listening to channels: ${channels.map(_.underlying).mkString(",")}") *>
        state.update(st => st ++ channels.map(_.underlying -> topic).toMap)
    }

  }

}
