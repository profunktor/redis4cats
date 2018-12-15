/*
 * Copyright 2018 Fs2 Redis
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
import cats.effect.{ Concurrent, Sync }
import cats.syntax.all._
import com.github.gvolpe.fs2redis.effect.JRFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands

private[fs2redis] trait Fs2RedisConnection[F[_], K, V] {
  def async: F[RedisClusterAsyncCommands[K, V]]
  def close: F[Unit]
}

private[fs2redis] class Fs2RedisStatefulConnection[F[_]: Concurrent, K, V](
    conn: StatefulRedisConnection[K, V]
) extends Fs2RedisConnection[F, K, V] {
  override def async: F[RedisClusterAsyncCommands[K, V]] = Sync[F].delay(conn.async())
  override def close: F[Unit]                            = JRFuture.fromCompletableFuture(Sync[F].delay(conn.closeAsync())).void
}

private[fs2redis] class Fs2RedisStatefulClusterConnection[F[_]: Concurrent, K, V](
    conn: StatefulRedisClusterConnection[K, V]
) extends Fs2RedisConnection[F, K, V] {
  override def async: F[RedisClusterAsyncCommands[K, V]] = Sync[F].delay(conn.async())
  override def close: F[Unit]                            = JRFuture.fromCompletableFuture(Sync[F].delay(conn.closeAsync())).void
}
