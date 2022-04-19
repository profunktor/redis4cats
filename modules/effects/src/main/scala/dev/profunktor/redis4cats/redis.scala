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
import java.util.concurrent.TimeUnit
import cats._
import cats.data.NonEmptyList
import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.BitCommandOperation
import dev.profunktor.redis4cats.algebra.BitCommandOperation.Overflows
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect._
import dev.profunktor.redis4cats.effect.FutureLift._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.tx.TransactionDiscarded
import io.lettuce.core.{
  BitFieldArgs,
  ClientOptions,
  GeoArgs,
  GeoRadiusStoreArgs,
  GeoWithin,
  ScoredValue,
  ZAddArgs,
  ZAggregateArgs,
  ZStoreArgs,
  Limit => JLimit,
  Range => JRange,
  ReadFrom => JReadFrom,
  ScanCursor => JScanCursor,
  SetArgs => JSetArgs
}
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.{ RedisClusterCommands => RedisClusterSyncCommands }

import scala.concurrent.duration._

object Redis {

  private[redis4cats] def acquireAndRelease[F[_]: FutureLift: Log: MonadThrow, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): (F[Redis[F, K, V]], Redis[F, K, V] => F[Unit]) = {
    val acquire: F[Redis[F, K, V]] = FutureLift[F]
      .liftConnectionFuture(client.underlying.connectAsync(codec.underlying, client.uri.underlying))
      .map(c => new Redis[F, K, V](new RedisStatefulConnection[F, K, V](c)))

    val release: Redis[F, K, V] => F[Unit] = c =>
      Log[F].info(s"Releasing Commands connection: ${client.uri.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseCluster[F[_]: FutureLift: Log: MonadThrow, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom]
  ): (F[RedisCluster[F, K, V]], RedisCluster[F, K, V] => F[Unit]) = {
    val acquire: F[RedisCluster[F, K, V]] = FutureLift[F]
      .liftCompletableFuture(client.underlying.connectAsync[K, V](codec.underlying))
      .flatTap(c => FutureLift[F].delay(readFrom.foreach(c.setReadFrom)))
      .map(c => new RedisCluster[F, K, V](new RedisStatefulClusterConnection[F, K, V](c)))

    val release: RedisCluster[F, K, V] => F[Unit] = c =>
      Log[F].info(s"Releasing cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseClusterByNode[F[_]: FutureLift: Log: MonadThrow, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom],
      nodeId: NodeId
  ): (F[BaseRedis[F, K, V]], BaseRedis[F, K, V] => F[Unit]) = {
    val acquire = FutureLift[F]
      .liftCompletableFuture(client.underlying.connectAsync[K, V](codec.underlying))
      .flatTap(c => FutureLift[F].delay(readFrom.foreach(c.setReadFrom)))
      .map { c =>
        new BaseRedis[F, K, V](new RedisStatefulClusterConnection[F, K, V](c), cluster = true) {
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

    /**
      * Creates a [[RedisCommands]] for a single-node connection.
      *
      * It will create an underlying RedisClient with default options to establish
      * connection with Redis.
      *
      * Example:
      *
      * {{{
      * Redis[IO].simple("redis://localhost", RedisCodec.Ascii)
      * }}}
      *
      * Note: if you need to create multiple connections, use `fromClient`
      * instead, which allows you to re-use the same client.
      */
    def simple[K, V](uri: String, codec: RedisCodec[K, V]): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].clientFrom(uri).flatMap(this.fromClient(_, codec))

    /**
      * Creates a [[RedisCommands]] for a single-node connection.
      *
      * It will create an underlying RedisClient using the supplied client options
      * to establish connection with Redis.
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
      * Note: if you need to create multiple connections, use `fromClient`
      * instead, which allows you to re-use the same client.
      */
    def withOptions[K, V](
        uri: String,
        opts: ClientOptions,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      MkRedis[F].clientWithOptions(uri, opts).flatMap(this.fromClient(_, codec))

    /**
      * Creates a [[RedisCommands]] for a single-node connection to deal
      * with UTF-8 encoded keys and values.
      *
      * It will create an underlying RedisClient with default options to establish
      * connection with Redis.
      *
      * Example:
      *
      * {{{
      * Redis[IO].utf8("redis://localhost")
      * }}}
      *
      * Note: if you need to create multiple connections, use `fromClient`
      * instead, which allows you to re-use the same client.
      */
    def utf8(uri: String): Resource[F, RedisCommands[F, String, String]] =
      simple(uri, RedisCodec.Utf8)

    /**
      * Creates a [[RedisCommands]] for a single-node connection.
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
      * Note: if you don't need to create multiple connections, you might
      * prefer to use either [[utf8]] or `simple` instead.
      */
    def fromClient[K, V](
        client: RedisClient,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] = {
      val (acquire, release) = acquireAndRelease[F, K, V](client, codec)
      Resource.make(acquire)(release).widen
    }

    /**
      * Creates a [[RedisCommands]] for a cluster connection.
      *
      * It will also create an underlying RedisClusterClient to establish
      * connection with Redis.
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
      * Note: if you need to create multiple connections, use either [[fromClusterClient]]
      * or [[fromClusterClientByNode]] instead, which allows you to re-use the same client.
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

    /**
      * Creates a [[RedisCommands]] for a cluster connection to deal
      * with UTF-8 encoded keys and values.
      *
      * It will also create an underlying RedisClusterClient to establish
      * connection with Redis.
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
      * Note: if you need to create multiple connections, use either [[fromClusterClient]]
      * or [[fromClusterClientByNode]] instead, which allows you to re-use the same client.
      */
    def clusterUtf8(
        uris: String*
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, String, String]] =
      cluster(RedisCodec.Utf8, uris: _*)(readFrom)

    /**
      * Creates a [[RedisCommands]] for a cluster connection
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
      * Note: if you don't need to create multiple connections, you might
      * prefer to use either [[clusterUtf8]] or [[cluster]] instead.
      */
    def fromClusterClient[K, V](
        clusterClient: RedisClusterClient,
        codec: RedisCodec[K, V]
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] = {
      val (acquire, release) = acquireAndReleaseCluster(clusterClient, codec, readFrom)
      Resource.make(acquire)(release).widen
    }

    /**
      * Creates a [[RedisCommands]] by trying to establish a cluster
      * connection to the specified node.
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
      * Note: if you don't need to create multiple connections, you might
      * prefer to use either [[clusterUtf8]] or [[cluster]] instead.
      */
    def fromClusterClientByNode[K, V](
        clusterClient: RedisClusterClient,
        codec: RedisCodec[K, V],
        nodeId: NodeId
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] = {
      val (acquire, release) = acquireAndReleaseClusterByNode(clusterClient, codec, readFrom, nodeId)
      Resource.make(acquire)(release).widen
    }

    /**
      * Creates a [[RedisCommands]] from a MasterReplica connection
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
      Resource.pure {
        new Redis[F, K, V](new RedisStatefulConnection(conn.underlying))
      }

  }

  def apply[F[_]: MkRedis: MonadThrow]: RedisPartiallyApplied[F] = new RedisPartiallyApplied[F]

}

private[redis4cats] class BaseRedis[F[_]: FutureLift: MonadThrow: Log, K, V](
    val conn: RedisConnection[F, K, V],
    val cluster: Boolean
) extends RedisCommands[F, K, V]
    with RedisConversionOps {

  def liftK[G[_]: Async: Log]: RedisCommands[G, K, V] =
    new BaseRedis[G, K, V](conn.liftK[G], cluster)

  import dev.profunktor.redis4cats.JavaConversions._

  def async: F[RedisClusterAsyncCommands[K, V]] =
    if (cluster) conn.clusterAsync else conn.async.widen

  def sync: F[RedisClusterSyncCommands[K, V]] =
    if (cluster) conn.clusterSync else conn.sync.widen

  /******************************* Keys API *************************************/
  def del(key: K*): F[Long] =
    async.flatMap(_.del(key: _*).futureLift.map(x => Long.box(x)))

  override def exists(key: K*): F[Boolean] =
    async.flatMap(_.exists(key: _*).futureLift.map(_ == key.size.toLong))

  /**
    * Expires a key with the given duration. If specified either in MILLISECONDS, MICROSECONDS or NANOSECONDS,
    * the value will be converted to MILLISECONDS. Otherwise, it will be converted to SECONDS.
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

  /**
    * Expires a key at the given date.
    *
    * It calls Redis' PEXPIREAT under the hood, which has milliseconds precision.
    */
  override def expireAt(key: K, at: Instant): F[Boolean] =
    async.flatMap(_.pexpireat(key, at.toEpochMilli()).futureLift.map(x => Boolean.box(x)))

  override def objectIdletime(key: K): F[Option[FiniteDuration]] =
    async.flatMap(_.objectIdletime(key).futureLift).map {
      case null => none[FiniteDuration]
      case d    => FiniteDuration(d, TimeUnit.SECONDS).some
    }

  private def toFiniteDuration(duration: java.lang.Long): Option[FiniteDuration] =
    duration match {
      case d if d < 0 => none[FiniteDuration]
      case d          => FiniteDuration(d, TimeUnit.SECONDS).some
    }

  override def ttl(key: K): F[Option[FiniteDuration]] =
    async.flatMap(_.ttl(key).futureLift.map(toFiniteDuration))

  override def pttl(key: K): F[Option[FiniteDuration]] =
    async.flatMap(_.pttl(key).futureLift.map(toFiniteDuration))

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

  /******************************* Transactions API **********************************/
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

  /******************************* AutoFlush API **********************************/
  override def enableAutoFlush: F[Unit] =
    async.flatMap(c => FutureLift[F].delay(c.setAutoFlushCommands(true)))

  override def disableAutoFlush: F[Unit] =
    async.flatMap(c => FutureLift[F].delay(c.setAutoFlushCommands(false)))

  override def flushCommands: F[Unit] =
    async.flatMap(c => FutureLift[F].delay(c.flushCommands()))

  /******************************* Strings API **********************************/
  override def append(key: K, value: V): F[Unit] =
    async.flatMap(_.append(key, value).futureLift.void)

  override def getSet(key: K, value: V): F[Option[V]] =
    async.flatMap(_.getset(key, value).futureLift.map(Option.apply))

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
    }

    async.flatMap(_.set(key, value, jSetArgs).futureLift.map(_ == "OK"))
  }

  override def setNx(key: K, value: V): F[Boolean] =
    async.flatMap(_.setnx(key, value).futureLift.map(x => Boolean.box(x)))

  override def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit] =
    expiresIn.unit match {
      case TimeUnit.MILLISECONDS | TimeUnit.MICROSECONDS | TimeUnit.NANOSECONDS =>
        async.flatMap(_.psetex(key, expiresIn.toMillis, value).futureLift.void)
      case _ =>
        async.flatMap(_.setex(key, expiresIn.toSeconds, value).futureLift.void)
    }

  override def setRange(key: K, value: V, offset: Long): F[Unit] =
    async.flatMap(_.setrange(key, offset, value).futureLift.void)

  override def decr(key: K): F[Long] =
    async.flatMap(_.decr(key).futureLift.map(x => Long.box(x)))

  override def decrBy(key: K, amount: Long): F[Long] =
    async.flatMap(_.incrby(key, amount).futureLift.map(x => Long.box(x)))

  override def incr(key: K): F[Long] =
    async.flatMap(_.incr(key).futureLift.map(x => Long.box(x)))

  override def incrBy(key: K, amount: Long): F[Long] =
    async.flatMap(_.incrby(key, amount).futureLift.map(x => Long.box(x)))

  override def incrByFloat(key: K, amount: Double): F[Double] =
    async.flatMap(_.incrbyfloat(key, amount).futureLift.map(x => Double.box(x)))

  override def get(key: K): F[Option[V]] =
    async.flatMap(_.get(key).futureLift.map(Option.apply))

  override def getRange(key: K, start: Long, end: Long): F[Option[V]] =
    async.flatMap(_.getrange(key, start, end).futureLift.map(Option.apply))

  override def strLen(key: K): F[Option[Long]] =
    async.flatMap(_.strlen(key).futureLift.map(x => Option(Long.unbox(x))))

  override def mGet(keys: Set[K]): F[Map[K, V]] =
    async
      .flatMap(_.mget(keys.toSeq: _*).futureLift)
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def mSet(keyValues: Map[K, V]): F[Unit] =
    async.flatMap(_.mset(keyValues.asJava).futureLift.void)

  override def mSetNx(keyValues: Map[K, V]): F[Boolean] =
    async.flatMap(_.msetnx(keyValues.asJava).futureLift.map(x => Boolean.box(x)))

  /******************************* Hashes API **********************************/
  override def hDel(key: K, fields: K*): F[Long] =
    async.flatMap(_.hdel(key, fields: _*).futureLift.map(x => Long.box(x)))

  override def hExists(key: K, field: K): F[Boolean] =
    async.flatMap(_.hexists(key, field).futureLift.map(x => Boolean.box(x)))

  override def hGet(key: K, field: K): F[Option[V]] =
    async.flatMap(_.hget(key, field).futureLift.map(Option.apply))

  override def hGetAll(key: K): F[Map[K, V]] =
    async.flatMap(_.hgetall(key).futureLift.map(_.asScala.toMap))

  override def hmGet(key: K, fields: K*): F[Map[K, V]] =
    async
      .flatMap(_.hmget(key, fields: _*).futureLift)
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def hKeys(key: K): F[List[K]] =
    async.flatMap(_.hkeys(key).futureLift.map(_.asScala.toList))

  override def hVals(key: K): F[List[V]] =
    async.flatMap(_.hvals(key).futureLift.map(_.asScala.toList))

  override def hStrLen(key: K, field: K): F[Option[Long]] =
    async.flatMap(_.hstrlen(key, field).futureLift.map(x => Option(Long.unbox(x))))

  override def hLen(key: K): F[Option[Long]] =
    async.flatMap(_.hlen(key).futureLift.map(x => Option(Long.unbox(x))))

  override def hSet(key: K, field: K, value: V): F[Boolean] =
    async.flatMap(_.hset(key, field, value).futureLift.map(x => Boolean.box(x)))

  override def hSet(key: K, fieldValues: Map[K, V]): F[Long] =
    async.flatMap(_.hset(key, fieldValues.asJava).futureLift.map(x => Long.box(x)))

  override def hSetNx(key: K, field: K, value: V): F[Boolean] =
    async.flatMap(_.hsetnx(key, field, value).futureLift.map(x => Boolean.box(x)))

  override def hmSet(key: K, fieldValues: Map[K, V]): F[Unit] =
    async.flatMap(_.hmset(key, fieldValues.asJava).futureLift.void)

  override def hIncrBy(key: K, field: K, amount: Long): F[Long] =
    async.flatMap(_.hincrby(key, field, amount).futureLift.map(x => Long.box(x)))

  override def hIncrByFloat(key: K, field: K, amount: Double): F[Double] =
    async.flatMap(_.hincrbyfloat(key, field, amount).futureLift.map(x => Double.box(x)))

  /******************************* Sets API **********************************/
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

  override def sRem(key: K, values: V*): F[Unit] =
    async.flatMap(_.srem(key, values: _*).futureLift.void)

  override def sCard(key: K): F[Long] =
    async.flatMap(_.scard(key).futureLift.map(x => Long.box(x)))

  override def sDiff(keys: K*): F[Set[V]] =
    async.flatMap(_.sdiff(keys: _*).futureLift.map(_.asScala.toSet))

  override def sInter(keys: K*): F[Set[V]] =
    async.flatMap(_.sinter(keys: _*).futureLift.map(_.asScala.toSet))

  override def sMembers(key: K): F[Set[V]] =
    async.flatMap(_.smembers(key).futureLift.map(_.asScala.toSet))

  override def sRandMember(key: K): F[Option[V]] =
    async.flatMap(_.srandmember(key).futureLift.map(Option.apply))

  override def sRandMember(key: K, count: Long): F[List[V]] =
    async.flatMap(_.srandmember(key, count).futureLift.map(_.asScala.toList))

  override def sUnion(keys: K*): F[Set[V]] =
    async.flatMap(_.sunion(keys: _*).futureLift.map(_.asScala.toSet))

  override def sUnionStore(destination: K, keys: K*): F[Unit] =
    async.flatMap(_.sunionstore(destination, keys: _*).futureLift.void)

  /******************************* Lists API **********************************/
  override def lIndex(key: K, index: Long): F[Option[V]] =
    async.flatMap(_.lindex(key, index).futureLift.map(Option.apply))

  override def lLen(key: K): F[Option[Long]] =
    async.flatMap(_.llen(key).futureLift.map(x => Option(Long.unbox(x))))

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

  override def brPopLPush(timeout: Duration, source: K, destination: K): F[Option[V]] =
    async.flatMap(_.brpoplpush(timeout.toSecondsOrZero, source, destination).futureLift.map(Option.apply))

  override def lPop(key: K): F[Option[V]] =
    async.flatMap(_.lpop(key).futureLift.map(Option.apply))

  override def lPush(key: K, values: V*): F[Long] =
    async.flatMap(_.lpush(key, values: _*).futureLift.map(x => Long.box(x)))

  override def lPushX(key: K, values: V*): F[Long] =
    async.flatMap(_.lpushx(key, values: _*).futureLift.map(x => Long.box(x)))

  override def rPop(key: K): F[Option[V]] =
    async.flatMap(_.rpop(key).futureLift.map(Option.apply))

  override def rPopLPush(source: K, destination: K): F[Option[V]] =
    async.flatMap(_.rpoplpush(source, destination).futureLift.map(Option.apply))

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

  /******************************* Bitmaps API **********************************/
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

  override def bitOpAnd(destination: K, sources: K*): F[Unit] =
    async.flatMap(_.bitopAnd(destination, sources: _*).futureLift.void)

  override def bitOpNot(destination: K, source: K): F[Unit] =
    async.flatMap(_.bitopNot(destination, source).futureLift.void)

  override def bitOpOr(destination: K, sources: K*): F[Unit] =
    async.flatMap(_.bitopOr(destination, sources: _*).futureLift.void)

  override def bitOpXor(destination: K, sources: K*): F[Unit] =
    async.flatMap(_.bitopXor(destination, sources: _*).futureLift.void)

  override def getBit(key: K, offset: Long): F[Option[Long]] =
    async.flatMap(_.getbit(key, offset).futureLift.map(x => Option(Long.unbox(x))))

  override def setBit(key: K, offset: Long, value: Int): F[Long] =
    async.flatMap(_.setbit(key, offset, value).futureLift.map(x => Long.box(x)))

  /******************************* Geo API **********************************/
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double] =
    async.flatMap(_.geodist(key, from, to, unit).futureLift.map(x => Double.box(x)))

  override def geoHash(key: K, values: V*): F[List[Option[String]]] =
    async
      .flatMap(_.geohash(key, values: _*).futureLift)
      .map(_.asScala.toList.map(x => Option(x.getValue)))

  override def geoPos(key: K, values: V*): F[List[GeoCoordinate]] =
    async
      .flatMap(_.geopos(key, values: _*).futureLift)
      .map(_.asScala.toList.map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue())))

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit): F[Set[V]] =
    async
      .flatMap(_.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit).futureLift)
      .map(_.asScala.toSet)

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    async
      .flatMap(_.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit, args).futureLift)
      .map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit): F[Set[V]] =
    async.flatMap(_.georadiusbymember(key, value, dist.value, unit).futureLift.map(_.asScala.toSet))

  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: Distance,
      unit: GeoArgs.Unit,
      args: GeoArgs
  ): F[List[GeoRadiusResult[V]]] =
    async
      .flatMap(_.georadiusbymember(key, value, dist.value, unit, args).futureLift)
      .map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Unit] = {
    val triplets = geoValues.flatMap(g => Seq[Any](g.lon.value, g.lat.value, g.value)).asInstanceOf[Seq[AnyRef]]
    async.flatMap(_.geoadd(key, triplets: _*).futureLift.void)
  }

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit] =
    conn.async.flatMap {
      _.georadius(
        key,
        geoRadius.lon.value,
        geoRadius.lat.value,
        geoRadius.dist.value,
        unit,
        storage.asGeoRadiusStoreArgs
      ).futureLift.void
    }

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusDistStorage[K]): F[Unit] =
    conn.async.flatMap {
      _.georadius(
        key,
        geoRadius.lon.value,
        geoRadius.lat.value,
        geoRadius.dist.value,
        unit,
        storage.asGeoRadiusStoreArgs
      ).futureLift.void
    }

  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: Distance,
      unit: GeoArgs.Unit,
      storage: GeoRadiusKeyStorage[K]
  ): F[Unit] =
    async.flatMap(_.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs).futureLift.void)

  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: Distance,
      unit: GeoArgs.Unit,
      storage: GeoRadiusDistStorage[K]
  ): F[Unit] =
    async.flatMap(_.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs).futureLift.void)

  /******************************* Sorted Sets API **********************************/
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

  override def zRem(key: K, values: V*): F[Long] =
    async.flatMap(_.zrem(key, values: _*).futureLift.map(x => Long.box(x)))

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

  override def zCard(key: K): F[Option[Long]] =
    async.flatMap(_.zcard(key).futureLift.map(x => Option(Long.unbox(x))))

  override def zCount[T: Numeric](key: K, range: ZRange[T]): F[Option[Long]] =
    async.flatMap(_.zcount(key, range.asJavaRange).futureLift.map(x => Option(Long.unbox(x))))

  override def zLexCount(key: K, range: ZRange[V]): F[Option[Long]] =
    async.flatMap(_.zlexcount(key, JRange.create[V](range.start, range.end)).futureLift.map(x => Option(Long.unbox(x))))

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
      .map(Option(_).map(kv => (kv.getKey, kv.getValue.asScoreWithValues)))

  override def bzPopMax(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]] =
    async
      .flatMap(_.bzpopmax(timeout.toSecondsOrZero, keys.toList: _*).futureLift)
      .map(Option(_).map(kv => (kv.getKey, kv.getValue.asScoreWithValues)))

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

  override def zDiff(keys: K*): F[List[V]] =
    async.flatMap(_.zdiff(keys: _*).futureLift.map(_.asScala.toList))

  override def zDiffWithScores(keys: K*): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(_.zdiffWithScores(keys: _*).futureLift)
      .map(_.asScala.toList.map(_.asScoreWithValues))

  /******************************* Connection API **********************************/
  override val ping: F[String] =
    async.flatMap(_.ping().futureLift)

  override def select(index: Int): F[Unit] =
    conn.async.flatMap(_.select(index).futureLift.void)

  override def auth(password: CharSequence): F[Boolean] =
    async.flatMap(_.auth(password).futureLift.map(_ == "OK"))

  override def auth(username: String, password: CharSequence): F[Boolean] =
    async.flatMap(_.auth(username, password).futureLift.map(_ == "OK"))

  /******************************* Server API **********************************/
  override val flushAll: F[Unit] =
    async.flatMap(_.flushall().futureLift.void)

  override def keys(key: K): F[List[K]] =
    async.flatMap(_.keys(key).futureLift.map(_.asScala.toList))

  override def info: F[Map[String, String]] =
    async
      .flatMap(_.info.futureLift)
      .flatMap { info =>
        FutureLift[F].delay(
          info
            .split("\\r?\\n")
            .toList
            .map(_.split(":", 2).toList)
            .collect { case k :: v :: Nil => (k, v) }
            .toMap
        )
      }

  override def dbsize: F[Long] =
    async.flatMap(_.dbsize.futureLift.map(Long.unbox))

  override def lastSave: F[Instant] =
    async.flatMap(_.lastsave.futureLift.map(_.toInstant))

  override def slowLogLen: F[Long] =
    async.flatMap(_.slowlogLen.futureLift.map(Long.unbox))

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
        // see comment in eval above
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
        // see comment in eval above
        keys.toArray[Any].asInstanceOf[Array[K with Object]]
      ).futureLift.map(output.convert(_))
    )

  override def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async.flatMap(
      _.evalsha[output.Underlying](
        digest,
        output.outputType,
        // see comment in eval above
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

  override def digest(script: String): F[String] =
    async.map(_.digest(script))

  /** ***************************** HyperLoglog API **********************************/
  override def pfAdd(key: K, values: V*): F[Long] =
    async.flatMap(_.pfadd(key, values: _*).futureLift.map(Long.box(_)))

  override def pfCount(key: K): F[Long] =
    async.flatMap(_.pfcount(key).futureLift.map(Long.box(_)))

  override def pfMerge(outputKey: K, inputKeys: K*): F[Unit] =
    async.flatMap(_.pfmerge(outputKey, inputKeys: _*).futureLift.void)
}

private[redis4cats] trait RedisConversionOps {

  private[redis4cats] implicit class GeoRadiusResultOps[V](v: GeoWithin[V]) {
    def asGeoRadiusResult: GeoRadiusResult[V] =
      GeoRadiusResult[V](
        v.getMember,
        Distance(v.getDistance),
        GeoHash(v.getGeohash),
        GeoCoordinate(v.getCoordinates.getX.doubleValue(), v.getCoordinates.getY.doubleValue())
      )
  }

  private[redis4cats] implicit class GeoRadiusKeyStorageOps[K](v: GeoRadiusKeyStorage[K]) {
    def asGeoRadiusStoreArgs: GeoRadiusStoreArgs[K] = {
      val store: GeoRadiusStoreArgs[_] = GeoRadiusStoreArgs.Builder
        .store[K](v.key)
        .withCount(v.count)
      store.asInstanceOf[GeoRadiusStoreArgs[K]]
    }
  }

  private[redis4cats] implicit class GeoRadiusDistStorageOps[K](v: GeoRadiusDistStorage[K]) {
    def asGeoRadiusStoreArgs: GeoRadiusStoreArgs[K] = {
      val store: GeoRadiusStoreArgs[_] = GeoRadiusStoreArgs.Builder
        .withStoreDist[K](v.key)
        .withCount(v.count)
      store.asInstanceOf[GeoRadiusStoreArgs[K]]
    }
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

  private[redis4cats] implicit class ScoredValuesOps[V](v: ScoredValue[V]) {
    def asScoreWithValues: ScoreWithValue[V] = ScoreWithValue[V](Score(v.getScore), v.getValue)
  }

  private[redis4cats] implicit class DurationOps(d: Duration) {
    def toSecondsOrZero: Long = d match {
      case _: Duration.Infinite     => 0
      case duration: FiniteDuration => duration.toSeconds
    }
  }

}

private[redis4cats] class Redis[F[_]: FutureLift: MonadThrow: Log, K, V](
    connection: RedisStatefulConnection[F, K, V]
) extends BaseRedis[F, K, V](connection, cluster = false)

private[redis4cats] class RedisCluster[F[_]: FutureLift: MonadThrow: Log, K, V](
    connection: RedisStatefulClusterConnection[F, K, V]
) extends BaseRedis[F, K, V](connection, cluster = true)
