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

import dev.profunktor.redis4cats.effects.{
  MessageId,
  StreamConsumer,
  StreamMessage,
  XAddArgs,
  XAutoClaimArgs,
  XAutoClaimResult,
  XClaimArgs,
  XGroupCreateArgs,
  XPendingMessage,
  XPendingSummary,
  XRangePoint,
  XReadGroupArgs,
  XReadOffsets,
  XTrimArgs
}

import scala.concurrent.duration.Duration

trait StreamCommands[F[_], K, V]
    extends StreamGetter[F, K, V]
    with StreamSetter[F, K, V]
    with StreamConsumerGroups[F, K, V]

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

/** Consumer-group commands for Redis Streams (the `XGROUP`/`XREADGROUP`/`XACK`/`XCLAIM`/`XPENDING` family).
  *
  * For reading new (never-delivered) messages with [[xReadGroup]], use `XReadOffsets.custom(">", key)`.
  */
trait StreamConsumerGroups[F[_], K, V] {

  def xGroupCreate(
      key: K,
      group: K,
      offset: String = "$",
      args: XGroupCreateArgs = XGroupCreateArgs()
  ): F[Unit]
  def xGroupSetId(key: K, group: K, offset: String): F[Unit]
  def xGroupDestroy(key: K, group: K): F[Boolean]
  def xGroupCreateConsumer(key: K, consumer: StreamConsumer[K]): F[Boolean]
  def xGroupDelConsumer(key: K, consumer: StreamConsumer[K]): F[Long]

  def xReadGroup(
      consumer: StreamConsumer[K],
      streams: Set[XReadOffsets[K]],
      args: XReadGroupArgs = XReadGroupArgs()
  ): F[List[StreamMessage[K, V]]]

  def xAck(key: K, group: K, ids: String*): F[Long]

  def xClaim(
      key: K,
      consumer: StreamConsumer[K],
      args: XClaimArgs,
      ids: String*
  ): F[List[StreamMessage[K, V]]]
  def xAutoClaim(key: K, args: XAutoClaimArgs[K]): F[XAutoClaimResult[K, V]]

  def xPending(key: K, group: K): F[XPendingSummary]
  def xPending(
      key: K,
      group: K,
      start: XRangePoint,
      end: XRangePoint,
      count: Long
  ): F[List[XPendingMessage]]
  def xPending(
      key: K,
      consumer: StreamConsumer[K],
      start: XRangePoint,
      end: XRangePoint,
      count: Long
  ): F[List[XPendingMessage]]
}
