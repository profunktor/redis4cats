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

package dev.profunktor.redis4cats

import scala.concurrent.duration._
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.resource.ClientResources

object config {

  // Builder-style abstract class instead of case class to allow for bincompat-friendly extension in future versions.
  sealed abstract class Redis4CatsConfig {
    val shutdown: ShutdownConfig
    val topologyViewRefreshStrategy: TopologyViewRefreshStrategy
    val nodeFilter: RedisClusterNode => Boolean
    val clientResources: Option[ClientResources]
    def withShutdown(shutdown: ShutdownConfig): Redis4CatsConfig
    def withTopologyViewRefreshStrategy(strategy: TopologyViewRefreshStrategy): Redis4CatsConfig
    def withNodeFilter(nodeFilter: RedisClusterNode => Boolean): Redis4CatsConfig
    def withClientResources(resources: Option[ClientResources]): Redis4CatsConfig
  }

  object Redis4CatsConfig {
    private case class Redis4CatsConfigImpl(
        shutdown: ShutdownConfig,
        topologyViewRefreshStrategy: TopologyViewRefreshStrategy = NoRefresh,
        nodeFilter: RedisClusterNode => Boolean = ClusterClientOptions.DEFAULT_NODE_FILTER.test,
        clientResources: Option[ClientResources] = None
    ) extends Redis4CatsConfig {
      override def withShutdown(_shutdown: ShutdownConfig): Redis4CatsConfig = copy(shutdown = _shutdown)
      override def withTopologyViewRefreshStrategy(strategy: TopologyViewRefreshStrategy): Redis4CatsConfig =
        copy(topologyViewRefreshStrategy = strategy)
      override def withNodeFilter(_nodeFilter: RedisClusterNode => Boolean): Redis4CatsConfig =
        copy(nodeFilter = _nodeFilter)
      override def withClientResources(resources: Option[ClientResources]): Redis4CatsConfig =
        copy(clientResources = resources)
    }
    def apply(): Redis4CatsConfig = Redis4CatsConfigImpl(ShutdownConfig())
  }

  /** Configure the shutdown of the lettuce redis client, controlling the time spent on shutting down Netty's thread
    * pools.
    *
    * @param quietPeriod
    *   the quiet period to allow the executor to gracefully shut down.
    * @param timeout
    *   timeout the maximum amount of time to wait until the backing executor is shutdown regardless if a task was
    *   submitted during the quiet period.
    */
  // Shutdown values from new Lettuce defaults coming in version 6 (#974dd70), defaults in 5.3 are causing long waiting time.
  case class ShutdownConfig(quietPeriod: FiniteDuration = 0.seconds, timeout: FiniteDuration = 2.seconds)

  sealed trait TopologyViewRefreshStrategy

  final case class Periodic(interval: FiniteDuration = 60.seconds) extends TopologyViewRefreshStrategy
  final case class Adaptive(timeout: FiniteDuration = 30.seconds) extends TopologyViewRefreshStrategy
  // sets periodic to 1 hour by default and the default adaptive timeout to 30 seconds
  // see https://github.com/profunktor/redis4cats/issues/656
  final case class Both(periodic: Periodic = Periodic(2.hours), adaptive: Adaptive = Adaptive())
      extends TopologyViewRefreshStrategy
  case object NoRefresh extends TopologyViewRefreshStrategy

}
