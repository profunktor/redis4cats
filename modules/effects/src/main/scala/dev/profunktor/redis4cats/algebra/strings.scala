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

import scala.concurrent.duration.FiniteDuration

import dev.profunktor.redis4cats.effects.{
  GetExArg,
  IncrexArgs,
  IncrexFloatArgs,
  IncrexResult,
  LcsResult,
  MSetExArgs,
  SetArgs
}

import io.lettuce.core.RedisFuture
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands

trait StringCommands[F[_], K, V]
    extends Getter[F, K, V]
    with Setter[F, K, V]
    with MultiKey[F, K, V]
    with Decrement[F, K, V]
    with Increment[F, K, V]
    with Unsafe[F, K, V]

trait Getter[F[_], K, V] {
  def get(key: K): F[Option[V]]
  def getDel(key: K): F[Option[V]]
  def getEx(key: K, getExArg: GetExArg): F[Option[V]]
  def getRange(key: K, start: Long, end: Long): F[Option[V]]
  def strLen(key: K): F[Long]

  /** Longest common subsequence of the values stored at `key1`/`key2`, without match positions. */
  def lcs(key1: K, key2: K): F[LcsResult]

  /** Just the length of the longest common subsequence — cheaper than `lcs` when the match itself isn't needed.
    */
  def lcsLen(key1: K, key2: K): F[Long]

  /** Longest common subsequence with match positions in each key's value populated. */
  def lcsIdx(key1: K, key2: K, minMatchLen: Option[Int] = None, withMatchLen: Boolean = false): F[LcsResult]
}

trait Setter[F[_], K, V] {
  def append(key: K, value: V): F[Long]
  def getSet(key: K, value: V): F[Option[V]]
  def set(key: K, value: V): F[Unit]
  def set(key: K, value: V, setArgs: SetArgs): F[Boolean]
  def setNx(key: K, value: V): F[Boolean]
  def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit]
  def setRange(key: K, value: V, offset: Long): F[Long]
}

trait MultiKey[F[_], K, V] {
  def mGet(keys: Set[K]): F[Map[K, V]]
  def mSet(keyValues: Map[K, V]): F[Unit]
  def mSetNx(keyValues: Map[K, V]): F[Boolean]

  /** Atomic multi-key SET with a shared TTL/existence policy — `false` iff `args.existence` was `Nx`/`Xx` and that
    * condition wasn't met, in which case no key was written.
    */
  def msetEx(keyValues: Map[K, V], args: MSetExArgs): F[Boolean]
}

trait Decrement[F[_], K, V] {
  def decr(key: K): F[Long]
  def decrBy(key: K, amount: Long): F[Long]
}

trait Increment[F[_], K, V] {
  def incr(key: K): F[Long]
  def incrBy(key: K, amount: Long): F[Long]
  def incrByFloat(key: K, amount: Double): F[Double]

  /** Atomically increment by 1 with no bound/TTL change — the plain read-modify form of INCREX. */
  def incrEx(key: K): F[IncrexResult[Long]]

  /** Atomically increment (optionally bounded/saturating) and set/clear the key's TTL in one round trip. */
  def incrEx(key: K, amount: Long, args: IncrexArgs): F[IncrexResult[Long]]

  /** Float-valued counterpart of the bounded `incrEx` overload. */
  def incrExFloat(key: K, amount: Double, args: IncrexFloatArgs): F[IncrexResult[Double]]
}

trait Unsafe[F[_], K, V] {

  /** USE WITH CAUTION! It gives you access to the underlying Java API.
    *
    * Useful whenever Redis4cats does not yet support the operation you're looking for.
    */
  def unsafe[A](f: RedisClusterAsyncCommands[K, V] => RedisFuture[A]): F[A]

  /** USE WITH CAUTION! It gives you access to the underlying Java API.
    *
    * Useful whenever Redis4cats does not yet support the operation you're looking for.
    */
  def unsafeSync[A](f: RedisClusterAsyncCommands[K, V] => A): F[A]
}
