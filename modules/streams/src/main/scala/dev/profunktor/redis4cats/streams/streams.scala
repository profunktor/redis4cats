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

package dev.profunktor.redis4cats.streams

import dev.profunktor.redis4cats.RestartOnTimeout
import dev.profunktor.redis4cats.effects.{ MessageId, StreamMessage, XReadOffsets }
import dev.profunktor.redis4cats.streams.data._

import scala.concurrent.duration.Duration

/** @tparam F
  *   the effect type
  * @tparam S
  *   the stream type
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
trait Streaming[F[_], S[_], K, V] {
  def append: S[XAddMessage[K, V]] => S[MessageId]

  def append(msg: XAddMessage[K, V]): F[MessageId]

  /** Read data from one or multiple streams, returning an entry per stream with an ID greater than the last received
    * ID. ID's are initialized with initialOffset field.
    *
    * @see
    *   https://redis.io/commands/xread
    */
  def read(
      streams: Set[XReadOffsets[K]],
      block: Option[Duration] = Some(Duration.Zero),
      count: Option[Long] = None,
      restartOnTimeout: RestartOnTimeout = RestartOnTimeout.always
  ): S[StreamMessage[K, V]]
}
