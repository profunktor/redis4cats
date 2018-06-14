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

import cats.effect.ConcurrentEffect
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.github.gvolpe.fs2redis.algebra.{PubSubCommands, PubSubConnection}
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import fs2.async.mutable
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class Fs2PubSub[F[_]](client: Fs2RedisClient)(implicit F: ConcurrentEffect[F]) extends PubSubConnection[Stream[F, ?]] {

  override def createPubSubConnection[K, V](codec: Fs2RedisCodec[K, V],
                                            uri: RedisURI): Stream[F, PubSubCommands[Stream[F, ?], K, V]] = {
    val acquire: F[StatefulRedisPubSubConnection[K, V]] = JRFuture.fromConnectionFuture {
      F.delay(client.underlying.connectPubSubAsync(codec.underlying, uri))
    }

    def release(c: StatefulRedisPubSubConnection[K, V]): F[Unit] =
      JRFuture.fromCompletableFuture(F.delay(c.closeAsync())).void

    // One exclusive connection for subscriptions and another connection for publishing / stats
    for {
      state <- Stream.eval(Ref.of(Map.empty[K, mutable.Topic[F, Option[V]]]))
      sConn <- Stream.bracket(acquire)(release)
      pConn <- Stream.bracket(acquire)(release)
      subs  <- Stream.emit(new Fs2PubSubCommands[F, K, V](state, sConn, pConn))
    } yield subs
  }

}
