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

package dev.profunktor.redis4cats.connection

import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.JavaConversions._
import dev.profunktor.redis4cats.domain.NodeId
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import dev.profunktor.redis4cats.effect.JRFuture._
import io.lettuce.core.cluster.{ SlotHash, RedisClusterClient => JClusterClient }
import io.lettuce.core.cluster.models.partitions.{ Partitions => JPartitions }

sealed abstract case class RedisClusterClient private (underlying: JClusterClient)

object RedisClusterClient {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: Log](
      blocker: Blocker,
      uri: RedisURI*
  ): (F[RedisClusterClient], RedisClusterClient => F[Unit]) = {

    val acquire: F[RedisClusterClient] =
      F.info(s"Acquire Redis Cluster client") *>
          F.delay(JClusterClient.create(uri.map(_.underlying).asJava))
            .flatTap(initializeClusterPartitions[F])
            .map(new RedisClusterClient(_) {})

    val release: RedisClusterClient => F[Unit] = client =>
      F.info(s"Releasing Redis Cluster client: ${client.underlying}") *>
          JRFuture.fromCompletableFuture(F.delay(client.underlying.shutdownAsync()))(blocker).void

    (acquire, release)
  }

  private[redis4cats] def initializeClusterPartitions[F[_]: Sync](client: JClusterClient): F[Unit] =
    F.delay(client.getPartitions).void

  def apply[F[_]: Concurrent: ContextShift: Log](uri: RedisURI*): Resource[F, RedisClusterClient] =
    mkBlocker[F].flatMap { blocker =>
      val (acquire, release) = acquireAndRelease(blocker, uri: _*)
      Resource.make(acquire)(release)
    }

  def fromUnderlying(underlying: JClusterClient): RedisClusterClient =
    new RedisClusterClient(underlying) {}

  def nodeId[F[_]: Sync](
      client: RedisClusterClient,
      keyName: String
  ): F[NodeId] =
    F.delay(SlotHash.getSlot(keyName)).flatMap { slot =>
      partitions(client).map(_.getPartitionBySlot(slot).getNodeId).map(NodeId)
    }

  def partitions[F[_]: Sync](client: RedisClusterClient): F[JPartitions] =
    F.delay(client.underlying.getPartitions())

}
