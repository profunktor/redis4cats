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

package com.github.gvolpe.fs2redis.interpreter.connection

import cats.effect.{Concurrent, Resource}
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.model.{DefaultRedisClient, Fs2RedisClient}
import com.github.gvolpe.fs2redis.util.{JRFuture, Log}
import fs2.Stream
import io.lettuce.core.{RedisClient, RedisURI}

object Fs2RedisClient {

  private[fs2redis] def acquireAndRelease[F[_]](
      uri: RedisURI)(implicit F: Concurrent[F], L: Log[F]): (F[Fs2RedisClient], Fs2RedisClient => F[Unit]) = {
    val acquire: F[Fs2RedisClient] = F.delay { DefaultRedisClient(RedisClient.create(uri)) }

    val release: Fs2RedisClient => F[Unit] = client =>
      L.info(s"Releasing Redis connection: $uri") *>
        JRFuture.fromCompletableFuture(F.delay(client.underlying.shutdownAsync())).void

    (acquire, release)
  }

  private[fs2redis] def acquireAndReleaseWithoutUri[F[_]](implicit F: Concurrent[F],
                                                          L: Log[F]): (F[Fs2RedisClient], Fs2RedisClient => F[Unit]) = {
    val acquire: F[Fs2RedisClient] = F.delay { DefaultRedisClient(RedisClient.create()) }

    val release: Fs2RedisClient => F[Unit] = client =>
      L.info(s"Releasing Redis connection: No URI") *>
        JRFuture.fromCompletableFuture(F.delay(client.underlying.shutdownAsync())).void

    (acquire, release)
  }

  def apply[F[_]: Concurrent: Log](uri: RedisURI): Resource[F, Fs2RedisClient] = {
    val (acquire, release) = acquireAndRelease(uri)
    Resource.make(acquire)(release)
  }

  def stream[F[_]: Concurrent: Log](uri: RedisURI): Stream[F, Fs2RedisClient] = {
    val (acquire, release) = acquireAndRelease(uri)
    Stream.bracket(acquire)(release)
  }

}
