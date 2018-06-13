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

package com.github.gvolpe.fs2redis.interpreter

import cats.effect.Async
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.PubSubConnection
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class Fs2PubSub[F[_]](client: Fs2RedisClient)(implicit F: Async[F]) extends PubSubConnection[Stream[F, ?]] {

  override def connect[K, V](codec: Fs2RedisCodec[K, V], uri: RedisURI): Stream[F, Fs2RedisPubSubConnection[K, V]] = {
    val acquire: F[StatefulRedisPubSubConnection[K, V]] = JRFuture.fromConnectionFuture {
      F.delay(client.underlying.connectPubSubAsync(codec.underlying, uri))
    }

    def release(c: StatefulRedisPubSubConnection[K, V]): F[Unit] =
      JRFuture.fromCompletableFuture(F.delay(c.closeAsync())).void

    Stream.bracket(acquire)(release).map(DefaultPubSubConnection.apply)
  }

}
