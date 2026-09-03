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
  LMovemArgs,
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

  /** Credentials for the destination instance in a [[MigrateArgs]]-driven `MIGRATE`, mirroring Redis's `AUTH` (password
    * only) vs `AUTH2` (username + password) forms.
    */
  sealed trait MigrateAuth
  object MigrateAuth {
    final case class Password(password: CharSequence) extends MigrateAuth
    final case class UsernamePassword(username: CharSequence, password: CharSequence) extends MigrateAuth
  }

  /** Options for the multi-key/`COPY`/`REPLACE`/`AUTH` form of `MIGRATE`.
    * @param keepSource
    *   Redis's `COPY` flag - don't remove the key(s) from the source instance
    */
  final case class MigrateArgs[K](
      keys: List[K] = Nil,
      keepSource: Boolean = false,
      replace: Boolean = false,
      auth: Option[MigrateAuth] = None
  )

  /** A compare condition for commands that support conditional value checks (currently `DELEX`; Lettuce also uses this
    * for `SET`'s `IFEQ`/`IFNE`/`IFDEQ`/`IFDNE`, not yet wrapped here). Digest-based comparisons use a 64-bit XXH3
    * digest as a 16-character lower-case hex string. Modeled as our own ADT rather than exposing Lettuce's
    * `CompareCondition[V]` directly, since that type is marked `@Experimental` upstream.
    */
  sealed trait CompareCondition[V]
  object CompareCondition {
    final case class ValueEqual[V](value: V) extends CompareCondition[V]
    final case class ValueNotEqual[V](value: V) extends CompareCondition[V]
    final case class DigestEqual[V](digest: String) extends CompareCondition[V]
    final case class DigestNotEqual[V](digest: String) extends CompareCondition[V]
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

  /** The result of `ACL DRYRUN` — whether the given user would be allowed to run the tested command. */
  sealed trait AclDryRunResult
  object AclDryRunResult {

    /** The user would be allowed to run the command. */
    case object Allowed extends AclDryRunResult

    /** The user would NOT be allowed to run the command, for the given reason (Redis's own explanation text, e.g.
      * `"This user has no permissions to run the 'set' command"`).
      */
    final case class Denied(reason: String) extends AclDryRunResult
  }

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

  sealed trait HSetExArg
  object HSetExArg {
    sealed trait Existence extends HSetExArg
    object Existence {

      /** Only set fields that do not already exist (FNX) */
      case object Nx extends Existence

      /** Only set fields that already exist (FXX) */
      case object Xx extends Existence
    }

    sealed trait Ttl extends HSetExArg
    object Ttl {

      /** Set expiration in seconds, relative */
      case class Ex(duration: FiniteDuration) extends Ttl

      /** Set expiration in millis, relative */
      case class Px(duration: FiniteDuration) extends Ttl

      /** Set expiration at an absolute time, second precision */
      case class ExAt(at: Instant) extends Ttl

      /** Set expiration at an absolute time, millisecond precision */
      case class PxAt(at: Instant) extends Ttl

      /** Retain the fields' existing TTL */
      case object Keep extends Ttl
    }
  }
  case class HSetExArgs(existence: Option[HSetExArg.Existence] = None, ttl: Option[HSetExArg.Ttl] = None)
  object HSetExArgs {
    def apply(ex: HSetExArg.Existence): HSetExArgs                     = HSetExArgs(Some(ex), None)
    def apply(ttl: HSetExArg.Ttl): HSetExArgs                          = HSetExArgs(None, Some(ttl))
    def apply(ex: HSetExArg.Existence, ttl: HSetExArg.Ttl): HSetExArgs = HSetExArgs(Some(ex), Some(ttl))
  }

  sealed trait MSetExTtl
  object MSetExTtl {

    /** Set expiration relative, in seconds */
    case class Ex(duration: FiniteDuration) extends MSetExTtl

    /** Set expiration relative, in millis */
    case class Px(duration: FiniteDuration) extends MSetExTtl

    /** Set expiration at an absolute time, second precision */
    case class ExAt(at: Instant) extends MSetExTtl

    /** Set expiration at an absolute time, millisecond precision */
    case class PxAt(at: Instant) extends MSetExTtl

    /** Retain the TTL already set on any keys being overwritten */
    case object KeepTtl extends MSetExTtl
  }

  /** Args for `msetEx`: an atomic multi-key SET with a shared TTL/existence policy across every key. */
  case class MSetExArgs(ttl: Option[MSetExTtl] = None, existence: Option[SetArg.Existence] = None)

  /** A single match found by `lcsIdx`: matching character ranges in each of the two compared keys. `matchLen` is
    * present only when the query asked for it.
    */
  case class LcsMatchPosition(start: Long, end: Long)
  case class LcsMatch(a: LcsMatchPosition, b: LcsMatchPosition, matchLen: Option[Long])

  /** Result of `lcs`/`lcsIdx`: `matchString` is present for the plain (non-idx) query, `matches` is populated only by
    * `lcsIdx`, and `len` (the LCS length) is always present.
    */
  case class LcsResult(matchString: Option[String], matches: List[LcsMatch], len: Long)

  sealed trait IncrexTtl
  object IncrexTtl {

    /** Set expiration relative, in seconds */
    case class Ex(duration: FiniteDuration) extends IncrexTtl

    /** Set expiration relative, in millis */
    case class Px(duration: FiniteDuration) extends IncrexTtl

    /** Set expiration at an absolute time, second precision */
    case class ExAt(at: Instant) extends IncrexTtl

    /** Set expiration at an absolute time, millisecond precision */
    case class PxAt(at: Instant) extends IncrexTtl

    /** Clear any existing expiration on the key */
    case object Persist extends IncrexTtl
  }

  /** Args for `incrEx`: bounds are clamped-to (rather than rejecting the operation) when `saturate` is set — without
    * it, an out-of-bounds increment is a no-op that leaves the key and its TTL unchanged. `ttlOnlyIfNoneSet` maps to
    * Redis's ENX flag: apply `ttl` only if the key doesn't already have one.
    */
  case class IncrexArgs(
      lowerBound: Option[Long] = None,
      upperBound: Option[Long] = None,
      saturate: Boolean = false,
      ttl: Option[IncrexTtl] = None,
      ttlOnlyIfNoneSet: Boolean = false
  )

  /** Float-bounded counterpart of `IncrexArgs`, for `incrExFloat`. */
  case class IncrexFloatArgs(
      lowerBound: Option[Double] = None,
      upperBound: Option[Double] = None,
      saturate: Boolean = false,
      ttl: Option[IncrexTtl] = None,
      ttlOnlyIfNoneSet: Boolean = false
  )

  /** Result of `incrEx`/`incrExFloat`: the key's new value, and the increment Redis actually applied (differs from the
    * requested increment only when `saturate` clamped the result to a bound).
    */
  case class IncrexResult[A](value: A, appliedIncrement: A)

  sealed trait LMoveSide
  object LMoveSide {
    case object Left extends LMoveSide
    case object Right extends LMoveSide
  }

  /** The optional COUNT/EXACTLY block of LMOVEM/BLMOVEM. Without one, LMOVEM/BLMOVEM behave like LMOVE/BLMOVE (move a
    * single element) — use `lMove`/`blMove` for that case instead.
    */
  sealed trait LMoveCount
  object LMoveCount {

    /** Move up to `count` elements. */
    final case class UpTo(count: Long, ordering: LMovemArgs.Ordering) extends LMoveCount

    /** Move exactly `count` elements, or none at all if the source doesn't have enough. */
    final case class Exactly(count: Long, ordering: LMovemArgs.Ordering) extends LMoveCount
  }

  final case class LPosArgs(rank: Option[Long] = None, maxLen: Option[Long] = None)

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

  /** Reply to `XINFO STREAM`. `firstEntry`/`lastEntry` are `None` when the stream currently holds no entries (Redis
    * reports them as a null reply in that case, per its own docs). `extra` carries any key/value pairs beyond the
    * well-established ones as flat strings - as of Redis 8.10 that includes a handful of idempotent-publish bookkeeping
    * fields (`idmp-duration`, `idmp-maxsize`, `pids-tracked`, `iids-tracked`, `iids-added`, `iids-duplicates`)
    * introduced alongside `XCFGSET`. Those are still new/evolving as of this writing, so rather than freeze their exact
    * names/shape into named fields, they land in `extra` - nothing Lettuce hands back is silently dropped, without
    * over-committing this type to an unstable surface.
    */
  final case class XStreamInfo[K, V](
      length: Long,
      radixTreeKeys: Long,
      radixTreeNodes: Long,
      lastGeneratedId: MessageId,
      maxDeletedEntryId: MessageId,
      entriesAdded: Long,
      recordedFirstEntryId: MessageId,
      groups: Long,
      firstEntry: Option[StreamMessage[K, V]],
      lastEntry: Option[StreamMessage[K, V]],
      extra: Map[String, String] = Map.empty
  )
  object XStreamInfo {
    import dev.profunktor.redis4cats.JavaConversions._

    private val knownFields = Set(
      "length",
      "radix-tree-keys",
      "radix-tree-nodes",
      "last-generated-id",
      "max-deleted-entry-id",
      "entries-added",
      "recorded-first-entry-id",
      "groups",
      "first-entry",
      "last-entry"
    )

    // A first-entry/last-entry value is Lettuce's untyped stand-in for a StreamMessage: [id, [field, value, ...]].
    // The K/V pairs have already passed through the connection's codec by the time they reach us as Object - the
    // cast here is exactly as safe as the one Lettuce's own typed StreamMessage[K, V] getters perform internally,
    // just visible instead of hidden behind a generic type parameter.
    private def toEntry[K, V](key: K, raw: Any): StreamMessage[K, V] =
      raw match {
        case entry: java.util.List[_] =>
          entry.asScala.toList match {
            case (id: String) :: (fields: java.util.List[_]) :: Nil =>
              val body = fields.asScala.toList
                .grouped(2)
                .collect { case (k: Any) :: (v: Any) :: Nil => k.asInstanceOf[K] -> v.asInstanceOf[V] }
                .toMap
              StreamMessage(MessageId(id), key, body)
            case other => throw XInfoError.UnexpectedStreamInfoReply(other.toString)
          }
        case other => throw XInfoError.UnexpectedStreamInfoReply(other.toString)
      }

    /** Decodes `XINFO STREAM`'s reply - a flat, untyped `List[Object]` of alternating field-name/value pairs. */
    private[redis4cats] def fromLettuce[K, V](key: K, reply: java.util.List[Object]): XStreamInfo[K, V] = {
      def fail                 = throw XInfoError.UnexpectedStreamInfoReply(reply.toString)
      def asLong(a: Any): Long = a.toString.toLong

      val fields = reply.asScala.toList
        .grouped(2)
        .collect { case (name: String) :: value :: Nil => name -> value }
        .toMap

      def required[A](name: String)(f: Any => A): A = fields.get(name).map(f).getOrElse(fail)
      // Redis reports an empty stream's first-entry/last-entry as a nil reply, which the field map above
      // stores as a present key with a null value rather than a missing key - Option(_) collapses that null
      // to None, same as Map.get already does for a genuinely absent key.
      def optional[A](name: String)(f: Any => A): Option[A] = fields.get(name).flatMap(Option(_)).map(f)

      XStreamInfo[K, V](
        length = required("length")(asLong),
        radixTreeKeys = required("radix-tree-keys")(asLong),
        radixTreeNodes = required("radix-tree-nodes")(asLong),
        lastGeneratedId = required("last-generated-id")(v => MessageId(v.toString)),
        maxDeletedEntryId = required("max-deleted-entry-id")(v => MessageId(v.toString)),
        entriesAdded = required("entries-added")(asLong),
        recordedFirstEntryId = required("recorded-first-entry-id")(v => MessageId(v.toString)),
        groups = required("groups")(asLong),
        firstEntry = optional("first-entry")(toEntry(key, _)),
        lastEntry = optional("last-entry")(toEntry(key, _)),
        extra = fields.collect { case (k, v) if !knownFields.contains(k) => k -> v.toString }
      )
    }
  }

  /** One entry of an `XINFO GROUPS` reply. `entriesRead`/`lag` are `None` when Redis cannot determine them (e.g. right
    * after `XGROUP CREATE`/`XSETID`, before anything has been read).
    */
  final case class XGroupInfo(
      name: String,
      consumers: Long,
      pending: Long,
      lastDeliveredId: MessageId,
      entriesRead: Option[Long],
      lag: Option[Long]
  )
  object XGroupInfo {
    import dev.profunktor.redis4cats.JavaConversions._

    /** Decodes one element of `XINFO GROUPS`' reply - a flat, untyped `List[Object]` of field-name/value pairs.
      * `entries-read`/`lag` are null when Redis can't compute them yet (e.g. right after `XGROUP CREATE`/`XSETID`).
      */
    private[redis4cats] def fromLettuce(raw: Any): XGroupInfo = {
      def fail = throw XInfoError.UnexpectedGroupInfoReply(raw.toString)
      raw match {
        case entry: java.util.List[_] =>
          val fields: Map[String, Any] = entry.asScala.toList
            .grouped(2)
            .collect { case (name: String) :: value :: Nil => name -> value }
            .toMap
          def required[A](name: String)(f: Any => A): A = fields.get(name).map(f).getOrElse(fail)
          def optionalLong(name: String): Option[Long]  = fields.get(name).flatMap(Option(_)).map(_.toString.toLong)
          XGroupInfo(
            name = required("name")(_.toString),
            consumers = required("consumers")(_.toString.toLong),
            pending = required("pending")(_.toString.toLong),
            lastDeliveredId = required("last-delivered-id")(v => MessageId(v.toString)),
            entriesRead = optionalLong("entries-read"),
            lag = optionalLong("lag")
          )
        case _ => fail
      }
    }
  }

  /** One entry of an `XINFO CONSUMERS` reply. `inactive` (time since the consumer's last successful command, distinct
    * from `idle` - time since its last attempted read) was added in Redis 7.2; `None` on servers that don't report it.
    */
  final case class XConsumerInfo(
      name: String,
      pending: Long,
      idle: FiniteDuration,
      inactive: Option[FiniteDuration]
  )
  object XConsumerInfo {
    import dev.profunktor.redis4cats.JavaConversions._

    /** Decodes one element of `XINFO CONSUMERS`' reply - a flat, untyped `List[Object]` of field-name/value pairs.
      * `inactive` (time since the consumer's last successful command, distinct from `idle` - time since its last
      * attempted read) was added in Redis 7.2; `None` on servers that don't report it.
      */
    private[redis4cats] def fromLettuce(raw: Any): XConsumerInfo = {
      def fail = throw XInfoError.UnexpectedConsumerInfoReply(raw.toString)
      raw match {
        case entry: java.util.List[_] =>
          val fields: Map[String, Any] = entry.asScala.toList
            .grouped(2)
            .collect { case (name: String) :: value :: Nil => name -> value }
            .toMap
          def required[A](name: String)(f: Any => A): A = fields.get(name).map(f).getOrElse(fail)
          XConsumerInfo(
            name = required("name")(_.toString),
            pending = required("pending")(_.toString.toLong),
            idle = required("idle")(v => FiniteDuration(v.toString.toLong, TimeUnit.MILLISECONDS)),
            inactive = fields
              .get("inactive")
              .flatMap(Option(_))
              .map(v => FiniteDuration(v.toString.toLong, TimeUnit.MILLISECONDS))
          )
        case _ => fail
      }
    }
  }

  /** Failure raised while decoding an `XINFO STREAM`/`XINFO GROUPS`/`XINFO CONSUMERS` reply. Lettuce hands these back
    * as untyped, flat key-value `List[Object]`s with no schema enforcement - a genuinely unexpected shape becomes one
    * of these instead of a `ClassCastException`/`NoSuchElementException` deep inside a fold.
    */
  sealed abstract class XInfoError(message: String) extends RuntimeException(message)
  object XInfoError {
    final case class UnexpectedStreamInfoReply(reply: String)
        extends XInfoError(s"Unexpected XINFO STREAM reply: $reply")
    final case class UnexpectedGroupInfoReply(reply: String)
        extends XInfoError(s"Unexpected XINFO GROUPS entry: $reply")
    final case class UnexpectedConsumerInfoReply(reply: String)
        extends XInfoError(s"Unexpected XINFO CONSUMERS entry: $reply")
  }

  /** Which stream entries `XDELEX`/`XACKDEL` are allowed to remove, and how they treat consumer-group PEL references to
    * them. Mirrors Lettuce's `StreamDeletionPolicy`, modelled as our own ADT since that type is marked `@Experimental`
    * upstream. Redis defaults to [[KeepReferences]] when none is given - the same behavior as plain `XDEL`.
    */
  sealed trait StreamDeletionPolicy
  object StreamDeletionPolicy {

    /** `KEEPREF` (default): delete the entry but leave any consumer-group PEL references to it in place. */
    case object KeepReferences extends StreamDeletionPolicy

    /** `DELREF`: delete the entry and remove all consumer-group PEL references to it. */
    case object DeleteReferences extends StreamDeletionPolicy

    /** `ACKED`: only delete entries that have already been read and acknowledged by every consumer group. */
    case object Acknowledged extends StreamDeletionPolicy
  }

  /** Per-id outcome of `XDELEX`/`XACKDEL`. Mirrors Lettuce's `StreamEntryDeletionResult`. */
  sealed trait StreamEntryDeletionResult
  object StreamEntryDeletionResult {
    case object Deleted extends StreamEntryDeletionResult
    case object NotDeletedUnacknowledgedOrStillReferenced extends StreamEntryDeletionResult
    case object NotFound extends StreamEntryDeletionResult
    case object Unknown extends StreamEntryDeletionResult
  }

  /** How `XNACK` adjusts a message's delivery counter in the consumer group's Pending Entries List. Mirrors Lettuce's
    * `XNackMode`.
    */
  sealed trait XNackMode
  object XNackMode {

    /** Internal error/shutdown on this consumer - decrements the delivery counter by 1, allowing normal redelivery
      * elsewhere.
      */
    case object Silent extends XNackMode

    /** The message is problematic for this consumer specifically but may succeed elsewhere - delivery counter left
      * unchanged.
      */
    case object Fail extends XNackMode

    /** The message is invalid/malicious - delivery counter is set to `LLONG_MAX`, effectively preventing further
      * redelivery.
      */
    case object Fatal extends XNackMode
  }

  /** Options for `XCFGSET`, Redis's per-stream idempotent-publish configuration. Both setters are independent -
    * mirroring Lettuce's `XCfgSetArgs` - and an unset field is left unchanged server-side rather than reset.
    * @param idempotencyMaxSize
    *   the maximum number of tracked producer ids to retain (`IDMP-MAXSIZE`)
    * @param idempotencyDuration
    *   how long a tracked producer id remains valid, in the unit Redis's own `IDMP-DURATION` argument expects
    */
  final case class XCfgSetArgs(
      idempotencyMaxSize: Option[Long] = None,
      idempotencyDuration: Option[Long] = None
  )

  implicit class TimePrecisionOps(val duration: FiniteDuration) extends AnyVal {
    def refine: Long = duration.unit match {
      case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS => duration.toMillis
      case _                                                                    => duration.toSeconds
    }
  }

  /** The reply to the Redis `ROLE` command. Lettuce decodes this as an untyped `List[Object]` (its RESP shape genuinely
    * differs between a master and a replica) — this models both cases.
    */
  sealed trait RedisRole
  object RedisRole {
    final case class ReplicaNode(ip: String, port: Long, replicationOffset: Long)
    final case class Master(replicationOffset: Long, replicas: List[ReplicaNode]) extends RedisRole
    final case class Replica(masterHost: String, masterPort: Long, replicationState: String, replicationOffset: Long)
        extends RedisRole
  }

  /** Failure raised while decoding a `ROLE` reply into [[RedisRole]]. */
  sealed abstract class ReplicationError(message: String) extends RuntimeException(message)
  object ReplicationError {

    /** The top-level `ROLE` reply didn't match either the master or the replica shape. */
    final case class UnexpectedRoleReply(reply: String) extends ReplicationError(s"Unexpected ROLE reply: $reply")

    /** A replica entry inside a master's `ROLE` reply didn't have the expected `[ip, port, offset]` shape. */
    final case class UnexpectedReplicaEntry(entry: String)
        extends ReplicationError(s"Unexpected replica entry in ROLE reply: $entry")
  }

  /** The reply to the Redis `TIME` command: the server's current Unix time. Lettuce decodes this as an untyped
    * `List[V]` of exactly two elements (seconds, microseconds) - this gives both a name and a numeric type instead of
    * positional list access.
    */
  final case class RedisServerTime(epochSecond: Long, microseconds: Long)

  /** Failure raised while decoding a `TIME` reply into [[RedisServerTime]]. Its `[seconds, microseconds]` shape has
    * been stable since Redis 1.0.0, so this should never fire in practice.
    */
  final case class UnexpectedTimeReply(reply: String) extends RuntimeException(s"Unexpected TIME reply: $reply")

  /** Which kind of client connection a `CLIENT LIST`/`CLIENT KILL` filter targets. */
  sealed trait ClientType
  object ClientType {
    case object Normal extends ClientType
    case object Master extends ClientType
    case object Replica extends ClientType
    case object PubSub extends ClientType
  }

  /** Options for the filtered form of `CLIENT LIST`. Redis's own syntax is `CLIENT LIST [TYPE type] | [ID id...]` - a
    * filter is either by type or by ids, never both - modeled as a sum type rather than two independent optional fields
    * so an invalid combination can't be constructed.
    */
  sealed trait ClientListArgs
  object ClientListArgs {
    final case class ByIds(ids: List[Long]) extends ClientListArgs
    final case class ByType(tpe: ClientType) extends ClientListArgs
  }

  /** Options for the filtered form of `CLIENT KILL`. `id`/`tpe`/`user`/`addr`/`laddr`/`maxAge` are independent filters
    * ANDed together by Redis; `skipMe` (Redis defaults it to `true`) controls whether the calling client's own
    * connection is excluded from the match.
    */
  final case class KillArgs(
      id: Option[Long] = None,
      tpe: Option[ClientType] = None,
      user: Option[String] = None,
      addr: Option[String] = None,
      laddr: Option[String] = None,
      skipMe: Option[Boolean] = None,
      maxAge: Option[Long] = None
  )

  /** Which condition unblocks a client via `CLIENT UNBLOCK`: as a successful timeout, or as an error. */
  sealed trait UnblockType
  object UnblockType {
    case object Timeout extends UnblockType
    case object Error extends UnblockType
  }

  /** A behavioral flag on a Redis command, as reported by `COMMAND`/`COMMAND INFO`. */
  sealed trait CommandFlag
  object CommandFlag {
    case object Write extends CommandFlag
    case object ReadOnly extends CommandFlag
    case object DenyOom extends CommandFlag
    case object Admin extends CommandFlag
    case object PubSub extends CommandFlag
    case object NoScript extends CommandFlag
    case object Random extends CommandFlag
    case object SortForScript extends CommandFlag
    case object Loading extends CommandFlag
    case object Stale extends CommandFlag
    case object SkipMonitor extends CommandFlag
    case object Asking extends CommandFlag
    case object Fast extends CommandFlag
    case object MovableKeys extends CommandFlag
  }

  /** One entry of a `COMMAND`/`COMMAND INFO` reply. `firstKeyPosition`/`lastKeyPosition`/`keyStepCount` describe where
    * the command's keys sit among its arguments - all `0` when the command has no predetermined key position (see
    * [[CommandFlag.MovableKeys]]).
    */
  final case class CommandInfo(
      name: String,
      arity: Int,
      flags: Set[CommandFlag],
      firstKeyPosition: Int,
      lastKeyPosition: Int,
      keyStepCount: Int,
      aclCategories: Set[AclCategory]
  )

  /** One entry of a `SLOWLOG GET` reply. `clientAddr`/`clientName` are absent on Redis servers older than 4.0, which
    * only reported `id`/`timestamp`/`duration`/`args`. `originalArgCount` is a newer field still - it's the true number
    * of arguments the command had, which can exceed `args.size` when Redis truncates a long argument list for display;
    * `None` on servers that don't report it.
    */
  final case class SlowLogEntry(
      id: Long,
      timestamp: Instant,
      duration: FiniteDuration,
      args: List[String],
      clientAddr: Option[String],
      clientName: Option[String],
      originalArgCount: Option[Int]
  )

  /** Failure raised while decoding a `SLOWLOG GET` reply into [[SlowLogEntry]]. */
  final case class UnexpectedSlowLogEntry(entry: String) extends RuntimeException(s"Unexpected SLOWLOG entry: $entry")

  /** Options for `CLIENT TRACKING`. `enabled` is Redis's mandatory `ON`/`OFF` first argument; the rest are optional
    * modifiers that only apply when enabling tracking. `optIn`/`optOut` are mutually exclusive by Redis's own rules,
    * but left as independent flags here rather than a sum type, mirroring `TrackingArgs`' own shape - Redis itself
    * rejects setting both, there's no silent-wrong-behavior risk in letting the server be the one to say so.
    */
  final case class ClientTrackingArgs(
      enabled: Boolean,
      bcast: Boolean = false,
      optIn: Boolean = false,
      optOut: Boolean = false,
      noLoop: Boolean = false,
      redirect: Option[Long] = None,
      prefixes: List[String] = Nil
  )

  /** A single flag in a `CLIENT TRACKINGINFO` reply, describing one aspect of the connection's client-side-caching
    * state.
    */
  sealed trait TrackingFlag
  object TrackingFlag {
    case object Off extends TrackingFlag
    case object On extends TrackingFlag
    case object Bcast extends TrackingFlag
    case object OptIn extends TrackingFlag
    case object OptOut extends TrackingFlag
    case object CachingYes extends TrackingFlag
    case object CachingNo extends TrackingFlag
    case object NoLoop extends TrackingFlag
    case object BrokenRedirect extends TrackingFlag
  }

  /** The reply to `CLIENT TRACKINGINFO`. */
  final case class TrackingInfo(flags: Set[TrackingFlag], redirect: Long, prefixes: List[String])
}
