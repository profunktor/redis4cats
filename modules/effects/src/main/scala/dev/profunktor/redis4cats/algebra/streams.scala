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
  StreamDeletionPolicy,
  StreamEntryDeletionResult,
  StreamMessage,
  XAddArgs,
  XAutoClaimArgs,
  XAutoClaimResult,
  XCfgSetArgs,
  XClaimArgs,
  XConsumerInfo,
  XGroupCreateArgs,
  XGroupInfo,
  XNackMode,
  XPendingMessage,
  XPendingSummary,
  XRangePoint,
  XReadGroupArgs,
  XReadOffsets,
  XStreamInfo,
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

  /** `XINFO STREAM` - general information about a stream. */
  def xInfoStream(key: K): F[XStreamInfo[K, V]]
}

trait StreamSetter[F[_], K, V] {

  def xAdd(key: K, body: Map[K, V], args: XAddArgs = XAddArgs()): F[MessageId]
  def xTrim(key: K, args: XTrimArgs): F[Long]
  def xDel(key: K, ids: String*): F[Long]

  /** `XDELEX` - like [[xDel]], but with control over what happens to consumer-group PEL references to the deleted
    * entries (`policy` defaults to `StreamDeletionPolicy.KeepReferences`, matching plain `XDEL`). Returns the per-id
    * outcome, in the same order as `ids`.
    */
  def xDelEx(
      key: K,
      policy: StreamDeletionPolicy = StreamDeletionPolicy.KeepReferences,
      ids: String*
  ): F[List[StreamEntryDeletionResult]]

  /** `XCFGSET` - sets stream-level idempotent-publish configuration (see [[XCfgSetArgs]]). */
  def xCfgSet(key: K, args: XCfgSetArgs): F[Unit]
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

  /** `XINFO GROUPS` - one entry per consumer group registered on the stream. */
  def xInfoGroups(key: K): F[List[XGroupInfo]]

  /** `XINFO CONSUMERS` - one entry per consumer registered on the given group. */
  def xInfoConsumers(key: K, group: K): F[List[XConsumerInfo]]

  def xReadGroup(
      consumer: StreamConsumer[K],
      streams: Set[XReadOffsets[K]],
      args: XReadGroupArgs = XReadGroupArgs()
  ): F[List[StreamMessage[K, V]]]

  def xAck(key: K, group: K, ids: String*): F[Long]

  /** `XACKDEL` - atomically combines [[xAck]] with an [[xDelEx]]-style deletion (`policy` defaults to
    * `StreamDeletionPolicy.KeepReferences`). Returns the per-id deletion outcome, in the same order as `ids`.
    */
  def xAckDel(
      key: K,
      group: K,
      policy: StreamDeletionPolicy = StreamDeletionPolicy.KeepReferences,
      ids: String*
  ): F[List[StreamEntryDeletionResult]]

  /** `XNACK` - negatively-acknowledges messages in `group`'s Pending Entries List, adjusting their delivery counter per
    * `mode` without acknowledging or removing them. Returns the number of entries affected.
    *
    * Lettuce also exposes a single-id overload; it's not mirrored here since this varargs form already covers that call
    * shape (`xNack(key, group, mode, id)`).
    */
  def xNack(key: K, group: K, mode: XNackMode, ids: String*): F[Long]

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
