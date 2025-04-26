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

package dev.profunktor.redis4cats.pubsub.internals

import scala.util.control.NoStackTrace
import cats.effect.std.Dispatcher
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.data.RedisPattern
import dev.profunktor.redis4cats.data.RedisPatternEvent
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.RedisPubSubAdapter

object PubSubInternals {
  case class DispatcherAlreadyShutdown() extends NoStackTrace

  private[redis4cats] def channelListener[F[_], K, V](
      channel: RedisChannel[K],
      publish: V => F[Unit],
      dispatcher: Dispatcher[F]
  ): RedisPubSubListener[K, V] =
    new RedisPubSubAdapter[K, V] {
      override def message(ch: K, msg: V): Unit =
        if (ch == channel.underlying) {
          try
            dispatcher.unsafeRunSync(publish(msg))
          catch {
            case _: IllegalStateException => throw DispatcherAlreadyShutdown()
          }
        }

      // Do not uncomment this, as if you will do this the channel listener will get a message twice
      // override def message(pattern: K, channel: K, message: V): Unit = {}
    }
  private[redis4cats] def patternListener[F[_], K, V](
      redisPattern: RedisPattern[K],
      publish: RedisPatternEvent[K, V] => F[Unit],
      dispatcher: Dispatcher[F]
  ): RedisPubSubListener[K, V] =
    new RedisPubSubAdapter[K, V] {
      override def message(pattern: K, channel: K, message: V): Unit =
        if (pattern == redisPattern.underlying) {
          try
            dispatcher.unsafeRunSync(publish(RedisPatternEvent(pattern, channel, message)))
          catch {
            case _: IllegalStateException => throw DispatcherAlreadyShutdown()
          }
        }
    }
}
