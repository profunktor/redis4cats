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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects.ScriptOutputType

trait ScriptCommands[F[_], K, V] extends Scripting[F, K, V]

trait Scripting[F[_], K, V] {
  def eval[R](script: String, output: ScriptOutputType.Aux[V, R], keys: K*): F[R]
  def evalWithValues[R](script: String, output: ScriptOutputType.Aux[V, R], keys: List[K], values: V*): F[R]
  def evalSha[R](script: String, output: ScriptOutputType.Aux[V, R], keys: K*): F[R]
  def evalShaWithValues[R](script: String, output: ScriptOutputType.Aux[V, R], keys: List[K], values: V*): F[R]
  // This unfortunately has to take a V instead of String due to a bug in lettuce:
  // https://github.com/lettuce-io/lettuce-core/issues/1010
  def scriptLoad(script: V): F[String]
  def scriptExists(digests: String*): F[List[Boolean]]
  def scriptFlush: F[Unit]
}
