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

package dev.profunktor.redis4cats.connection

import java.time.Duration
import java.util.concurrent.TimeUnit

import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.JavaConversions._
import dev.profunktor.redis4cats.config._
import dev.profunktor.redis4cats.data.NodeId
import dev.profunktor.redis4cats.effect.{ JRFuture, Log, RedisExecutor }
import io.lettuce.core.cluster.models.partitions.{ Partitions => JPartitions }
import io.lettuce.core.cluster.{
  ClusterClientOptions,
  ClusterTopologyRefreshOptions,
  SlotHash,
  RedisClusterClient => JClusterClient
}

sealed abstract case class RedisClusterClient private (underlying: JClusterClient)

object RedisClusterClient {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: RedisExecutor: Log](
      config: Redis4CatsConfig,
      uri: RedisURI*
  ): (F[RedisClusterClient], RedisClusterClient => F[Unit]) = {

    val acquire: F[RedisClusterClient] =
      F.info(s"Acquire Redis Cluster client") *>
          F.delay(JClusterClient.create(uri.map(_.underlying).asJava))
            .flatTap(initializeClusterTopology[F](_, config.topologyViewRefreshStrategy))
            .map(new RedisClusterClient(_) {})

    val release: RedisClusterClient => F[Unit] = client =>
      F.info(s"Releasing Redis Cluster client: ${client.underlying}") *>
          JRFuture
            .fromCompletableFuture(
              F.delay(
                client.underlying.shutdownAsync(
                  config.shutdown.quietPeriod.toNanos,
                  config.shutdown.timeout.toNanos,
                  TimeUnit.NANOSECONDS
                )
              )
            )
            .void

    (acquire, release)
  }

  private[redis4cats] def initializeClusterTopology[F[_]: Sync](
      client: JClusterClient,
      topologyViewRefreshStrategy: TopologyViewRefreshStrategy
  ): F[Unit] =
    F.delay {
      topologyViewRefreshStrategy match {
        case NoRefresh =>
          client.getPartitions
        case Periodic(interval) =>
          client.setOptions(
            ClusterClientOptions
              .builder()
              .topologyRefreshOptions(
                ClusterTopologyRefreshOptions
                  .builder()
                  // Use implicit duration converters from scala 2.13 once 2.12 support is removed
                  .enablePeriodicRefresh(Duration.ofMillis(interval.toMillis))
                  .build()
              )
              .build()
          )
        case Adaptive(timeout) =>
          client.setOptions(
            ClusterClientOptions
              .builder()
              .topologyRefreshOptions(
                ClusterTopologyRefreshOptions
                  .builder()
                  .enableAllAdaptiveRefreshTriggers()
                  // Use implicit duration converters from scala 2.13 once 2.12 support is removed
                  .adaptiveRefreshTriggersTimeout(Duration.ofMillis(timeout.toMillis))
                  .build()
              )
              .build()
          )
      }
    }.void

  def apply[F[_]: Concurrent: ContextShift: Log](uri: RedisURI*): Resource[F, RedisClusterClient] =
    configured[F](Redis4CatsConfig(), uri: _*)

  def configured[F[_]: Concurrent: ContextShift: Log](
      config: Redis4CatsConfig,
      uri: RedisURI*
  ): Resource[F, RedisClusterClient] =
    RedisExecutor.make[F].flatMap { implicit redisExecutor =>
      val (acquire, release) = acquireAndRelease(config, uri: _*)
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
