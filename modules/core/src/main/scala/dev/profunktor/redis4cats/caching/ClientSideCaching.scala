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

package dev.profunktor.redis4cats.caching

import cats.effect.kernel.{ Async, Resource }
import dev.profunktor.redis4cats.effect.TxExecutor
import io.lettuce.core.TrackingArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.support.caching.{
  CacheAccessor => JCacheAccessor,
  CacheFrontend => JCacheFrontend,
  ClientSideCaching => JClientSideCaching
}

object ClientSideCaching {

  def make[F[_]: Async, K, V](
      connection: StatefulRedisConnection[K, V],
      args: TrackingArgs,
      cacheAccessor: CacheAccessor[F, K, V]
  ): Resource[F, JCacheFrontend[K, V]] =
    TxExecutor.make[F].flatMap { redisExecutor =>
      Resource.make[F, JCacheFrontend[K, V]] {
        Async[F].delay {
          JClientSideCaching.enable(
            new JCacheAccessor[K, V] {
              override def get(key: K): V              = redisExecutor.unsafeRun(cacheAccessor.get(key))
              override def put(key: K, value: V): Unit = redisExecutor.unsafeRun(cacheAccessor.put(key, value))
              override def evict(key: K): Unit         = redisExecutor.unsafeRun(cacheAccessor.evict(key))
            },
            connection,
            args
          )
        }
      }(cacheFrontend => Async[F].delay(cacheFrontend.close()))
    }
}
