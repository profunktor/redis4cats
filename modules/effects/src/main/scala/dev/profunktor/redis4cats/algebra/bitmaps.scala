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

sealed trait BitCommandOperation

object BitCommandOperation {
  sealed trait Encoding
  object Encoding {
    case object Signed extends Encoding
    case object Unsigned extends Encoding
  }

  final case class Get(encoding: Encoding, offset: Int, bits: Int = 1) extends BitCommandOperation
  object Get {
    def signed(offset: Int, bits: Int = 1): Get   = Get(Encoding.Signed, offset, bits)
    def unsigned(offset: Int, bits: Int = 1): Get = Get(Encoding.Unsigned, offset, bits)
  }

  final case class Set(encoding: Encoding, offset: Int, value: Long, bits: Int = 1) extends BitCommandOperation
  object Set {
    def signed(offset: Int, value: Long, bits: Int = 1): Set   = Set(Encoding.Signed, offset, value, bits)
    def unsigned(offset: Int, value: Long, bits: Int = 1): Set = Set(Encoding.Unsigned, offset, value, bits)
  }

  final case class IncrBy(encoding: Encoding, offset: Int, increment: Long, bits: Int = 1) extends BitCommandOperation
  object IncrBy {
    def signed(offset: Int, increment: Long, bits: Int = 1): IncrBy = IncrBy(Encoding.Signed, offset, increment, bits)
    def unsigned(offset: Int, increment: Long, bits: Int = 1): IncrBy =
      IncrBy(Encoding.Unsigned, offset, increment, bits)
  }

  final case class Overflow(policy: OverflowPolicy) extends BitCommandOperation

  sealed trait OverflowPolicy
  object OverflowPolicy {
    case object Wrap extends OverflowPolicy
    case object Sat extends OverflowPolicy
    case object Fail extends OverflowPolicy
  }
}

trait BitCommands[F[_], K, V] {
  def bitCount(key: K): F[Long]

  def bitCount(key: K, start: Long, end: Long): F[Long]

  /** `None` at a position produced by a `Set`/`IncrBy` operation that overflowed under an `Overflow(Fail)` policy.
    */
  def bitField(key: K, operations: BitCommandOperation*): F[List[Option[Long]]]

  def bitOpAnd(destination: K, source: K, sources: K*): F[Long]

  def bitOpNot(destination: K, source: K): F[Long]

  def bitOpOr(destination: K, source: K, sources: K*): F[Long]

  def bitOpXor(destination: K, source: K, sources: K*): F[Long]

  /** X ∧ ¬(Y1 ∨ Y2 ∨ …) — bits set in `source` but in none of `keys`. */
  def bitOpDiff(destination: K, source: K, keys: K*): F[Long]

  /** ¬X ∧ (Y1 ∨ Y2 ∨ …) — bits set in at least one of `keys` but not in `source`. */
  def bitOpDiff1(destination: K, source: K, keys: K*): F[Long]

  /** X ∧ (Y1 ∨ Y2 ∨ …) — bits set in `source` and in at least one of `keys`. */
  def bitOpAndOr(destination: K, source: K, keys: K*): F[Long]

  /** Bits set in exactly one of `keys` (a generalized XOR — for two keys it's equivalent to `bitOpXor`). */
  def bitOpOne(destination: K, keys: K*): F[Long]

  def bitPos(key: K, state: Boolean): F[Long]

  def bitPos(key: K, state: Boolean, start: Long): F[Long]

  def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long]

  def getBit(key: K, offset: Long): F[Option[Long]]

  def setBit(key: K, offset: Long, value: Int): F[Long]
}
