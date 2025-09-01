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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects.{ FlushMode, FunctionRestoreMode, ScriptOutputType }

trait ScriptCommands[F[_], K, V] extends Scripting[F, K, V] with Functions[F, K, V]

trait Scripting[F[_], K, V] {
  // these methods don't use varargs as they cause problems with type inference, see:
  // https://github.com/scala/bug/issues/11488
  def eval(script: String, output: ScriptOutputType[V]): F[output.R]
  def eval(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R]
  def eval(script: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R]
  def evalReadOnly(script: String, output: ScriptOutputType[V]): F[output.R]
  def evalReadOnly(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R]
  def evalReadOnly(script: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R]
  def evalSha(digest: String, output: ScriptOutputType[V]): F[output.R]
  def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K]): F[output.R]
  def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R]
  def evalShaReadOnly(digest: String, output: ScriptOutputType[V]): F[output.R]
  def evalShaReadOnly(digest: String, output: ScriptOutputType[V], keys: List[K]): F[output.R]
  def evalShaReadOnly(digest: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R]
  def scriptLoad(script: String): F[String]
  def scriptLoad(script: Array[Byte]): F[String]
  def scriptExists(digests: String*): F[List[Boolean]]
  def scriptFlush: F[Unit]
  def digest(script: String): F[String]
}

trait Functions[F[_], K, V] {
  def fcall(function: String, output: ScriptOutputType[V], keys: List[K]): F[output.R]
  def fcall(function: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R]
  def fcallReadOnly(function: String, output: ScriptOutputType[V], keys: List[K]): F[output.R]
  def fcallReadOnly(function: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R]
  def functionLoad(functionCode: String): F[String]
  def functionLoad(functionCode: String, replace: Boolean): F[String]
  def functionDump(): F[Array[Byte]]
  def functionRestore(dump: Array[Byte]): F[String]
  def functionRestore(dump: Array[Byte], mode: FunctionRestoreMode): F[String]
  def functionFlush(flushMode: FlushMode): F[String]
  def functionKill(): F[String]
  def functionList(): F[List[Map[String, Any]]]
  def functionList(libraryName: String): F[List[Map[String, Any]]]
}
