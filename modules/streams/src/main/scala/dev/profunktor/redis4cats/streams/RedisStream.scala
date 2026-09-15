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
package streams

import cats.effect.kernel._
import cats.syntax.all._
import dev.profunktor.redis4cats.StreamsInstances._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.effects.{ MessageId, StreamMessage, XReadOffsets }
import dev.profunktor.redis4cats.streams.data._
import fs2.Stream
import io.lettuce.core.{ ReadFrom => JReadFrom, RedisCommandExecutionException }

import scala.concurrent.duration.Duration

object RedisStream {

  def apply[F[_]: Sync, K, V](redis: RedisCommands[F, K, V]): RedisStream[F, K, V] = new RedisStream(redis)

  def mkStreamingConnection[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Stream[F, Streaming[F, Stream[F, *], K, V]] =
    Stream.resource(mkStreamingConnectionResource(client, codec))

  def mkStreamingConnectionResource[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, Streaming[F, Stream[F, *], K, V]] =
    Redis[F].fromClient(client, codec).map(apply[F, K, V])

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
      .flatMap(Redis[F].masterReplica)
      .map(apply[F, K, V])

}

class RedisStream[F[_]: Sync, K, V](redis: RedisCommands[F, K, V]) extends Streaming[F, Stream[F, *], K, V] {

  override def append: Stream[F, XAddMessage[K, V]] => Stream[F, MessageId] =
    _.evalMap(append)

  override def append(msg: XAddMessage[K, V]): F[MessageId] =
    redis.xAdd(msg.key, msg.body, msg.args)

  override def read(
      streams: Set[XReadOffsets[K]],
      block: Option[Duration],
      count: Option[Long],
      restartOnTimeout: RestartOnTimeout
  ): Stream[F, StreamMessage[K, V]] = {
    val initialOffsets = streams.map(o => o.key -> o).toMap

    Stream.eval(Ref.of[F, Map[K, XReadOffsets[K]]](initialOffsets)).flatMap { offsets =>
      val streamMessages =
        Stream.force {
          for {
            currentOffsets <- offsets.get
            resolvedOffsets <-
              currentOffsets.toList.traverse { case (k, o) => resolveOffset(o).tupleLeft(k) }.map(_.toMap)
            messages <- redis.xRead(resolvedOffsets.values.toSet, block, count)
            _ <- offsets.set(resolvedOffsets ++ latestOffsets(messages))
          } yield Stream.fromIterator[F](iterator = messages.iterator, chunkSize = messages.size)
        }.repeat

      restartOnTimeout.wrap(streamMessages)
    }
  }

  /** A `Latest` ($) offset re-resolves to "now" on every call, so a poll that returns no messages for that key would
    * otherwise skip any entries published before the next poll. Pin it down to the stream's actual last id instead, so
    * later reads use a stable, advancing id. Left as `Latest` if the stream doesn't exist yet - `XINFO STREAM` errors
    * on a missing key, while `XREAD $` legitimately waits for the stream to be created.
    */
  private def resolveOffset(o: XReadOffsets[K]): F[XReadOffsets[K]] =
    o match {
      case XReadOffsets.Latest(key) =>
        redis
          .xInfoStream(key)
          .map(info => XReadOffsets.Custom(key, info.lastGeneratedId.value): XReadOffsets[K])
          .recover {
            // Only a missing stream falls back to Latest - any other failure (e.g. a timeout) must
            // propagate, both so RestartOnTimeout's cumulative-elapsed accounting sees it and so this
            // doesn't silently reintroduce the $ skip-window this whole resolution step exists to close.
            case e: RedisCommandExecutionException
                if e.getMessage != null && e.getMessage.startsWith("ERR no such key") =>
              o
          }
      case _ => o.pure[F]
    }

  private[streams] def latestOffsets(iter: Iterable[StreamMessage[K, V]]) =
    iter
      .foldLeft(collection.mutable.Map.empty[K, XReadOffsets[K]]) { case (offsets, msg) =>
        offsets += msg.key -> XReadOffsets.Custom(msg.key, msg.id.value)
      }
}
