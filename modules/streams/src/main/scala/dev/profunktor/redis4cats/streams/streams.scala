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

import data._

// format: off
trait RawStreaming[F[_], K, V] {

  /**
    * @param approxMaxlen does XTRIM ~ maxlen if defined
    */
  def xAdd(key: K, body: Map[K, V], approxMaxlen: Option[Long] = None): F[MessageId]
  def xRead(streams: Set[StreamingOffset[K]]): F[List[XReadMessage[K, V]]]
}

trait Streaming[F[_], K, V] {
  def append: F[XAddMessage[K, V]] => F[MessageId]
  def read(keys: Set[K], initialOffset: K => StreamingOffset[K] = StreamingOffset.All[K]): F[XReadMessage[K, V]]
}
