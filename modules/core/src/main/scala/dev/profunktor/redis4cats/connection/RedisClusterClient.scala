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
import cats.implicits._
import dev.profunktor.redis4cats.domain.{ LiveRedisClusterClient, NodeId, RedisClusterClient }
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import io.lettuce.core.{ RedisURI => JRedisURI }
import io.lettuce.core.cluster.{ SlotHash, RedisClusterClient => JClusterClient }
import io.lettuce.core.cluster.models.partitions.{ Partitions => JPartitions }

import dev.profunktor.redis4cats.JavaConversions._

object RedisClusterClient {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: Log](
      uri: JRedisURI*
  ): (F[RedisClusterClient], RedisClusterClient => F[Unit]) = {

    val acquire: F[RedisClusterClient] =
      Log[F].info(s"Acquire Redis Cluster client") *>
        Sync[F]
          .delay(JClusterClient.create(uri.asJava))
          .flatTap(initializeClusterPartitions[F])
          .map(LiveRedisClusterClient)

    val release: RedisClusterClient => F[Unit] = client =>
      Log[F].info(s"Releasing Redis Cluster client: ${client.underlying}") *>
        JRFuture.fromCompletableFuture(Sync[F].delay(client.underlying.shutdownAsync())).void

    (acquire, release)
  }

  private[redis4cats] def initializeClusterPartitions[F[_]: Sync](client: JClusterClient): F[Unit] =
    Sync[F].delay(client.getPartitions).void

  def apply[F[_]: Concurrent: ContextShift: Log](uri: JRedisURI*): Resource[F, RedisClusterClient] = {
    val (acquire, release) = acquireAndRelease(uri: _*)
    Resource.make(acquire)(release)
  }

  def nodeId[F[_]: Sync](
      client: RedisClusterClient,
      keyName: String
  ): F[NodeId] =
    Sync[F].delay(SlotHash.getSlot(keyName)).flatMap { slot =>
      partitions(client).map(_.getPartitionBySlot(slot).getNodeId).map(NodeId)
    }

  def partitions[F[_]: Sync](client: RedisClusterClient): F[JPartitions] =
    Sync[F].delay(client.underlying.getPartitions())

}
