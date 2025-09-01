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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects.{ MessageId, StreamMessage, XAddArgs, XRangePoint, XReadOffsets, XTrimArgs }

import scala.concurrent.duration.Duration

trait StreamCommands[F[_], K, V] extends StreamGetter[F, K, V] with StreamSetter[F, K, V]

trait StreamGetter[F[_], K, V] {

  def xRead(
      streams: Set[XReadOffsets[K]],
      block: Option[Duration] = None,
      count: Option[Long] = None
  ): F[List[StreamMessage[K, V]]]
  def xRange(key: K, start: XRangePoint, end: XRangePoint, count: Option[Long] = None): F[List[StreamMessage[K, V]]]
  def xRevRange(key: K, start: XRangePoint, end: XRangePoint, count: Option[Long] = None): F[List[StreamMessage[K, V]]]
  def xLen(key: K): F[Long]
}

trait StreamSetter[F[_], K, V] {

  def xAdd(key: K, body: Map[K, V], args: XAddArgs = XAddArgs()): F[MessageId]
  def xTrim(key: K, args: XTrimArgs): F[Long]
  def xDel(key: K, ids: String*): F[Long]
}
