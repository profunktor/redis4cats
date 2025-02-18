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

import cats.{ FlatMap, Functor }
import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.JavaConversions._
import dev.profunktor.redis4cats.config._
import dev.profunktor.redis4cats.data.NodeId
import dev.profunktor.redis4cats.effect._
import io.lettuce.core.cluster.models.partitions.{ Partitions => JPartitions, RedisClusterNode }
import io.lettuce.core.cluster.{
  ClusterClientOptions,
  ClusterTopologyRefreshOptions,
  RedisClusterClient => JClusterClient,
  SlotHash
}

sealed abstract case class RedisClusterClient private (underlying: JClusterClient)

object RedisClusterClient {

  private[redis4cats] def acquireAndRelease[F[_]: FlatMap: FutureLift: Log](
      config: Redis4CatsConfig,
      uri: RedisURI*
  ): (F[RedisClusterClient], RedisClusterClient => F[Unit]) = {

    val acquire: F[RedisClusterClient] =
      Log[F].info(s"Acquire Redis Cluster client") *>
        FutureLift[F]
          .delay {
            val javaUris = uri.map(_.underlying).asJava
            config.clientResources.fold(JClusterClient.create(javaUris))(JClusterClient.create(_, javaUris))
          }
          .flatTap(initializeClusterTopology[F](_, config.topologyViewRefreshStrategy, config.nodeFilter))
          .map(new RedisClusterClient(_) {})

    val release: RedisClusterClient => F[Unit] = client =>
      Log[F].info(s"Releasing Redis Cluster client: ${client.underlying}") *>
        FutureLift[F]
          .lift(
            client.underlying.shutdownAsync(
              config.shutdown.quietPeriod.toNanos,
              config.shutdown.timeout.toNanos,
              TimeUnit.NANOSECONDS
            )
          )
          .void

    (acquire, release)
  }

  private[redis4cats] def initializeClusterTopology[F[_]: Functor: FutureLift](
      client: JClusterClient,
      topologyViewRefreshStrategy: TopologyViewRefreshStrategy,
      nodeFilter: RedisClusterNode => Boolean
  ): F[Unit] =
    FutureLift[F].delay {
      topologyViewRefreshStrategy match {
        case NoRefresh =>
          client.setOptions(
            ClusterClientOptions
              .builder()
              .nodeFilter(nodeFilter(_))
              .build()
          )
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
              .nodeFilter(nodeFilter(_))
              .build()
          )
          client.getPartitions
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
              .nodeFilter(nodeFilter(_))
              .build()
          )
          client.getPartitions

        case Both(Periodic(interval), Adaptive(timeout)) =>
          client.setOptions(
            ClusterClientOptions
              .builder()
              .topologyRefreshOptions(
                // Use implicit duration converters from scala 2.13 once 2.12 support is removed
                ClusterTopologyRefreshOptions
                  .builder()
                  .enablePeriodicRefresh(Duration.ofMillis(interval.toMillis))
                  .enableAllAdaptiveRefreshTriggers()
                  .adaptiveRefreshTriggersTimeout(Duration.ofMillis(timeout.toMillis))
                  .build()
              )
              .nodeFilter(nodeFilter(_))
              .build()
          )
          client.getPartitions
      }
    }.void

  def apply[F[_]: FlatMap: MkRedis](uri: RedisURI*): Resource[F, RedisClusterClient] =
    configured[F](Redis4CatsConfig(), uri: _*)

  def configured[F[_]: FlatMap: MkRedis](
      config: Redis4CatsConfig,
      uri: RedisURI*
  ): Resource[F, RedisClusterClient] = {
    implicit val fl: FutureLift[F] = MkRedis[F].futureLift
    implicit val log: Log[F]       = MkRedis[F].log

    val (acquire, release) = acquireAndRelease(config, uri: _*)
    Resource.make(acquire)(release)
  }

  def fromUnderlying(underlying: JClusterClient): RedisClusterClient =
    new RedisClusterClient(underlying) {}

  def nodeId[F[_]: Sync](
      client: RedisClusterClient,
      keyName: String
  ): F[NodeId] =
    Sync[F].delay(SlotHash.getSlot(keyName)).flatMap { slot =>
      partitions(client).map(_.getPartitionBySlot(slot).getNodeId).map(NodeId.apply)
    }

  def partitions[F[_]: Sync](client: RedisClusterClient): F[JPartitions] =
    Sync[F].delay(client.underlying.getPartitions())

}
