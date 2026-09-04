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

import cats._
import cats.data.NonEmptyList
import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.{ json, BitCommandOperation }
import dev.profunktor.redis4cats.algebra.BitCommandOperation.Overflows
import dev.profunktor.redis4cats.config.Redis4CatsConfig
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.FutureLift._
import dev.profunktor.redis4cats.effect._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.tx.{ TransactionDiscarded, TxRunner, TxStore }
import io.lettuce.core
import io.lettuce.core.XReadArgs.StreamOffset
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.{ RedisClusterCommands => RedisClusterSyncCommands }
import io.lettuce.core.json.arguments.{ JsonMsetArgs, JsonRangeArgs, JsonSetArgs }
import io.lettuce.core.json.{ JsonPath, JsonType, JsonValue }
import io.lettuce.core.{
  AclSetuserArgs,
  BLMovemArgs,
  BitFieldArgs,
  ClientListArgs => JClientListArgs,
  ClientOptions,
  CompareCondition => JCompareCondition,
  Consumer => JConsumer,
  CopyArgs => JCopyArgs,
  ExpireArgs => JExpireArgs,
  FlushMode => JFlushMode,
  FunctionRestoreMode => JFunctionRestoreMode,
  GeoArgs,
  GeoSearch,
  GeoWithin,
  GetExArgs => JGetExArgs,
  HGetExArgs => JHGetExArgs,
  HSetExArgs => JHSetExArgs,
  IncrexArgs => JIncrexArgs,
  IncrexFloatArgs => JIncrexFloatArgs,
  KillArgs => JKillArgs,
  LMPopArgs,
  LMoveArgs,
  LMovemArgs,
  LPosArgs => JLPosArgs,
  LcsArgs => JLcsArgs,
  Limit => JLimit,
  MSetExArgs => JMSetExArgs,
  MigrateArgs => JMigrateArgs,
  Range => JRange,
  ReadFrom => JReadFrom,
  RedisFuture,
  RestoreArgs => JRestoreArgs,
  SDiffCardArgs,
  SUnionCardArgs,
  ScanCursor => JScanCursor,
  ScoredValue,
  SetArgs => JSetArgs,
  SortArgs => JSortArgs,
  StreamDeletionPolicy => JStreamDeletionPolicy,
  StringMatchResult => JStringMatchResult,
  TrackingArgs => JTrackingArgs,
  UnblockType => JUnblockType,
  XAddArgs => JXAddArgs,
  XAutoClaimArgs => JXAutoClaimArgs,
  XCfgSetArgs => JXCfgSetArgs,
  XClaimArgs => JXClaimArgs,
  XGroupCreateArgs => JXGroupCreateArgs,
  XNackMode => JXNackMode,
  XReadArgs,
  XTrimArgs => JXTrimArgs,
  ZAddArgs,
  ZAggregateArgs,
  ZPopArgs,
  ZStoreArgs
}
import io.lettuce.core.models.command.CommandDetailParser
import io.lettuce.core.models.stream.{
  ClaimedMessages,
  PendingMessage,
  PendingMessages,
  StreamEntryDeletionResult => JStreamEntryDeletionResult
}
import io.lettuce.core.protocol.CommandType
import org.typelevel.keypool.KeyPool

import java.time.Instant
import java.util
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import dev.profunktor.redis4cats.data.Subscription.toSubscription

object Redis {

  object Pool {
    final case class Settings(maxTotal: Int, maxIdle: Int, idleTimeAllowedInPool: FiniteDuration)
    object Settings {
      object Defaults {
        val minimumTotal: Int                     = 10
        val maxIdle: Int                          = 2
        val idleTimeAllowedInPool: FiniteDuration = 60.seconds
      }

      def default[F[_]: MkRedis: Functor]: F[Settings] =
        MkRedis[F].availableProcessors.map { cpu =>
          Settings(
            maxTotal = Math.max(Defaults.minimumTotal, cpu),
            maxIdle = Defaults.maxIdle,
            idleTimeAllowedInPool = Defaults.idleTimeAllowedInPool
          )
        }
    }

    implicit class PoolOps[F[_], K, V](val pool: KeyPool[F, Unit, RedisCommands[F, K, V]]) extends AnyVal {
      @inline def withRedisCommands[A](
          fn: RedisCommands[F, K, V] => F[A]
      )(
          implicit M: MonadCancel[F, Throwable]
      ): F[A] =
        pool.take(()).use(managed => fn(managed.value))
    }
  }

  private[redis4cats] def acquireAndRelease[F[_]: FutureLift: Log: MonadThrow, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V],
      tx: TxRunner[F]
  ): (F[Redis[F, K, V]], Redis[F, K, V] => F[Unit]) = {
    val acquire: F[Redis[F, K, V]] = FutureLift[F]
      .lift(client.underlying.connectAsync(codec.underlying, client.uri.underlying))
      .map(c => new Redis[F, K, V](new RedisStatefulConnection[F, K, V](c), tx))

    val release: Redis[F, K, V] => F[Unit] = c =>
      Log[F].info(s"Releasing Commands connection: ${client.uri.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseCluster[F[_]: FutureLift: Log: MonadThrow, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom],
      tx: TxRunner[F]
  ): (F[RedisCluster[F, K, V]], RedisCluster[F, K, V] => F[Unit]) = {
    val acquire: F[RedisCluster[F, K, V]] = FutureLift[F]
      .lift(client.underlying.connectAsync[K, V](codec.underlying))
      .flatTap(c => FutureLift[F].delay(readFrom.foreach(c.setReadFrom)))
      .map(c => new RedisCluster[F, K, V](new RedisStatefulClusterConnection[F, K, V](c), tx))

    val release: RedisCluster[F, K, V] => F[Unit] = c =>
      Log[F].info(s"Releasing cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseClusterByNode[F[_]: FutureLift: Log: MonadThrow, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom],
      nodeId: NodeId,
      tx: TxRunner[F]
  ): (F[BaseRedis[F, K, V]], BaseRedis[F, K, V] => F[Unit]) = {
    val acquire = FutureLift[F]
      .lift(client.underlying.connectAsync[K, V](codec.underlying))
      .flatTap(c => FutureLift[F].delay(readFrom.foreach(c.setReadFrom)))
      .map { c =>
        new BaseRedis[F, K, V](new RedisStatefulClusterConnection[F, K, V](c), tx, cluster = true) {
          override def async: F[RedisClusterAsyncCommands[K, V]] =
            if (cluster) conn.byNode(nodeId).widen[RedisClusterAsyncCommands[K, V]]
            else conn.async.widen[RedisClusterAsyncCommands[K, V]]
        }
      }

    val release: BaseRedis[F, K, V] => F[Unit] = c =>
      Log[F].info(s"Releasing single-shard cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  class RedisPartiallyApplied[F[_]: MkRedis: MonadThrow] {
    implicit val fl: FutureLift[F] = MkRedis[F].futureLift
    implicit val log: Log[F]       = MkRedis[F].log

    /** Creates a [[RedisCommands]] for a single-node connection.
      *
      * It will create an underlying RedisClient with default options to establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * Redis[IO].simple("redis://localhost", RedisCodec.Ascii)
      * }}}
      *
      * Note: if you need to create multiple connections, use `fromClient` instead, which allows you to re-use the same
      * client.
      */
    def simple[K, V](uri: String, codec: RedisCodec[K, V]): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].clientFrom(uri).flatMap(this.fromClient(_, codec))

    /** Creates a [[RedisCommands]] for a single-node connection.
      *
      * It will create an underlying RedisClient using the supplied client options to establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * for {
      *   opts <- Resource.eval(Sync[F].delay(ClientOptions.create())) // configure timeouts, etc
      *   cmds <- Redis[IO].withOptions("redis://localhost", opts, RedisCodec.Ascii)
      * } yield cmds
      * }}}
      *
      * Note: if you need to create multiple connections, use `fromClient` instead, which allows you to re-use the same
      * client.
      */
    def withOptions[K, V](
        uri: String,
        opts: ClientOptions,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].clientWithOptions(uri, opts).flatMap(this.fromClient(_, codec))

    /** Creates a [[RedisCommands]] for a single-node connection.
      *
      * It will create an underlying RedisClient using the supplied client options and config to establish connection
      * with Redis. Can be used to customise advanced features like metric recording or shutdown delays.
      *
      * Example:
      *
      * {{{
      * for {
      *   opts <- Resource.eval(Sync[F].delay(ClientOptions.create())) // configure timeouts, etc
      *   config = Redis4CatsConfig()
      *   cmds <- Redis[IO].custom("redis://localhost", opts, config, RedisCodec.Ascii)
      * } yield cmds
      * }}}
      *
      * Note: if you need to create multiple connections, use `fromClient` instead, which allows you to re-use the same
      * client.
      */
    def custom[K, V](
        uri: String,
        opts: ClientOptions,
        config: Redis4CatsConfig,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      Resource
        .eval(RedisURI.make(uri))
        .flatMap(MkRedis[F].clientCustom(_, opts, config))
        .flatMap(this.fromClient(_, codec))

    /** Creates a [[RedisCommands]] for a single-node connection to deal with UTF-8 encoded keys and values.
      *
      * It will create an underlying RedisClient with default options to establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * Redis[IO].utf8("redis://localhost")
      * }}}
      *
      * Note: if you need to create multiple connections, use `fromClient` instead, which allows you to re-use the same
      * client.
      */
    def utf8(uri: String): Resource[F, RedisCommands[F, String, String]] =
      simple(uri, RedisCodec.Utf8)

    /** Creates a [[RedisCommands]] for a single-node connection.
      *
      * Example:
      *
      * {{{
      * val redis: Resource[IO, RedisCommands[IO, String, String]] =
      *   for {
      *     uri <- Resource.eval(RedisURI.make[IO]("redis://localhost"))
      *     cli <- RedisClient[IO](uri)
      *     cmd <- Redis[IO].fromClient(cli, RedisCodec.Utf8)
      *   } yield cmd
      * }}}
      *
      * Note: if you don't need to create multiple connections, you might prefer to use either [[utf8]] or `simple`
      * instead.
      */
    def fromClient[K, V](
        client: RedisClient,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].txRunner.flatMap { tx =>
        val (acquire, release) = acquireAndRelease[F, K, V](client, codec, tx)
        Resource.make(acquire)(release).widen
      }

    /** Creates a pool of [[RedisCommands]] for a single-node connection.
      *
      * Example:
      *
      * {{{
      * val pool: Resource[IO, KeyPool[IO, Unit, RedisCommands[IO, String, String]]] =
      *   for {
      *     uri <- Resource.eval(RedisURI.make[IO]("redis://localhost"))
      *     cli <- RedisClient[IO](uri)
      *     pool <- Redis[IO].pooled(cli, RedisCodec.Utf8)
      *   } yield pool
      *
      *  pool.use(_.withRedisCommands(redis => redis.set(usernameKey, "some value")))
      * }}}
      */
    def pooled[K, V](
        client: RedisClient,
        codec: RedisCodec[K, V]
    )(
        implicit T: Temporal[F]
    ): Resource[F, KeyPool[F, Unit, RedisCommands[F, K, V]]] =
      Resource
        .eval(Redis.Pool.Settings.default[F])
        .flatMap(poolSettings => customPooled[K, V](client, codec, poolSettings))

    /** Creates a pool of [[RedisCommands]] for a single-node connection. Similar to [[pooled]] but allows custom
      * [[Redis.Pool.Settings]]
      */
    def customPooled[K, V](
        client: RedisClient,
        codec: RedisCodec[K, V],
        poolSettings: Redis.Pool.Settings
    )(
        implicit T: Temporal[F]
    ): Resource[F, KeyPool[F, Unit, RedisCommands[F, K, V]]] = {
      val cmdsResource: Resource[F, RedisCommands[F, K, V]] = fromClient(client, codec)
      KeyPool
        .Builder[F, Unit, RedisCommands[F, K, V]]((_: Unit) => cmdsResource)
        .withMaxPerKey(Function.const(poolSettings.maxTotal))
        .withMaxTotal(poolSettings.maxTotal)
        .withMaxIdle(poolSettings.maxIdle)
        .withIdleTimeAllowedInPool(poolSettings.idleTimeAllowedInPool)
        .build
    }

    /** Creates a [[RedisCommands]] for a cluster connection.
      *
      * It will also create an underlying RedisClusterClient to establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * Redis[IO].cluster(
      *   RedisCodec.Utf8,
      *   "redis://localhost:30001",
      *   "redis://localhost:30002"
      * )
      * }}}
      *
      * Note: if you need to create multiple connections, use either [[fromClusterClient]] or
      * [[fromClusterClientByNode]] instead, which allows you to re-use the same client.
      */
    def cluster[K, V](
        codec: RedisCodec[K, V],
        uris: String*
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] =
      for {
        redisUris <- Resource.eval(uris.toList.traverse(RedisURI.make[F](_)))
        client <- MkRedis[F].clusterClient(redisUris: _*)
        redis <- this.fromClusterClient[K, V](client, codec)(readFrom)
      } yield redis

    /** Creates a [[RedisCommands]] for a cluster connection to deal with UTF-8 encoded keys and values.
      *
      * It will also create an underlying RedisClusterClient to establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * Redis[IO].clusterUtf8(
      *   "redis://localhost:30001",
      *   "redis://localhost:30002"
      * )
      * }}}
      *
      * Note: if you need to create multiple connections, use either [[fromClusterClient]] or
      * [[fromClusterClientByNode]] instead, which allows you to re-use the same client.
      */
    def clusterUtf8(
        uris: String*
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, String, String]] =
      cluster(RedisCodec.Utf8, uris: _*)(readFrom)

    /** Creates a [[RedisCommands]] for a cluster connection
      *
      * Example:
      *
      * {{{
      * val redis: Resource[IO, RedisCommands[IO, String, String]] =
      *   for {
      *     uris <- Resource.eval(
      *             List("redis://localhost:30001", "redis://localhost:30002")
      *               .traverse(RedisURI.make[F](_))
      *           )
      *     cli <- RedisClusterClient[IO](uris: _*)
      *     cmd <- Redis[IO].fromClusterClient(cli, RedisCodec.Utf8)
      *   } yield cmd
      * }}}
      *
      * Note: if you don't need to create multiple connections, you might prefer to use either [[clusterUtf8]] or
      * [[cluster]] instead.
      */
    def fromClusterClient[K, V](
        clusterClient: RedisClusterClient,
        codec: RedisCodec[K, V]
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].txRunner.flatMap { tx =>
        val (acquire, release) = acquireAndReleaseCluster(clusterClient, codec, readFrom, tx)
        Resource.make(acquire)(release).widen
      }

    /** Creates a [[RedisCommands]] by trying to establish a cluster connection to the specified node.
      *
      * Example:
      *
      * {{{
      * val redis: Resource[IO, RedisCommands[IO, String, String]] =
      *   for {
      *     uris <- Resource.eval(
      *             List("redis://localhost:30001", "redis://localhost:30002")
      *               .traverse(RedisURI.make[F](_))
      *           )
      *     cli <- RedisClusterClient[IO](uris: _*)
      *     cmd <- Redis[IO].fromClusterClientByNode(cli, RedisCodec.Utf8, NodeId("1"))
      *   } yield cmd
      * }}}
      *
      * Note: if you don't need to create multiple connections, you might prefer to use either [[clusterUtf8]] or
      * [[cluster]] instead.
      */
    def fromClusterClientByNode[K, V](
        clusterClient: RedisClusterClient,
        codec: RedisCodec[K, V],
        nodeId: NodeId
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].txRunner.flatMap { tx =>
        val (acquire, release) = acquireAndReleaseClusterByNode(clusterClient, codec, readFrom, nodeId, tx)
        Resource.make(acquire)(release).widen
      }

    /** Creates a [[RedisCommands]] from a MasterReplica connection
      *
      * Example:
      *
      * {{{
      * val redis: Resource[IO, RedisCommands[IO, String, String]] =
      *   for {
      *     uri <- Resource.eval(RedisURI.make[IO](redisURI))
      *     conn <- RedisMasterReplica[IO].make(RedisCodec.Utf8, uri)(Some(ReadFrom.MasterPreferred))
      *     cmds <- Redis[IO].masterReplica(conn)
      *   } yield cmds
      * }}}
      */
    def masterReplica[K, V](
        conn: RedisMasterReplica[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].txRunner.map(tx => new Redis[F, K, V](new RedisStatefulConnection(conn.underlying), tx))

  }

  def apply[F[_]: MkRedis: MonadThrow]: RedisPartiallyApplied[F] = new RedisPartiallyApplied[F]

}

private[redis4cats] class BaseRedis[F[_]: FutureLift: MonadThrow: Log, K, V](
    val conn: RedisConnection[F, K, V],
    val tx: TxRunner[F],
    val cluster: Boolean
) extends RedisCommands[F, K, V]
    with RedisConversionOps {

  def liftK[G[_]: Async: Log]: RedisCommands[G, K, V] =
    new BaseRedis[G, K, V](conn.liftK[G], tx.liftK[G], cluster)

  import dev.profunktor.redis4cats.JavaConversions._

  def async: F[RedisClusterAsyncCommands[K, V]] =
    if (cluster) conn.clusterAsync else conn.async.widen

  def sync: F[RedisClusterSyncCommands[K, V]] =
    if (cluster) conn.clusterSync else conn.sync.widen

  // format: off
  /******************************* Keys API *************************************/
  // format: on
  override def copy(source: K, destination: K): F[Boolean] =
    async.flatMap(_.copy(source, destination).futureLift.map(x => Boolean.box(x)))

  override def copy(source: K, destination: K, copyArgs: CopyArgs): F[Boolean] =
    async.flatMap(_.copy(source, destination, copyArgs.asJava).futureLift.map(x => Boolean.box(x)))

  def del(k: K, keys: K*): F[Long] =
    async.flatMap(_.del((k +: keys): _*).futureLift.map(x => Long.box(x)))

  override def delex(key: K, condition: CompareCondition[V]): F[Boolean] =
    async.flatMap(_.delex(key, toJCompareCondition(condition)).futureLift.map(x => Long.unbox(x) > 0))

  private def toJCompareCondition(condition: CompareCondition[V]): JCompareCondition[V] =
    condition match {
      case CompareCondition.ValueEqual(value)      => JCompareCondition.valueEq(value)
      case CompareCondition.ValueNotEqual(value)   => JCompareCondition.valueNe(value)
      case CompareCondition.DigestEqual(digest)    => JCompareCondition.digestEq(digest)
      case CompareCondition.DigestNotEqual(digest) => JCompareCondition.digestNe(digest)
    }

  override def dump(key: K): F[Option[Array[Byte]]] =
    async.flatMap(_.dump(key).futureLift.map(Option(_)))

  override def exists(key: K, keys: K*): F[Boolean] = {
    val all = key +: keys
    async.flatMap(_.exists(all: _*).futureLift.map(_ == all.size.toLong))
  }

  /** Expires a key with the given duration. If specified either in MILLISECONDS, MICROSECONDS or NANOSECONDS, the value
    * will be converted to MILLISECONDS. Otherwise, it will be converted to SECONDS.
    *
    * As expected by Redis' PEXPIRE and EXPIRE commands, respectively.
    */
  override def expire(key: K, expiresIn: FiniteDuration): F[Boolean] =
    async
      .flatMap { c =>
        expiresIn.unit match {
          case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS =>
            c.pexpire(key, expiresIn.toMillis).futureLift
          case _ =>
            c.expire(key, expiresIn.toSeconds).futureLift
        }
      }
      .map(x => Boolean.box(x))

  override def expire(key: K, expiresIn: FiniteDuration, expireExistenceArg: ExpireExistenceArg): F[Boolean] =
    async
      .flatMap { c =>
        expiresIn.unit match {
          case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS =>
            c.pexpire(key, expiresIn.toMillis, expireExistenceArg.asJava).futureLift
          case _ =>
            c.expire(key, expiresIn.toSeconds, expireExistenceArg.asJava).futureLift
        }
      }
      .map(x => Boolean.box(x))

  /** Expires a key at the given date.
    *
    * It calls Redis' PEXPIREAT under the hood, which has milliseconds precision.
    */
  override def expireAt(key: K, at: Instant): F[Boolean] =
    async.flatMap(_.pexpireat(key, at.toEpochMilli()).futureLift.map(x => Boolean.box(x)))

  override def expireAt(key: K, at: Instant, expireExistenceArg: ExpireExistenceArg): F[Boolean] =
    async.flatMap(_.pexpireat(key, at.toEpochMilli(), expireExistenceArg.asJava).futureLift.map(x => Boolean.box(x)))

  override def objectIdletime(key: K): F[Option[FiniteDuration]] =
    async.flatMap(_.objectIdletime(key).futureLift).map {
      case null => none[FiniteDuration]
      case d    => FiniteDuration(d, TimeUnit.SECONDS).some
    }

  override def objectEncoding(key: K): F[Option[String]] =
    async.flatMap(_.objectEncoding(key).futureLift.map(Option(_)))

  override def objectFreq(key: K): F[Long] =
    async.flatMap(_.objectFreq(key).futureLift.map(x => Long.box(x)))

  override def objectRefcount(key: K): F[Long] =
    async.flatMap(_.objectRefcount(key).futureLift.map(x => Long.box(x)))

  private def toFiniteDuration(units: TimeUnit)(duration: java.lang.Long): Option[FiniteDuration] =
    duration match {
      case d if d < 0 => none[FiniteDuration]
      case d          => FiniteDuration(d, units).some
    }

  private def toEpoch(duration: java.lang.Long): Option[Instant] =
    duration match {
      case d if d < 0 => none[Instant]
      case d          => Instant.ofEpochMilli(d).some
    }

  // EXPIRETIME (unlike PEXPIRETIME/HPEXPIRETIME) returns whole seconds since the epoch, not millis.
  private def toEpochSeconds(duration: java.lang.Long): Option[Instant] =
    duration match {
      case d if d < 0 => none[Instant]
      case d          => Instant.ofEpochSecond(d).some
    }

  override def persist(key: K): F[Boolean] =
    async.flatMap(_.persist(key).futureLift.map(x => Boolean.box(x)))

  override def pttl(key: K): F[Option[FiniteDuration]] =
    async.flatMap(_.pttl(key).futureLift.map(toFiniteDuration(TimeUnit.MILLISECONDS)))

  override def randomKey: F[Option[K]] =
    async.flatMap(_.randomkey().futureLift.map(Option(_)))

  override def rename(key: K, newKey: K): F[Unit] =
    async.flatMap(_.rename(key, newKey).futureLift.void)

  override def renameNx(key: K, newKey: K): F[Boolean] =
    async.flatMap(_.renamenx(key, newKey).futureLift.map(x => Boolean.box(x)))

  override def restore(key: K, value: Array[Byte]): F[Unit] =
    async.flatMap(_.restore(key, 0, value).futureLift.void)

  override def restore(key: K, value: Array[Byte], restoreArgs: RestoreArgs): F[Unit] =
    async.flatMap(_.restore(key, value, restoreArgs.asJava).futureLift.void)

  override def scan: F[KeyScanCursor[K]] =
    async.flatMap(_.scan().futureLift.map(KeyScanCursor[K]))

  override def scan(cursor: Long): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(JScanCursor.of(cursor.toString)).futureLift.map(KeyScanCursor[K]))

  override def scan(previous: KeyScanCursor[K]): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(previous.underlying).futureLift.map(KeyScanCursor[K]))

  override def scan(scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(scanArgs.underlying).futureLift.map(KeyScanCursor[K]))

  override def scan(cursor: Long, scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(JScanCursor.of(cursor.toString), scanArgs.underlying).futureLift.map(KeyScanCursor[K]))

  override def scan(previous: KeyScanCursor[K], scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(previous.underlying, scanArgs.underlying).futureLift.map(KeyScanCursor[K]))

  override def scan(keyScanArgs: KeyScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(keyScanArgs.underlying).futureLift.map(KeyScanCursor[K]))

  override def scan(cursor: KeyScanCursor[K], keyScanArgs: KeyScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.scan(cursor.underlying, keyScanArgs.underlying).futureLift.map(KeyScanCursor[K]))

  private def toSortArgs(sortArgs: SortArgs): JSortArgs = {
    val jArgs = new JSortArgs()
    sortArgs.by.foreach(jArgs.by)
    sortArgs.limit.foreach(l => jArgs.limit(JLimit.create(l.offset, l.count)))
    sortArgs.get.foreach(jArgs.get)
    sortArgs.order.foreach {
      case SortOrder.Asc  => jArgs.asc()
      case SortOrder.Desc => jArgs.desc()
    }
    if (sortArgs.alpha) jArgs.alpha(): Unit
    jArgs
  }

  override def sort(key: K): F[List[V]] =
    async.flatMap(_.sort(key).futureLift.map(_.asScala.toList))

  override def sort(key: K, sortArgs: SortArgs): F[List[V]] =
    async.flatMap(_.sort(key, toSortArgs(sortArgs)).futureLift.map(_.asScala.toList))

  override def sortReadOnly(key: K): F[List[V]] =
    async.flatMap(_.sortReadOnly(key).futureLift.map(_.asScala.toList))

  override def sortReadOnly(key: K, sortArgs: SortArgs): F[List[V]] =
    async.flatMap(_.sortReadOnly(key, toSortArgs(sortArgs)).futureLift.map(_.asScala.toList))

  override def sortStore(key: K, sortArgs: SortArgs, destination: K): F[Long] =
    async.flatMap(_.sortStore(key, toSortArgs(sortArgs), destination).futureLift.map(x => Long.box(x)))

  override def ttl(key: K): F[Option[FiniteDuration]] =
    async.flatMap(_.ttl(key).futureLift.map(toFiniteDuration(TimeUnit.SECONDS)))

  override def typeOf(key: K): F[Option[RedisType]] =
    async.flatMap(_.`type`(key).futureLift.map(RedisType.fromString))

  override def unlink(key: K*): F[Long] =
    async.flatMap(_.unlink(key: _*).futureLift.map(x => Long.box(x)))

  override def expireTime(key: K): F[Option[Instant]] =
    async.flatMap(_.expiretime(key).futureLift.map(toEpochSeconds))

  override def pExpireTime(key: K): F[Option[Instant]] =
    async.flatMap(_.pexpiretime(key).futureLift.map(toEpoch))

  override def move(key: K, db: Int): F[Boolean] =
    async.flatMap(_.move(key, db).futureLift.map(x => Boolean.box(x)))

  override def migrate(host: String, port: Int, key: K, destinationDb: Int, timeout: FiniteDuration): F[Boolean] =
    async.flatMap(_.migrate(host, port, key, destinationDb, timeout.toMillis).futureLift.map(_ != "NOKEY"))

  override def migrate(
      host: String,
      port: Int,
      destinationDb: Int,
      timeout: FiniteDuration,
      args: MigrateArgs[K]
  ): F[Boolean] =
    async
      .flatMap(_.migrate(host, port, destinationDb, timeout.toMillis, toJMigrateArgs(args)).futureLift)
      .map(_ != "NOKEY")

  private def toJMigrateArgs(args: MigrateArgs[K]): JMigrateArgs[K] = {
    val jArgs = new JMigrateArgs[K]()
    if (args.keys.nonEmpty) jArgs.keys(args.keys.asJava): Unit
    if (args.keepSource) jArgs.copy(): Unit
    if (args.replace) jArgs.replace(): Unit
    args.auth.foreach {
      case MigrateAuth.Password(password)                   => jArgs.auth(password): Unit
      case MigrateAuth.UsernamePassword(username, password) => jArgs.auth2(username, password): Unit
    }
    jArgs
  }

  override def touch(key: K, keys: K*): F[Long] =
    async.flatMap(_.touch((key +: keys): _*).futureLift.map(x => Long.box(x)))

  // format: off
  /******************************* Transactions API **********************************/
  // format: on
  // When in a cluster, transactions should run against a single node.

  def multi: F[Unit] =
    async.flatMap {
      case c: RedisAsyncCommands[K, V] => c.multi().futureLift.void
      case _                           => conn.async.flatMap(_.multi().futureLift).void
    }

  def exec: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => c.exec().futureLift
        case _                           => conn.async.flatMap(_.exec().futureLift)
      }
      .flatMap {
        case res if res.wasDiscarded() || res.isEmpty() => TransactionDiscarded.raiseError
        case _                                          => Applicative[F].unit
      }

  def discard: F[Unit] =
    async.flatMap {
      case c: RedisAsyncCommands[K, V] => c.discard().futureLift.void
      case _                           => conn.async.flatMap(_.discard().futureLift).void
    }

  def watch(keys: K*): F[Unit] =
    async.flatMap {
      case c: RedisAsyncCommands[K, V] => c.watch(keys: _*).futureLift.void
      case _                           => conn.async.flatMap(_.watch(keys: _*).futureLift).void
    }

  def unwatch: F[Unit] =
    async.flatMap {
      case c: RedisAsyncCommands[K, V] => c.unwatch().futureLift.void
      case _                           => conn.async.flatMap(_.unwatch().futureLift).void
    }

  override def transact[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]] =
    tx.run[A](
      acquire = this.multi,
      release = this.exec,
      onError = this.discard
    )(fs)

  override def transact_(fs: List[F[Unit]]): F[Unit] =
    transact[Nothing](_ => fs).void

  override def pipeline[A](fs: TxStore[F, String, A] => List[F[Unit]]): F[Map[String, A]] =
    tx.run[A](
      acquire = this.disableAutoFlush,
      release = FutureLift[F].guarantee(this.flushCommands, this.enableAutoFlush),
      onError = ().pure[F]
    )(fs)

  override def pipeline_(fs: List[F[Unit]]): F[Unit] =
    pipeline[Nothing](_ => fs).void

  // format: off
  /******************************* AutoFlush API **********************************/
  // format: on
  override def enableAutoFlush: F[Unit] = conn.setAutoFlushCommands(true)

  override def disableAutoFlush: F[Unit] = conn.setAutoFlushCommands(false)

  override def flushCommands: F[Unit] = conn.flushCommands

  // format: off
  /******************************* Unsafe API **********************************/
  // format: on
  override def unsafe[A](f: RedisClusterAsyncCommands[K, V] => RedisFuture[A]): F[A] =
    async.flatMap(f(_).futureLift)

  override def unsafeSync[A](f: RedisClusterAsyncCommands[K, V] => A): F[A] =
    async.flatMap(cmd => FutureLift[F].delay(f(cmd)))

  // format: off
  /******************************* Strings API **********************************/
  // format: on
  override def append(key: K, value: V): F[Long] =
    async.flatMap(_.append(key, value).futureLift.map(x => Long.box(x)))

  override def getSet(key: K, value: V): F[Option[V]] =
    async.flatMap(_.setGet(key, value).futureLift.map(Option.apply))

  override def set(key: K, value: V): F[Unit] =
    async.flatMap(_.set(key, value).futureLift.void)

  override def set(key: K, value: V, setArgs: SetArgs): F[Boolean] = {
    val jSetArgs = new JSetArgs()

    setArgs.existence.foreach {
      case SetArg.Existence.Nx => jSetArgs.nx()
      case SetArg.Existence.Xx => jSetArgs.xx()
    }

    setArgs.ttl.foreach {
      case SetArg.Ttl.Px(d) => jSetArgs.px(d.toMillis)
      case SetArg.Ttl.Ex(d) => jSetArgs.ex(d.toSeconds)
      case SetArg.Ttl.Keep  => jSetArgs.keepttl()
    }

    async.flatMap(_.set(key, value, jSetArgs).futureLift.map(_.isSuccess))
  }

  override def setNx(key: K, value: V): F[Boolean] =
    async.flatMap(_.set(key, value, new JSetArgs().nx()).futureLift.map(_.isSuccess))

  override def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit] =
    expiresIn.unit match {
      case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS =>
        async.flatMap(_.set(key, value, new JSetArgs().px(expiresIn.toMillis)).futureLift.void)
      case _ =>
        async.flatMap(_.set(key, value, new JSetArgs().ex(expiresIn.toSeconds)).futureLift.void)
    }

  override def setRange(key: K, value: V, offset: Long): F[Long] =
    async.flatMap(_.setrange(key, offset, value).futureLift.map(x => Long.box(x)))

  override def decr(key: K): F[Long] =
    async.flatMap(_.decr(key).futureLift.map(x => Long.box(x)))

  override def decrBy(key: K, amount: Long): F[Long] =
    async.flatMap(_.decrby(key, amount).futureLift.map(x => Long.box(x)))

  override def incr(key: K): F[Long] =
    async.flatMap(_.incr(key).futureLift.map(x => Long.box(x)))

  override def incrBy(key: K, amount: Long): F[Long] =
    async.flatMap(_.incrby(key, amount).futureLift.map(x => Long.box(x)))

  override def incrByFloat(key: K, amount: Double): F[Double] =
    async.flatMap(_.incrbyfloat(key, amount).futureLift.map(x => Double.box(x)))

  // ex/px/exAt/pxAt on IncrexArgs/IncrexFloatArgs take raw longs (seconds/millis/epoch), unlike
  // GetExArgs/HGetExArgs's Instant-accepting exAt/pxAt — a genuine difference in Lettuce's own API
  // shape between the two command families, not an inconsistency on our side.
  private def toJIncrexArgs(args: IncrexArgs): JIncrexArgs = {
    val jArgs = new JIncrexArgs()
    args.lowerBound.foreach(jArgs.lbound)
    args.upperBound.foreach(jArgs.ubound)
    if (args.saturate) jArgs.saturate(): Unit
    args.ttl.foreach {
      case IncrexTtl.Ex(d)    => jArgs.ex(d.toSeconds)
      case IncrexTtl.Px(d)    => jArgs.px(d.toMillis)
      case IncrexTtl.ExAt(at) => jArgs.exAt(at.getEpochSecond)
      case IncrexTtl.PxAt(at) => jArgs.pxAt(at.toEpochMilli)
      case IncrexTtl.Persist  => jArgs.persist()
    }
    if (args.ttlOnlyIfNoneSet) jArgs.enx(): Unit
    jArgs
  }

  private def toJIncrexFloatArgs(args: IncrexFloatArgs): JIncrexFloatArgs = {
    val jArgs = new JIncrexFloatArgs()
    args.lowerBound.foreach(jArgs.lbound)
    args.upperBound.foreach(jArgs.ubound)
    if (args.saturate) jArgs.saturate(): Unit
    args.ttl.foreach {
      case IncrexTtl.Ex(d)    => jArgs.ex(d.toSeconds)
      case IncrexTtl.Px(d)    => jArgs.px(d.toMillis)
      case IncrexTtl.ExAt(at) => jArgs.exAt(at.getEpochSecond)
      case IncrexTtl.PxAt(at) => jArgs.pxAt(at.toEpochMilli)
      case IncrexTtl.Persist  => jArgs.persist()
    }
    if (args.ttlOnlyIfNoneSet) jArgs.enx(): Unit
    jArgs
  }

  override def incrEx(key: K): F[IncrexResult[Long]] =
    async.flatMap(_.increx(key).futureLift.map(v => IncrexResult(v.getValue.longValue(), v.getIncrement.longValue())))

  override def incrEx(key: K, amount: Long, args: IncrexArgs): F[IncrexResult[Long]] =
    async.flatMap(
      _.increx(key, amount, toJIncrexArgs(args)).futureLift
        .map(v => IncrexResult(v.getValue.longValue(), v.getIncrement.longValue()))
    )

  override def incrExFloat(key: K, amount: Double, args: IncrexFloatArgs): F[IncrexResult[Double]] =
    async.flatMap(
      _.increx(key, amount, toJIncrexFloatArgs(args)).futureLift
        .map(v => IncrexResult(v.getValue.doubleValue(), v.getIncrement.doubleValue()))
    )

  override def get(key: K): F[Option[V]] =
    async.flatMap(_.get(key).futureLift.map(Option.apply))

  override def getDel(key: K): F[Option[V]] =
    async.flatMap(_.getdel(key).futureLift.map(Option.apply))

  override def getEx(key: K, getExArg: GetExArg): F[Option[V]] = {
    val jgetExArgs = new JGetExArgs()

    getExArg match {
      case GetExArg.Ex(d)    => jgetExArgs.ex(d.toSeconds)
      case GetExArg.Px(d)    => jgetExArgs.ex(d.toMillis)
      case GetExArg.ExAt(at) => jgetExArgs.exAt(at)
      case GetExArg.PxAt(at) => jgetExArgs.pxAt(at)
      case GetExArg.Persist  => jgetExArgs.persist()
    }

    async.flatMap(_.getex(key, jgetExArgs).futureLift.map(Option.apply))
  }

  override def getRange(key: K, start: Long, end: Long): F[Option[V]] =
    async.flatMap(_.getrange(key, start, end).futureLift.map(Option.apply))

  override def strLen(key: K): F[Long] =
    async.flatMap(_.strlen(key).futureLift.map(x => Long.unbox(x)))

  private def toLcsMatch(withMatchLen: Boolean)(m: JStringMatchResult.MatchedPosition): LcsMatch =
    LcsMatch(
      LcsMatchPosition(m.getA.getStart, m.getA.getEnd),
      LcsMatchPosition(m.getB.getStart, m.getB.getEnd),
      // matchLen is a Java primitive long (always 0 when WITHMATCHLEN wasn't requested) rather than a
      // nullable field, so — same as GeoSearchResult — Option-ness is decided from what was actually
      // requested, not inferred from the value.
      if (withMatchLen) Some(m.getMatchLen) else None
    )

  // Redis's plain (non-LEN, non-IDX) LCS reply is just the matched string - no length field at all
  // on the wire - so Lettuce's StringMatchResult.getLen() defaults to 0 in that mode, not the actual
  // length. LEN/IDX replies do carry a real length. isIdx tells us which reply shape we're decoding;
  // the plain case derives len from the matched string we do have, rather than trusting an unset 0.
  private def toLcsResult(isIdx: Boolean, withMatchLen: Boolean)(r: JStringMatchResult): LcsResult = {
    val matchString = Option(r.getMatchString)
    // Redis counts LCS length in bytes (matching lcsLen's raw integer reply), not in UTF-16 code
    // units - re-encoding via UTF-8 keeps the plain (non-IDX) case's derived length consistent with
    // lcsLen's for any non-ASCII match, since String#length() alone would undercount those.
    val len =
      if (isIdx) r.getLen else matchString.fold(0L)(_.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong)
    LcsResult(matchString, r.getMatches.asScala.toList.map(toLcsMatch(withMatchLen)), len)
  }

  // Lettuce's LcsArgs.Builder.keys(String...) takes raw key names rather than K-encoded values (it
  // calls CommandArgs.add(String), not addKey(K)) — a Lettuce API limitation, not a redis4cats one.
  // key.toString only produces the correct Redis key when K's toString matches its actual encoded
  // text, which holds for the common String/UTF8 codec but isn't guaranteed for an arbitrary K.
  override def lcs(key1: K, key2: K): F[LcsResult] =
    async.flatMap(
      _.lcs(JLcsArgs.Builder.keys(key1.toString, key2.toString)).futureLift
        .map(toLcsResult(isIdx = false, withMatchLen = false))
    )

  override def lcsLen(key1: K, key2: K): F[Long] =
    async.flatMap(_.lcs(JLcsArgs.Builder.keys(key1.toString, key2.toString).justLen()).futureLift.map(_.getLen))

  override def lcsIdx(key1: K, key2: K, minMatchLen: Option[Int], withMatchLen: Boolean): F[LcsResult] = {
    val jArgs = JLcsArgs.Builder.keys(key1.toString, key2.toString).withIdx()
    minMatchLen.foreach(jArgs.minMatchLen)
    if (withMatchLen) jArgs.withMatchLen(): Unit
    async.flatMap(_.lcs(jArgs).futureLift.map(toLcsResult(isIdx = true, withMatchLen)))
  }

  override def mGet(keys: Set[K]): F[Map[K, V]] =
    async
      .flatMap(_.mget(keys.toSeq: _*).futureLift)
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def mSet(keyValues: Map[K, V]): F[Unit] =
    async.flatMap(_.mset(keyValues.asJava).futureLift.void)

  override def mSetNx(keyValues: Map[K, V]): F[Boolean] =
    async.flatMap(_.msetnx(keyValues.asJava).futureLift.map(x => Boolean.box(x)))

  override def msetEx(keyValues: Map[K, V], args: MSetExArgs): F[Boolean] = {
    val jArgs = new JMSetExArgs()

    args.ttl.foreach {
      case MSetExTtl.Ex(d)    => jArgs.ex(java.time.Duration.ofMillis(d.toMillis))
      case MSetExTtl.Px(d)    => jArgs.px(java.time.Duration.ofMillis(d.toMillis))
      case MSetExTtl.ExAt(at) => jArgs.exAt(at)
      case MSetExTtl.PxAt(at) => jArgs.pxAt(at)
      case MSetExTtl.KeepTtl  => jArgs.keepttl()
    }
    args.existence.foreach {
      case SetArg.Existence.Nx => jArgs.nx()
      case SetArg.Existence.Xx => jArgs.xx()
    }

    async.flatMap(_.msetex(keyValues.asJava, jArgs).futureLift.map(x => Boolean.box(x)))
  }

  /** ***************************** JSON API *********************************
    */
  override def jsonType(key: K, path: JsonPath): F[List[JsonType]] =
    async.flatMap(_.jsonType(key, path).futureLift.map(_.asScala.toList))

  override def jsonType(key: K): F[List[JsonType]] = async.flatMap(_.jsonType(key).futureLift.map(_.asScala.toList))

  override def jClear(key: K, path: JsonPath): F[Long] =
    async.flatMap(_.jsonClear(key, path).futureLift.map(x => Long.box(x)))

  override def jClear(key: K): F[Long] =
    async.flatMap(_.jsonClear(key).futureLift.map(x => Long.box(x)))

  override def jDel(key: K, path: JsonPath): F[Long] =
    async.flatMap(_.jsonDel(key, path).futureLift.map(x => Long.box(x)))

  override def jDel(key: K): F[Long] = async.flatMap(_.jsonDel(key).futureLift.map(x => Long.box(x)))

  /** * JSON GETTERS **
    */
  override def jGet(key: K, path: JsonPath, paths: JsonPath*): F[List[JsonValue]] = {
    val all = path +: paths
    async.flatMap(_.jsonGet(key, all: _*).futureLift.map(_.asScala.toList))
  }

  override def jGet(key: K, arg: json.JsonGetArgs, path: JsonPath, paths: JsonPath*): F[List[JsonValue]] = {
    val all     = path +: paths
    val options = arg.underlying
    async.flatMap(_.jsonGet(key, options, all: _*).futureLift.map(_.asScala.toList))
  }

  override def jMget(path: JsonPath, key: K, keys: K*): F[List[JsonValue]] = {
    val all = key +: keys
    async.flatMap(_.jsonMGet(path, all: _*).futureLift.map(_.asScala.toList))
  }

  override def jObjKeys(key: K, path: JsonPath): F[List[V]] =
    async.flatMap(_.jsonObjkeys(key, path).futureLift.map(_.asScala.toList))

  override def jObjLen(key: K, path: JsonPath): F[Long] =
    async.flatMap(_.jsonObjlen(key, path).futureLift.map(x => Long.unbox(x)))

  /** * JSON ARRAY **
    */
  override def arrAppend(key: K, path: JsonPath, value: JsonValue*): F[List[Long]] =
    async.flatMap(_.jsonArrappend(key, path, value: _*).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrAppend(key: K, value: JsonValue*): F[List[Long]] =
    async.flatMap(_.jsonArrappend(key, value: _*).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrAppendStr(key: K, path: JsonPath, jsonStrings: String*): F[List[Long]] =
    async.flatMap(_.jsonArrappend(key, path, jsonStrings: _*).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrAppendStr(key: K, jsonStrings: String*): F[List[Long]] =
    async.flatMap(_.jsonArrappend(key, jsonStrings: _*).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrIndex(key: K, path: JsonPath, value: JsonValue, range: JsonRangeArgs): F[List[Long]] =
    async.flatMap(_.jsonArrindex(key, path, value, range).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrIndex(key: K, path: JsonPath, value: JsonValue): F[List[Long]] =
    async.flatMap(_.jsonArrindex(key, path, value).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrIndexStr(key: K, path: JsonPath, jsonString: String): F[List[Long]] =
    async.flatMap(_.jsonArrindex(key, path, jsonString).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrIndexStr(key: K, path: JsonPath, jsonString: String, range: JsonRangeArgs): F[List[Long]] =
    async.flatMap(_.jsonArrindex(key, path, jsonString, range).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrInsert(key: K, path: JsonPath, index: Int, value: JsonValue*): F[List[Long]] =
    async.flatMap(_.jsonArrinsert(key, path, index, value: _*).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrInsertStr(key: K, path: JsonPath, index: Int, jsonStrings: String*): F[List[Long]] =
    async.flatMap(
      _.jsonArrinsert(key, path, index, jsonStrings: _*).futureLift.map(_.asScala.toList.map(Long.unbox(_)))
    )

  override def arrLen(key: K, path: JsonPath): F[List[Long]] =
    async.flatMap(_.jsonArrlen(key, path).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrLen(key: K): F[List[Long]] =
    async.flatMap(_.jsonArrlen(key).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def arrPop(key: K, path: JsonPath, index: Int): F[List[JsonValue]] =
    async.flatMap(_.jsonArrpop(key, path, index).futureLift.map(_.asScala.toList))

  override def arrPop(key: K, path: JsonPath): F[List[JsonValue]] =
    async.flatMap(_.jsonArrpop(key, path).futureLift.map(_.asScala.toList))

  override def arrPop(key: K): F[List[JsonValue]] =
    async.flatMap(_.jsonArrpop(key).futureLift.map(_.asScala.toList))

  override def arrTrim(key: K, path: JsonPath, range: JsonRangeArgs): F[List[Long]] =
    async.flatMap(_.jsonArrtrim(key, path, range).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def toggle(key: K, path: JsonPath): F[List[Long]] =
    async.flatMap(_.jsonToggle(key, path).futureLift.map(_.asScala.toList.map(Long.unbox(_))))

  override def numIncrBy(key: K, path: JsonPath, number: Number): F[List[Number]] =
    async.flatMap(_.jsonNumincrby(key, path, number).futureLift.map(_.asScala.toList))

  override def jMset(key: K, values: (JsonPath, JsonValue)*): F[Boolean] = {
    val jValues: util.List[JsonMsetArgs[K, V]] =
      values
        .map { case (path, value) => new JsonMsetArgs(key, path, value) }
        .asJava
        .asInstanceOf[util.List[JsonMsetArgs[K, V]]]
    async.flatMap(_.jsonMSet(jValues).futureLift.map(_.isSuccess))
  }

  override def jSet(key: K, path: JsonPath, value: JsonValue): F[Boolean] =
    async.flatMap(_.jsonSet(key, path, value).futureLift).map(Option(_).exists(_.isSuccess))

  override def jSet(key: K, path: JsonPath, value: JsonValue, args: JsonSetArgs): F[Boolean] =
    async.flatMap(_.jsonSet(key, path, value, args).futureLift.map(_.isSuccess))

  override def jSetStr(key: K, path: JsonPath, jsonString: String): F[Boolean] =
    async.flatMap(_.jsonSet(key, path, jsonString).futureLift.map(_.isSuccess))

  override def jSetStr(key: K, path: JsonPath, jsonString: String, args: JsonSetArgs): F[Boolean] =
    async.flatMap(_.jsonSet(key, path, jsonString, args).futureLift.map(_.isSuccess))

  override def jSetnx(key: K, path: JsonPath, value: JsonValue): F[Boolean] =
    async.flatMap(_.jsonSet(key, path, value, new JsonSetArgs().nx()).futureLift.map(_.isSuccess))

  override def jSetxx(key: K, path: JsonPath, value: JsonValue): F[Boolean] =
    async.flatMap(_.jsonSet(key, path, value, new JsonSetArgs().xx()).futureLift.map(_.isSuccess))

  override def jsonMerge(key: K, jsonPath: JsonPath, value: JsonValue): F[String] =
    async.flatMap(_.jsonMerge(key, jsonPath, value).futureLift)

  override def jsonMergeStr(key: K, jsonPath: JsonPath, jsonString: String): F[String] =
    async.flatMap(_.jsonMerge(key, jsonPath, jsonString).futureLift)

  override def strAppend(key: K, path: JsonPath, value: JsonValue): F[List[Long]] =
    async.flatMap(_.jsonStrappend(key, path, value).futureLift.map(_.asScala.toList.map(x => Long.unbox(x))))

  override def strAppend(key: K, value: JsonValue): F[List[Long]] =
    async.flatMap(_.jsonStrappend(key, value).futureLift.map(_.asScala.toList.map(x => Long.unbox(x))))

  override def strAppendStr(key: K, path: JsonPath, jsonString: String): F[List[Long]] =
    async.flatMap(_.jsonStrappend(key, path, jsonString).futureLift.map(_.asScala.toList.map(x => Long.unbox(x))))

  override def strAppendStr(key: K, jsonString: String): F[List[Long]] =
    async.flatMap(_.jsonStrappend(key, jsonString).futureLift.map(_.asScala.toList.map(x => Long.unbox(x))))

  override def jsonStrLen(key: K, path: JsonPath): F[List[Long]] =
    async.flatMap(_.jsonStrlen(key, path).futureLift.map(_.asScala.toList.map(x => Long.unbox(x))))

  override def jsonStrLen(key: K): F[List[Long]] =
    async.flatMap(_.jsonStrlen(key).futureLift.map(_.asScala.toList.map(x => Long.unbox(x))))

  // format: off
  /******************************* Hashes API **********************************/
  // format: on
  override def hDel(key: K, field: K, fields: K*): F[Long] =
    async.flatMap(_.hdel(key, (field +: fields): _*).futureLift.map(x => Long.box(x)))

  override def hGetDel(key: K, field: K, fields: K*): F[List[Option[V]]] =
    async.flatMap(
      _.hgetdel(key, (field +: fields): _*).futureLift.map(
        _.asScala.toList
          .map(kv => Option.apply(kv.getValue()))
      )
    )

  override def hExists(key: K, field: K): F[Boolean] =
    async.flatMap(_.hexists(key, field).futureLift.map(x => Boolean.box(x)))

  override def hGet(key: K, field: K): F[Option[V]] =
    async.flatMap(_.hget(key, field).futureLift.map(Option.apply))

  override def hGetEx(key: K, getExArg: HGetExArgs, field: K, fields: K*): F[List[Option[V]]] = {
    val jgetExArgs = new JHGetExArgs()

    getExArg match {
      case HGetExArgs.Ex(d)    => jgetExArgs.ex(java.time.Duration.ofMillis(d.toMillis))
      case HGetExArgs.Px(d)    => jgetExArgs.px(java.time.Duration.ofMillis(d.toMillis))
      case HGetExArgs.ExAt(at) => jgetExArgs.exAt(at)
      case HGetExArgs.PxAt(at) => jgetExArgs.pxAt(at)
      case HGetExArgs.Persist  => jgetExArgs.persist()
    }

    async.flatMap(
      _.hgetex(key, jgetExArgs, (field +: fields): _*).futureLift
        .map(_.asScala.toList.map(kv => Option.apply(kv.getValue())))
    )
  }

  override def hGetAll(key: K): F[Map[K, V]] =
    async.flatMap(_.hgetall(key).futureLift.map(_.asScala.toMap))

  override def hmGet(key: K, field: K, fields: K*): F[Map[K, V]] =
    async
      .flatMap(_.hmget(key, (field +: fields): _*).futureLift)
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def hKeys(key: K): F[List[K]] =
    async.flatMap(_.hkeys(key).futureLift.map(_.asScala.toList))

  override def hVals(key: K): F[List[V]] =
    async.flatMap(_.hvals(key).futureLift.map(_.asScala.toList))

  override def hStrLen(key: K, field: K): F[Long] =
    async.flatMap(_.hstrlen(key, field).futureLift.map(x => Long.unbox(x)))

  override def hLen(key: K): F[Long] =
    async.flatMap(_.hlen(key).futureLift.map(x => Long.unbox(x)))

  override def hScan(key: K): F[MapScanCursor[K, V]] =
    async.flatMap(_.hscan(key).futureLift.map(MapScanCursor[K, V](_)))

  override def hScan(key: K, cursor: MapScanCursor[K, V]): F[MapScanCursor[K, V]] =
    async.flatMap(_.hscan(key, cursor.underlying).futureLift.map(MapScanCursor[K, V](_)))

  override def hScan(key: K, scanArgs: ScanArgs): F[MapScanCursor[K, V]] =
    async.flatMap(_.hscan(key, scanArgs.underlying).futureLift.map(MapScanCursor[K, V](_)))

  override def hScan(key: K, cursor: MapScanCursor[K, V], scanArgs: ScanArgs): F[MapScanCursor[K, V]] =
    async.flatMap(_.hscan(key, cursor.underlying, scanArgs.underlying).futureLift.map(MapScanCursor[K, V](_)))

  override def hScanNoValues(key: K): F[KeyScanCursor[K]] =
    async.flatMap(_.hscanNovalues(key).futureLift.map(KeyScanCursor[K](_)))

  override def hScanNoValues(key: K, cursor: KeyScanCursor[K]): F[KeyScanCursor[K]] =
    async.flatMap(_.hscanNovalues(key, cursor.underlying).futureLift.map(KeyScanCursor[K](_)))

  override def hScanNoValues(key: K, scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.hscanNovalues(key, scanArgs.underlying).futureLift.map(KeyScanCursor[K](_)))

  override def hScanNoValues(key: K, cursor: KeyScanCursor[K], scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async.flatMap(_.hscanNovalues(key, cursor.underlying, scanArgs.underlying).futureLift.map(KeyScanCursor[K](_)))

  override def hRandField(key: K): F[Option[K]] =
    async.flatMap(_.hrandfield(key).futureLift.map(Option.apply))

  override def hRandField(key: K, count: Long): F[List[K]] =
    async.flatMap(_.hrandfield(key, count).futureLift.map(_.asScala.toList))

  override def hRandFieldWithValues(key: K): F[Option[(K, V)]] =
    async.flatMap(_.hrandfieldWithvalues(key).futureLift.map(kv => Option(kv).map(kv => kv.getKey -> kv.getValue)))

  override def hRandFieldWithValues(key: K, count: Long): F[List[(K, V)]] =
    async.flatMap(
      _.hrandfieldWithvalues(key, count).futureLift.map(_.asScala.toList.map(kv => kv.getKey -> kv.getValue))
    )

  override def hSet(key: K, field: K, value: V): F[Boolean] =
    async.flatMap(_.hset(key, field, value).futureLift.map(x => Boolean.box(x)))

  override def hSet(key: K, fieldValues: Map[K, V]): F[Long] =
    async.flatMap(_.hset(key, fieldValues.asJava).futureLift.map(x => Long.box(x)))

  override def hExpire(key: K, expiresIn: FiniteDuration, fields: K*): F[List[Long]] =
    async
      .flatMap { c =>
        {
          expiresIn.unit match {
            case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS =>
              c.hpexpire(key, expiresIn.toMillis, fields: _*)
            case _ => c.hexpire(key, expiresIn.toSeconds, fields: _*)
          }
        }.futureLift.map(_.asScala.map(x => Long.unbox(x)).toList)
      }

  override def hExpire(key: K, expire: FiniteDuration, args: ExpireExistenceArg, fields: K*): F[List[Long]] =
    async
      .flatMap { c =>
        {
          expire.unit match {
            case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS =>
              c.hpexpire(key, expire.toMillis, args.asJava, fields: _*)
            case _ => c.hexpire(key, expire.toSeconds, args.asJava, fields: _*)
          }
        }.futureLift.map(_.asScala.map(x => Long.unbox(x)).toList)
      }

  override def hExpireAt(key: K, expireAt: Instant, fields: K*): F[List[Long]] =
    async.flatMap(
      _.hpexpireat(key, expireAt.toEpochMilli(), fields: _*).futureLift.map(_.asScala.map(x => Long.unbox(x)).toList)
    )
  override def hExpireAt(key: K, expireAt: Instant, args: ExpireExistenceArg, fields: K*): F[List[Long]] =
    async.flatMap(
      _.hpexpireat(key, expireAt.toEpochMilli(), args.asJava, fields: _*).futureLift
        .map(_.asScala.map(x => Long.unbox(x)).toList)
    )

  // milli precision command under hood
  override def hExpireTime(key: K, fields: K*): F[List[Option[Instant]]] =
    async.flatMap(
      _.hpexpiretime(key, fields: _*).futureLift.map(_.asScala.map(toEpoch).toList)
    )

  override def hpExpireTime(key: K, fields: K*): F[List[Option[Instant]]] =
    async.flatMap(
      _.hpexpiretime(key, fields: _*).futureLift.map(_.asScala.map(toEpoch).toList)
    )

  override def hPersist(key: K, fields: K*): F[List[Boolean]] =
    async.flatMap(_.hpersist(key, fields: _*).futureLift.map(_.asScala.map(l => l == 1).toList))

  override def hpttl(key: K, fields: K*): F[List[Option[FiniteDuration]]] =
    async.flatMap(
      _.hpttl(key, fields: _*).futureLift.map(_.asScala.map(toFiniteDuration(TimeUnit.MILLISECONDS)).toList)
    )

  override def httl(key: K, fields: K*): F[List[Option[FiniteDuration]]] =
    async.flatMap(_.httl(key, fields: _*).futureLift.map(_.asScala.map(toFiniteDuration(TimeUnit.SECONDS)).toList))

  override def hSetNx(key: K, field: K, value: V): F[Boolean] =
    async.flatMap(_.hsetnx(key, field, value).futureLift.map(x => Boolean.box(x)))

  override def hSetEx(key: K, fieldValues: Map[K, V]): F[Long] =
    async.flatMap(_.hsetex(key, fieldValues.asJava).futureLift.map(x => Long.box(x)))

  override def hSetEx(key: K, args: HSetExArgs, fieldValues: Map[K, V]): F[Long] = {
    val jArgs = new JHSetExArgs()

    args.existence.foreach {
      case HSetExArg.Existence.Nx => jArgs.fnx()
      case HSetExArg.Existence.Xx => jArgs.fxx()
    }

    args.ttl.foreach {
      case HSetExArg.Ttl.Ex(d)    => jArgs.ex(java.time.Duration.ofMillis(d.toMillis))
      case HSetExArg.Ttl.Px(d)    => jArgs.px(java.time.Duration.ofMillis(d.toMillis))
      case HSetExArg.Ttl.ExAt(at) => jArgs.exAt(at)
      case HSetExArg.Ttl.PxAt(at) => jArgs.pxAt(at)
      case HSetExArg.Ttl.Keep     => jArgs.keepttl()
    }

    async.flatMap(_.hsetex(key, jArgs, fieldValues.asJava).futureLift.map(x => Long.box(x)))
  }

  override def hIncrBy(key: K, field: K, amount: Long): F[Long] =
    async.flatMap(_.hincrby(key, field, amount).futureLift.map(x => Long.box(x)))

  override def hIncrByFloat(key: K, field: K, amount: Double): F[Double] =
    async.flatMap(_.hincrbyfloat(key, field, amount).futureLift.map(x => Double.box(x)))

  // format: off
  /******************************* Sets API **********************************/
  // format: on
  override def sIsMember(key: K, value: V): F[Boolean] =
    async.flatMap(_.sismember(key, value).futureLift.map(x => Boolean.box(x)))

  override def sMisMember(key: K, values: V*): F[List[Boolean]] =
    async.flatMap(_.smismember(key, values: _*).futureLift.map(_.asScala.map(Boolean.unbox(_)).toList))

  override def sAdd(key: K, values: V*): F[Long] =
    async.flatMap(_.sadd(key, values: _*).futureLift.map(x => Long.box(x)))

  override def sDiffStore(destination: K, keys: K*): F[Long] =
    async.flatMap(_.sdiffstore(destination, keys: _*).futureLift.map(x => Long.box(x)))

  override def sInterStore(destination: K, keys: K*): F[Long] =
    async.flatMap(_.sinterstore(destination, keys: _*).futureLift.map(x => Long.box(x)))

  override def sMove(source: K, destination: K, value: V): F[Boolean] =
    async.flatMap(_.smove(source, destination, value).futureLift.map(x => Boolean.box(x)))

  override def sPop(key: K): F[Option[V]] =
    async.flatMap(_.spop(key).futureLift.map(Option.apply))

  override def sPop(key: K, count: Long): F[Set[V]] =
    async.flatMap(_.spop(key, count).futureLift.map(_.asScala.toSet))

  override def sRem(key: K, values: V*): F[Long] =
    async.flatMap(_.srem(key, values: _*).futureLift.map(x => Long.box(x)))

  override def sCard(key: K): F[Long] =
    async.flatMap(_.scard(key).futureLift.map(x => Long.box(x)))

  override def sDiff(keys: K*): F[Set[V]] =
    async.flatMap(_.sdiff(keys: _*).futureLift.map(_.asScala.toSet))

  override def sDiffCard(keys: K*): F[Long] =
    async.flatMap(_.sdiffcard(keys.asJava).futureLift.map(x => Long.box(x)))

  override def sDiffCard(limit: Long, keys: K*): F[Long] =
    async.flatMap(_.sdiffcard(keys.asJava, new SDiffCardArgs().limit(limit)).futureLift.map(x => Long.box(x)))

  override def sInter(keys: K*): F[Set[V]] =
    async.flatMap(_.sinter(keys: _*).futureLift.map(_.asScala.toSet))

  override def sInterCard(keys: K*): F[Long] =
    async.flatMap(_.sintercard(keys: _*).futureLift.map(x => Long.box(x)))

  override def sInterCard(limit: Long, keys: K*): F[Long] =
    async.flatMap(_.sintercard(limit, keys: _*).futureLift.map(x => Long.box(x)))

  override def sMembers(key: K): F[Set[V]] =
    async.flatMap(_.smembers(key).futureLift.map(_.asScala.toSet))

  override def sRandMember(key: K): F[Option[V]] =
    async.flatMap(_.srandmember(key).futureLift.map(Option.apply))

  override def sRandMember(key: K, count: Long): F[List[V]] =
    async.flatMap(_.srandmember(key, count).futureLift.map(_.asScala.toList))

  override def sUnion(keys: K*): F[Set[V]] =
    async.flatMap(_.sunion(keys: _*).futureLift.map(_.asScala.toSet))

  override def sUnionCard(keys: K*): F[Long] =
    async.flatMap(_.sunioncard(keys.asJava).futureLift.map(x => Long.box(x)))

  override def sUnionCard(limit: Long, keys: K*): F[Long] =
    async.flatMap(_.sunioncard(keys.asJava, new SUnionCardArgs().limit(limit)).futureLift.map(x => Long.box(x)))

  override def sUnionStore(destination: K, keys: K*): F[Long] =
    async.flatMap(_.sunionstore(destination, keys: _*).futureLift.map(x => Long.box(x)))

  override def sScan(key: K): F[ValueScanCursor[V]] =
    async.flatMap(_.sscan(key).futureLift.map(ValueScanCursor[V]))

  override def sScan(key: K, cursor: ValueScanCursor[V]): F[ValueScanCursor[V]] =
    async.flatMap(_.sscan(key, cursor.underlying).futureLift.map(ValueScanCursor[V]))

  override def sScan(key: K, scanArgs: ScanArgs): F[ValueScanCursor[V]] =
    async.flatMap(_.sscan(key, scanArgs.underlying).futureLift.map(ValueScanCursor[V]))

  override def sScan(key: K, cursor: ValueScanCursor[V], scanArgs: ScanArgs): F[ValueScanCursor[V]] =
    async.flatMap(_.sscan(key, cursor.underlying, scanArgs.underlying).futureLift.map(ValueScanCursor[V]))

  // format: off
  /******************************* Lists API **********************************/
  // format: on
  override def lIndex(key: K, index: Long): F[Option[V]] =
    async.flatMap(_.lindex(key, index).futureLift.map(Option.apply))

  override def lLen(key: K): F[Long] =
    async.flatMap(_.llen(key).futureLift.map(x => Long.unbox(x)))

  override def lRange(key: K, start: Long, stop: Long): F[List[V]] =
    async.flatMap(_.lrange(key, start, stop).futureLift.map(_.asScala.toList))

  override def blPop(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, V)]] =
    async
      .flatMap(_.blpop(timeout.toSecondsOrZero, keys.toList: _*).futureLift)
      .map(Option(_).map(kv => kv.getKey -> kv.getValue))

  override def brPop(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, V)]] =
    async
      .flatMap(_.brpop(timeout.toSecondsOrZero, keys.toList: _*).futureLift)
      .map(Option(_).map(kv => kv.getKey -> kv.getValue))

  private def toLMoveArgs(sourceSide: LMoveSide, destinationSide: LMoveSide): LMoveArgs =
    (sourceSide, destinationSide) match {
      case (LMoveSide.Left, LMoveSide.Left)   => LMoveArgs.Builder.leftLeft()
      case (LMoveSide.Left, LMoveSide.Right)  => LMoveArgs.Builder.leftRight()
      case (LMoveSide.Right, LMoveSide.Left)  => LMoveArgs.Builder.rightLeft()
      case (LMoveSide.Right, LMoveSide.Right) => LMoveArgs.Builder.rightRight()
    }

  private def toLMovemArgs(sourceSide: LMoveSide, destinationSide: LMoveSide): LMovemArgs =
    (sourceSide, destinationSide) match {
      case (LMoveSide.Left, LMoveSide.Left)   => LMovemArgs.Builder.leftLeft()
      case (LMoveSide.Left, LMoveSide.Right)  => LMovemArgs.Builder.leftRight()
      case (LMoveSide.Right, LMoveSide.Left)  => LMovemArgs.Builder.rightLeft()
      case (LMoveSide.Right, LMoveSide.Right) => LMovemArgs.Builder.rightRight()
    }

  private def toJLMoveOrdering(ordering: LMoveOrdering): LMovemArgs.Ordering =
    ordering match {
      case LMoveOrdering.OneByOne => LMovemArgs.Ordering.OBO
      case LMoveOrdering.Bulk     => LMovemArgs.Ordering.BULK
    }

  private def applyLMoveCount(args: LMovemArgs, count: LMoveCount): LMovemArgs =
    count match {
      case LMoveCount.UpTo(n, ordering)    => args.count(n, toJLMoveOrdering(ordering))
      case LMoveCount.Exactly(n, ordering) => args.exactly(n, toJLMoveOrdering(ordering))
    }

  private def toBLMovemArgs(sourceSide: LMoveSide, destinationSide: LMoveSide): BLMovemArgs =
    (sourceSide, destinationSide) match {
      case (LMoveSide.Left, LMoveSide.Left)   => BLMovemArgs.Builder.leftLeft()
      case (LMoveSide.Left, LMoveSide.Right)  => BLMovemArgs.Builder.leftRight()
      case (LMoveSide.Right, LMoveSide.Left)  => BLMovemArgs.Builder.rightLeft()
      case (LMoveSide.Right, LMoveSide.Right) => BLMovemArgs.Builder.rightRight()
    }

  private def applyLMoveCount(args: BLMovemArgs, count: LMoveCount): BLMovemArgs =
    count match {
      case LMoveCount.UpTo(n, ordering)    => args.count(n, toJLMoveOrdering(ordering))
      case LMoveCount.Exactly(n, ordering) => args.exactly(n, toJLMoveOrdering(ordering))
    }

  private def toLMPopArgs(side: LMoveSide): LMPopArgs =
    side match {
      case LMoveSide.Left  => LMPopArgs.Builder.left()
      case LMoveSide.Right => LMPopArgs.Builder.right()
    }

  private def toJLPosArgs(args: LPosArgs): JLPosArgs = {
    val jArgs = JLPosArgs.Builder.empty()
    args.rank.foreach(jArgs.rank)
    args.maxLen.foreach(jArgs.maxlen)
    jArgs
  }

  override def brPopLPush(timeout: Duration, source: K, destination: K): F[Option[V]] =
    async.flatMap(
      _.blmove(source, destination, LMoveArgs.Builder.rightLeft(), timeout.toSecondsOrZero).futureLift.map(Option.apply)
    )

  override def blMove(
      timeout: Duration,
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide
  ): F[Option[V]] =
    async.flatMap(
      _.blmove(source, destination, toLMoveArgs(sourceSide, destinationSide), timeout.toSecondsOrZero).futureLift
        .map(Option.apply)
    )

  override def blMoveMany(
      timeout: Duration,
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide
  ): F[List[V]] =
    async.flatMap(
      _.blmovem(
        source,
        destination,
        toBLMovemArgs(sourceSide, destinationSide).timeout(timeout.toSecondsOrZero)
      ).futureLift
        .map(_.asScala.toList)
    )

  override def blMoveMany(
      timeout: Duration,
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide,
      count: LMoveCount
  ): F[List[V]] =
    async.flatMap(
      _.blmovem(
        source,
        destination,
        applyLMoveCount(toBLMovemArgs(sourceSide, destinationSide), count).timeout(timeout.toSecondsOrZero)
      ).futureLift.map(_.asScala.toList)
    )

  override def blmPop(timeout: Duration, keys: NonEmptyList[K], side: LMoveSide): F[Option[(K, List[V])]] =
    async
      .flatMap(_.blmpop(timeout.toSecondsOrZero, toLMPopArgs(side), keys.toList: _*).futureLift)
      .map(Option(_).map(kv => kv.getKey -> kv.getValue.asScala.toList))

  override def blmPop(
      timeout: Duration,
      keys: NonEmptyList[K],
      side: LMoveSide,
      count: Long
  ): F[Option[(K, List[V])]] =
    async
      .flatMap(_.blmpop(timeout.toSecondsOrZero, toLMPopArgs(side).count(count), keys.toList: _*).futureLift)
      .map(Option(_).map(kv => kv.getKey -> kv.getValue.asScala.toList))

  override def lPop(key: K): F[Option[V]] =
    async.flatMap(_.lpop(key).futureLift.map(Option.apply))

  override def lPop(key: K, count: Long): F[List[V]] =
    async.flatMap(_.lpop(key, count).futureLift.map(_.asScala.toList))

  override def lPush(key: K, values: V*): F[Long] =
    async.flatMap(_.lpush(key, values: _*).futureLift.map(x => Long.box(x)))

  override def lPushX(key: K, values: V*): F[Long] =
    async.flatMap(_.lpushx(key, values: _*).futureLift.map(x => Long.box(x)))

  override def rPop(key: K): F[Option[V]] =
    async.flatMap(_.rpop(key).futureLift.map(Option.apply))

  override def rPop(key: K, count: Long): F[List[V]] =
    async.flatMap(_.rpop(key, count).futureLift.map(_.asScala.toList))

  override def rPopLPush(source: K, destination: K): F[Option[V]] =
    async.flatMap(_.lmove(source, destination, LMoveArgs.Builder.rightLeft()).futureLift.map(Option.apply))

  override def lMove(source: K, destination: K, sourceSide: LMoveSide, destinationSide: LMoveSide): F[Option[V]] =
    async.flatMap(_.lmove(source, destination, toLMoveArgs(sourceSide, destinationSide)).futureLift.map(Option.apply))

  override def lMoveMany(
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide
  ): F[List[V]] =
    async.flatMap(
      _.lmovem(source, destination, toLMovemArgs(sourceSide, destinationSide)).futureLift.map(_.asScala.toList)
    )

  override def lMoveMany(
      source: K,
      destination: K,
      sourceSide: LMoveSide,
      destinationSide: LMoveSide,
      count: LMoveCount
  ): F[List[V]] =
    async.flatMap(
      _.lmovem(source, destination, applyLMoveCount(toLMovemArgs(sourceSide, destinationSide), count)).futureLift
        .map(_.asScala.toList)
    )

  override def lmPop(keys: NonEmptyList[K], side: LMoveSide): F[Option[(K, List[V])]] =
    async
      .flatMap(_.lmpop(toLMPopArgs(side), keys.toList: _*).futureLift)
      .map(Option(_).map(kv => kv.getKey -> kv.getValue.asScala.toList))

  override def lmPop(keys: NonEmptyList[K], side: LMoveSide, count: Long): F[Option[(K, List[V])]] =
    async
      .flatMap(_.lmpop(toLMPopArgs(side).count(count), keys.toList: _*).futureLift)
      .map(Option(_).map(kv => kv.getKey -> kv.getValue.asScala.toList))

  override def lPos(key: K, value: V): F[Option[Long]] =
    async.flatMap(_.lpos(key, value).futureLift.map(x => Option(x).map(Long.unbox)))

  override def lPos(key: K, value: V, args: LPosArgs): F[Option[Long]] =
    async.flatMap(_.lpos(key, value, toJLPosArgs(args)).futureLift.map(x => Option(x).map(Long.unbox)))

  override def lPos(key: K, value: V, count: Int): F[List[Long]] =
    async.flatMap(_.lpos(key, value, count).futureLift.map(_.asScala.toList.map(Long.unbox)))

  override def lPos(key: K, value: V, count: Int, args: LPosArgs): F[List[Long]] =
    async.flatMap(_.lpos(key, value, count, toJLPosArgs(args)).futureLift.map(_.asScala.toList.map(Long.unbox)))

  override def rPush(key: K, values: V*): F[Long] =
    async.flatMap(_.rpush(key, values: _*).futureLift.map(x => Long.box(x)))

  override def rPushX(key: K, values: V*): F[Long] =
    async.flatMap(_.rpushx(key, values: _*).futureLift.map(x => Long.box(x)))

  override def lInsertAfter(key: K, pivot: V, value: V): F[Long] =
    async.flatMap(_.linsert(key, false, pivot, value).futureLift.map(x => Long.box(x)))

  override def lInsertBefore(key: K, pivot: V, value: V): F[Long] =
    async.flatMap(_.linsert(key, true, pivot, value).futureLift.map(x => Long.box(x)))

  override def lRem(key: K, count: Long, value: V): F[Long] =
    async.flatMap(_.lrem(key, count, value).futureLift.map(x => Long.box(x)))

  override def lSet(key: K, index: Long, value: V): F[Unit] =
    async.flatMap(_.lset(key, index, value).futureLift.void)

  override def lTrim(key: K, start: Long, stop: Long): F[Unit] =
    async.flatMap(_.ltrim(key, start, stop).futureLift.void)

  // format: off
  /******************************* Bitmaps API **********************************/
  // format: on
  override def bitCount(key: K): F[Long] =
    async.flatMap(_.bitcount(key).futureLift.map(x => Long.box(x)))

  override def bitCount(key: K, start: Long, end: Long): F[Long] =
    async.flatMap(_.bitcount(key, start, end).futureLift.map(x => Long.box(x)))

  override def bitField(key: K, operations: BitCommandOperation*): F[List[Long]] =
    async
      .flatMap(
        _.bitfield(
          key,
          operations.foldLeft(new BitFieldArgs()) {
            case (b, BitCommandOperation.Get(fieldType, offset)) =>
              b.get(fieldType, offset)
            case (b, BitCommandOperation.SetSigned(offset, value, bits)) =>
              b.set(BitFieldArgs.signed(bits), offset, value)
            case (b, BitCommandOperation.SetUnsigned(offset, value, bits)) =>
              b.set(BitFieldArgs.unsigned(bits), offset, value)
            case (b, BitCommandOperation.IncrSignedBy(offset, value, bits)) =>
              b.incrBy(BitFieldArgs.signed(bits), offset, value)
            case (b, BitCommandOperation.IncrUnsignedBy(offset, value, bits)) =>
              b.incrBy(BitFieldArgs.unsigned(bits), offset, value)
            case (b, BitCommandOperation.Overflow(Overflows.SAT)) =>
              b.overflow(BitFieldArgs.OverflowType.SAT)
            case (b, BitCommandOperation.Overflow(Overflows.WRAP)) =>
              b.overflow(BitFieldArgs.OverflowType.WRAP)
            case (b, BitCommandOperation.Overflow(_)) =>
              b.overflow(BitFieldArgs.OverflowType.FAIL)
          }
        ).futureLift
      )
      .map(_.asScala.toList.map(_.toLong))

  override def bitPos(key: K, state: Boolean): F[Long] =
    async.flatMap(_.bitpos(key, state).futureLift.map(x => Long.box(x)))

  override def bitPos(key: K, state: Boolean, start: Long): F[Long] =
    async.flatMap(_.bitpos(key, state, start).futureLift.map(x => Long.box(x)))

  override def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long] =
    async.flatMap(_.bitpos(key, state, start, end).futureLift.map(x => Long.box(x)))

  override def bitOpAnd(destination: K, source: K, sources: K*): F[Long] =
    async.flatMap(_.bitopAnd(destination, (source +: sources): _*).futureLift.map(x => Long.box(x)))

  override def bitOpNot(destination: K, source: K): F[Long] =
    async.flatMap(_.bitopNot(destination, source).futureLift.map(x => Long.box(x)))

  override def bitOpOr(destination: K, source: K, sources: K*): F[Long] =
    async.flatMap(_.bitopOr(destination, (source +: sources): _*).futureLift.map(x => Long.box(x)))

  override def bitOpXor(destination: K, source: K, sources: K*): F[Long] =
    async.flatMap(_.bitopXor(destination, (source +: sources): _*).futureLift.map(x => Long.box(x)))

  override def bitOpDiff(destination: K, source: K, keys: K*): F[Long] =
    async.flatMap(_.bitopDiff(destination, source, keys: _*).futureLift.map(x => Long.box(x)))

  override def bitOpDiff1(destination: K, source: K, keys: K*): F[Long] =
    async.flatMap(_.bitopDiff1(destination, source, keys: _*).futureLift.map(x => Long.box(x)))

  override def bitOpAndOr(destination: K, source: K, keys: K*): F[Long] =
    async.flatMap(_.bitopAndor(destination, source, keys: _*).futureLift.map(x => Long.box(x)))

  override def bitOpOne(destination: K, keys: K*): F[Long] =
    async.flatMap(_.bitopOne(destination, keys: _*).futureLift.map(x => Long.box(x)))

  override def getBit(key: K, offset: Long): F[Option[Long]] =
    async.flatMap(_.getbit(key, offset).futureLift.map(x => Option(Long.unbox(x))))

  override def setBit(key: K, offset: Long, value: Int): F[Long] =
    async.flatMap(_.setbit(key, offset, value).futureLift.map(x => Long.box(x)))

  // format: off
  /******************************* Geo API **********************************/
  // format: on
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double] =
    async.flatMap(_.geodist(key, from, to, unit).futureLift.map(x => Double.box(x)))

  override def geoHash(key: K, value: V, values: V*): F[List[Option[String]]] =
    async
      .flatMap(_.geohash(key, (value +: values): _*).futureLift)
      .map(_.asScala.toList.map(x => Option(x.getValue)))

  override def geoPos(key: K, value: V, values: V*): F[List[GeoCoordinate]] =
    async
      .flatMap(_.geopos(key, (value +: values): _*).futureLift)
      .map(_.asScala.toList.map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue())))

  private def toGeoRef(ref: GeoSearchReference[V]): GeoSearch.GeoRef[K] =
    ref match {
      case GeoSearchReference.FromCoordinates(lon, lat) =>
        GeoSearch.fromCoordinates(lon.value, lat.value)
      case GeoSearchReference.FromMember(value) =>
        // Lettuce's GeoSearch.fromMember is generically typed over K (Lettuce's own source
        // marks this "TODO: Should be V") and internally encodes the member using the key
        // codec instead of the value codec via an unchecked cast. For a RedisCodec[K, V]
        // where K and V are encoded differently, this can produce a mismatched encoding on
        // the wire. This is a real, currently-shipped Lettuce bug (see
        // https://github.com/redis/lettuce/issues/826 for the identical class of bug,
        // previously reported and fixed for ZINCRBY), not something redis4cats works around:
        // using the modern GEOSEARCH API uniformly here, since the legacy GEORADIUSBYMEMBER
        // command never supported box-shaped search and so has no safe fallback for every
        // case anyway.
        GeoSearch.fromMember[K](value.asInstanceOf[K])
    }

  private def toGeoPredicate(predicate: GeoSearchPredicate): GeoSearch.GeoPredicate =
    predicate match {
      case GeoSearchPredicate.ByRadius(dist, unit) =>
        GeoSearch.byRadius(dist.value, unit)
      case GeoSearchPredicate.ByBox(width, height, unit) =>
        GeoSearch.byBox(width.value, height.value, unit)
    }

  private def toGeoArgs(args: GeoStoreArgs): GeoArgs = {
    val jArgs = new GeoArgs()
    args.count.foreach(jArgs.withCount)
    args.sort.foreach(jArgs.sort)
    jArgs
  }

  override def geoSearch(key: K, ref: GeoSearchReference[V], predicate: GeoSearchPredicate): F[Set[V]] =
    async
      .flatMap(_.geosearch(key, toGeoRef(ref), toGeoPredicate(predicate)).futureLift)
      .map(_.asScala.toSet)

  override def geoSearch(
      key: K,
      ref: GeoSearchReference[V],
      predicate: GeoSearchPredicate,
      args: GeoArgs
  ): F[List[GeoSearchResult[V]]] =
    async
      .flatMap(_.geosearch(key, toGeoRef(ref), toGeoPredicate(predicate), args).futureLift)
      .map(_.asScala.toList.map(_.asGeoSearchResult))

  override def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Long] = {
    val triplets = geoValues.flatMap(g => Seq[Any](g.lon.value, g.lat.value, g.value)).asInstanceOf[Seq[AnyRef]]
    async.flatMap(_.geoadd(key, triplets: _*).futureLift.map(x => Long.box(x)))
  }

  override def geoSearchStore(
      destination: K,
      key: K,
      ref: GeoSearchReference[V],
      predicate: GeoSearchPredicate,
      storeDist: Boolean
  ): F[Long] =
    geoSearchStore(destination, key, ref, predicate, storeDist, GeoStoreArgs())

  override def geoSearchStore(
      destination: K,
      key: K,
      ref: GeoSearchReference[V],
      predicate: GeoSearchPredicate,
      storeDist: Boolean,
      args: GeoStoreArgs
  ): F[Long] =
    async.flatMap(
      _.geosearchstore(
        destination,
        key,
        toGeoRef(ref),
        toGeoPredicate(predicate),
        toGeoArgs(args),
        storeDist
      ).futureLift
        .map(x => Long.box(x))
    )

  // format: off
  /******************************* Sorted Sets API **********************************/
  // format: on
  override def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Long] = {
    val res = args match {
      case Some(x) =>
        async.flatMap(_.zadd(key, x, values.map(s => ScoredValue.just(s.score.value, s.value)): _*).futureLift)
      case None =>
        async.flatMap(_.zadd(key, values.map(s => ScoredValue.just(s.score.value, s.value)): _*).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zAddIncr(key: K, args: Option[ZAddArgs], member: ScoreWithValue[V]): F[Double] = {
    val res = args match {
      case Some(x) => async.flatMap(_.zaddincr(key, x, member.score.value, member.value).futureLift)
      case None    => async.flatMap(_.zaddincr(key, member.score.value, member.value).futureLift)
    }
    res.map(x => Double.box(x))
  }

  override def zIncrBy(key: K, member: V, amount: Double): F[Double] =
    async.flatMap(_.zincrby(key, amount, member).futureLift.map(x => Double.box(x)))

  override def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long] = {
    val res = args match {
      case Some(x) => async.flatMap(_.zinterstore(destination, x, keys: _*).futureLift)
      case None    => async.flatMap(_.zinterstore(destination, keys: _*).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zRem(key: K, value: V, values: V*): F[Long] =
    async.flatMap(_.zrem(key, (value +: values): _*).futureLift.map(x => Long.box(x)))

  override def zRemRangeByLex(key: K, range: ZRange[V]): F[Long] =
    async
      .flatMap(_.zremrangebylex(key, JRange.create[V](range.start, range.end)).futureLift)
      .map(x => Long.box(x))

  override def zRemRangeByRank(key: K, start: Long, stop: Long): F[Long] =
    async.flatMap(_.zremrangebyrank(key, start, stop).futureLift.map(x => Long.box(x)))

  override def zRemRangeByScore[T: Numeric](key: K, range: ZRange[T]): F[Long] =
    async.flatMap(_.zremrangebyscore(key, range.asJavaRange).futureLift.map(x => Long.box(x)))

  override def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long] = {
    val res = args match {
      case Some(x) => async.flatMap(_.zunionstore(destination, x, keys: _*).futureLift)
      case None    => async.flatMap(_.zunionstore(destination, keys: _*).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zRangeStore(destination: K, key: K, start: Long, stop: Long): F[Long] =
    async
      .flatMap(_.zrangestore(destination, key, JRange.create[java.lang.Long](start, stop)).futureLift)
      .map(x => Long.box(x))

  override def zRangeStoreByScore[T: Numeric](
      destination: K,
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[Long] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(
          _.zrangestorebyscore(destination, key, range.asJavaRange, JLimit.create(x.offset, x.count)).futureLift
        )
      case None =>
        async.flatMap(_.zrangestorebyscore(destination, key, range.asJavaRange, JLimit.unlimited()).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zRangeStoreByLex(destination: K, key: K, range: ZRange[V], limit: Option[RangeLimit]): F[Long] = {
    val jRange = JRange.create[V](range.start, range.end)
    val res = limit match {
      case Some(x) =>
        async.flatMap(_.zrangestorebylex(destination, key, jRange, JLimit.create(x.offset, x.count)).futureLift)
      case None => async.flatMap(_.zrangestorebylex(destination, key, jRange, JLimit.unlimited()).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zRevRangeStore(destination: K, key: K, start: Long, stop: Long): F[Long] =
    async
      .flatMap(_.zrevrangestore(destination, key, JRange.create[java.lang.Long](start, stop)).futureLift)
      .map(x => Long.box(x))

  override def zRevRangeStoreByScore[T: Numeric](
      destination: K,
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[Long] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(
          _.zrevrangestorebyscore(destination, key, range.asJavaRange, JLimit.create(x.offset, x.count)).futureLift
        )
      case None =>
        async.flatMap(_.zrevrangestorebyscore(destination, key, range.asJavaRange, JLimit.unlimited()).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zRevRangeStoreByLex(destination: K, key: K, range: ZRange[V], limit: Option[RangeLimit]): F[Long] = {
    val jRange = JRange.create[V](range.start, range.end)
    val res = limit match {
      case Some(x) =>
        async.flatMap(_.zrevrangestorebylex(destination, key, jRange, JLimit.create(x.offset, x.count)).futureLift)
      case None => async.flatMap(_.zrevrangestorebylex(destination, key, jRange, JLimit.unlimited()).futureLift)
    }
    res.map(x => Long.box(x))
  }

  override def zCard(key: K): F[Long] =
    async.flatMap(_.zcard(key).futureLift.map(x => Long.unbox(x)))

  override def zCount[T: Numeric](key: K, range: ZRange[T]): F[Long] =
    async.flatMap(_.zcount(key, range.asJavaRange).futureLift.map(x => Long.unbox(x)))

  override def zMScore(key: K, values: V*): F[List[Option[Double]]] =
    async
      .flatMap(_.zmscore(key, values: _*).futureLift)
      .map(_.asScala.toList.map(x => Option(Double.unbox(x))))

  override def zLexCount(key: K, range: ZRange[V]): F[Long] =
    async.flatMap(_.zlexcount(key, JRange.create[V](range.start, range.end)).futureLift.map(x => Long.unbox(x)))

  override def zRandMember(key: K): F[Option[V]] =
    async.flatMap(_.zrandmember(key).futureLift.map(Option.apply))

  override def zRandMember(key: K, count: Long): F[List[V]] =
    async.flatMap(_.zrandmember(key, count).futureLift.map(_.asScala.toList))

  override def zRandMemberWithScores(key: K): F[Option[ScoreWithValue[V]]] =
    async
      .flatMap(_.zrandmemberWithScores(key).futureLift)
      .map(Option(_).map(_.asScoreWithValues))

  override def zRandMemberWithScores(key: K, count: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zrandmemberWithScores(key, count).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRange(key: K, start: Long, stop: Long): F[List[V]] =
    async.flatMap(_.zrange(key, start, stop).futureLift.map(_.asScala.toList))

  override def zRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(
          _.zrangebylex(key, JRange.create[V](range.start, range.end), JLimit.create(x.offset, x.count)).futureLift
        )
      case None =>
        async.flatMap(_.zrangebylex(key, JRange.create[V](range.start, range.end)).futureLift)
    }
    res.map(_.asScala.toList)
  }

  override def zRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(_.zrangebyscore(key, range.asJavaRange, JLimit.create(x.offset, x.count)).futureLift)
      case None => async.flatMap(_.zrangebyscore(key, range.asJavaRange).futureLift)
    }
    res.map(_.asScala.toList)
  }

  override def zRangeByScoreWithScores[T: Numeric](
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[List[ScoreWithValue[V]]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(_.zrangebyscoreWithScores(key, range.asJavaRange, JLimit.create(x.offset, x.count)).futureLift)
      case None =>
        async.flatMap(_.zrangebyscoreWithScores(key, range.asJavaRange).futureLift)
    }
    res.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zrangeWithScores(key, start, stop).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRank(key: K, value: V): F[Option[Long]] =
    async.flatMap(_.zrank(key, value).futureLift.map(x => Option(Long.unbox(x))))

  override def zRevRange(key: K, start: Long, stop: Long): F[List[V]] =
    async.flatMap(_.zrevrange(key, start, stop).futureLift.map(_.asScala.toList))

  override def zRevRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(
          _.zrevrangebylex(key, JRange.create[V](range.start, range.end), JLimit.create(x.offset, x.count)).futureLift
        )
      case None =>
        async.flatMap(_.zrevrangebylex(key, JRange.create[V](range.start, range.end)).futureLift)
    }
    res.map(_.asScala.toList)
  }

  override def zRevRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(_.zrevrangebyscore(key, range.asJavaRange, JLimit.create(x.offset, x.count)).futureLift)
      case None =>
        async.flatMap(_.zrevrangebyscore(key, range.asJavaRange).futureLift)
    }
    res.map(_.asScala.toList)
  }

  override def zRevRangeByScoreWithScores[T: Numeric](
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[List[ScoreWithValue[V]]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(_.zrevrangebyscoreWithScores(key, range.asJavaRange, JLimit.create(x.offset, x.count)).futureLift)
      case None =>
        async.flatMap(_.zrevrangebyscoreWithScores(key, range.asJavaRange).futureLift)
    }
    res.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zRevRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zrevrangeWithScores(key, start, stop).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRank(key: K, value: V): F[Option[Long]] =
    async.flatMap(_.zrevrank(key, value).futureLift.map(x => Option(Long.unbox(x))))

  override def zScore(key: K, value: V): F[Option[Double]] =
    async.flatMap(_.zscore(key, value).futureLift.map(x => Option(Double.unbox(x))))

  override def zPopMin(key: K, count: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zpopmin(key, count).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zPopMax(key: K, count: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zpopmax(key, count).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def bzPopMin(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]] =
    async
      .flatMap(_.bzpopmin(timeout.toSecondsOrZero, keys.toList: _*).futureLift)
      .map(Option(_).filter(_.hasValue).map(kv => (kv.getKey, kv.getValue.asScoreWithValues)))

  override def bzPopMax(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]] =
    async
      .flatMap(_.bzpopmax(timeout.toSecondsOrZero, keys.toList: _*).futureLift)
      .map(Option(_).filter(_.hasValue).map(kv => (kv.getKey, kv.getValue.asScoreWithValues)))

  override def zmPopMin(keys: NonEmptyList[K], count: Int): F[Option[(K, List[ScoreWithValue[V]])]] =
    async
      .flatMap(_.zmpop(count, ZPopArgs.Builder.min(), keys.toList: _*).futureLift)
      .map(Option(_).filter(_.hasValue).map(kv => (kv.getKey, kv.getValue.asScala.toList.map(_.asScoreWithValues))))

  override def zmPopMax(keys: NonEmptyList[K], count: Int): F[Option[(K, List[ScoreWithValue[V]])]] =
    async
      .flatMap(_.zmpop(count, ZPopArgs.Builder.max(), keys.toList: _*).futureLift)
      .map(Option(_).filter(_.hasValue).map(kv => (kv.getKey, kv.getValue.asScala.toList.map(_.asScoreWithValues))))

  override def bzmPopMin(
      timeout: Duration,
      keys: NonEmptyList[K],
      count: Long
  ): F[Option[(K, List[ScoreWithValue[V]])]] =
    async
      .flatMap(_.bzmpop(timeout.toSecondsOrZero, count, ZPopArgs.Builder.min(), keys.toList: _*).futureLift)
      .map(Option(_).filter(_.hasValue).map(kv => (kv.getKey, kv.getValue.asScala.toList.map(_.asScoreWithValues))))

  override def bzmPopMax(
      timeout: Duration,
      keys: NonEmptyList[K],
      count: Long
  ): F[Option[(K, List[ScoreWithValue[V]])]] =
    async
      .flatMap(_.bzmpop(timeout.toSecondsOrZero, count, ZPopArgs.Builder.max(), keys.toList: _*).futureLift)
      .map(Option(_).filter(_.hasValue).map(kv => (kv.getKey, kv.getValue.asScala.toList.map(_.asScoreWithValues))))

  override def zUnion(args: Option[ZAggregateArgs], keys: K*): F[List[V]] = {
    val res = args match {
      case Some(aggArgs) => async.flatMap(_.zunion(aggArgs, keys: _*).futureLift)
      case None          => async.flatMap(_.zunion(keys: _*).futureLift)
    }
    res.map(_.asScala.toList)
  }

  override def zUnionWithScores(args: Option[ZAggregateArgs], keys: K*): F[List[ScoreWithValue[V]]] = {
    val res = args match {
      case Some(aggArgs) => async.flatMap(_.zunionWithScores(aggArgs, keys: _*).futureLift)
      case None          => async.flatMap(_.zunionWithScores(keys: _*).futureLift)
    }
    res.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zInter(args: Option[ZAggregateArgs], keys: K*): F[List[V]] = {
    val res = args match {
      case Some(aggArgs) => async.flatMap(_.zinter(aggArgs, keys: _*).futureLift)
      case None          => async.flatMap(_.zinter(keys: _*).futureLift)
    }
    res.map(_.asScala.toList)
  }

  override def zInterWithScores(args: Option[ZAggregateArgs], keys: K*): F[List[ScoreWithValue[V]]] = {
    val res = args match {
      case Some(aggArgs) => async.flatMap(_.zinterWithScores(aggArgs, keys: _*).futureLift)
      case None          => async.flatMap(_.zinterWithScores(keys: _*).futureLift)
    }
    res.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zInterCard(keys: K*): F[Long] =
    async.flatMap(_.zintercard(keys: _*).futureLift.map(x => Long.box(x)))

  override def zInterCard(limit: Long, keys: K*): F[Long] =
    async.flatMap(_.zintercard(limit, keys: _*).futureLift.map(x => Long.box(x)))

  override def zDiff(keys: K*): F[List[V]] =
    async.flatMap(_.zdiff(keys: _*).futureLift.map(_.asScala.toList))

  override def zDiffWithScores(keys: K*): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zdiffWithScores(keys: _*).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  // format: off
  /******************************* Connection API **********************************/
  // format: on
  override val ping: F[String] =
    async.flatMap(_.ping().futureLift)

  override def select(index: Int): F[Unit] =
    conn.async.flatMap(_.select(index).futureLift.void)

  override def auth(password: CharSequence): F[Boolean] =
    async.flatMap(_.auth(password).futureLift.map(_.isSuccess))

  override def auth(username: String, password: CharSequence): F[Boolean] =
    async.flatMap(_.auth(username, password).futureLift.map(_.isSuccess))

  override def setClientName(name: K): F[Boolean] =
    async.flatMap(_.clientSetname(name).futureLift.map(_.isSuccess))

  override def getClientName(): F[Option[K]] =
    async.flatMap(_.clientGetname().futureLift).map(Option.apply)

  override def getClientId(): F[Long] =
    async.flatMap(_.clientId().futureLift.map(Long.unbox))

  override def setLibName(name: String): F[Boolean] =
    async.flatMap(_.clientSetinfo("LIB-NAME", name).futureLift.map(_.isSuccess))

  override def setLibVersion(version: String): F[Boolean] =
    async.flatMap(_.clientSetinfo("LIB-VER", version).futureLift.map(_.isSuccess))

  override def getClientInfo: F[Map[String, String]] =
    async.flatMap(
      _.clientInfo().futureLift
        .flatMap(parseClientInfo)
    )

  private def parseClientInfo(info: String): F[Map[String, String]] =
    FutureLift[F].delay(
      info
        .replace("\n", "")
        .split(" ")
        .toList
        .map(_.split("=", 2).toList)
        .collect { case k :: v :: Nil => (k, v) }
        .toMap
    )

  override def echo(msg: V): F[V] =
    async.flatMap(_.echo(msg).futureLift)

  override def waitForReplication(numReplicas: Int, timeout: FiniteDuration): F[Long] =
    async.flatMap(_.waitForReplication(numReplicas, timeout.toMillis).futureLift.map(x => Long.box(x)))

  override def readOnly: F[Unit] =
    async.flatMap(_.readOnly().futureLift.void)

  override def readWrite: F[Unit] =
    async.flatMap(_.readWrite().futureLift.void)

  override def role: F[RedisRole] =
    async.flatMap(_.role().futureLift).flatMap(reply => FutureLift[F].delay(RedisRole.fromLettuce(reply)))

  // format: off
  /******************************* ACL API **********************************/
  // format: on
  override val aclWhoAmI: F[String] =
    async.flatMap(_.aclWhoami().futureLift)

  override val aclList: F[List[String]] =
    async.flatMap(_.aclList().futureLift.map(_.asScala.toList))

  override val aclUsers: F[List[String]] =
    async.flatMap(_.aclUsers().futureLift.map(_.asScala.toList))

  override val aclCat: F[Set[AclCategory]] =
    async.flatMap(_.aclCat().futureLift).flatMap { js =>
      js.asScala.toList.traverse(AclCategory.fromJava).map(_.toSet).liftTo[F]
    }

  override def aclCat(category: AclCategory): F[Set[String]] =
    async.flatMap(
      _.aclCat(category.asJava).futureLift
        .map(_.asScala.toSet.map((c: CommandType) => c.toString.toLowerCase(java.util.Locale.ROOT)))
    )

  override val aclGenPass: F[String] =
    async.flatMap(_.aclGenpass().futureLift)

  override def aclGenPass(bits: Int): F[String] =
    async.flatMap(_.aclGenpass(bits).futureLift)

  override val aclLoad: F[Unit] =
    async.flatMap(_.aclLoad().futureLift.void)

  override val aclSave: F[Unit] =
    async.flatMap(_.aclSave().futureLift.void)

  override val aclLogReset: F[Unit] =
    async.flatMap(_.aclLogReset().futureLift.void)

  override val aclLog: F[List[Map[String, String]]] =
    async.flatMap(_.aclLog().futureLift).flatMap(es => AclDecoder.decodeLog(es).liftTo[F])

  override def aclLog(count: Int): F[List[Map[String, String]]] =
    async.flatMap(_.aclLog(count).futureLift).flatMap(es => AclDecoder.decodeLog(es).liftTo[F])

  override def aclDelUser(username: String, usernames: String*): F[Long] =
    async.flatMap(_.aclDeluser((username +: usernames): _*).futureLift.map(Long.unbox))

  override def aclDryRun(username: String, command: String, args: String*): F[AclDryRunResult] =
    async.flatMap(_.aclDryRun(username, command, args: _*).futureLift).map {
      case "OK"    => AclDryRunResult.Allowed
      case message => AclDryRunResult.Denied(message)
    }

  override def aclSetUser(username: String, rules: List[AclSetUserRule]): F[Unit] =
    aclSetuserArgs(rules).liftTo[F].flatMap(args => async.flatMap(_.aclSetuser(username, args).futureLift.void))

  override def aclGetUser(username: String): F[Option[AclUser]] =
    async.flatMap(_.aclGetuser(username).futureLift).flatMap(raw => AclDecoder.decodeUser(raw).liftTo[F])

  private def commandType(name: String): Either[AclError, CommandType] =
    Either
      .catchOnly[IllegalArgumentException](CommandType.valueOf(name.toUpperCase(java.util.Locale.ROOT)))
      .leftMap(_ => AclError.UnknownCommand(name))

  private def aclSetuserArgs(rules: List[AclSetUserRule]): Either[AclError, AclSetuserArgs] =
    rules.foldLeft(Right(new AclSetuserArgs()): Either[AclError, AclSetuserArgs])((acc, rule) =>
      acc.flatMap(applyRule(_, rule))
    )

  private def applyRule(args: AclSetuserArgs, rule: AclSetUserRule): Either[AclError, AclSetuserArgs] =
    rule match {
      case AclSetUserRule.On                      => Right(args.on())
      case AclSetUserRule.Off                     => Right(args.off())
      case AclSetUserRule.Reset                   => Right(args.reset())
      case AclSetUserRule.NoPass                  => Right(args.nopass())
      case AclSetUserRule.ResetPass               => Right(args.resetpass())
      case AclSetUserRule.AddPassword(p)          => Right(args.addPassword(p))
      case AclSetUserRule.RemovePassword(p)       => Right(args.removePassword(p))
      case AclSetUserRule.AddHashedPassword(h)    => Right(args.addHashedPassword(h))
      case AclSetUserRule.RemoveHashedPassword(h) => Right(args.removeHashedPassword(h))
      case AclSetUserRule.AllKeys                 => Right(args.allKeys())
      case AclSetUserRule.ResetKeys               => Right(args.resetKeys())
      case AclSetUserRule.KeyPattern(p)           => Right(args.keyPattern(p))
      case AclSetUserRule.AllChannels             => Right(args.allChannels())
      case AclSetUserRule.ResetChannels           => Right(args.resetChannels())
      case AclSetUserRule.ChannelPattern(p)       => Right(args.channelPattern(p))
      case AclSetUserRule.AllCommands             => Right(args.allCommands())
      case AclSetUserRule.NoCommands              => Right(args.noCommands())
      case AclSetUserRule.AddCommand(c)           => commandType(c.value).map(ct => args.addCommand(ct))
      case AclSetUserRule.RemoveCommand(c)        => commandType(c.value).map(ct => args.removeCommand(ct))
      case AclSetUserRule.AddCategory(c)          => Right(args.addCategory(c.asJava))
      case AclSetUserRule.RemoveCategory(c)       => Right(args.removeCategory(c.asJava))
    }

  // format: off
  /******************************* Server API **********************************/
  // format: on
  override val flushAll: F[Unit] =
    async.flatMap(_.flushall().futureLift.void)

  override def flushAll(mode: FlushMode): F[Unit] =
    async.flatMap(_.flushall(mode.asJava).futureLift.void)

  override val flushDb: F[Unit] =
    async.flatMap(_.flushdb().futureLift.void)

  override def flushDb(mode: FlushMode): F[Unit] =
    async.flatMap(_.flushdb(mode.asJava).futureLift.void)

  override def keys(key: String): F[List[K]] =
    async.flatMap(_.keys(key).futureLift.map(_.asScala.toList))

  private def parseInfo(info: String): F[Map[String, String]] =
    FutureLift[F].delay(
      info
        .split("\\r?\\n")
        .toList
        .map(_.split(":", 2).toList)
        .collect { case k :: v :: Nil => (k, v) }
        .toMap
    )

  override def info: F[Map[String, String]] =
    async.flatMap(_.info.futureLift).flatMap(parseInfo)

  override def info(section: String): F[Map[String, String]] =
    async.flatMap(_.info(section).futureLift).flatMap(parseInfo)

  override def dbsize: F[Long] =
    async.flatMap(_.dbsize.futureLift.map(Long.unbox))

  override def lastSave: F[Instant] =
    async.flatMap(_.lastsave.futureLift.map(_.toInstant))

  override def slowLogLen: F[Long] =
    async.flatMap(_.slowlogLen.futureLift.map(Long.unbox))

  override def slowLogReset: F[Unit] =
    async.flatMap(_.slowlogReset().futureLift.void)

  override def slowLogGet: F[List[SlowLogEntry]] =
    async.flatMap(_.slowlogGet().futureLift).flatMap(toSlowLogEntries)

  override def slowLogGet(count: Int): F[List[SlowLogEntry]] =
    async.flatMap(_.slowlogGet(count).futureLift).flatMap(toSlowLogEntries)

  private def toSlowLogEntries(reply: java.util.List[Object]): F[List[SlowLogEntry]] =
    reply.asScala.toList.traverse(SlowLogEntry.fromLettuce).liftTo[F]

  override def commandCount: F[Long] =
    async.flatMap(_.commandCount().futureLift.map(Long.unbox))

  override def command: F[List[CommandInfo]] =
    async.flatMap(_.command().futureLift).flatMap(toCommandInfoList)

  override def commandInfo(names: String*): F[List[CommandInfo]] =
    async.flatMap(_.commandInfo(names: _*).futureLift).flatMap(toCommandInfoList)

  private def toCommandInfoList(reply: java.util.List[Object]): F[List[CommandInfo]] =
    CommandDetailParser.parse(reply).asScala.toList.traverse(CommandInfo.fromLettuce).liftTo[F]

  override def time: F[RedisServerTime] =
    async.flatMap(_.time().futureLift.map(reply => RedisServerTime.fromLettuce(reply)))

  override def configGet(parameter: String): F[Map[String, String]] =
    async.flatMap(_.configGet(parameter).futureLift.map(_.asScala.toMap))

  override def configGet(parameters: String*): F[Map[String, String]] =
    async.flatMap(_.configGet(parameters: _*).futureLift.map(_.asScala.toMap))

  override def configSet(parameter: String, value: String): F[Unit] =
    async.flatMap(_.configSet(parameter, value).futureLift.void)

  override def configSet(values: Map[String, String]): F[Unit] =
    async.flatMap(_.configSet(values.asJava).futureLift.void)

  override def configResetStat: F[Unit] =
    async.flatMap(_.configResetstat().futureLift.void)

  override def configRewrite: F[Unit] =
    async.flatMap(_.configRewrite().futureLift.void)

  private def parseClientLines(info: String): F[List[Map[String, String]]] =
    FutureLift[F].delay(
      info
        .split("\\r?\\n")
        .toList
        .filter(_.nonEmpty)
        .map(
          _.split(" ").toList
            .map(_.split("=", 2).toList)
            .collect { case k :: v :: Nil => (k, v) }
            .toMap
        )
    )

  override def clientList: F[List[Map[String, String]]] =
    async.flatMap(_.clientList().futureLift).flatMap(parseClientLines)

  override def clientList(args: ClientListArgs): F[List[Map[String, String]]] =
    async.flatMap(_.clientList(toJClientListArgs(args)).futureLift).flatMap(parseClientLines)

  override def clientKill(addr: String): F[Unit] =
    async.flatMap(_.clientKill(addr).futureLift.void)

  override def clientKill(args: KillArgs): F[Long] =
    async.flatMap(_.clientKill(toJKillArgs(args)).futureLift.map(Long.unbox))

  override def clientPause(timeout: FiniteDuration): F[Unit] =
    async.flatMap(_.clientPause(timeout.toMillis).futureLift.void)

  override def clientUnblock(id: Long, unblockType: UnblockType): F[Long] =
    async.flatMap(_.clientUnblock(id, toJUnblockType(unblockType)).futureLift.map(Long.unbox))

  // ClientListArgs.Type/KillArgs.Type are private to their enclosing Java class, unreachable from
  // outside io.lettuce.core - Lettuce's own workaround is a set of Builder.typeX() static factories,
  // each returning a fresh instance with that type already set. We start from one of those (or a
  // plain constructor when no type filter applies) and chain the remaining public setters onto it.
  private def toJClientListArgs(args: ClientListArgs): JClientListArgs =
    args match {
      case ClientListArgs.ByIds(ids) => JClientListArgs.Builder.ids(ids: _*)
      case ClientListArgs.ByType(tpe) =>
        tpe match {
          case ClientType.Normal  => JClientListArgs.Builder.typeNormal()
          case ClientType.Master  => JClientListArgs.Builder.typeMaster()
          case ClientType.Replica => JClientListArgs.Builder.typeReplica()
          case ClientType.PubSub  => JClientListArgs.Builder.typePubsub()
        }
    }

  private def toJKillArgs(args: KillArgs): JKillArgs = {
    val jArgs = args.tpe match {
      case None => new JKillArgs()
      case Some(tpe) =>
        tpe match {
          case ClientType.Normal => JKillArgs.Builder.typeNormal()
          case ClientType.Master => JKillArgs.Builder.typeMaster()
          // Not a copy-paste slip: KillArgs.Builder genuinely has no typeReplica() (only the legacy
          // typeSlave()), unlike ClientListArgs.Builder's typeReplica() used just above. Both map the
          // same ClientType.Replica case, just to differently-named Lettuce factories.
          case ClientType.Replica => JKillArgs.Builder.typeSlave()
          case ClientType.PubSub  => JKillArgs.Builder.typePubsub()
        }
    }
    args.id.foreach(jArgs.id)
    args.user.foreach(jArgs.user)
    args.addr.foreach(jArgs.addr)
    args.laddr.foreach(jArgs.laddr)
    args.skipMe.foreach(jArgs.skipme)
    args.maxAge.foreach(v => jArgs.maxAge(v): Unit)
    jArgs
  }

  private def toJUnblockType(tpe: UnblockType): JUnblockType =
    tpe match {
      case UnblockType.Timeout => JUnblockType.TIMEOUT
      case UnblockType.Error   => JUnblockType.ERROR
    }

  override def clientGetRedir: F[Long] =
    async.flatMap(_.clientGetredir().futureLift.map(Long.unbox))

  override def clientCaching(enabled: Boolean): F[Unit] =
    async.flatMap(_.clientCaching(enabled).futureLift.void)

  override def clientNoTouch(enabled: Boolean): F[Unit] =
    async.flatMap(_.clientNoTouch(enabled).futureLift.void)

  override def clientNoEvict(enabled: Boolean): F[Unit] =
    async.flatMap(_.clientNoEvict(enabled).futureLift.void)

  override def clientTracking(args: ClientTrackingArgs): F[Unit] =
    async.flatMap(_.clientTracking(toJTrackingArgs(args)).futureLift.void)

  private def toJTrackingArgs(args: ClientTrackingArgs): JTrackingArgs = {
    val jArgs = new JTrackingArgs().enabled(args.enabled)
    if (args.bcast) jArgs.bcast(): Unit
    if (args.optIn) jArgs.optin(): Unit
    if (args.optOut) jArgs.optout(): Unit
    if (args.noLoop) jArgs.noloop(): Unit
    args.redirect.foreach(r => jArgs.redirect(r): Unit)
    if (args.prefixes.nonEmpty) jArgs.prefixes(args.prefixes: _*): Unit
    jArgs
  }

  override def clientTrackingInfo: F[TrackingInfo] =
    async.flatMap(_.clientTrackinginfo().futureLift).map(TrackingInfo.fromLettuce)

  override def memoryUsage(key: K): F[Option[Long]] =
    async.flatMap(_.memoryUsage(key).futureLift.map(x => Option(x).map(Long.unbox)))

  override def save: F[Unit] =
    async.flatMap(_.save().futureLift.void)

  override def bgSave: F[Unit] =
    async.flatMap(_.bgsave().futureLift.void)

  override def bgRewriteAof: F[Unit] =
    async.flatMap(_.bgrewriteaof().futureLift.void)

  override def eval(script: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(_.eval[output.Underlying](script, output.outputType).futureLift)
      .map(r => output.convert(r))

  override def eval(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async.flatMap(
      _.eval[output.Underlying](
        script,
        output.outputType,
        // The Object requirement comes from the limitations of Java Generics. It is safe to assume K <: Object as
        // the underlying JRedisCodec would also only support K <: Object.
        keys.toArray[Any].asInstanceOf[Array[K with Object]]
      ).futureLift.map(output.convert(_))
    )

  override def eval(script: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async.flatMap(
      _.eval[output.Underlying](
        script,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]],
        values: _*
      ).futureLift.map(output.convert(_))
    )

  override def evalReadOnly(script: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(
        _.evalReadOnly[output.Underlying](
          script,
          output.outputType,
          // see comment in eval above.
          Array.emptyObjectArray.asInstanceOf[Array[K with Object]]
        ).futureLift
      )
      .map(r => output.convert(r))

  override def evalReadOnly(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async.flatMap(
      _.evalReadOnly[output.Underlying](
        script,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]]
      ).futureLift.map(output.convert(_))
    )

  override def evalReadOnly(script: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async.flatMap(
      _.evalReadOnly[output.Underlying](
        script,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]],
        values: _*
      ).futureLift.map(output.convert(_))
    )

  override def evalSha(digest: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(_.evalsha[output.Underlying](digest, output.outputType).futureLift)
      .map(output.convert(_))

  override def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async.flatMap(
      _.evalsha[output.Underlying](
        digest,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]]
      ).futureLift.map(output.convert(_))
    )

  override def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async.flatMap(
      _.evalsha[output.Underlying](
        digest,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]],
        values: _*
      ).futureLift.map(output.convert(_))
    )

  override def evalShaReadOnly(digest: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(
        _.evalshaReadOnly[output.Underlying](
          digest,
          output.outputType,
          // see comment in eval above.
          Array.emptyObjectArray.asInstanceOf[Array[K with Object]]
        ).futureLift
      )
      .map(output.convert(_))

  override def evalShaReadOnly(digest: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async.flatMap(
      _.evalshaReadOnly[output.Underlying](
        digest,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]]
      ).futureLift.map(output.convert(_))
    )

  override def evalShaReadOnly(
      digest: String,
      output: ScriptOutputType[V],
      keys: List[K],
      values: List[V]
  ): F[output.R] =
    async.flatMap(
      _.evalshaReadOnly[output.Underlying](
        digest,
        output.outputType,
        // see comment in eval above.
        keys.toArray[Any].asInstanceOf[Array[K with Object]],
        values: _*
      ).futureLift.map(output.convert(_))
    )

  override def scriptLoad(script: String): F[String] =
    async.flatMap(_.scriptLoad(script).futureLift)

  override def scriptLoad(script: Array[Byte]): F[String] =
    async.flatMap(_.scriptLoad(script).futureLift)

  override def scriptExists(digests: String*): F[List[Boolean]] =
    async
      .flatMap(_.scriptExists(digests: _*).futureLift)
      .map(_.asScala.map(Boolean.unbox(_)).toList)

  override def scriptFlush: F[Unit] =
    async.flatMap(_.scriptFlush().futureLift.void)

  override def scriptKill: F[String] =
    async.flatMap(_.scriptKill().futureLift)

  override def digest(script: String): F[String] =
    async.map(_.digest(script))

  override def fcall(function: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async.flatMap(
      _.fcall[output.Underlying](
        function,
        output.outputType,
        keys: _*
      ).futureLift.map(output.convert(_))
    )

  override def fcall(function: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async.flatMap(
      _.fcall[output.Underlying](
        function,
        output.outputType,
        // The Object requirement comes from the limitations of Java Generics. It is safe to assume K <: Object as
        // the underlying JRedisCodec would also only support K <: Object.
        keys.toArray[Any].asInstanceOf[Array[K with Object]],
        values: _*
      ).futureLift.map(output.convert(_))
    )

  override def fcallReadOnly(function: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async.flatMap(
      _.fcallReadOnly[output.Underlying](
        function,
        output.outputType,
        keys: _*
      ).futureLift.map(output.convert(_))
    )

  override def fcallReadOnly(
      function: String,
      output: ScriptOutputType[V],
      keys: List[K],
      values: List[V]
  ): F[output.R] =
    async.flatMap(
      _.fcallReadOnly[output.Underlying](
        function,
        output.outputType,
        // The Object requirement comes from the limitations of Java Generics. It is safe to assume K <: Object as
        // the underlying JRedisCodec would also only support K <: Object.
        keys.toArray[Any].asInstanceOf[Array[K with Object]],
        values: _*
      ).futureLift.map(output.convert(_))
    )

  override def functionLoad(functionCode: String): F[String] =
    async.flatMap(_.functionLoad(functionCode).futureLift)

  override def functionLoad(functionCode: String, replace: Boolean): F[String] =
    async.flatMap(_.functionLoad(functionCode, replace).futureLift)

  override def functionDump(): F[Array[Byte]] =
    async.flatMap(_.functionDump().futureLift)

  override def functionRestore(dump: Array[Byte]): F[String] =
    async.flatMap(_.functionRestore(dump).futureLift)

  override def functionRestore(dump: Array[Byte], mode: FunctionRestoreMode): F[String] = {
    val jMode = mode match {
      case FunctionRestoreMode.Flush   => JFunctionRestoreMode.FLUSH
      case FunctionRestoreMode.Append  => JFunctionRestoreMode.APPEND
      case FunctionRestoreMode.Replace => JFunctionRestoreMode.REPLACE
    }
    async.flatMap(_.functionRestore(dump, jMode).futureLift)
  }

  override def functionFlush(flushMode: FlushMode): F[String] = {
    val jFlushMode = flushMode match {
      case FlushMode.Sync  => JFlushMode.SYNC
      case FlushMode.Async => JFlushMode.ASYNC
    }
    async.flatMap(_.functionFlush(jFlushMode).futureLift)
  }

  override def functionKill(): F[String] =
    async.flatMap(_.functionKill().futureLift)

  override def functionList(): F[List[Map[String, Any]]] =
    async
      .flatMap(_.functionList().futureLift)
      .map(_.asScala.map(_.asScala.toMap).toList)

  override def functionList(libraryName: String): F[List[Map[String, Any]]] =
    async
      .flatMap(_.functionList(libraryName).futureLift)
      .map(_.asScala.map(_.asScala.toMap).toList)

  // format: off
  /******************************* HyperLoglog API **********************************/
  // format: on
  override def pfAdd(key: K, values: V*): F[Long] =
    async.flatMap(_.pfadd(key, values: _*).futureLift.map(Long.box(_)))

  override def pfCount(key: K): F[Long] =
    async.flatMap(_.pfcount(key).futureLift.map(Long.box(_)))

  override def pfMerge(outputKey: K, inputKeys: K*): F[Unit] =
    async.flatMap(_.pfmerge(outputKey, inputKeys: _*).futureLift.void)

  // format: off
  /******************************* Streams API **********************************/
  // format: on
  override def xRead(
      streams: Set[XReadOffsets[K]],
      block: Option[Duration],
      count: Option[Long]
  ): F[List[StreamMessage[K, V]]] = {
    val offsets = streams.map {
      case XReadOffsets.All(key)            => StreamOffset.from(key, "0")
      case XReadOffsets.Latest(key)         => StreamOffset.latest(key)
      case XReadOffsets.Custom(key, offset) => StreamOffset.from(key, offset)
    }.toSeq

    async
      .flatMap { redis =>
        ((block, count) match {
          case (None, None)        => redis.xread(offsets: _*)
          case (None, Some(count)) => redis.xread(XReadArgs.Builder.count(count), offsets: _*)
          case (Some(block), None) => redis.xread(XReadArgs.Builder.block(block.toMillis), offsets: _*)
          case (Some(block), Some(count)) =>
            redis.xread(XReadArgs.Builder.block(block.toMillis).count(count), offsets: _*)
        }).futureLift
      }
      .map(_.toScala)
  }

  override def xRange(key: K, start: XRangePoint, end: XRangePoint, count: Option[Long]): F[List[StreamMessage[K, V]]] =
    async
      .flatMap(_.xrange(key, (start, end).asJavaRange, count.fold(JLimit.unlimited())(JLimit.from)).futureLift)
      .map(_.toScala)

  override def xRevRange(
      key: K,
      start: XRangePoint,
      end: XRangePoint,
      count: Option[Long]
  ): F[List[StreamMessage[K, V]]] =
    async
      .flatMap(_.xrevrange(key, (start, end).asJavaRange, count.fold(JLimit.unlimited())(JLimit.from)).futureLift)
      .map(_.toScala)

  override def xLen(key: K): F[Long] =
    async.flatMap(_.xlen(key).futureLift.map(Long.box(_)))

  override def xInfoStream(key: K): F[XStreamInfo[K, V]] =
    async
      .flatMap(_.xinfoStream(key).futureLift)
      .flatMap(reply => FutureLift[F].delay(XStreamInfo.fromLettuce(key, reply)))

  override def xAdd(key: K, body: Map[K, V], args: XAddArgs): F[MessageId] = {
    val jArgs = JXAddArgs.Builder.nomkstream()
    jArgs.nomkstream(args.nomkstream)
    args.id.foreach(jArgs.id)
    args.xTrimArgs.foreach { xTrimArgs =>
      xTrimArgs.strategy match {
        case XTrimArgs.Strategy.MAXLEN(threshold) =>
          jArgs.maxlen(threshold)
        case XTrimArgs.Strategy.MINID(id) =>
          jArgs.minId(id)
      }
      xTrimArgs.precision match {
        case XTrimArgs.Precision.Exact =>
          jArgs.exactTrimming()
        case XTrimArgs.Precision.Approximate(limit) =>
          jArgs.approximateTrimming()
          limit.foreach(jArgs.limit)
      }
    }

    async.flatMap(_.xadd(key, jArgs, body.asJava).futureLift.map(MessageId.apply))
  }

  override def xTrim(key: K, args: XTrimArgs): F[Long] =
    async.flatMap(_.xtrim(key, args.asJava).futureLift.map(Long.box(_)))

  override def xDel(key: K, ids: String*): F[Long] =
    async.flatMap(_.xdel(key, ids: _*).futureLift.map(Long.box(_)))

  override def xDelEx(key: K, ids: String*): F[List[StreamEntryDeletionResult]] =
    async.flatMap(_.xdelex(key, ids: _*).futureLift).map(_.asScala.toList.map(_.asScala))

  override def xDelEx(key: K, policy: StreamDeletionPolicy, ids: String*): F[List[StreamEntryDeletionResult]] =
    async
      .flatMap(_.xdelex(key, toJStreamDeletionPolicy(policy), ids: _*).futureLift)
      .map(_.asScala.toList.map(_.asScala))

  override def xCfgSet(key: K, args: XCfgSetArgs): F[Unit] = {
    val jArgs = new JXCfgSetArgs()
    args.idempotencyMaxSize.foreach(jArgs.idmpMaxsize)
    args.idempotencyDuration.foreach(jArgs.idmpDuration)
    async.flatMap(_.xcfgset(key, jArgs).futureLift.void)
  }

  private def toJStreamDeletionPolicy(policy: StreamDeletionPolicy): JStreamDeletionPolicy =
    policy match {
      case StreamDeletionPolicy.KeepReferences   => JStreamDeletionPolicy.KEEP_REFERENCES
      case StreamDeletionPolicy.DeleteReferences => JStreamDeletionPolicy.DELETE_REFERENCES
      case StreamDeletionPolicy.Acknowledged     => JStreamDeletionPolicy.ACKNOWLEDGED
    }

  // format: off
  /************************** Stream Consumer Groups API ************************/
  // format: on
  private def streamOffset(o: XReadOffsets[K]): StreamOffset[K] =
    o match {
      case XReadOffsets.All(key)            => StreamOffset.from(key, "0")
      case XReadOffsets.Latest(key)         => StreamOffset.latest(key)
      case XReadOffsets.Custom(key, offset) => StreamOffset.from(key, offset)
    }

  override def xGroupCreate(key: K, group: K, offset: String, args: XGroupCreateArgs): F[Unit] = {
    val jArgs = new JXGroupCreateArgs().mkstream(args.mkStream)
    args.entriesRead.foreach(jArgs.entriesRead)
    async.flatMap(_.xgroupCreate(StreamOffset.from(key, offset), group, jArgs).futureLift.void)
  }

  override def xGroupSetId(key: K, group: K, offset: String): F[Unit] =
    async.flatMap(_.xgroupSetid(StreamOffset.from(key, offset), group).futureLift.void)

  override def xGroupDestroy(key: K, group: K): F[Boolean] =
    async.flatMap(_.xgroupDestroy(key, group).futureLift.map(x => Boolean.box(x)))

  override def xGroupCreateConsumer(key: K, consumer: StreamConsumer[K]): F[Boolean] =
    async.flatMap(_.xgroupCreateconsumer(key, consumer.asJava).futureLift.map(x => Boolean.box(x)))

  override def xGroupDelConsumer(key: K, consumer: StreamConsumer[K]): F[Long] =
    async.flatMap(_.xgroupDelconsumer(key, consumer.asJava).futureLift.map(x => Long.box(x)))

  override def xInfoGroups(key: K): F[List[XGroupInfo]] =
    async
      .flatMap(_.xinfoGroups(key).futureLift)
      .flatMap(reply => FutureLift[F].delay(reply.asScala.toList.map(XGroupInfo.fromLettuce)))

  override def xInfoConsumers(key: K, group: K): F[List[XConsumerInfo]] =
    async
      .flatMap(_.xinfoConsumers(key, group).futureLift)
      .flatMap(reply => FutureLift[F].delay(reply.asScala.toList.map(XConsumerInfo.fromLettuce)))

  override def xReadGroup(
      consumer: StreamConsumer[K],
      streams: Set[XReadOffsets[K]],
      args: XReadGroupArgs
  ): F[List[StreamMessage[K, V]]] = {
    val offsets = streams.toSeq.map(streamOffset)
    val jArgs   = new XReadArgs().noack(args.noack)
    args.count.foreach(jArgs.count)
    args.block.foreach(b => jArgs.block(b.toMillis))
    async.flatMap(_.xreadgroup(consumer.asJava, jArgs, offsets: _*).futureLift).map(_.toScala)
  }

  override def xAck(key: K, group: K, ids: String*): F[Long] =
    async.flatMap(_.xack(key, group, ids: _*).futureLift.map(x => Long.box(x)))

  override def xAckDel(key: K, group: K, ids: String*): F[List[StreamEntryDeletionResult]] =
    async.flatMap(_.xackdel(key, group, ids: _*).futureLift).map(_.asScala.toList.map(_.asScala))

  override def xAckDel(
      key: K,
      group: K,
      policy: StreamDeletionPolicy,
      ids: String*
  ): F[List[StreamEntryDeletionResult]] =
    async
      .flatMap(_.xackdel(key, group, toJStreamDeletionPolicy(policy), ids: _*).futureLift)
      .map(_.asScala.toList.map(_.asScala))

  override def xNack(key: K, group: K, mode: XNackMode, ids: String*): F[Long] =
    async.flatMap(_.xnack(key, group, toJXNackMode(mode), ids: _*).futureLift.map(x => Long.box(x)))

  private def toJXNackMode(mode: XNackMode): JXNackMode =
    mode match {
      case XNackMode.Silent => JXNackMode.SILENT
      case XNackMode.Fail   => JXNackMode.FAIL
      case XNackMode.Fatal  => JXNackMode.FATAL
    }

  override def xClaim(
      key: K,
      consumer: StreamConsumer[K],
      args: XClaimArgs,
      ids: String*
  ): F[List[StreamMessage[K, V]]] =
    async.flatMap(_.xclaim(key, consumer.asJava, args.asJava, ids: _*).futureLift).map(_.toScala)

  override def xAutoClaim(key: K, args: XAutoClaimArgs[K]): F[XAutoClaimResult[K, V]] =
    async.flatMap(_.xautoclaim(key, args.asJava).futureLift.map(_.asScalaResult))

  override def xPending(key: K, group: K): F[XPendingSummary] =
    async.flatMap(_.xpending(key, group).futureLift.map(_.asScalaSummary))

  override def xPending(
      key: K,
      group: K,
      start: XRangePoint,
      end: XRangePoint,
      count: Long
  ): F[List[XPendingMessage]] = {
    val range = (start, end).asJavaRange
    val limit = JLimit.from(count)
    async.flatMap(_.xpending(key, group, range, limit).futureLift).map(_.asScala.map(_.asScalaMessage).toList)
  }

  override def xPending(
      key: K,
      consumer: StreamConsumer[K],
      start: XRangePoint,
      end: XRangePoint,
      count: Long
  ): F[List[XPendingMessage]] = {
    val range = (start, end).asJavaRange
    val limit = JLimit.from(count)
    async.flatMap(_.xpending(key, consumer.asJava, range, limit).futureLift).map(_.asScala.map(_.asScalaMessage).toList)
  }

  // format: off
  /******************************* PubSub API ***********************************/
  // format: on
  override def publish(channel: RedisChannel[K], message: V): F[Long] =
    async.flatMap(_.publish(channel.underlying, message).futureLift.map(Long.box(_)))

  override def spublish(channel: RedisChannel[K], message: V): F[Long] =
    async.flatMap(_.spublish(channel.underlying, message).futureLift.map(Long.box(_)))

  override def numPat: F[Long] =
    async.flatMap(_.pubsubNumpat.futureLift.map(Long.box(_)))

  override def numSub(channels: NonEmptyList[RedisChannel[K]]): F[List[Subscription[K]]] =
    async.flatMap(_.pubsubNumsub(channels.toList.map(_.underlying): _*).futureLift.map(toSubscription[K]))

  override def pubSubChannels: F[List[RedisChannel[K]]] =
    async.flatMap(_.pubsubChannels().futureLift.map(_.asScala.toList.map(RedisChannel.apply)))

  override def pubSubShardChannels: F[List[RedisChannel[K]]] =
    async.flatMap(_.pubsubShardChannels().futureLift.map(_.asScala.toList.map(RedisChannel.apply)))

  override def pubSubSubscriptions(channel: RedisChannel[K]): F[Option[Subscription[K]]] =
    pubSubSubscriptions(List(channel)).map(_.headOption)

  override def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] =
    async.flatMap(_.pubsubNumsub(channels.map(_.underlying): _*).futureLift.map(toSubscription[K]))

  override def shardNumSub(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] =
    async.flatMap(_.pubsubShardNumsub(channels.map(_.underlying): _*).futureLift.map(toSubscription[K]))
}

private[redis4cats] trait RedisConversionOps {

  import dev.profunktor.redis4cats.JavaConversions._

  private[redis4cats] implicit class GeoWithinOps[V](v: GeoWithin[V]) {
    // Lettuce's GeoWithin fields are null unless the corresponding GeoArgs flag was requested
    // ("if requested, otherwise null" per its own scaladoc) — Option(...) is the correct, safe
    // check here, since it wraps the (possibly-null) boxed reference before anything unboxes it.
    def asGeoSearchResult: GeoSearchResult[V] =
      GeoSearchResult[V](
        v.getMember,
        Option(v.getDistance).map(Distance(_)),
        Option(v.getGeohash).map(GeoHash(_)),
        Option(v.getCoordinates).map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue()))
      )
  }

  private[redis4cats] implicit class ZRangeOps[T: Numeric](range: ZRange[T]) {
    def asJavaRange: JRange[Number] = {
      def toJavaNumber(t: T): java.lang.Number = t match {
        case b: Byte  => b
        case s: Short => s
        case i: Int   => i
        case l: Long  => l
        case f: Float => f
        case _        => implicitly[Numeric[T]].toDouble(t)
      }
      val start: Number = toJavaNumber(range.start)
      val end: Number   = toJavaNumber(range.end)
      JRange.create(start, end)
    }
  }

  private[redis4cats] implicit class XTrimArgsOps(args: XTrimArgs) {
    def asJava: JXTrimArgs = {
      val jArgs = args.strategy match {
        case XTrimArgs.Strategy.MAXLEN(threshold) =>
          JXTrimArgs.Builder.maxlen(threshold)
        case XTrimArgs.Strategy.MINID(id) =>
          JXTrimArgs.Builder.minId(id)
      }
      args.precision match {
        case XTrimArgs.Precision.Exact =>
          jArgs.exactTrimming()
        case XTrimArgs.Precision.Approximate(limit) =>
          jArgs.approximateTrimming()
          limit.foreach(jArgs.limit)
      }
      jArgs
    }
  }

  private[redis4cats] implicit class XRangeOps(range: (XRangePoint, XRangePoint)) {
    def asJavaRange: JRange[String] =
      JRange.from(range._1.asJavaBoundary, range._2.asJavaBoundary)
  }

  private[redis4cats] implicit class XRangePointOps(point: XRangePoint) {
    def asJavaBoundary: JRange.Boundary[String] =
      point match {
        case XRangePoint.Unbounded =>
          JRange.Boundary.unbounded()
        case XRangePoint.Inclusive(id) =>
          JRange.Boundary.including(id)
        case XRangePoint.Exclusive(id) =>
          JRange.Boundary.excluding(id)
      }
  }

  private[redis4cats] implicit class StreamMessagesOps[K, V](list: util.List[core.StreamMessage[K, V]]) {
    def toScala: List[StreamMessage[K, V]] =
      list.asScala.map { msg =>
        // The body is null for id-only replies (e.g. XCLAIM/XAUTOCLAIM with JUSTID).
        val body = Option(msg.getBody).map(_.asScala.toMap).getOrElse(Map.empty[K, V])
        StreamMessage[K, V](MessageId(msg.getId), msg.getStream, body)
      }.toList
  }

  private[redis4cats] implicit class StreamConsumerOps[K](consumer: StreamConsumer[K]) {
    def asJava: JConsumer[K] = JConsumer.from(consumer.group, consumer.consumer)
  }

  private[redis4cats] implicit class StreamEntryDeletionResultOps(result: JStreamEntryDeletionResult) {
    def asScala: StreamEntryDeletionResult =
      result match {
        case JStreamEntryDeletionResult.DELETED => StreamEntryDeletionResult.Deleted
        case JStreamEntryDeletionResult.NOT_DELETED_UNACKNOWLEDGED_OR_STILL_REFERENCED =>
          StreamEntryDeletionResult.NotDeletedUnacknowledgedOrStillReferenced
        case JStreamEntryDeletionResult.NOT_FOUND => StreamEntryDeletionResult.NotFound
        case JStreamEntryDeletionResult.UNKNOWN   => StreamEntryDeletionResult.Unknown
      }
  }

  private[redis4cats] implicit class XClaimArgsOps(args: XClaimArgs) {
    def asJava: JXClaimArgs = {
      val jArgs = new JXClaimArgs().minIdleTime(args.minIdleTime.toMillis)
      args.idle.foreach {
        case XClaimIdle.Relative(d) => jArgs.idle(d.toMillis)
        case XClaimIdle.At(t)       => jArgs.time(t)
      }
      args.retryCount.foreach(jArgs.retryCount)
      if (args.force) { jArgs.force(); () }
      if (args.justId) { jArgs.justid(); () }
      jArgs
    }
  }

  private[redis4cats] implicit class XAutoClaimArgsOps[K](args: XAutoClaimArgs[K]) {
    def asJava: JXAutoClaimArgs[K] = {
      val jArgs = new JXAutoClaimArgs[K]()
        .consumer(args.consumer.asJava)
        .minIdleTime(args.minIdleTime.toMillis)
        .startId(args.start)
      args.count.foreach(jArgs.count)
      if (args.justId) { jArgs.justid(); () }
      jArgs
    }
  }

  private[redis4cats] implicit class PendingMessagesOps(pm: PendingMessages) {
    def asScalaSummary: XPendingSummary = {
      val ids = Option(pm.getMessageIds)
      def boundary(b: JRange.Boundary[String]): Option[MessageId] =
        if (b == null || b.isUnbounded) None else Option(b.getValue).map(MessageId(_))
      XPendingSummary(
        count = pm.getCount,
        minId = ids.flatMap(r => boundary(r.getLower)),
        maxId = ids.flatMap(r => boundary(r.getUpper)),
        consumers = pm.getConsumerMessageCount.asScala.iterator.map { case (k, v) => k -> Long.unbox(v) }.toMap
      )
    }
  }

  private[redis4cats] implicit class PendingMessageOps(pm: PendingMessage) {
    def asScalaMessage: XPendingMessage =
      XPendingMessage(
        id = MessageId(pm.getId),
        consumer = pm.getConsumer,
        sinceLastDelivery = FiniteDuration(pm.getMsSinceLastDelivery, MILLISECONDS),
        redeliveryCount = pm.getRedeliveryCount
      )
  }

  private[redis4cats] implicit class ClaimedMessagesOps[K, V](cm: ClaimedMessages[K, V]) {
    def asScalaResult: XAutoClaimResult[K, V] =
      XAutoClaimResult(MessageId(cm.getId), cm.getMessages.toScala)
  }

  private[redis4cats] implicit class CopyArgOps(underlying: CopyArgs) {
    def asJava: JCopyArgs = {
      val jCopyArgs = new JCopyArgs()
      underlying.destinationDb.foreach(jCopyArgs.destinationDb)
      underlying.replace.foreach(jCopyArgs.replace)
      jCopyArgs
    }
  }

  private[redis4cats] implicit class RestoreArgOps(underlying: RestoreArgs) {

    def asJava: JRestoreArgs = {
      val u = new JRestoreArgs
      underlying.ttl.foreach(u.ttl)
      underlying.replace.foreach(u.replace)
      underlying.absttl.foreach(u.absttl)
      underlying.idleTime.foreach(u.idleTime)
      u
    }
  }

  private[redis4cats] implicit class ExpireExistenceArgOps(underlying: ExpireExistenceArg) {
    def asJava: JExpireArgs = {
      val jExpireArgs = new JExpireArgs()

      underlying match {
        case ExpireExistenceArg.Nx => jExpireArgs.nx()
        case ExpireExistenceArg.Xx => jExpireArgs.xx()
        case ExpireExistenceArg.Gt => jExpireArgs.gt()
        case ExpireExistenceArg.Lt => jExpireArgs.lt()
      }

      jExpireArgs
    }
  }

  private[redis4cats] implicit class ScoredValuesOps[V](v: ScoredValue[V]) {
    def asScoreWithValues: ScoreWithValue[V] = ScoreWithValue[V](Score(v.getScore), v.getValue)
  }

  private[redis4cats] implicit class DurationOps(d: Duration) {
    def toSecondsOrZero: Long = d match {
      case _: Duration.Infinite     => 0
      case duration: FiniteDuration => duration.toSeconds
    }
  }

  private[redis4cats] implicit class ResponseOps(str: String) {
    def isSuccess: Boolean = str == "OK"
  }

}

private[redis4cats] class Redis[F[_]: FutureLift: MonadThrow: Log, K, V](
    connection: RedisStatefulConnection[F, K, V],
    tx: TxRunner[F]
) extends BaseRedis[F, K, V](connection, tx, cluster = false)

private[redis4cats] class RedisCluster[F[_]: FutureLift: MonadThrow: Log, K, V](
    connection: RedisStatefulClusterConnection[F, K, V],
    tx: TxRunner[F]
) extends BaseRedis[F, K, V](connection, tx, cluster = true)
