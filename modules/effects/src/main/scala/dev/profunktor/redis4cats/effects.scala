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

import cats.Eq

import java.time.Instant
import io.lettuce.core.{
  AclCategory => JAclCategory,
  GeoArgs,
  KeyScanArgs => JKeyScanArgs,
  ScanArgs => JScanArgs,
  ScriptOutputType => JScriptOutputType
}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{ Duration, FiniteDuration }

object effects {

  final case class Distance(value: Double) extends AnyVal
  final case class GeoHash(value: Long) extends AnyVal
  final case class Latitude(value: Double) extends AnyVal
  final case class Longitude(value: Double) extends AnyVal

  final case class GeoLocation[V](lon: Longitude, lat: Latitude, value: V)

  sealed trait GeoSearchReference[V]
  object GeoSearchReference {
    final case class FromCoordinates[V](lon: Longitude, lat: Latitude) extends GeoSearchReference[V]
    final case class FromMember[V](value: V) extends GeoSearchReference[V]
  }

  sealed trait GeoSearchPredicate
  object GeoSearchPredicate {
    final case class ByRadius(dist: Distance, unit: GeoArgs.Unit) extends GeoSearchPredicate
    final case class ByBox(width: Distance, height: Distance, unit: GeoArgs.Unit) extends GeoSearchPredicate
  }

  final case class GeoCoordinate(x: Double, y: Double)

  /** `dist`/`hash`/`coordinate` are each present only when the `GeoArgs` passed to the `geoSearch` call that produced
    * this result set the corresponding `withDistance()`/`withHash()`/ `withCoordinates()` flag — `None` otherwise,
    * matching Lettuce's own `GeoWithin` contract ("if requested, otherwise null").
    */
  final case class GeoSearchResult[V](
      value: V,
      dist: Option[Distance],
      hash: Option[GeoHash],
      coordinate: Option[GeoCoordinate]
  )

  /** The subset of `GeoArgs` that `GEOSEARCHSTORE` actually accepts (COUNT/ASC/DESC) — unlike `GEOSEARCH`, it rejects
    * the WITHDIST/WITHHASH/WITHCOORD flags a raw `GeoArgs` could also carry.
    */
  final case class GeoStoreArgs(count: Option[Long] = None, sort: Option[GeoArgs.Sort] = None)

  final case class Score(value: Double) extends AnyVal
  final case class ScoreWithValue[V](score: Score, value: V)
  final case class ZRange[V](start: V, end: V)
  final case class RangeLimit(offset: Long, count: Long)

  sealed trait SortOrder
  object SortOrder {
    case object Asc extends SortOrder
    case object Desc extends SortOrder
  }

  /** Options for the `SORT`/`SORT_RO`/`SORT ... STORE` commands.
    * @param by
    *   external-key sort-weight pattern (`*` is substituted with each element)
    * @param limit
    *   offset/count to page the sorted result
    * @param get
    *   external-key patterns to fetch instead of the sorted elements themselves
    * @param order
    *   ascending/descending; Redis defaults to ascending when unset
    * @param alpha
    *   sort lexicographically instead of numerically
    */
  final case class SortArgs(
      by: Option[String] = None,
      limit: Option[RangeLimit] = None,
      get: List[String] = Nil,
      order: Option[SortOrder] = None,
      alpha: Boolean = false
  )

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

  /** Failure raised while encoding ACL arguments or decoding ACL replies. */
  sealed abstract class AclError(message: String) extends RuntimeException(message)
  object AclError {

    /** A command name was given to `ACL SETUSER` that Lettuce's `CommandType` does not know. */
    final case class UnknownCommand(name: String) extends AclError(s"Unknown Redis command: '$name'")

    /** An `ACL` reply could not be decoded into the expected shape. */
    final case class DecodingFailure(message: String) extends AclError(message)
  }

  /** A raw Redis command name for use in `ACL SETUSER` rules (e.g. `RawCommand("get")`).
    *
    * The set of Redis commands is open (modules and new versions add commands), so it is modelled as an explicit string
    * rather than a closed enum. Names unknown to the driver surface as [[AclError.UnknownCommand]] when the rule is
    * applied, rather than throwing.
    */
  final case class RawCommand(value: String) extends AnyVal

  /** A Redis ACL command category (the closed set understood by the driver). */
  sealed trait AclCategory {
    private[redis4cats] def asJava: JAclCategory =
      this match {
        case AclCategory.Keyspace    => JAclCategory.KEYSPACE
        case AclCategory.Read        => JAclCategory.READ
        case AclCategory.Write       => JAclCategory.WRITE
        case AclCategory.Set         => JAclCategory.SET
        case AclCategory.SortedSet   => JAclCategory.SORTEDSET
        case AclCategory.List        => JAclCategory.LIST
        case AclCategory.Hash        => JAclCategory.HASH
        case AclCategory.String      => JAclCategory.STRING
        case AclCategory.Bitmap      => JAclCategory.BITMAP
        case AclCategory.HyperLogLog => JAclCategory.HYPERLOGLOG
        case AclCategory.Geo         => JAclCategory.GEO
        case AclCategory.Stream      => JAclCategory.STREAM
        case AclCategory.PubSub      => JAclCategory.PUBSUB
        case AclCategory.Admin       => JAclCategory.ADMIN
        case AclCategory.Fast        => JAclCategory.FAST
        case AclCategory.Slow        => JAclCategory.SLOW
        case AclCategory.Blocking    => JAclCategory.BLOCKING
        case AclCategory.Dangerous   => JAclCategory.DANGEROUS
        case AclCategory.Connection  => JAclCategory.CONNECTION
        case AclCategory.Transaction => JAclCategory.TRANSACTION
        case AclCategory.Scripting   => JAclCategory.SCRIPTING
        case AclCategory.Bloom       => JAclCategory.BLOOM
        case AclCategory.Cuckoo      => JAclCategory.CUCKOO
        case AclCategory.Cms         => JAclCategory.CMS
        case AclCategory.TopK        => JAclCategory.TOPK
        case AclCategory.TDigest     => JAclCategory.TDIGEST
        case AclCategory.Search      => JAclCategory.SEARCH
        case AclCategory.TimeSeries  => JAclCategory.TIMESERIES
        case AclCategory.Json        => JAclCategory.JSON
      }
  }
  object AclCategory {
    // NOTE: `Set`, `List` and `String` below shadow the Scala types of the same name within this object.
    // Nothing here references those Scala types, so it is safe; if you add a member that does (e.g. a
    // `List[AclCategory]`), fully-qualify it (`scala.collection.immutable.List`) or it will resolve to the case object.
    case object Keyspace extends AclCategory
    case object Read extends AclCategory
    case object Write extends AclCategory
    case object Set extends AclCategory
    case object SortedSet extends AclCategory
    case object List extends AclCategory
    case object Hash extends AclCategory
    case object String extends AclCategory
    case object Bitmap extends AclCategory
    case object HyperLogLog extends AclCategory
    case object Geo extends AclCategory
    case object Stream extends AclCategory
    case object PubSub extends AclCategory
    case object Admin extends AclCategory
    case object Fast extends AclCategory
    case object Slow extends AclCategory
    case object Blocking extends AclCategory
    case object Dangerous extends AclCategory
    case object Connection extends AclCategory
    case object Transaction extends AclCategory
    case object Scripting extends AclCategory
    case object Bloom extends AclCategory
    case object Cuckoo extends AclCategory
    case object Cms extends AclCategory
    case object TopK extends AclCategory
    case object TDigest extends AclCategory
    case object Search extends AclCategory
    case object TimeSeries extends AclCategory
    case object Json extends AclCategory

    val values: Vector[AclCategory] =
      Vector(
        Keyspace,
        Read,
        Write,
        Set,
        SortedSet,
        List,
        Hash,
        String,
        Bitmap,
        HyperLogLog,
        Geo,
        Stream,
        PubSub,
        Admin,
        Fast,
        Slow,
        Blocking,
        Dangerous,
        Connection,
        Transaction,
        Scripting,
        Bloom,
        Cuckoo,
        Cms,
        TopK,
        TDigest,
        Search,
        TimeSeries,
        Json
      )

    private[redis4cats] def fromJava(j: JAclCategory): Either[AclError, AclCategory] =
      values.find(_.asJava == j).toRight(AclError.DecodingFailure(s"Unknown ACL category: '$j'"))
  }

  /** A command/key/channel selector attached to an ACL user (Redis 7+), as returned by `ACL GETUSER`. */
  final case class AclSelector(commands: String, keys: String, channels: String)

  /** An ACL user as returned by `ACL GETUSER`. The `commands`, `keys` and `channels` fields hold the rule strings
    * exactly as Redis reports them (e.g. `"-@all +get"`, `"~app:*"`, `"&*"`).
    */
  final case class AclUser(
      flags: List[String],
      passwords: List[String],
      commands: String,
      keys: String,
      channels: String,
      selectors: List[AclSelector]
  )

  /** A single rule passed to `ACL SETUSER`, applied in the order given. Mirrors Lettuce's `AclSetuserArgs`. */
  sealed trait AclSetUserRule
  object AclSetUserRule {

    /** Enable the user (`on`). */
    case object On extends AclSetUserRule

    /** Disable the user (`off`). */
    case object Off extends AclSetUserRule

    /** Reset the user to its just-created state (`reset`). */
    case object Reset extends AclSetUserRule

    /** Allow logging in with no password (`nopass`). */
    case object NoPass extends AclSetUserRule

    /** Remove all passwords (`resetpass`). */
    case object ResetPass extends AclSetUserRule

    final case class AddPassword(password: String) extends AclSetUserRule
    final case class RemovePassword(password: String) extends AclSetUserRule
    final case class AddHashedPassword(hashedPassword: String) extends AclSetUserRule
    final case class RemoveHashedPassword(hashedPassword: String) extends AclSetUserRule

    /** Allow access to all keys (`allkeys` / `~*`). */
    case object AllKeys extends AclSetUserRule

    /** Revoke access to all keys (`resetkeys`). */
    case object ResetKeys extends AclSetUserRule

    /** Allow access to keys matching a glob pattern, given without the `~` prefix (e.g. `app:*`). */
    final case class KeyPattern(pattern: String) extends AclSetUserRule

    /** Allow access to all pub/sub channels (`allchannels` / `&*`). */
    case object AllChannels extends AclSetUserRule

    /** Revoke access to all pub/sub channels (`resetchannels`). */
    case object ResetChannels extends AclSetUserRule

    /** Allow access to channels matching a glob pattern, given without the `&` prefix (e.g. `news.*`). */
    final case class ChannelPattern(pattern: String) extends AclSetUserRule

    /** Allow every command (`allcommands` / `+@all`). */
    case object AllCommands extends AclSetUserRule

    /** Disallow every command (`nocommands` / `-@all`). */
    case object NoCommands extends AclSetUserRule

    /** Allow a single command, e.g. `AddCommand(RawCommand("get"))`. */
    final case class AddCommand(command: RawCommand) extends AclSetUserRule

    /** Disallow a single command, e.g. `RemoveCommand(RawCommand("set"))`. */
    final case class RemoveCommand(command: RawCommand) extends AclSetUserRule

    /** Allow a command category, e.g. `AddCategory(AclCategory.Read)`. */
    final case class AddCategory(category: AclCategory) extends AclSetUserRule

    /** Disallow a command category, e.g. `RemoveCategory(AclCategory.Dangerous)`. */
    final case class RemoveCategory(category: AclCategory) extends AclSetUserRule
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

  sealed trait HGetExArgs
  object HGetExArgs {

    /** Set Expiration in Millis */
    case class Px(duration: FiniteDuration) extends HGetExArgs

    /** Set Expiration in Seconds */
    case class Ex(duration: FiniteDuration) extends HGetExArgs

    /** Set Expiration time in Seconds */
    case class ExAt(at: Instant) extends HGetExArgs

    /** Set Expiration time in Millis */
    case class PxAt(at: Instant) extends HGetExArgs

    /** Set KeepTtl */
    case object Persist extends HGetExArgs
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

  sealed trait LMoveSide
  object LMoveSide {
    case object Left extends LMoveSide
    case object Right extends LMoveSide
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

  /*
  Streams
   */

  final case class XTrimArgs(
      strategy: XTrimArgs.Strategy,
      precision: XTrimArgs.Precision = XTrimArgs.Precision.Exact
  )

  object XTrimArgs {
    sealed trait Strategy extends Product with Serializable
    object Strategy {
      final case class MAXLEN(threshold: Long) extends Strategy
      final case class MINID(id: String) extends Strategy
    }

    sealed trait Precision extends Product with Serializable
    object Precision {
      case object Exact extends Precision
      final case class Approximate(limit: Option[Long] = None) extends Precision
    }
  }

  final case class XAddArgs(
      nomkstream: Boolean = false,
      id: Option[String] = None,
      xTrimArgs: Option[XTrimArgs] = None
  )

  final case class MessageId(value: String) extends AnyVal

  object MessageId {
    implicit val eq: Eq[MessageId] = Eq.by(_.value)
  }

  sealed trait XReadOffsets[K] extends Product with Serializable {
    def key: K
    def offset: String
  }

  object XReadOffsets {

    def all[K](keys: K*): Set[XReadOffsets[K]]                    = All.of(keys: _*).map(identity)
    def latest[K](keys: K*): Set[XReadOffsets[K]]                 = Latest.of(keys: _*).map(identity)
    def custom[K](offset: String, keys: K*): Set[XReadOffsets[K]] = Custom.of(offset, keys: _*).map(identity)

    case class All[K](key: K) extends XReadOffsets[K] {
      override def offset: String = "0"
    }
    object All {
      def of[K](keys: K*): Set[All[K]] = keys.toSet.map(k => new All[K](k))
    }

    case class Latest[K](key: K) extends XReadOffsets[K] {
      override def offset: String = "$"
    }
    object Latest {
      def of[K](keys: K*): Set[Latest[K]] = keys.toSet.map(k => new Latest[K](k))
    }

    case class Custom[K](key: K, offset: String) extends XReadOffsets[K]
    object Custom {
      def of[K](offset: String, keys: K*): Set[Custom[K]] = keys.toSet.map(k => new Custom[K](k, offset))
    }
  }

  final case class StreamMessage[K, V](id: MessageId, key: K, body: Map[K, V])

  object StreamMessage {
    implicit def eq[K: Eq, V: Eq]: Eq[StreamMessage[K, V]] = Eq.and(Eq.by(_.id), Eq.and(Eq.by(_.key), Eq.by(_.body)))
  }

  sealed abstract class XRangePoint extends Product with Serializable

  object XRangePoint {

    implicit val eq: Eq[XRangePoint] = Eq.fromUniversalEquals

    case object Unbounded extends XRangePoint
    final case class Inclusive(id: String) extends XRangePoint
    final case class Exclusive(id: String) extends XRangePoint
  }

  /** Identifies a consumer within a consumer group (the `<group> <consumer>` pair shared by `XREADGROUP`, `XCLAIM`,
    * `XAUTOCLAIM` and the `XGROUP *CONSUMER` commands).
    */
  final case class StreamConsumer[K](group: K, consumer: K)

  /** Options for `XGROUP CREATE`. */
  final case class XGroupCreateArgs(
      mkStream: Boolean = false,
      entriesRead: Option[Long] = None
  )

  /** Options for `XREADGROUP`. `noack` skips adding the messages to the Pending Entries List. */
  final case class XReadGroupArgs(
      count: Option[Long] = None,
      block: Option[Duration] = None,
      noack: Boolean = false
  )

  /** The idle time to set on entries claimed by `XCLAIM` (the `IDLE`/`TIME` options). Both express the same thing — the
    * time since the entry was last delivered — so they are modelled as alternatives rather than a pair that could be
    * set together.
    */
  sealed trait XClaimIdle extends Product with Serializable
  object XClaimIdle {

    /** `IDLE <ms>`: idle time as a duration relative to now. */
    final case class Relative(idle: FiniteDuration) extends XClaimIdle

    /** `TIME <ms-unix-time>`: idle time as an absolute Unix timestamp in milliseconds. */
    final case class At(unixTimeMillis: Long) extends XClaimIdle
  }

  /** Options for `XCLAIM`. */
  final case class XClaimArgs(
      minIdleTime: FiniteDuration,
      idle: Option[XClaimIdle] = None,
      retryCount: Option[Long] = None,
      force: Boolean = false,
      justId: Boolean = false
  )

  /** Options for `XAUTOCLAIM`. `start` is the message id to start scanning the PEL from (defaults to `0`). */
  final case class XAutoClaimArgs[K](
      consumer: StreamConsumer[K],
      minIdleTime: FiniteDuration,
      start: String = "0",
      count: Option[Long] = None,
      justId: Boolean = false
  )

  /** Summary form of `XPENDING <key> <group>`. */
  final case class XPendingSummary(
      count: Long,
      minId: Option[MessageId],
      maxId: Option[MessageId],
      consumers: Map[String, Long]
  )

  /** A single entry from the extended form of `XPENDING`. */
  final case class XPendingMessage(
      id: MessageId,
      consumer: String,
      sinceLastDelivery: FiniteDuration,
      redeliveryCount: Long
  )

  /** Result of `XAUTOCLAIM`: the cursor to resume from plus the claimed messages. */
  final case class XAutoClaimResult[K, V](
      nextId: MessageId,
      messages: List[StreamMessage[K, V]]
  )

  implicit class TimePrecisionOps(val duration: FiniteDuration) extends AnyVal {
    def refine: Long = duration.unit match {
      case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS => duration.toMillis
      case _                                                                    => duration.toSeconds
    }
  }
}
