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

import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.{ JRFuture, Log, RedisExecutor }
import dev.profunktor.redis4cats.effect.JRFuture._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.transactions.TransactionDiscarded
import io.lettuce.core.{
  ClientOptions,
  GeoArgs,
  GeoRadiusStoreArgs,
  GeoWithin,
  ScanCursor => JScanCursor,
  ScoredValue,
  ZAddArgs,
  ZStoreArgs,
  Limit => JLimit,
  Range => JRange,
  ReadFrom => JReadFrom,
  SetArgs => JSetArgs
}
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.{ RedisClusterCommands => RedisClusterSyncCommands }

import scala.concurrent.duration._

object Redis {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): (F[Redis[F, K, V]], Redis[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromConnectionFuture(
        RedisExecutor[F].delay(client.underlying.connectAsync(codec.underlying, client.uri.underlying))
      )
      .map(c => new Redis(new RedisStatefulConnection(c)))

    val release: Redis[F, K, V] => F[Unit] = c =>
      F.info(s"Releasing Commands connection: ${client.uri.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseCluster[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom]
  ): (F[RedisCluster[F, K, V]], RedisCluster[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromCompletableFuture(
        RedisExecutor[F].delay(client.underlying.connectAsync[K, V](codec.underlying))
      )
      .flatTap(c => F.delay(readFrom.foreach(c.setReadFrom)))
      .map(c => new RedisCluster(new RedisStatefulClusterConnection(c)))

    val release: RedisCluster[F, K, V] => F[Unit] = c =>
      F.info(s"Releasing cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseClusterByNode[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom],
      nodeId: NodeId
  ): (F[BaseRedis[F, K, V]], BaseRedis[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromCompletableFuture(
        RedisExecutor[F].delay(client.underlying.connectAsync[K, V](codec.underlying))
      )
      .flatTap(c => F.delay(readFrom.foreach(c.setReadFrom)))
      .map { c =>
        new BaseRedis[F, K, V](new RedisStatefulClusterConnection(c), cluster = true) {
          override def async: F[RedisClusterAsyncCommands[K, V]] =
            if (cluster) conn.byNode(nodeId).widen[RedisClusterAsyncCommands[K, V]]
            else conn.async.widen[RedisClusterAsyncCommands[K, V]]
        }
      }

    val release: BaseRedis[F, K, V] => F[Unit] = c =>
      F.info(s"Releasing single-shard cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  class RedisPartiallyApplied[F[_]: Concurrent: ContextShift: Log] {

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
      * Note: if you need to create multiple connections, use [[fromClient]]
      * instead, which allows you to re-use the same client.
      */
    def simple[K, V](uri: String, codec: RedisCodec[K, V]): Resource[F, RedisCommands[F, K, V]] =
      RedisClient[F].from(uri).flatMap(this.fromClient(_, codec))

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
      *   opts <- Resource.liftF(F.delay(ClientOptions.create())) // configure timeouts, etc
      *   cmds <- Redis[IO].withOptions("redis://localhost", opts, RedisCodec.Ascii)
      * } yield cmds
      * }}}
      *
      * Note: if you need to create multiple connections, use [[fromClient]]
      * instead, which allows you to re-use the same client.
      */
    def withOptions[K, V](
        uri: String,
        opts: ClientOptions,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      RedisClient[F].withOptions(uri, opts).flatMap(this.fromClient(_, codec))

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
      * Note: if you need to create multiple connections, use [[fromClient]]
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
      *     uri <- Resource.liftF(RedisURI.make[IO]("redis://localhost"))
      *     cli <- RedisClient[IO](uri)
      *     cmd <- Redis[IO].fromClient(cli, RedisCodec.Utf8)
      *   } yield cmd
      * }}}
      *
      * Note: if you don't need to create multiple connections, you might
      * prefer to use either [[utf8]] or [[simple]] instead.
      */
    def fromClient[K, V](
        client: RedisClient,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      RedisExecutor.make[F].flatMap { implicit redisExecutor =>
        val (acquire, release) = acquireAndRelease(client, codec)
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
        redisUris <- Resource.liftF(uris.toList.traverse(RedisURI.make[F](_)))
        client <- RedisClusterClient[F](redisUris: _*)
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
    def clusterUtf8(uris: String*)(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, String, String]] =
      cluster(RedisCodec.Utf8, uris: _*)(readFrom)

    /**
      * Creates a [[RedisCommands]] for a cluster connection
      *
      * Example:
      *
      * {{{
      * val redis: Resource[IO, RedisCommands[IO, String, String]] =
      *   for {
      *     uris <- Resource.liftF(
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
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] =
      RedisExecutor.make[F].flatMap { implicit redisExecutor =>
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
      *     uris <- Resource.liftF(
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
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisCommands[F, K, V]] =
      RedisExecutor.make[F].flatMap { implicit redisExecutor =>
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
      *     uri <- Resource.liftF(RedisURI.make[IO](redisURI))
      *     conn <- RedisMasterReplica[IO].make(RedisCodec.Utf8, uri)(Some(ReadFrom.MasterPreferred))
      *     cmds <- Redis[IO].masterReplica(conn)
      *   } yield cmds
      * }}}
      */
    def masterReplica[K, V](
        conn: RedisMasterReplica[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      RedisExecutor.make[F].flatMap { implicit redisExecutor =>
        Resource.liftF {
          F.delay(new RedisStatefulConnection(conn.underlying))
            .map(c => new Redis[F, K, V](c))
        }
      }

  }

  def apply[F[_]: Concurrent: ContextShift: Log]: RedisPartiallyApplied[F] = new RedisPartiallyApplied[F]

}

private[redis4cats] class BaseRedis[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
    val conn: RedisConnection[F, K, V],
    val cluster: Boolean
) extends RedisCommands[F, K, V]
    with RedisConversionOps {

  def liftK[G[_]: Concurrent: ContextShift: Log]: RedisCommands[G, K, V] = {
    implicit val redisEcG = RedisExecutor[F].liftK[G]
    new BaseRedis[G, K, V](conn.liftK[G], cluster)
  }

  import dev.profunktor.redis4cats.JavaConversions._

  def async: F[RedisClusterAsyncCommands[K, V]] =
    if (cluster) conn.clusterAsync else conn.async.widen

  def sync: F[RedisClusterSyncCommands[K, V]] =
    if (cluster) conn.clusterSync else conn.sync.widen

  /******************************* Keys API *************************************/
  def del(key: K*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.del(key: _*))).futureLift.map(x => Long.box(x))

  override def exists(key: K*): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.exists(key: _*)))
      .futureLift
      .map(x => x == key.size.toLong)

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
            RedisExecutor[F].delay(c.pexpire(key, expiresIn.toMillis))
          case _ =>
            RedisExecutor[F].delay(c.expire(key, expiresIn.toSeconds))
        }
      }
      .futureLift
      .map(x => Boolean.box(x))

  /**
    * Expires a key at the given date.
    *
    * It calls Redis' PEXPIREAT under the hood, which has milliseconds precision.
    */
  override def expireAt(key: K, at: Instant): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.pexpireat(key, at.toEpochMilli())))
      .futureLift
      .map(x => Boolean.box(x))

  override def objectIdletime(key: K): F[Option[FiniteDuration]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.objectIdletime(key)))
      .futureLift
      .map {
        case null => none[FiniteDuration]
        case d    => FiniteDuration(d, TimeUnit.SECONDS).some
      }

  private def toFiniteDuration(duration: java.lang.Long): Option[FiniteDuration] =
    duration match {
      case d if d < 0 => none[FiniteDuration]
      case d          => FiniteDuration(d, TimeUnit.SECONDS).some
    }

  override def ttl(key: K): F[Option[FiniteDuration]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.ttl(key)))
      .futureLift
      .map(toFiniteDuration)

  override def pttl(key: K): F[Option[FiniteDuration]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.pttl(key)))
      .futureLift
      .map(toFiniteDuration)

  override def scan: F[KeyScanCursor[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scan()))
      .futureLift
      .map(KeyScanCursor[K])

  override def scan(cursor: Long): F[KeyScanCursor[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scan(JScanCursor.of(cursor.toString))))
      .futureLift
      .map(KeyScanCursor[K])

  override def scan(previous: KeyScanCursor[K]): F[KeyScanCursor[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scan(previous.underlying)))
      .futureLift
      .map(KeyScanCursor[K])

  override def scan(scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scan(scanArgs.underlying)))
      .futureLift
      .map(KeyScanCursor[K])

  override def scan(cursor: Long, scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scan(JScanCursor.of(cursor.toString), scanArgs.underlying)))
      .futureLift
      .map(KeyScanCursor[K])

  override def scan(previous: KeyScanCursor[K], scanArgs: ScanArgs): F[KeyScanCursor[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scan(previous.underlying, scanArgs.underlying)))
      .futureLift
      .map(KeyScanCursor[K])

  /******************************* Transactions API **********************************/
  // When in a cluster, transactions should run against a single node.

  def multi: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => RedisExecutor[F].delay(c.multi())
        case _                           => conn.async.flatMap(c => RedisExecutor[F].delay(c.multi()))
      }
      .futureLift
      .void

  def exec: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => RedisExecutor[F].delay(c.exec())
        case _                           => conn.async.flatMap(c => RedisExecutor[F].delay(c.exec()))
      }
      .futureLift
      .flatMap {
        case res if res.wasDiscarded() || res.isEmpty() => F.raiseError(TransactionDiscarded)
        case _                                          => F.unit
      }

  def discard: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => RedisExecutor[F].delay(c.discard())
        case _                           => conn.async.flatMap(c => RedisExecutor[F].delay(c.discard()))
      }
      .futureLift
      .void

  def watch(keys: K*): F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => RedisExecutor[F].delay(c.watch(keys: _*))
        case _                           => conn.async.flatMap(c => RedisExecutor[F].delay(c.watch(keys: _*)))
      }
      .futureLift
      .void

  def unwatch: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => RedisExecutor[F].delay(c.unwatch())
        case _                           => conn.async.flatMap(c => RedisExecutor[F].delay(c.unwatch()))
      }
      .futureLift
      .void

  /******************************* AutoFlush API **********************************/
  override def enableAutoFlush: F[Unit] =
    RedisExecutor[F].eval(async.flatMap(c => RedisExecutor[F].delay(c.setAutoFlushCommands(true))))

  override def disableAutoFlush: F[Unit] =
    RedisExecutor[F].eval(async.flatMap(c => RedisExecutor[F].delay(c.setAutoFlushCommands(false))))

  override def flushCommands: F[Unit] =
    RedisExecutor[F].eval(async.flatMap(c => RedisExecutor[F].delay(c.flushCommands())))

  /******************************* Strings API **********************************/
  override def append(key: K, value: V): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.append(key, value))).futureLift.void

  override def getSet(key: K, value: V): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.getset(key, value)))
      .futureLift
      .map(Option.apply)

  override def set(key: K, value: V): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.set(key, value))).futureLift.void

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

    async
      .flatMap(c => RedisExecutor[F].delay(c.set(key, value, jSetArgs)))
      .futureLift
      .map(_ == "OK")
  }

  override def setNx(key: K, value: V): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.setnx(key, value)))
      .futureLift
      .map(x => Boolean.box(x))

  override def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit] = {
    val command = expiresIn.unit match {
      case TimeUnit.MILLISECONDS =>
        async.flatMap(c => RedisExecutor[F].delay(c.psetex(key, expiresIn.toMillis, value)))
      case _ =>
        async.flatMap(c => RedisExecutor[F].delay(c.setex(key, expiresIn.toSeconds, value)))
    }
    command.futureLift.void
  }

  override def setRange(key: K, value: V, offset: Long): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.setrange(key, offset, value))).futureLift.void

  override def decr(key: K)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.decr(key)))
      .futureLift
      .map(x => Long.box(x))

  override def decrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.incrby(key, amount)))
      .futureLift
      .map(x => Long.box(x))

  override def incr(key: K)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.incr(key)))
      .futureLift
      .map(x => Long.box(x))

  override def incrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.incrby(key, amount)))
      .futureLift
      .map(x => Long.box(x))

  override def incrByFloat(key: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.incrbyfloat(key, amount)))
      .futureLift
      .map(x => Double.box(x))

  override def get(key: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.get(key)))
      .futureLift
      .map(Option.apply)

  override def getBit(key: K, offset: Long): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.getbit(key, offset)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def getRange(key: K, start: Long, end: Long): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.getrange(key, start, end)))
      .futureLift
      .map(Option.apply)

  override def strLen(key: K): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.strlen(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def mGet(keys: Set[K]): F[Map[K, V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.mget(keys.toSeq: _*)))
      .futureLift
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def mSet(keyValues: Map[K, V]): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.mset(keyValues.asJava))).futureLift.void

  override def mSetNx(keyValues: Map[K, V]): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.msetnx(keyValues.asJava)))
      .futureLift
      .map(x => Boolean.box(x))

  override def bitCount(key: K): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bitcount(key)))
      .futureLift
      .map(x => Long.box(x))

  override def bitCount(key: K, start: Long, end: Long): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bitcount(key, start, end)))
      .futureLift
      .map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bitpos(key, state)))
      .futureLift
      .map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bitpos(key, state, start)))
      .futureLift
      .map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bitpos(key, state, start, end)))
      .futureLift
      .map(x => Long.box(x))

  override def bitOpAnd(destination: K, sources: K*): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.bitopAnd(destination, sources: _*))).futureLift.void

  override def bitOpNot(destination: K, source: K): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.bitopNot(destination, source))).futureLift.void

  override def bitOpOr(destination: K, sources: K*): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.bitopOr(destination, sources: _*))).futureLift.void

  override def bitOpXor(destination: K, sources: K*): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.bitopXor(destination, sources: _*))).futureLift.void

  /******************************* Hashes API **********************************/
  override def hDel(key: K, fields: K*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.hdel(key, fields: _*))).futureLift.map(x => Long.box(x))

  override def hExists(key: K, field: K): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hexists(key, field)))
      .futureLift
      .map(x => Boolean.box(x))

  override def hGet(key: K, field: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hget(key, field)))
      .futureLift
      .map(Option.apply)

  override def hGetAll(key: K): F[Map[K, V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hgetall(key)))
      .futureLift
      .map(_.asScala.toMap)

  override def hmGet(key: K, fields: K*): F[Map[K, V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hmget(key, fields: _*)))
      .futureLift
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def hKeys(key: K): F[List[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hkeys(key)))
      .futureLift
      .map(_.asScala.toList)

  override def hVals(key: K): F[List[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hvals(key)))
      .futureLift
      .map(_.asScala.toList)

  override def hStrLen(key: K, field: K): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hstrlen(key, field)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def hLen(key: K): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hlen(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def hSet(key: K, field: K, value: V): F[Boolean] =
    async.flatMap(c => RedisExecutor[F].delay(c.hset(key, field, value))).futureLift.map(x => Boolean.box(x))

  override def hSetNx(key: K, field: K, value: V): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hsetnx(key, field, value)))
      .futureLift
      .map(x => Boolean.box(x))

  override def hmSet(key: K, fieldValues: Map[K, V]): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.hmset(key, fieldValues.asJava))).futureLift.void

  override def hIncrBy(key: K, field: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hincrby(key, field, amount)))
      .futureLift
      .map(x => Long.box(x))

  override def hIncrByFloat(key: K, field: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.hincrbyfloat(key, field, amount)))
      .futureLift
      .map(x => Double.box(x))

  /******************************* Sets API **********************************/
  override def sIsMember(key: K, value: V): F[Boolean] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.sismember(key, value)))
      .futureLift
      .map(x => Boolean.box(x))

  override def sAdd(key: K, values: V*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.sadd(key, values: _*))).futureLift.map(x => Long.box(x))

  override def sDiffStore(destination: K, keys: K*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.sdiffstore(destination, keys: _*))).futureLift.map(x => Long.box(x))

  override def sInterStore(destination: K, keys: K*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.sinterstore(destination, keys: _*))).futureLift.map(x => Long.box(x))

  override def sMove(source: K, destination: K, value: V): F[Boolean] =
    async.flatMap(c => RedisExecutor[F].delay(c.smove(source, destination, value))).futureLift.map(x => Boolean.box(x))

  override def sPop(key: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.spop(key)))
      .futureLift
      .map(Option.apply)

  override def sPop(key: K, count: Long): F[Set[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.spop(key, count)))
      .futureLift
      .map(_.asScala.toSet)

  override def sRem(key: K, values: V*): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.srem(key, values: _*))).futureLift.void

  override def sCard(key: K): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scard(key)))
      .futureLift
      .map(x => Long.box(x))

  override def sDiff(keys: K*): F[Set[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.sdiff(keys: _*)))
      .futureLift
      .map(_.asScala.toSet)

  override def sInter(keys: K*): F[Set[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.sinter(keys: _*)))
      .futureLift
      .map(_.asScala.toSet)

  override def sMembers(key: K): F[Set[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.smembers(key)))
      .futureLift
      .map(_.asScala.toSet)

  override def sRandMember(key: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.srandmember(key)))
      .futureLift
      .map(Option.apply)

  override def sRandMember(key: K, count: Long): F[List[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.srandmember(key, count)))
      .futureLift
      .map(_.asScala.toList)

  override def sUnion(keys: K*): F[Set[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.sunion(keys: _*)))
      .futureLift
      .map(_.asScala.toSet)

  override def sUnionStore(destination: K, keys: K*): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.sunionstore(destination, keys: _*))).futureLift.void

  /******************************* Lists API **********************************/
  override def lIndex(key: K, index: Long): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.lindex(key, index)))
      .futureLift
      .map(Option.apply)

  override def lLen(key: K): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.llen(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def lRange(key: K, start: Long, stop: Long): F[List[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.lrange(key, start, stop)))
      .futureLift
      .map(_.asScala.toList)

  override def blPop(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, V)]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.blpop(timeout.toSecondsOrZero, keys.toList: _*)))
      .futureLift
      .map(Option(_).map(kv => kv.getKey -> kv.getValue))

  override def brPop(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, V)]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.brpop(timeout.toSecondsOrZero, keys.toList: _*)))
      .futureLift
      .map(Option(_).map(kv => kv.getKey -> kv.getValue))

  override def brPopLPush(timeout: Duration, source: K, destination: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.brpoplpush(timeout.toSecondsOrZero, source, destination)))
      .futureLift
      .map(Option.apply)

  override def lPop(key: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.lpop(key)))
      .futureLift
      .map(Option.apply)

  override def lPush(key: K, values: V*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.lpush(key, values: _*))).futureLift.map(x => Long.box(x))

  override def lPushX(key: K, values: V*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.lpushx(key, values: _*))).futureLift.map(x => Long.box(x))

  override def rPop(key: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.rpop(key)))
      .futureLift
      .map(Option.apply)

  override def rPopLPush(source: K, destination: K): F[Option[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.rpoplpush(source, destination)))
      .futureLift
      .map(Option.apply)

  override def rPush(key: K, values: V*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.rpush(key, values: _*))).futureLift.map(x => Long.box(x))

  override def rPushX(key: K, values: V*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.rpushx(key, values: _*))).futureLift.map(x => Long.box(x))

  override def lInsertAfter(key: K, pivot: V, value: V): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.linsert(key, false, pivot, value))).futureLift.map(x => Long.box(x))

  override def lInsertBefore(key: K, pivot: V, value: V): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.linsert(key, true, pivot, value))).futureLift.map(x => Long.box(x))

  override def lRem(key: K, count: Long, value: V): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.lrem(key, count, value))).futureLift.map(x => Long.box(x))

  override def lSet(key: K, index: Long, value: V): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.lset(key, index, value))).futureLift.void

  override def lTrim(key: K, start: Long, stop: Long): F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.ltrim(key, start, stop))).futureLift.void

  /******************************* Geo API **********************************/
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.geodist(key, from, to, unit)))
      .futureLift
      .map(x => Double.box(x))

  override def geoHash(key: K, values: V*): F[List[Option[String]]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.geohash(key, values: _*)))
      .futureLift
      .map(_.asScala.toList.map(x => Option(x.getValue)))

  override def geoPos(key: K, values: V*): F[List[GeoCoordinate]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.geopos(key, values: _*)))
      .futureLift
      .map(_.asScala.toList.map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue())))

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit): F[Set[V]] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(c.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit))
      )
      .futureLift
      .map(_.asScala.toSet)

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(
          c.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit, args)
        )
      )
      .futureLift
      .map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit): F[Set[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.georadiusbymember(key, value, dist.value, unit)))
      .futureLift
      .map(_.asScala.toSet)

  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: Distance,
      unit: GeoArgs.Unit,
      args: GeoArgs
  ): F[List[GeoRadiusResult[V]]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.georadiusbymember(key, value, dist.value, unit, args)))
      .futureLift
      .map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Unit] = {
    val triplets = geoValues.flatMap(g => Seq[Any](g.lon.value, g.lat.value, g.value)).asInstanceOf[Seq[AnyRef]]
    async.flatMap(c => RedisExecutor[F].delay(c.geoadd(key, triplets: _*))).futureLift.void
  }

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit] =
    conn.async
      .flatMap { c =>
        RedisExecutor[F].delay {
          c.georadius(
            key,
            geoRadius.lon.value,
            geoRadius.lat.value,
            geoRadius.dist.value,
            unit,
            storage.asGeoRadiusStoreArgs
          )
        }
      }
      .futureLift
      .void

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusDistStorage[K]): F[Unit] =
    conn.async
      .flatMap { c =>
        RedisExecutor[F].delay {
          c.georadius(
            key,
            geoRadius.lon.value,
            geoRadius.lat.value,
            geoRadius.dist.value,
            unit,
            storage.asGeoRadiusStoreArgs
          )
        }
      }
      .futureLift
      .void

  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: Distance,
      unit: GeoArgs.Unit,
      storage: GeoRadiusKeyStorage[K]
  ): F[Unit] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(c.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs))
      )
      .futureLift
      .void

  override def geoRadiusByMember(
      key: K,
      value: V,
      dist: Distance,
      unit: GeoArgs.Unit,
      storage: GeoRadiusDistStorage[K]
  ): F[Unit] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(c.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs))
      )
      .futureLift
      .void

  /******************************* Sorted Sets API **********************************/
  override def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Long] = {
    val res = args match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(c.zadd(key, x, values.map(s => ScoredValue.just(s.score.value, s.value)): _*))
        )
      case None =>
        async.flatMap(c =>
          RedisExecutor[F].delay(c.zadd(key, values.map(s => ScoredValue.just(s.score.value, s.value)): _*))
        )
    }
    res.futureLift.map(x => Long.box(x))
  }

  override def zAddIncr(key: K, args: Option[ZAddArgs], member: ScoreWithValue[V]): F[Double] = {
    val res = args match {
      case Some(x) => async.flatMap(c => RedisExecutor[F].delay(c.zaddincr(key, x, member.score.value, member.value)))
      case None    => async.flatMap(c => RedisExecutor[F].delay(c.zaddincr(key, member.score.value, member.value)))
    }
    res.futureLift.map(x => Double.box(x))
  }

  override def zIncrBy(key: K, member: V, amount: Double): F[Double] =
    async.flatMap(c => RedisExecutor[F].delay(c.zincrby(key, amount, member))).futureLift.map(x => Double.box(x))

  override def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long] = {
    val res = args match {
      case Some(x) => async.flatMap(c => RedisExecutor[F].delay(c.zinterstore(destination, x, keys: _*)))
      case None    => async.flatMap(c => RedisExecutor[F].delay(c.zinterstore(destination, keys: _*)))
    }
    res.futureLift.map(x => Long.box(x))
  }

  override def zRem(key: K, values: V*): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.zrem(key, values: _*))).futureLift.map(x => Long.box(x))

  override def zRemRangeByLex(key: K, range: ZRange[V]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zremrangebylex(key, JRange.create[V](range.start, range.end))))
      .futureLift
      .map(x => Long.box(x))

  override def zRemRangeByRank(key: K, start: Long, stop: Long): F[Long] =
    async.flatMap(c => RedisExecutor[F].delay(c.zremrangebyrank(key, start, stop))).futureLift.map(x => Long.box(x))

  override def zRemRangeByScore[T: Numeric](key: K, range: ZRange[T]): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zremrangebyscore(key, range.asJavaRange)))
      .futureLift
      .map(x => Long.box(x))

  override def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Long] = {
    val res = args match {
      case Some(x) => async.flatMap(c => RedisExecutor[F].delay(c.zunionstore(destination, x, keys: _*)))
      case None    => async.flatMap(c => RedisExecutor[F].delay(c.zunionstore(destination, keys: _*)))
    }
    res.futureLift.map(x => Long.box(x))
  }

  override def zCard(key: K): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zcard(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zCount[T: Numeric](key: K, range: ZRange[T]): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zcount(key, range.asJavaRange)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zLexCount(key: K, range: ZRange[V]): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zlexcount(key, JRange.create[V](range.start, range.end))))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zRange(key: K, start: Long, stop: Long): F[List[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zrange(key, start, stop)))
      .futureLift
      .map(_.asScala.toList)

  override def zRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(
            c.zrangebylex(key, JRange.create[V](range.start, range.end), JLimit.create(x.offset, x.count))
          )
        )
      case None =>
        async.flatMap(c => RedisExecutor[F].delay(c.zrangebylex(key, JRange.create[V](range.start, range.end))))
    }
    res.futureLift.map(_.asScala.toList)
  }

  override def zRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(c.zrangebyscore(key, range.asJavaRange, JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => RedisExecutor[F].delay(c.zrangebyscore(key, range.asJavaRange)))
    }
    res.futureLift.map(_.asScala.toList)
  }

  override def zRangeByScoreWithScores[T: Numeric](
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[List[ScoreWithValue[V]]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(c.zrangebyscoreWithScores(key, range.asJavaRange, JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => RedisExecutor[F].delay(c.zrangebyscoreWithScores(key, range.asJavaRange)))
    }
    res.futureLift.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zrangeWithScores(key, start, stop)))
      .futureLift
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRank(key: K, value: V): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zrank(key, value)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zRevRange(key: K, start: Long, stop: Long): F[List[V]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zrevrange(key, start, stop)))
      .futureLift
      .map(_.asScala.toList)

  override def zRevRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(
            c.zrevrangebylex(key, JRange.create[V](range.start, range.end), JLimit.create(x.offset, x.count))
          )
        )
      case None =>
        async.flatMap(c => RedisExecutor[F].delay(c.zrevrangebylex(key, JRange.create[V](range.start, range.end))))
    }
    res.futureLift.map(_.asScala.toList)
  }

  override def zRevRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(c.zrevrangebyscore(key, range.asJavaRange, JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => RedisExecutor[F].delay(c.zrevrangebyscore(key, range.asJavaRange)))
    }
    res.futureLift.map(_.asScala.toList)
  }

  override def zRevRangeByScoreWithScores[T: Numeric](
      key: K,
      range: ZRange[T],
      limit: Option[RangeLimit]
  ): F[List[ScoreWithValue[V]]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          RedisExecutor[F].delay(c.zrevrangebyscoreWithScores(key, range.asJavaRange, JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => RedisExecutor[F].delay(c.zrangebyscoreWithScores(key, range.asJavaRange)))
    }
    res.futureLift.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zRevRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zrevrangeWithScores(key, start, stop)))
      .futureLift
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRank(key: K, value: V): F[Option[Long]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zrevrank(key, value)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zScore(key: K, value: V): F[Option[Double]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zscore(key, value)))
      .futureLift
      .map(x => Option(Double.unbox(x)))

  override def zPopMin(key: K, count: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zpopmin(key, count)))
      .futureLift
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zPopMax(key: K, count: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.zpopmax(key, count)))
      .futureLift
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def bzPopMin(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bzpopmin(timeout.toSecondsOrZero, keys.toList: _*)))
      .futureLift
      .map(Option(_).map(kv => (kv.getKey, kv.getValue.asScoreWithValues)))

  override def bzPopMax(timeout: Duration, keys: NonEmptyList[K]): F[Option[(K, ScoreWithValue[V])]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.bzpopmax(timeout.toSecondsOrZero, keys.toList: _*)))
      .futureLift
      .map(Option(_).map(kv => (kv.getKey, kv.getValue.asScoreWithValues)))

  /******************************* Connection API **********************************/
  override val ping: F[String] =
    async.flatMap(c => F.delay(c.ping())).futureLift

  override def select(index: Int): F[Unit] =
    conn.async.flatMap(c => RedisExecutor[F].delay(c.select(index))).void

  override def auth(password: CharSequence): F[Boolean] =
    async
      .flatMap(c => F.delay(c.auth(password)))
      .futureLift
      .map(_ == "OK")

  override def auth(username: String, password: CharSequence): F[Boolean] =
    async
      .flatMap(c => F.delay(c.auth(username, password)))
      .futureLift
      .map(_ == "OK")

  /******************************* Server API **********************************/
  override val flushAll: F[Unit] =
    async.flatMap(c => F.delay(c.flushall())).futureLift.void

  override val flushAllAsync: F[Unit] =
    async.flatMap(c => F.delay(c.flushallAsync())).futureLift.void

  override def keys(key: K): F[List[K]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.keys(key)))
      .futureLift
      .map(_.asScala.toList)

  override def info: F[Map[String, String]] =
    async
      .flatMap(c => F.delay(c.info))
      .futureLift
      .flatMap(info =>
        F.delay(
          info
            .split("\\r?\\n")
            .toList
            .map(_.split(":", 2).toList)
            .collect { case k :: v :: Nil => (k, v) }
            .toMap
        )
      )

  override def dbsize: F[Long] =
    async
      .flatMap(c => F.delay(c.dbsize))
      .futureLift
      .map(Long.unbox)

  override def lastSave: F[Instant] =
    async
      .flatMap(c => F.delay(c.lastsave))
      .futureLift
      .map(_.toInstant)

  override def slowLogLen: F[Long] =
    async
      .flatMap(c => F.delay(c.slowlogLen))
      .futureLift
      .map(Long.unbox)

  override def eval(script: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.eval[output.Underlying](script, output.outputType)))
      .futureLift
      .map(r => output.convert(r))

  override def eval(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(
          c.eval[output.Underlying](
            script,
            output.outputType,
            // The Object requirement comes from the limitations of Java Generics. It is safe to assume K <: Object as
            // the underlying JRedisCodec would also only support K <: Object.
            keys.asInstanceOf[Seq[K with Object]].toArray
          )
        )
      )
      .futureLift
      .map(output.convert(_))

  override def eval(script: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(
          c.eval[output.Underlying](
            script,
            output.outputType,
            // see comment in eval above
            keys.asInstanceOf[Seq[K with Object]].toArray,
            values: _*
          )
        )
      )
      .futureLift
      .map(output.convert(_))

  override def evalSha(digest: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.evalsha[output.Underlying](digest, output.outputType)))
      .futureLift
      .map(output.convert(_))

  override def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(
          c.evalsha[output.Underlying](
            digest,
            output.outputType,
            // see comment in eval above
            keys.asInstanceOf[Seq[K with Object]].toArray
          )
        )
      )
      .futureLift
      .map(output.convert(_))

  override def evalSha(digest: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async
      .flatMap(c =>
        RedisExecutor[F].delay(
          c.evalsha[output.Underlying](
            digest,
            output.outputType,
            // see comment in eval above
            keys.asInstanceOf[Seq[K with Object]].toArray,
            values: _*
          )
        )
      )
      .futureLift
      .map(output.convert(_))

  override def scriptLoad(script: String): F[String] =
    async.flatMap(c => RedisExecutor[F].delay(c.scriptLoad(script))).futureLift

  override def scriptLoad(script: Array[Byte]): F[String] =
    async.flatMap(c => RedisExecutor[F].delay(c.scriptLoad(script))).futureLift

  override def scriptExists(digests: String*): F[List[Boolean]] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.scriptExists(digests: _*)))
      .futureLift
      .map(_.asScala.map(Boolean.unbox(_)).toList)

  override def scriptFlush: F[Unit] =
    async.flatMap(c => RedisExecutor[F].delay(c.scriptFlush())).futureLift.void

  override def digest(script: String): F[String] = async.flatMap(c => F.delay(c.digest(script)))

  /** ***************************** HyperLoglog API **********************************/
  override def pfAdd(key: K, values: V*): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.pfadd(key, values: _*)))
      .futureLift
      .map(Long.box(_))

  override def pfCount(key: K): F[Long] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.pfcount(key)))
      .futureLift
      .map(Long.box(_))

  override def pfMerge(outputKey: K, inputKeys: K*): F[Unit] =
    async
      .flatMap(c => RedisExecutor[F].delay(c.pfmerge(outputKey, inputKeys: _*)))
      .futureLift
      .void
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
        case _        => T.toDouble(t)
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

private[redis4cats] class Redis[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
    connection: RedisStatefulConnection[F, K, V]
) extends BaseRedis[F, K, V](connection, cluster = false)

private[redis4cats] class RedisCluster[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
    connection: RedisStatefulClusterConnection[F, K, V]
) extends BaseRedis[F, K, V](connection, cluster = true)
