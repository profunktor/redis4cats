/*
 * Copyright 2018-2019 Fs2 Redis
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

package com.github.gvolpe.fs2redis.connection

import cats.implicits._
import cats.effect.{ Concurrent, Resource, Sync }
import com.github.gvolpe.fs2redis.domain.{ DefaultRedisClusterClient, Fs2RedisClusterClient }
import com.github.gvolpe.fs2redis.effect.{ JRFuture, Log }
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.RedisClusterClient

import scala.collection.JavaConverters._

object Fs2RedisClusterClient {

  private[fs2redis] def acquireAndRelease[F[_]: Concurrent: Log](
      uri: RedisURI*
  ): (F[Fs2RedisClusterClient], Fs2RedisClusterClient => F[Unit]) = {

    val acquire: F[Fs2RedisClusterClient] =
      Log[F].info(s"Acquire Redis Cluster client") *>
        Sync[F]
          .delay(RedisClusterClient.create(uri.asJava))
          .flatTap(initializeClusterPartitions[F])
          .map(DefaultRedisClusterClient)

    val release: Fs2RedisClusterClient => F[Unit] = client =>
      Log[F].info(s"Releasing Redis Cluster client: ${client.underlying}") *>
        JRFuture.fromCompletableFuture(Sync[F].delay(client.underlying.shutdownAsync())).void

    (acquire, release)
  }

  private[fs2redis] def acquireAndReleaseWithoutUri[F[_]: Concurrent: Log]
    : (F[Fs2RedisClusterClient], Fs2RedisClusterClient => F[Unit]) = acquireAndRelease(new RedisURI())

  private[fs2redis] def initializeClusterPartitions[F[_]: Sync](client: RedisClusterClient): F[Unit] =
    Sync[F].delay(client.getPartitions)

  def apply[F[_]: Concurrent: Log](uri: RedisURI*): Resource[F, Fs2RedisClusterClient] = {
    val (acquire, release) = acquireAndRelease(uri: _*)
    Resource.make(acquire)(release)
  }

}
