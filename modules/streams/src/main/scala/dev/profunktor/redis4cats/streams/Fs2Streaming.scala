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
package streams

import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.{ FutureLift, Log }
import dev.profunktor.redis4cats.streams.data._
import fs2.Stream
import io.lettuce.core.{ ReadFrom => JReadFrom }
import dev.profunktor.redis4cats.StreamsInstances._

import scala.concurrent.duration.Duration

object RedisStream {

  def mkStreamingConnection[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Stream[F, Streaming[F, Stream[F, *], K, V]] =
    Stream.resource(mkStreamingConnectionResource(client, codec))

  def mkStreamingConnectionResource[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, Streaming[F, Stream[F, *], K, V]] = {
    val acquire =
      FutureLift[F]
        .lift(client.underlying.connectAsync[K, V](codec.underlying, client.uri.underlying))
        .map(new RedisRawStreaming(_))

    val release: RedisRawStreaming[F, K, V] => F[Unit] = c =>
      FutureLift[F].lift(c.client.closeAsync()) *>
        Log[F].info(s"Releasing Streaming connection: ${client.uri.underlying}")

    Resource.make(acquire)(release).map(rs => new RedisStream(rs))
  }

  def mkMasterReplicaConnection[F[_]: Async: Log, K, V](
      codec: RedisCodec[K, V],
      uris: RedisURI*
  )(readFrom: Option[JReadFrom] = None): Stream[F, Streaming[F, Stream[F, *], K, V]] =
    Stream.resource(mkMasterReplicaConnectionResource(codec, uris: _*)(readFrom))

  def mkMasterReplicaConnectionResource[F[_]: Async: Log, K, V](
      codec: RedisCodec[K, V],
      uris: RedisURI*
  )(readFrom: Option[JReadFrom] = None): Resource[F, Streaming[F, Stream[F, *], K, V]] =
    RedisMasterReplica[F]
      .make(codec, uris: _*)(readFrom)
      .map(fromMasterReplica[F, K, V](_))

  def fromMasterReplica[F[_]: Async, K, V](
      connection: RedisMasterReplica[K, V]
  ): Streaming[F, Stream[F, *], K, V] =
    new RedisStream(new RedisRawStreaming(connection.underlying))

}

class RedisStream[F[_]: Sync, K, V](rawStreaming: RedisRawStreaming[F, K, V]) extends Streaming[F, Stream[F, *], K, V] {

  private[streams] def nextOffset(key: K, msg: XReadMessage[K, V]): StreamingOffset[K] =
    StreamingOffset.Custom(key, msg.id.value)

  private[streams] def offsetsByKey(iter: Iterable[XReadMessage[K, V]]): Iterator[(K, StreamingOffset[K])] = {
    val map = collection.mutable.Map.empty[K, XReadMessage[K, V]]
    iter.iterator.foreach(msg => map += msg.key -> msg)
    map.iterator.map { case (key, msg) => key -> nextOffset(key, msg) }
  }

  override def append: Stream[F, XAddMessage[K, V]] => Stream[F, MessageId] =
    _.evalMap(append)

  override def append(msg: XAddMessage[K, V]): F[MessageId] =
    rawStreaming.xAdd(msg.key, msg.body, msg.approxMaxlen, msg.minId)

  override def read(
      keys: Set[K],
      chunkSize: Int,
      initialOffset: K => StreamingOffset[K],
      block: Option[Duration],
      count: Option[Long],
      restartOnTimeout: RestartOnTimeout
  ): Stream[F, XReadMessage[K, V]] = {
    val initial = keys.map(k => k -> initialOffset(k)).toMap
    Stream.eval(Ref.of[F, Map[K, StreamingOffset[K]]](initial)).flatMap { ref =>
      def withoutRestarts =
        (for {
          offsets <- Stream.eval(ref.get)
          list <- Stream.eval(rawStreaming.xRead(offsets.values.toSet, block, count))
          offsetUpdates = offsetsByKey(list)
          _ <- Stream.eval(ref.update(map => offsetUpdates.foldLeft(map) { case (acc, (k, v)) => acc.updated(k, v) }))
          result <- Stream.fromIterator[F](list.iterator, chunkSize)
        } yield result).repeat

      restartOnTimeout.wrap(withoutRestarts)
    }
  }

}
