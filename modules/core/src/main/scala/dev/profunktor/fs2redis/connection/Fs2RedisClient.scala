/*
 * Copyright 2018-2019 ProfunKtor
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

package dev.profunktor.redis4cats.connection

import cats.effect.{ Concurrent, ContextShift, Resource, Sync }
import cats.syntax.apply._
import cats.syntax.functor._
import dev.profunktor.redis4cats.domain.{ DefaultRedisClient, Fs2RedisClient }
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import io.lettuce.core.{ RedisClient, RedisURI }

object Fs2RedisClient {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: Log](
      uri: => RedisURI
  ): (F[Fs2RedisClient], Fs2RedisClient => F[Unit]) = {
    val acquire: F[Fs2RedisClient] = Sync[F].delay { DefaultRedisClient(RedisClient.create(uri)) }

    val release: Fs2RedisClient => F[Unit] = client =>
      Log[F].info(s"Releasing Redis connection: $uri") *>
        JRFuture.fromCompletableFuture(Sync[F].delay(client.underlying.shutdownAsync())).void

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseWithoutUri[F[_]: Concurrent: ContextShift: Log]
    : F[(F[Fs2RedisClient], Fs2RedisClient => F[Unit])] = Sync[F].delay(new RedisURI()).map(acquireAndRelease(_))

  def apply[F[_]: Concurrent: ContextShift: Log](uri: => RedisURI): Resource[F, Fs2RedisClient] = {
    val (acquire, release) = acquireAndRelease(uri)
    Resource.make(acquire)(release)
  }

}
