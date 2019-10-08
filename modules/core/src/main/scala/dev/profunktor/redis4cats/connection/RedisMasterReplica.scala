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
import cats.syntax.all._
import dev.profunktor.redis4cats.domain._
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import io.lettuce.core.masterreplica.{ MasterReplica, StatefulRedisMasterReplicaConnection }
import io.lettuce.core.{ ReadFrom => JReadFrom, RedisURI => JRedisURI }

import dev.profunktor.redis4cats.JavaConversions._

object RedisMasterReplica {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom],
      uris: JRedisURI*
  ): (F[RedisMasterReplicaConnection[K, V]], RedisMasterReplicaConnection[K, V] => F[Unit]) = {

    val acquire: F[RedisMasterReplicaConnection[K, V]] = {

      val connection: F[RedisMasterReplicaConnection[K, V]] =
        JRFuture
          .fromCompletableFuture[F, StatefulRedisMasterReplicaConnection[K, V]] {
            Sync[F].delay { MasterReplica.connectAsync[K, V](client.underlying, codec.underlying, uris.asJava) }
          }
          .map(LiveRedisMasterReplicaConnection.apply)

      readFrom.fold(connection)(rf => connection.flatMap(c => Sync[F].delay(c.underlying.setReadFrom(rf)) *> c.pure[F]))
    }

    val release: RedisMasterReplicaConnection[K, V] => F[Unit] = connection =>
      Log[F].info(s"Releasing Redis Master/Replica connection: ${connection.underlying}") *>
        JRFuture.fromCompletableFuture(Sync[F].delay(connection.underlying.closeAsync())).void

    (acquire, release)
  }

  def apply[F[_]: Concurrent: ContextShift: Log, K, V](
      codec: RedisCodec[K, V],
      uris: JRedisURI*
  )(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplicaConnection[K, V]] =
    Resource.liftF(RedisClient.acquireAndReleaseWithoutUri[F]).flatMap {
      case (acquireClient, releaseClient) =>
        Resource.make(acquireClient)(releaseClient).flatMap { client =>
          val (acquire, release) = acquireAndRelease(client, codec, readFrom, uris: _*)
          Resource.make(acquire)(release)
        }
    }

}
