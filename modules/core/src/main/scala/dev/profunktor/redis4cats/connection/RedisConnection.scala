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

import cats.{ ApplicativeThrow, MonadThrow }
import cats.effect.kernel.Async
import cats.syntax.all._
import dev.profunktor.redis4cats.data.NodeId
import dev.profunktor.redis4cats.effect.FutureLift
import io.lettuce.core.ClientOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.{ RedisCommands => RedisSyncCommands }
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.{ RedisClusterCommands => RedisClusterSyncCommands }

import scala.util.control.NoStackTrace

case class OperationNotSupported(value: String) extends NoStackTrace {
  override def toString(): String = s"OperationNotSupported($value)"
}

private[redis4cats] trait RedisConnection[F[_], K, V] {
  def sync: F[RedisSyncCommands[K, V]]
  def clusterSync: F[RedisClusterSyncCommands[K, V]]
  def async: F[RedisAsyncCommands[K, V]]
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]]
  def close: F[Unit]
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]]
  def liftK[G[_]: Async]: RedisConnection[G, K, V]
  private[redis4cats] def clientOptions: F[ClientOptions]
  private[redis4cats] def setAutoFlushCommands(autoFlush: Boolean): F[Unit]
  private[redis4cats] def flushCommands: F[Unit]
}

private[redis4cats] class RedisStatefulConnection[F[_]: ApplicativeThrow: FutureLift, K, V](
    conn: StatefulRedisConnection[K, V]
) extends RedisConnection[F, K, V] {
  def sync: F[RedisSyncCommands[K, V]] = FutureLift[F].delay(conn.sync())
  def clusterSync: F[RedisClusterSyncCommands[K, V]] =
    OperationNotSupported("Running in a single node").raiseError
  def async: F[RedisAsyncCommands[K, V]] = FutureLift[F].delay(conn.async())
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]] =
    OperationNotSupported("Running in a single node").raiseError
  def close: F[Unit] = FutureLift[F].lift(conn.closeAsync()).void
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]] =
    OperationNotSupported("Running in a single node").raiseError
  def liftK[G[_]: Async]: RedisConnection[G, K, V] =
    new RedisStatefulConnection[G, K, V](conn)
  private[redis4cats] def clientOptions: F[ClientOptions] = FutureLift[F].delay(conn.getOptions)
  private[redis4cats] def setAutoFlushCommands(autoFlush: Boolean): F[Unit] =
    FutureLift[F].delay(conn.setAutoFlushCommands(autoFlush))
  private[redis4cats] def flushCommands: F[Unit] = FutureLift[F].blocking(conn.flushCommands())
}

private[redis4cats] class RedisStatefulClusterConnection[F[_]: FutureLift: MonadThrow, K, V](
    conn: StatefulRedisClusterConnection[K, V]
) extends RedisConnection[F, K, V] {
  def sync: F[RedisSyncCommands[K, V]] =
    OperationNotSupported("Transactions are not supported in a cluster. You must select a single node.").raiseError
  def async: F[RedisAsyncCommands[K, V]] =
    OperationNotSupported("Transactions are not supported in a cluster. You must select a single node.").raiseError
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]] = FutureLift[F].delay(conn.async())
  def clusterSync: F[RedisClusterSyncCommands[K, V]]   = FutureLift[F].delay(conn.sync())
  def close: F[Unit]                                   = FutureLift[F].lift(conn.closeAsync()).void
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]] =
    FutureLift[F].lift(conn.getConnectionAsync(nodeId.value)).flatMap { stateful =>
      FutureLift[F].delay(stateful.async())
    }
  def liftK[G[_]: Async]: RedisConnection[G, K, V] =
    new RedisStatefulClusterConnection[G, K, V](conn)
  private[redis4cats] def clientOptions: F[ClientOptions] = FutureLift[F].delay(conn.getOptions)
  private[redis4cats] def setAutoFlushCommands(autoFlush: Boolean): F[Unit] =
    FutureLift[F].delay(conn.setAutoFlushCommands(autoFlush))
  private[redis4cats] def flushCommands: F[Unit] = FutureLift[F].delay(conn.flushCommands())
}
