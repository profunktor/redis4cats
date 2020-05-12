/*
 * Copyright 2018-2020 ProfunKtor
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

import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.{ JRFuture, Log, RedisBlocker }
import dev.profunktor.redis4cats.effect.JRFuture._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.transactions.TransactionDiscarded
import io.lettuce.core.{
  GeoArgs,
  GeoRadiusStoreArgs,
  GeoWithin,
  ScanCursor,
  ScoredValue,
  ZAddArgs,
  ZStoreArgs,
  Limit => JLimit,
  Range => JRange,
  SetArgs => JSetArgs
}
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.{ RedisClusterCommands => RedisClusterSyncCommands }
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object Redis {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V],
      blocker: Blocker
  ): (F[Redis[F, K, V]], Redis[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromConnectionFuture(
        F.delay(client.underlying.connectAsync(codec.underlying, client.uri.underlying))
      )(blocker)
      .map(c => new Redis(new RedisStatefulConnection(c, blocker), blocker))

    val release: Redis[F, K, V] => F[Unit] = c =>
      F.info(s"Releasing Commands connection: ${client.uri.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseCluster[F[_]: Concurrent: ContextShift: Log, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      blocker: Blocker
  ): (F[RedisCluster[F, K, V]], RedisCluster[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromCompletableFuture(
        F.delay(client.underlying.connectAsync[K, V](codec.underlying))
      )(blocker)
      .map(c => new RedisCluster(new RedisStatefulClusterConnection(c, blocker), blocker))

    val release: RedisCluster[F, K, V] => F[Unit] = c =>
      F.info(s"Releasing cluster Commands connection: ${client.underlying}") *> c.conn.close

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseClusterByNode[F[_]: Concurrent: ContextShift: Log, K, V](
      client: RedisClusterClient,
      codec: RedisCodec[K, V],
      nodeId: NodeId,
      blocker: Blocker
  ): (F[BaseRedis[F, K, V]], BaseRedis[F, K, V] => F[Unit]) = {
    val acquire = JRFuture
      .fromCompletableFuture(
        F.delay(client.underlying.connectAsync[K, V](codec.underlying))
      )(blocker)
      .map { c =>
        new BaseRedis[F, K, V](new RedisStatefulClusterConnection(c, blocker), cluster = true, blocker) {
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
      * Creates a simple [[RedisCommands]]
      *
      * It will create an underlying RedisClient to establish a
      * connection with Redis.
      *
      * Example:
      * {{{
      * Redis[IO].simple("redis://localhost", RedisCodec.Ascii)
      * }}}
      *
      * Note: if you need to create multiple connections, use [[fromClient]]
      * instead, which allows you to re-use the same client.
      */
    def simple[K, V](uri: String, codec: RedisCodec[K, V]): Resource[F, RedisCommands[F, K, V]] =
      for {
        redisUri <- Resource.liftF(RedisURI.make[F](uri))
        client <- RedisClient[F](redisUri)
        redis <- this.fromClient(client, codec)
      } yield redis

    /**
      * Creates a simple [[RedisCommands]] to deal with UTF-8 encoded
      * keys and values given
      *
      * It will create an underlying RedisClient to establish a
      * connection with Redis.
      *
      * Example:
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
      * Creates a simple [[RedisCommands]] given
      *
      * It will create an underlying RedisClient to establish a
      * connection with Redis.
      *
      * Example:
      * {{{
      * val redis: Resource[IO, RedisCommands[IO, String, String]] =
      * for {
      *   uri <- Resource.liftF(RedisURI.make[IO]("redis://localhost"))
      *   cli <- RedisClient[IO](uri)
      *   cmd <- Redis[IO].fromClient(cli, RedisCodec.Utf8)
      * } yield cmd
      * }}}
      *
      * Note: if you don't need to create multiple connections, you might
      * prefer to use either [[utf8]] or [[simple]] instead.
      */
    def fromClient[K, V](
        client: RedisClient,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      mkBlocker[F].flatMap { blocker =>
        val (acquire, release) = acquireAndRelease(client, codec, blocker)
        Resource.make(acquire)(release).widen
      }

    def cluster[K, V](
        uri: String,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      for {
        redisUri <- Resource.liftF(RedisURI.make[F](uri))
        client <- RedisClusterClient[F](redisUri)
        redis <- this.fromClusterClient[K, V](client, codec)
      } yield redis

    def clusterUtf8(uri: String): Resource[F, RedisCommands[F, String, String]] =
      cluster(uri, RedisCodec.Utf8)

    def fromClusterClient[K, V](
        clusterClient: RedisClusterClient,
        codec: RedisCodec[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      mkBlocker[F].flatMap { blocker =>
        val (acquire, release) = acquireAndReleaseCluster(clusterClient, codec, blocker)
        Resource.make(acquire)(release).widen
      }

    def fromClusterClientByNode[K, V](
        clusterClient: RedisClusterClient,
        codec: RedisCodec[K, V],
        nodeId: NodeId
    ): Resource[F, RedisCommands[F, K, V]] =
      mkBlocker[F].flatMap { blocker =>
        val (acquire, release) = acquireAndReleaseClusterByNode(clusterClient, codec, nodeId, blocker)
        Resource.make(acquire)(release).widen
      }

    def masterReplica[K, V](
        conn: RedisMasterReplica[K, V]
    ): Resource[F, RedisCommands[F, K, V]] =
      mkBlocker[F].flatMap { blocker =>
        Resource.liftF {
          F.delay(new RedisStatefulConnection(conn.underlying, blocker))
            .map(c => new Redis[F, K, V](c, blocker))
        }
      }

  }

  def apply[F[_]: Concurrent: ContextShift: Log]: RedisPartiallyApplied[F] = new RedisPartiallyApplied[F]

}

private[redis4cats] class BaseRedis[F[_]: Concurrent: ContextShift, K, V](
    val conn: RedisConnection[F, K, V],
    val cluster: Boolean,
    blocker: Blocker
) extends RedisCommands[F, K, V]
    with RedisConversionOps {
  override def liftK[G[_]: Concurrent: ContextShift]: BaseRedis[G, K, V] =
    new BaseRedis[G, K, V](conn.liftK[G], cluster, blocker)

  import dev.profunktor.redis4cats.JavaConversions._

  // To avoid passing it to every `futureLift`
  implicit val redisBlocker = RedisBlocker(blocker)

  def async: F[RedisClusterAsyncCommands[K, V]] =
    if (cluster) conn.clusterAsync else conn.async.widen

  def sync: F[RedisClusterSyncCommands[K, V]] =
    if (cluster) conn.clusterSync else conn.sync.widen

  /******************************* Keys API *************************************/
  def del(key: K*): F[Unit] =
    async.flatMap(c => F.delay(c.del(key: _*))).futureLift.void

  def exists(key: K*): F[Boolean] =
    async
      .flatMap(c => F.delay(c.exists(key: _*)))
      .futureLift
      .map(x => x == key.size.toLong)

  def expire(key: K, expiresIn: FiniteDuration): F[Unit] =
    async.flatMap(c => F.delay(c.expire(key, expiresIn.toSeconds))).futureLift.void

  def ttl(key: K): F[Option[FiniteDuration]] =
    async
      .flatMap(c => F.delay(c.ttl(key)))
      .futureLift
      .map {
        case d if d == -2 || d == -1 => none[FiniteDuration]
        case d                       => FiniteDuration(d, TimeUnit.SECONDS).some
      }

  def pttl(key: K): F[Option[FiniteDuration]] =
    async
      .flatMap(c => F.delay(c.pttl(key)))
      .futureLift
      .map {
        case d if d == -2 || d == -1 => none[FiniteDuration]
        case d                       => FiniteDuration(d, TimeUnit.MILLISECONDS).some
      }

  def scan: F[KeyScanCursor[K]] =
    async
      .flatMap(c => F.delay(c.scan()))
      .futureLift
      .map(KeyScanCursor[K])

  def scan(cursor: Long): F[KeyScanCursor[K]] =
    async
      .flatMap(c => F.delay(c.scan(ScanCursor.of(cursor.toString))))
      .futureLift
      .map(KeyScanCursor[K])

  /******************************* Transactions API **********************************/
  // When in a cluster, transactions should run against a single node.

  def multi: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => F.delay(c.multi())
        case _                           => conn.async.flatMap(c => F.delay(c.multi()))
      }
      .futureLift
      .void

  def exec: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => F.delay(c.exec())
        case _                           => conn.async.flatMap(c => F.delay(c.exec()))
      }
      .futureLift
      .flatMap {
        case res if res.wasDiscarded() => F.raiseError(TransactionDiscarded)
        case _                         => F.unit
      }

  def discard: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => F.delay(c.discard())
        case _                           => conn.async.flatMap(c => F.delay(c.discard()))
      }
      .futureLift
      .void

  def watch(keys: K*): F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => F.delay(c.watch(keys: _*))
        case _                           => conn.async.flatMap(c => F.delay(c.watch(keys: _*)))
      }
      .futureLift
      .void

  def unwatch: F[Unit] =
    async
      .flatMap {
        case c: RedisAsyncCommands[K, V] => F.delay(c.unwatch())
        case _                           => conn.async.flatMap(c => F.delay(c.unwatch()))
      }
      .futureLift
      .void

  /******************************* AutoFlush API **********************************/
  override def enableAutoFlush: F[Unit] =
    blocker.blockOn(async.flatMap(c => blocker.delay(c.setAutoFlushCommands(true))))

  override def disableAutoFlush: F[Unit] =
    blocker.blockOn(async.flatMap(c => blocker.delay(c.setAutoFlushCommands(false))))

  override def flushCommands: F[Unit] =
    blocker.blockOn(async.flatMap(c => blocker.delay(c.flushCommands())))

  /******************************* Strings API **********************************/
  override def append(key: K, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.append(key, value))).futureLift.void

  override def getSet(key: K, value: V): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.getset(key, value)))
      .futureLift
      .map(Option.apply)

  override def set(key: K, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.set(key, value))).futureLift.void

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
      .flatMap(c => F.delay(c.set(key, value, jSetArgs)))
      .futureLift
      .map(_ == "OK")
  }

  override def setNx(key: K, value: V): F[Boolean] =
    async
      .flatMap(c => F.delay(c.setnx(key, value)))
      .futureLift
      .map(x => Boolean.box(x))

  override def setEx(key: K, value: V, expiresIn: FiniteDuration): F[Unit] = {
    val command = expiresIn.unit match {
      case TimeUnit.MILLISECONDS =>
        async.flatMap(c => F.delay(c.psetex(key, expiresIn.toMillis, value)))
      case _ =>
        async.flatMap(c => F.delay(c.setex(key, expiresIn.toSeconds, value)))
    }
    command.futureLift.void
  }

  override def setRange(key: K, value: V, offset: Long): F[Unit] =
    async.flatMap(c => F.delay(c.setrange(key, offset, value))).futureLift.void

  override def decr(key: K)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => F.delay(c.decr(key)))
      .futureLift
      .map(x => Long.box(x))

  override def decrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => F.delay(c.incrby(key, amount)))
      .futureLift
      .map(x => Long.box(x))

  override def incr(key: K)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => F.delay(c.incr(key)))
      .futureLift
      .map(x => Long.box(x))

  override def incrBy(key: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => F.delay(c.incrby(key, amount)))
      .futureLift
      .map(x => Long.box(x))

  override def incrByFloat(key: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    async
      .flatMap(c => F.delay(c.incrbyfloat(key, amount)))
      .futureLift
      .map(x => Double.box(x))

  override def get(key: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.get(key)))
      .futureLift
      .map(Option.apply)

  override def getBit(key: K, offset: Long): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.getbit(key, offset)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def getRange(key: K, start: Long, end: Long): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.getrange(key, start, end)))
      .futureLift
      .map(Option.apply)

  override def strLen(key: K): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.strlen(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def mGet(keys: Set[K]): F[Map[K, V]] =
    async
      .flatMap(c => F.delay(c.mget(keys.toSeq: _*)))
      .futureLift
      .map(_.asScala.toList.collect { case kv if kv.hasValue => kv.getKey -> kv.getValue }.toMap)

  override def mSet(keyValues: Map[K, V]): F[Unit] =
    async.flatMap(c => F.delay(c.mset(keyValues.asJava))).futureLift.void

  override def mSetNx(keyValues: Map[K, V]): F[Boolean] =
    async
      .flatMap(c => F.delay(c.msetnx(keyValues.asJava)))
      .futureLift
      .map(x => Boolean.box(x))

  override def bitCount(key: K): F[Long] =
    async
      .flatMap(c => F.delay(c.bitcount(key)))
      .futureLift
      .map(x => Long.box(x))

  override def bitCount(key: K, start: Long, end: Long): F[Long] =
    async
      .flatMap(c => F.delay(c.bitcount(key, start, end)))
      .futureLift
      .map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean): F[Long] =
    async
      .flatMap(c => F.delay(c.bitpos(key, state)))
      .futureLift
      .map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long): F[Long] =
    async
      .flatMap(c => F.delay(c.bitpos(key, state, start)))
      .futureLift
      .map(x => Long.box(x))

  override def bitPos(key: K, state: Boolean, start: Long, end: Long): F[Long] =
    async
      .flatMap(c => F.delay(c.bitpos(key, state, start, end)))
      .futureLift
      .map(x => Long.box(x))

  override def bitOpAnd(destination: K, sources: K*): F[Unit] =
    async.flatMap(c => F.delay(c.bitopAnd(destination, sources: _*))).futureLift.void

  override def bitOpNot(destination: K, source: K): F[Unit] =
    async.flatMap(c => F.delay(c.bitopNot(destination, source))).futureLift.void

  override def bitOpOr(destination: K, sources: K*): F[Unit] =
    async.flatMap(c => F.delay(c.bitopOr(destination, sources: _*))).futureLift.void

  override def bitOpXor(destination: K, sources: K*): F[Unit] =
    async.flatMap(c => F.delay(c.bitopXor(destination, sources: _*))).futureLift.void

  /******************************* Hashes API **********************************/
  override def hDel(key: K, fields: K*): F[Unit] =
    async.flatMap(c => F.delay(c.hdel(key, fields: _*))).futureLift.void

  override def hExists(key: K, field: K): F[Boolean] =
    async
      .flatMap(c => F.delay(c.hexists(key, field)))
      .futureLift
      .map(x => Boolean.box(x))

  override def hGet(key: K, field: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.hget(key, field)))
      .futureLift
      .map(Option.apply)

  override def hGetAll(key: K): F[Map[K, V]] =
    async
      .flatMap(c => F.delay(c.hgetall(key)))
      .futureLift
      .map(_.asScala.toMap)

  override def hmGet(key: K, fields: K*): F[Map[K, V]] =
    async
      .flatMap(c => F.delay(c.hmget(key, fields: _*)))
      .futureLift
      .map(_.asScala.toList.map(kv => kv.getKey -> kv.getValue).toMap)

  override def hKeys(key: K): F[List[K]] =
    async
      .flatMap(c => F.delay(c.hkeys(key)))
      .futureLift
      .map(_.asScala.toList)

  override def hVals(key: K): F[List[V]] =
    async
      .flatMap(c => F.delay(c.hvals(key)))
      .futureLift
      .map(_.asScala.toList)

  override def hStrLen(key: K, field: K): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.hstrlen(key, field)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def hLen(key: K): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.hlen(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def hSet(key: K, field: K, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.hset(key, field, value))).futureLift.void

  override def hSetNx(key: K, field: K, value: V): F[Boolean] =
    async
      .flatMap(c => F.delay(c.hsetnx(key, field, value)))
      .futureLift
      .map(x => Boolean.box(x))

  override def hmSet(key: K, fieldValues: Map[K, V]): F[Unit] =
    async.flatMap(c => F.delay(c.hmset(key, fieldValues.asJava))).futureLift.void

  override def hIncrBy(key: K, field: K, amount: Long)(implicit N: Numeric[V]): F[Long] =
    async
      .flatMap(c => F.delay(c.hincrby(key, field, amount)))
      .futureLift
      .map(x => Long.box(x))

  override def hIncrByFloat(key: K, field: K, amount: Double)(implicit N: Numeric[V]): F[Double] =
    async
      .flatMap(c => F.delay(c.hincrbyfloat(key, field, amount)))
      .futureLift
      .map(x => Double.box(x))

  /******************************* Sets API **********************************/
  override def sIsMember(key: K, value: V): F[Boolean] =
    async
      .flatMap(c => F.delay(c.sismember(key, value)))
      .futureLift
      .map(x => Boolean.box(x))

  override def sAdd(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.sadd(key, values: _*))).futureLift.void

  override def sDiffStore(destination: K, keys: K*): F[Unit] =
    async.flatMap(c => F.delay(c.sdiffstore(destination, keys: _*))).futureLift.void

  override def sInterStore(destination: K, keys: K*): F[Unit] =
    async.flatMap(c => F.delay(c.sinterstore(destination, keys: _*))).futureLift.void

  override def sMove(source: K, destination: K, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.smove(source, destination, value))).futureLift.void

  override def sPop(key: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.spop(key)))
      .futureLift
      .map(Option.apply)

  override def sPop(key: K, count: Long): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.spop(key, count)))
      .futureLift
      .map(_.asScala.toSet)

  override def sRem(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.srem(key, values: _*))).futureLift.void

  override def sCard(key: K): F[Long] =
    async
      .flatMap(c => F.delay(c.scard(key)))
      .futureLift
      .map(x => Long.box(x))

  override def sDiff(keys: K*): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.sdiff(keys: _*)))
      .futureLift
      .map(_.asScala.toSet)

  override def sInter(keys: K*): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.sinter(keys: _*)))
      .futureLift
      .map(_.asScala.toSet)

  override def sMembers(key: K): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.smembers(key)))
      .futureLift
      .map(_.asScala.toSet)

  override def sRandMember(key: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.srandmember(key)))
      .futureLift
      .map(Option.apply)

  override def sRandMember(key: K, count: Long): F[List[V]] =
    async
      .flatMap(c => F.delay(c.srandmember(key, count)))
      .futureLift
      .map(_.asScala.toList)

  override def sUnion(keys: K*): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.sunion(keys: _*)))
      .futureLift
      .map(_.asScala.toSet)

  override def sUnionStore(destination: K, keys: K*): F[Unit] =
    async.flatMap(c => F.delay(c.sunionstore(destination, keys: _*))).futureLift.void

  /******************************* Lists API **********************************/
  override def lIndex(key: K, index: Long): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.lindex(key, index)))
      .futureLift
      .map(Option.apply)

  override def lLen(key: K): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.llen(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def lRange(key: K, start: Long, stop: Long): F[List[V]] =
    async
      .flatMap(c => F.delay(c.lrange(key, start, stop)))
      .futureLift
      .map(_.asScala.toList)

  override def blPop(timeout: Duration, keys: K*): F[(K, V)] =
    async
      .flatMap(c => F.delay(c.blpop(timeout.toSecondsOrZero, keys: _*)))
      .futureLift
      .map(kv => kv.getKey -> kv.getValue)

  override def brPop(timeout: Duration, keys: K*): F[(K, V)] =
    async
      .flatMap(c => F.delay(c.brpop(timeout.toSecondsOrZero, keys: _*)))
      .futureLift
      .map(kv => kv.getKey -> kv.getValue)

  override def brPopLPush(timeout: Duration, source: K, destination: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.brpoplpush(timeout.toSecondsOrZero, source, destination)))
      .futureLift
      .map(Option.apply)

  override def lPop(key: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.lpop(key)))
      .futureLift
      .map(Option.apply)

  override def lPush(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.lpush(key, values: _*))).futureLift.void

  override def lPushX(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.lpushx(key, values: _*))).futureLift.void

  override def rPop(key: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.rpop(key)))
      .futureLift
      .map(Option.apply)

  override def rPopLPush(source: K, destination: K): F[Option[V]] =
    async
      .flatMap(c => F.delay(c.rpoplpush(source, destination)))
      .futureLift
      .map(Option.apply)

  override def rPush(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.rpush(key, values: _*))).futureLift.void

  override def rPushX(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.rpushx(key, values: _*))).futureLift.void

  override def lInsertAfter(key: K, pivot: V, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.linsert(key, false, pivot, value))).futureLift.void

  override def lInsertBefore(key: K, pivot: V, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.linsert(key, true, pivot, value))).futureLift.void

  override def lRem(key: K, count: Long, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.lrem(key, count, value))).futureLift.void

  override def lSet(key: K, index: Long, value: V): F[Unit] =
    async.flatMap(c => F.delay(c.lset(key, index, value))).futureLift.void

  override def lTrim(key: K, start: Long, stop: Long): F[Unit] =
    async.flatMap(c => F.delay(c.ltrim(key, start, stop))).futureLift.void

  /******************************* Geo API **********************************/
  override def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double] =
    async
      .flatMap(c => F.delay(c.geodist(key, from, to, unit)))
      .futureLift
      .map(x => Double.box(x))

  override def geoHash(key: K, values: V*): F[List[Option[String]]] =
    async
      .flatMap(c => F.delay(c.geohash(key, values: _*)))
      .futureLift
      .map(_.asScala.toList.map(x => Option(x.getValue)))

  override def geoPos(key: K, values: V*): F[List[GeoCoordinate]] =
    async
      .flatMap(c => F.delay(c.geopos(key, values: _*)))
      .futureLift
      .map(_.asScala.toList.map(c => GeoCoordinate(c.getX.doubleValue(), c.getY.doubleValue())))

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit)))
      .futureLift
      .map(_.asScala.toSet)

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]] =
    async
      .flatMap(c =>
        F.delay(c.georadius(key, geoRadius.lon.value, geoRadius.lat.value, geoRadius.dist.value, unit, args))
      )
      .futureLift
      .map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit): F[Set[V]] =
    async
      .flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit)))
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
      .flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit, args)))
      .futureLift
      .map(_.asScala.toList.map(_.asGeoRadiusResult))

  override def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Unit] = {
    val triplets = geoValues.flatMap(g => Seq[Any](g.lon.value, g.lat.value, g.value)).asInstanceOf[Seq[AnyRef]]
    async.flatMap(c => F.delay(c.geoadd(key, triplets: _*))).futureLift.void
  }

  override def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit] =
    conn.async
      .flatMap { c =>
        F.delay {
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
        F.delay {
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
      .flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs)))
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
      .flatMap(c => F.delay(c.georadiusbymember(key, value, dist.value, unit, storage.asGeoRadiusStoreArgs)))
      .futureLift
      .void

  /******************************* Sorted Sets API **********************************/
  override def zAdd(key: K, args: Option[ZAddArgs], values: ScoreWithValue[V]*): F[Unit] = {
    val res = args match {
      case Some(x) =>
        async.flatMap(c => F.delay(c.zadd(key, x, values.map(s => ScoredValue.just(s.score.value, s.value)): _*)))
      case None =>
        async.flatMap(c => F.delay(c.zadd(key, values.map(s => ScoredValue.just(s.score.value, s.value)): _*)))
    }
    res.futureLift.void
  }

  override def zAddIncr(key: K, args: Option[ZAddArgs], member: ScoreWithValue[V]): F[Unit] = {
    val res = args match {
      case Some(x) => async.flatMap(c => F.delay(c.zaddincr(key, x, member.score.value, member.value)))
      case None    => async.flatMap(c => F.delay(c.zaddincr(key, member.score.value, member.value)))
    }
    res.futureLift.void
  }

  override def zIncrBy(key: K, member: V, amount: Double): F[Unit] =
    async.flatMap(c => F.delay(c.zincrby(key, amount, member))).futureLift.void

  override def zInterStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Unit] = {
    val res = args match {
      case Some(x) => async.flatMap(c => F.delay(c.zinterstore(destination, x, keys: _*)))
      case None    => async.flatMap(c => F.delay(c.zinterstore(destination, keys: _*)))
    }
    res.futureLift.void
  }

  override def zRem(key: K, values: V*): F[Unit] =
    async.flatMap(c => F.delay(c.zrem(key, values: _*))).futureLift.void

  override def zRemRangeByLex(key: K, range: ZRange[V]): F[Unit] =
    async.flatMap(c => F.delay(c.zremrangebylex(key, JRange.create[V](range.start, range.end)))).futureLift.void

  override def zRemRangeByRank(key: K, start: Long, stop: Long): F[Unit] =
    async.flatMap(c => F.delay(c.zremrangebyrank(key, start, stop))).futureLift.void

  override def zRemRangeByScore(key: K, range: ZRange[V])(implicit ev: Numeric[V]): F[Unit] =
    async.flatMap(c => F.delay(c.zremrangebyscore(key, range.asJavaRange))).futureLift.void

  override def zUnionStore(destination: K, args: Option[ZStoreArgs], keys: K*): F[Unit] = {
    val res = args match {
      case Some(x) => async.flatMap(c => F.delay(c.zunionstore(destination, x, keys: _*)))
      case None    => async.flatMap(c => F.delay(c.zunionstore(destination, keys: _*)))
    }
    res.futureLift.void
  }

  override def zCard(key: K): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.zcard(key)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zCount(key: K, range: ZRange[V])(implicit ev: Numeric[V]): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.zcount(key, range.asJavaRange)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zLexCount(key: K, range: ZRange[V]): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.zlexcount(key, JRange.create[V](range.start, range.end))))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zRange(key: K, start: Long, stop: Long): F[List[V]] =
    async
      .flatMap(c => F.delay(c.zrange(key, start, stop)))
      .futureLift
      .map(_.asScala.toList)

  override def zRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          F.delay(c.zrangebylex(key, JRange.create[V](range.start, range.end), JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => F.delay(c.zrangebylex(key, JRange.create[V](range.start, range.end))))
    }
    res.futureLift.map(_.asScala.toList)
  }

  override def zRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c => F.delay(c.zrangebyscore(key, range.asJavaRange, JLimit.create(x.offset, x.count))))
      case None => async.flatMap(c => F.delay(c.zrangebyscore(key, range.asJavaRange)))
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
        async.flatMap(c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange, JLimit.create(x.offset, x.count))))
      case None => async.flatMap(c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange)))
    }
    res.futureLift.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(c => F.delay(c.zrangeWithScores(key, start, stop)))
      .futureLift
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRank(key: K, value: V): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.zrank(key, value)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zRevRange(key: K, start: Long, stop: Long): F[List[V]] =
    async
      .flatMap(c => F.delay(c.zrevrange(key, start, stop)))
      .futureLift
      .map(_.asScala.toList)

  override def zRevRangeByLex(key: K, range: ZRange[V], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c =>
          F.delay(c.zrevrangebylex(key, JRange.create[V](range.start, range.end), JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => F.delay(c.zrevrangebylex(key, JRange.create[V](range.start, range.end))))
    }
    res.futureLift.map(_.asScala.toList)
  }

  override def zRevRangeByScore[T: Numeric](key: K, range: ZRange[T], limit: Option[RangeLimit]): F[List[V]] = {
    val res = limit match {
      case Some(x) =>
        async.flatMap(c => F.delay(c.zrevrangebyscore(key, range.asJavaRange, JLimit.create(x.offset, x.count))))
      case None => async.flatMap(c => F.delay(c.zrevrangebyscore(key, range.asJavaRange)))
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
          F.delay(c.zrevrangebyscoreWithScores(key, range.asJavaRange, JLimit.create(x.offset, x.count)))
        )
      case None => async.flatMap(c => F.delay(c.zrangebyscoreWithScores(key, range.asJavaRange)))
    }
    res.futureLift.map(_.asScala.toList.map(_.asScoreWithValues))
  }

  override def zRevRangeWithScores(key: K, start: Long, stop: Long): F[List[ScoreWithValue[V]]] =
    async
      .flatMap(c => F.delay(c.zrevrangeWithScores(key, start, stop)))
      .futureLift
      .map(_.asScala.toList.map(_.asScoreWithValues))

  override def zRevRank(key: K, value: V): F[Option[Long]] =
    async
      .flatMap(c => F.delay(c.zrevrank(key, value)))
      .futureLift
      .map(x => Option(Long.unbox(x)))

  override def zScore(key: K, value: V): F[Option[Double]] =
    async
      .flatMap(c => F.delay(c.zscore(key, value)))
      .futureLift
      .map(x => Option(Double.unbox(x)))

  /******************************* Connection API **********************************/
  override val ping: F[String] =
    async.flatMap(c => F.delay(c.ping())).futureLift

  /******************************* Server API **********************************/
  override val flushAll: F[Unit] =
    async.flatMap(c => F.delay(c.flushall())).futureLift.void

  override val flushAllAsync: F[Unit] =
    async.flatMap(c => F.delay(c.flushallAsync())).futureLift.void

  override def keys(key: K): F[List[K]] =
    async
      .flatMap(c => F.delay(c.keys(key)))
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
      .flatMap(c => F.delay(c.eval[output.Underlying](script, output.outputType)))
      .futureLift
      .map(r => output.convert(r))

  override def eval(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async
      .flatMap(c =>
        F.delay(
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
        F.delay(
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

  override def evalSha(script: String, output: ScriptOutputType[V]): F[output.R] =
    async
      .flatMap(c => F.delay(c.evalsha[output.Underlying](script, output.outputType)))
      .futureLift
      .map(output.convert(_))

  override def evalSha(script: String, output: ScriptOutputType[V], keys: List[K]): F[output.R] =
    async
      .flatMap(c =>
        F.delay(
          c.evalsha[output.Underlying](
            script,
            output.outputType,
            // see comment in eval above
            keys.asInstanceOf[Seq[K with Object]].toArray
          )
        )
      )
      .futureLift
      .map(output.convert(_))

  override def evalSha(script: String, output: ScriptOutputType[V], keys: List[K], values: List[V]): F[output.R] =
    async
      .flatMap(c =>
        F.delay(
          c.evalsha[output.Underlying](
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

  override def scriptLoad(script: V): F[String] =
    async.flatMap(c => F.delay(c.scriptLoad(script))).futureLift

  override def scriptExists(digests: String*): F[List[Boolean]] =
    async
      .flatMap(c => F.delay(c.scriptExists(digests: _*)))
      .futureLift
      .map(_.asScala.map(Boolean.unbox(_)).toList)

  override def scriptFlush: F[Unit] =
    async.flatMap(c => F.delay(c.scriptFlush())).futureLift.void
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

private[redis4cats] class Redis[F[_]: Concurrent: ContextShift, K, V](
    connection: RedisStatefulConnection[F, K, V],
    blocker: Blocker
) extends BaseRedis[F, K, V](connection, cluster = false, blocker)

private[redis4cats] class RedisCluster[F[_]: Concurrent: ContextShift, K, V](
    connection: RedisStatefulClusterConnection[F, K, V],
    blocker: Blocker
) extends BaseRedis[F, K, V](connection, cluster = true, blocker)
