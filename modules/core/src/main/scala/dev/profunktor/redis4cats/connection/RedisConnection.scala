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

import cats.effect.{ Concurrent, ContextShift, Sync }
import cats.syntax.all._
import dev.profunktor.redis4cats.domain.NodeId
import dev.profunktor.redis4cats.effect.JRFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands

private[redis4cats] trait RedisConnection[F[_], K, V] {
  def async: F[RedisAsyncCommands[K, V]]
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]]
  def close: F[Unit]
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]]
}

private[redis4cats] class RedisStatefulConnection[F[_]: Concurrent: ContextShift, K, V](
    conn: StatefulRedisConnection[K, V]
) extends RedisConnection[F, K, V] {
  def async: F[RedisAsyncCommands[K, V]] = Sync[F].delay(conn.async())
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]] =
    Sync[F].raiseError(new Exception("Operation not supported"))
  def close: F[Unit] = JRFuture.fromCompletableFuture(Sync[F].delay(conn.closeAsync())).void
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]] =
    Sync[F].raiseError(new Exception("Operation not supported"))
}

private[redis4cats] class RedisStatefulClusterConnection[F[_]: Concurrent: ContextShift, K, V](
    conn: StatefulRedisClusterConnection[K, V]
) extends RedisConnection[F, K, V] {
  def async: F[RedisAsyncCommands[K, V]] =
    Sync[F].raiseError(new Exception("Transactions are not supported on a cluster"))
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]] = Sync[F].delay(conn.async())
  def close: F[Unit] =
    JRFuture.fromCompletableFuture(Sync[F].delay(conn.closeAsync())).void
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]] =
    JRFuture.fromCompletableFuture(Sync[F].delay(conn.getConnectionAsync(nodeId.value))).flatMap { stateful =>
      Sync[F].delay(stateful.async())
    }
}
