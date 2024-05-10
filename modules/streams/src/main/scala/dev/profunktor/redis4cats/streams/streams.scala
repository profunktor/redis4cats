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

package dev.profunktor.redis4cats.streams

import dev.profunktor.redis4cats.streams.data._

import scala.concurrent.duration.Duration

trait RawStreaming[F[_], K, V] {

  /**
    * @param approxMaxlen does XTRIM ~ maxlen if defined
    * @param minId  the oldest ID in the stream will be exactly the minimum between its original oldest ID and the specified threshold.
    */
  def xAdd(
      key: K,
      body: Map[K, V],
      approxMaxlen: Option[Long] = None,
      minId: Option[String] = None
  ): F[MessageId]

  def xRead(
      streams: Set[StreamingOffset[K]],
      block: Option[Duration] = Some(Duration.Zero),
      count: Option[Long] = None
  ): F[List[XReadMessage[K, V]]]
}

trait Streaming[F[_], K, V] {
  def append: F[XAddMessage[K, V]] => F[MessageId]

  def read(
      keys: Set[K],
      chunkSize: Int,
      initialOffset: K => StreamingOffset[K] = StreamingOffset.All[K],
      block: Option[Duration] = Some(Duration.Zero),
      count: Option[Long] = None
  ): F[XReadMessage[K, V]]
}
