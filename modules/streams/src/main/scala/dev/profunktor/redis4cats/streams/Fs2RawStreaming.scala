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

import cats.effect._
import cats.syntax.functor._
import dev.profunktor.redis4cats.effect.{ JRFuture, RedisExecutor }
import dev.profunktor.redis4cats.streams.data._
import io.lettuce.core.XReadArgs.StreamOffset
import io.lettuce.core.api.StatefulRedisConnection
import dev.profunktor.redis4cats.JavaConversions._
import io.lettuce.core.{ XAddArgs, XReadArgs }

private[streams] class RedisRawStreaming[F[_]: Concurrent: ContextShift: RedisExecutor, K, V](
    val client: StatefulRedisConnection[K, V]
) extends RawStreaming[F, K, V] {

  override def xAdd(key: K, body: Map[K, V], approxMaxlen: Option[Long] = None): F[MessageId] =
    JRFuture {
      val args = approxMaxlen.map(XAddArgs.Builder.maxlen(_).approximateTrimming(true))

      F.delay(client.async().xadd(key, args.orNull, body.asJava))
    }.map(MessageId)

  override def xRead(streams: Set[StreamingOffset[K]]): F[List[XReadMessage[K, V]]] = {
    val offsets = streams.map(s => StreamOffset.from(s.key, s.offset)).toSeq
    JRFuture {
      F.delay(client.async().xread(XReadArgs.Builder.block(0), offsets: _*))
    }.map { list =>
      list.asScala.toList.map { msg =>
        XReadMessage[K, V](MessageId(msg.getId), msg.getStream, msg.getBody.asScala.toMap)
      }
    }
  }

}
