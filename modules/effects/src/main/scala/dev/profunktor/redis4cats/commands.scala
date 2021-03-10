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

package dev.profunktor.redis4cats

import algebra._
import cats.effect.{ Concurrent, ContextShift }
import dev.profunktor.redis4cats.effect.Log

trait RedisCommands[F[_], K, V]
    extends StringCommands[F, K, V]
    with HashCommands[F, K, V]
    with SetCommands[F, K, V]
    with SortedSetCommands[F, K, V]
    with ListCommands[F, K, V]
    with GeoCommands[F, K, V]
    with ConnectionCommands[F]
    with ServerCommands[F, K]
    with TransactionalCommands[F, K]
    with PipelineCommands[F]
    with ScriptCommands[F, K, V]
    with KeyCommands[F, K]
    with HyperLogLogCommands[F, K, V]

object RedisCommands {
  implicit class LiftKOps[F[_], K, V](val cmd: RedisCommands[F, K, V]) extends AnyVal {
    def liftK[G[_]: Concurrent: ContextShift: Log]: RedisCommands[G, K, V] =
      cmd.asInstanceOf[BaseRedis[F, K, V]].liftK[G]
  }
}
