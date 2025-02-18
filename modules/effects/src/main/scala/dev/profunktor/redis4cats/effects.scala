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

import java.time.Instant

import io.lettuce.core.{
  GeoArgs,
  KeyScanArgs => JKeyScanArgs,
  ScanArgs => JScanArgs,
  ScriptOutputType => JScriptOutputType
}

import scala.concurrent.duration.FiniteDuration

object effects {

  final case class Distance(value: Double) extends AnyVal
  final case class GeoHash(value: Long) extends AnyVal
  final case class Latitude(value: Double) extends AnyVal
  final case class Longitude(value: Double) extends AnyVal

  final case class GeoLocation[V](lon: Longitude, lat: Latitude, value: V)
  final case class GeoRadius(lon: Longitude, lat: Latitude, dist: Distance)

  final case class GeoCoordinate(x: Double, y: Double)
  final case class GeoRadiusResult[V](value: V, dist: Distance, hash: GeoHash, coordinate: GeoCoordinate)
  final case class GeoRadiusKeyStorage[K](key: K, count: Long, sort: GeoArgs.Sort)
  final case class GeoRadiusDistStorage[K](key: K, count: Long, sort: GeoArgs.Sort)

  final case class Score(value: Double) extends AnyVal
  final case class ScoreWithValue[V](score: Score, value: V)
  final case class ZRange[V](start: V, end: V)
  final case class RangeLimit(offset: Long, count: Long)

  sealed trait ScriptOutputType[V] {
    type R
    private[redis4cats] type Underlying
    private[redis4cats] val outputType: JScriptOutputType
    private[redis4cats] def convert(in: Underlying): R
  }

  object ScriptOutputType {
    import dev.profunktor.redis4cats.JavaConversions._

    type Aux[A0, R0] = ScriptOutputType[A0] { type R = R0 }

    def Boolean[V]: ScriptOutputType.Aux[V, Boolean] = new ScriptOutputType[V] {
      type R                              = Boolean
      private[redis4cats] type Underlying = java.lang.Boolean
      override private[redis4cats] val outputType                              = JScriptOutputType.BOOLEAN
      override private[redis4cats] def convert(in: java.lang.Boolean): Boolean = scala.Boolean.box(in)
    }

    def Integer[V]: ScriptOutputType.Aux[V, Long] = new ScriptOutputType[V] {
      type R                              = Long
      private[redis4cats] type Underlying = java.lang.Long
      override private[redis4cats] val outputType                        = JScriptOutputType.INTEGER
      override private[redis4cats] def convert(in: java.lang.Long): Long = Long.box(in)
    }

    def Value[V]: ScriptOutputType.Aux[V, V] = new ScriptOutputType[V] {
      type R                              = V
      private[redis4cats] type Underlying = V
      override private[redis4cats] val outputType        = JScriptOutputType.VALUE
      override private[redis4cats] def convert(in: V): V = in
    }

    def Multi[V]: ScriptOutputType.Aux[V, List[V]] = new ScriptOutputType[V] {
      type R                              = List[V]
      private[redis4cats] type Underlying = java.util.List[V]
      override private[redis4cats] val outputType                              = JScriptOutputType.MULTI
      override private[redis4cats] def convert(in: java.util.List[V]): List[V] = in.asScala.toList
    }

    def Status[V]: ScriptOutputType.Aux[V, String] = new ScriptOutputType[V] {
      type R                              = String
      private[redis4cats] type Underlying = String
      override private[redis4cats] val outputType                  = JScriptOutputType.STATUS
      override private[redis4cats] def convert(in: String): String = in
    }
  }

  final case class CopyArgs(destinationDb: Option[Long], replace: Option[Boolean])
  object CopyArgs {
    def apply(destinationDb: Long): CopyArgs                   = CopyArgs(Some(destinationDb), None)
    def apply(replace: Boolean): CopyArgs                      = CopyArgs(None, Some(replace))
    def apply(destinationDb: Long, replace: Boolean): CopyArgs = CopyArgs(Some(destinationDb), Some(replace))
  }

  final case class RestoreArgs(
      ttl: Option[Long] = None,
      replace: Option[Boolean] = None,
      absttl: Option[Boolean] = None,
      idleTime: Option[Long] = None
  ) {
    def replace(replace: Boolean): RestoreArgs = copy(replace = Some(replace))
    def ttl(ttl: Long): RestoreArgs            = copy(ttl = Some(ttl))
    def absttl(absttl: Boolean): RestoreArgs   = copy(absttl = Some(absttl))
    def idleTime(idleTime: Long): RestoreArgs  = copy(idleTime = Some(idleTime))
  }

  case class ScanArgs(`match`: Option[String], count: Option[Long]) {
    def underlying: JScanArgs = {
      val u = new JScanArgs
      `match`.foreach(u.`match`)
      count.foreach(u.limit)
      u
    }
  }
  object ScanArgs {
    def apply(`match`: String): ScanArgs              = ScanArgs(Some(`match`), None)
    def apply(count: Long): ScanArgs                  = ScanArgs(None, Some(count))
    def apply(`match`: String, count: Long): ScanArgs = ScanArgs(Some(`match`), Some(count))
  }

  sealed abstract class KeyScanArgs(tpe: Option[RedisType], pattern: Option[String], count: Option[Long]) {
    def underlying: JKeyScanArgs = {
      val u = new JKeyScanArgs
      pattern.foreach(u.`match`)
      count.foreach(u.limit)
      tpe.foreach(t => u.`type`(t.asString))
      u
    }
  }

  object KeyScanArgs {
    def apply(pattern: String): KeyScanArgs                 = new KeyScanArgs(None, Some(pattern), None) {}
    def apply(tpe: RedisType): KeyScanArgs                  = new KeyScanArgs(Some(tpe), None, None) {}
    def apply(tpe: RedisType, pattern: String): KeyScanArgs = new KeyScanArgs(Some(tpe), Some(pattern), None) {}
    def apply(count: Long): KeyScanArgs                     = new KeyScanArgs(None, None, Some(count)) {}
    def apply(pattern: String, count: Long): KeyScanArgs    = new KeyScanArgs(None, Some(pattern), Some(count)) {}
    def apply(tpe: RedisType, count: Long): KeyScanArgs     = new KeyScanArgs(Some(tpe), None, Some(count)) {}
    def apply(tpe: RedisType, pattern: String, count: Long): KeyScanArgs =
      new KeyScanArgs(Some(tpe), Some(pattern), Some(count)) {}
  }

  sealed trait FlushMode {
    def asJava: io.lettuce.core.FlushMode =
      this match {
        case FlushMode.Sync  => io.lettuce.core.FlushMode.SYNC
        case FlushMode.Async => io.lettuce.core.FlushMode.ASYNC
      }

  }
  object FlushMode {
    case object Sync extends FlushMode
    case object Async extends FlushMode
  }

  sealed trait FunctionRestoreMode
  object FunctionRestoreMode {
    case object Append extends FunctionRestoreMode
    case object Flush extends FunctionRestoreMode
    case object Replace extends FunctionRestoreMode
  }

  sealed trait GetExArg
  object GetExArg {

    /** Set Expiration in Millis */
    case class Px(duration: FiniteDuration) extends GetExArg

    /** Set Expiration in Seconds */
    case class Ex(duration: FiniteDuration) extends GetExArg

    /** Set Expiration time in Seconds */
    case class ExAt(at: Instant) extends GetExArg

    /** Set Expiration time in Millis */
    case class PxAt(at: Instant) extends GetExArg

    case object Persist extends GetExArg
  }

  sealed trait SetArg
  object SetArg {
    sealed trait Existence extends SetArg
    object Existence {

      /** Only set key if it does not exist */
      case object Nx extends Existence

      /** Only set key if it already exists */
      case object Xx extends Existence
    }

    sealed trait Ttl extends SetArg
    object Ttl {

      /** Set Expiration in Millis */
      case class Px(duration: FiniteDuration) extends Ttl

      /** Set Expiration in Seconds */
      case class Ex(duration: FiniteDuration) extends Ttl

      /** Set KeepTtl */
      case object Keep extends Ttl
    }
  }
  case class SetArgs(existence: Option[SetArg.Existence], ttl: Option[SetArg.Ttl])
  object SetArgs {
    def apply(ex: SetArg.Existence): SetArgs                  = SetArgs(Some(ex), None)
    def apply(ttl: SetArg.Ttl): SetArgs                       = SetArgs(None, Some(ttl))
    def apply(ex: SetArg.Existence, ttl: SetArg.Ttl): SetArgs = SetArgs(Some(ex), Some(ttl))
  }

  sealed trait ExpireExistenceArg
  object ExpireExistenceArg {

    /** Set expiry only when the key has no expiry */
    case object Nx extends ExpireExistenceArg

    /** Set expiry only when the key has an existing expiry */
    case object Xx extends ExpireExistenceArg

    /** Set expiry only when the new expiry is greater than current one */
    case object Gt extends ExpireExistenceArg

    /** Set expiry only when the new expiry is greater than current one */
    case object Lt extends ExpireExistenceArg
  }

  // Models the core Redis Types as described in https://redis.io/docs/latest/develop/data-types/
  // Caveat: BitSet, GeoSpatial etc... are implemented in terms of the core types , i.e. Geo is a Sorted Set etc..
  sealed abstract class RedisType(val asString: String)
  object RedisType {
    val all = scala.List(String, List, Set, SortedSet, Hash, Stream)

    def fromString(s: String): Option[RedisType] = all.find(_.asString == s)

    case object String extends RedisType("string")

    case object List extends RedisType("list")

    case object Set extends RedisType("set")

    case object SortedSet extends RedisType("zset")

    case object Hash extends RedisType("hash")

    case object Stream extends RedisType("stream")
  }

}
