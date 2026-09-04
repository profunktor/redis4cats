/*
 * Copyright 2018-2025 ProfunKtor
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

import java.util.concurrent.TimeUnit

import scala.jdk.DurationConverters._

import cats.{ Functor, MonadThrow }
import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.JavaConversions._
import dev.profunktor.redis4cats.config._
import dev.profunktor.redis4cats.data.NodeId
import dev.profunktor.redis4cats.effect._
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.{
  ClusterClientOptions,
  ClusterTopologyRefreshOptions,
  RedisClusterClient => JClusterClient,
  SlotHash
}

sealed abstract case class RedisClusterClient private (underlying: JClusterClient)

object RedisClusterClient {

  private[redis4cats] def acquireAndRelease[F[_]: MonadThrow: FutureLift: Log](
      config: Redis4CatsConfig,
      uri: RedisURI*
  ): (F[RedisClusterClient], RedisClusterClient => F[Unit]) = {

    def shutdownJClient(jClient: JClusterClient): F[Unit] =
      FutureLift[F]
        .lift(
          jClient.shutdownAsync(
            config.shutdown.quietPeriod.toNanos,
            config.shutdown.timeout.toNanos,
            TimeUnit.NANOSECONDS
          )
        )
        .void

    val acquire: F[RedisClusterClient] =
      Log[F].info(s"Acquire Redis Cluster client") *>
        FutureLift[F]
          .delay {
            val javaUris = uri.map(_.underlying).asJava
            config.clientResources.fold(JClusterClient.create(javaUris))(JClusterClient.create(_, javaUris))
          }
          .flatTap { jClient =>
            // The client is already allocated (real Netty resources) by this point; if topology
            // initialization throws, it must be shut down here - Resource.make never calls `release` when
            // `acquire` itself fails, so without this the just-created client would otherwise leak. onError
            // only re-raises the original error after this action succeeds, so a shutdown failure here must
            // be swallowed (.attempt.void) or it would replace the real topology-init failure instead of
            // just failing to clean up after it.
            initializeClusterTopology[F](jClient, config.topologyViewRefreshStrategy, config.nodeFilter)
              .onError { case _ => shutdownJClient(jClient).attempt.void }
          }
          .map(new RedisClusterClient(_) {})

    val release: RedisClusterClient => F[Unit] = client =>
      Log[F].info(s"Releasing Redis Cluster client: ${client.underlying}") *> shutdownJClient(client.underlying)

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
                  .enablePeriodicRefresh(interval.toJava)
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
                  .adaptiveRefreshTriggersTimeout(timeout.toJava)
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
                ClusterTopologyRefreshOptions
                  .builder()
                  .enablePeriodicRefresh(interval.toJava)
                  .adaptiveRefreshTriggersTimeout(timeout.toJava)
                  .build()
              )
              .nodeFilter(nodeFilter(_))
              .build()
          )
          client.getPartitions
      }
    }.void

  def apply[F[_]: MonadThrow: MkRedis](uri: RedisURI*): Resource[F, RedisClusterClient] =
    configured[F](Redis4CatsConfig(), uri: _*)

  def configured[F[_]: MonadThrow: MkRedis](
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

  /** `None` when no partition currently covers `keyName`'s slot - e.g. mid-resharding, or before a cluster's slots are
    * fully assigned.
    */
  def nodeId[F[_]: Sync](
      client: RedisClusterClient,
      keyName: String
  ): F[Option[NodeId]] =
    Sync[F].delay(SlotHash.getSlot(keyName)).flatMap { slot =>
      partitions(client).map(_.find(_.hasSlot(slot)).map(n => NodeId(n.getNodeId)))
    }

  /** An immutable snapshot of the cluster's current topology, taken at call time. Lettuce's own `Partitions` is a live
    * collection it mutates in place on every topology refresh; this copies it once so callers don't observe surprise
    * mutation (or risk a `ConcurrentModificationException` while iterating).
    */
  def partitions[F[_]: Sync](client: RedisClusterClient): F[List[RedisClusterNode]] =
    Sync[F].delay(client.underlying.getPartitions().asScala.toList)

}
