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

import cats.effect.IO
import dev.profunktor.redis4cats.effects._

import scala.concurrent.duration._

class RedisStreamConsumerGroupSpec extends Redis4CatsFunSuite(isCluster = false) {

  test("consumer groups: create, read, ack, pending and claim") {
    val key   = "test-cg-stream"
    val group = "test-cg-group"
    val c1    = StreamConsumer(group, "consumer-1")
    val c2    = StreamConsumer(group, "consumer-2")

    withRedis { redis =>
      for {
        _ <- redis.del(key)
        _ <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        id2 <- redis.xAdd(key, Map("b" -> "2"))
        // Read new (never-delivered) messages as consumer-1.
        read1 <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        _ <- IO(assertEquals(read1.map(_.id), List(id1, id2)))
        // Both messages are now in the group's Pending Entries List, owned by consumer-1.
        pending <- redis.xPending(key, group)
        _ <- IO(assertEquals(pending.count, 2L))
        _ <- IO(assertEquals(pending.minId, Some(id1)))
        _ <- IO(assertEquals(pending.maxId, Some(id2)))
        _ <- IO(assertEquals(pending.consumers, Map("consumer-1" -> 2L)))
        // Acknowledge the first message.
        acked <- redis.xAck(key, group, id1.value)
        _ <- IO(assertEquals(acked, 1L))
        pendingAfterAck <- redis.xPending(key, group)
        _ <- IO(assertEquals(pendingAfterAck.count, 1L))
        // Extended XPENDING form returns one entry, still owned by consumer-1.
        details <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(details.map(_.id), List(id2)))
        _ <- IO(assertEquals(details.map(_.consumer), List("consumer-1")))
        // Claim the remaining message for consumer-2 (minIdleTime 0 makes it immediately claimable).
        claimed <- redis.xClaim(key, c2, XClaimArgs(minIdleTime = 0.millis), id2.value)
        _ <- IO(assertEquals(claimed.map(_.id), List(id2)))
        reowned <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(reowned.map(_.consumer), List("consumer-2")))
        // Consumer-filtered XPENDING returns only the given consumer's pending entries.
        byC2 <- redis.xPending(key, c2, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(byC2.map(_.id), List(id2)))
        byC1 <- redis.xPending(key, c1, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(byC1, List.empty[XPendingMessage]))
        // XCLAIM with force/justId returns the claimed ids (without message bodies).
        claimedIds <- redis.xClaim(key, c2, XClaimArgs(minIdleTime = 0.millis, force = true, justId = true), id2.value)
        _ <- IO(assertEquals(claimedIds.map(_.id), List(id2)))
        // Create then delete an idle consumer.
        created <- redis.xGroupCreateConsumer(key, StreamConsumer(group, "consumer-3"))
        _ <- IO(assert(created, "consumer-3 should be newly created"))
        delCount <- redis.xGroupDelConsumer(key, StreamConsumer(group, "consumer-3"))
        _ <- IO(assertEquals(delCount, 0L))
        // Destroy the group.
        destroyed <- redis.xGroupDestroy(key, group)
        _ <- IO(assert(destroyed, "group should have been destroyed"))
        _ <- redis.del(key)
      } yield ()
    }
  }

  test("consumer groups: xAutoClaim reassigns pending messages") {
    val key   = "test-cg-autoclaim"
    val group = "test-cg-ac-group"
    val c1    = StreamConsumer(group, "c1")
    val c2    = StreamConsumer(group, "c2")

    withRedis { redis =>
      for {
        _ <- redis.del(key)
        _ <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        _ <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        result <- redis.xAutoClaim(key, XAutoClaimArgs(consumer = c2, minIdleTime = 0.millis))
        _ <- IO(assertEquals(result.messages.map(_.id), List(id1)))
        // JustId form re-claims and returns the id without the message body.
        resultJustId <- redis.xAutoClaim(key, XAutoClaimArgs(consumer = c2, minIdleTime = 0.millis, justId = true))
        _ <- IO(assertEquals(resultJustId.messages.map(_.id), List(id1)))
        owners <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(owners.map(_.consumer), List("c2")))
        _ <- redis.del(key)
      } yield ()
    }
  }

  test("consumer groups: xInfoGroups and xInfoConsumers") {
    val key   = "test-cg-info"
    val group = "test-cg-info-group"
    val other = "test-cg-info-other-group"
    val c1    = StreamConsumer(group, "info-consumer-1")

    withRedis { redis =>
      for {
        _ <- redis.del(key)
        _ <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        _ <- redis.xGroupCreate(key, other, offset = "0")
        // Fresh group, nothing read yet: no consumers/pending, and entries-read is not yet determinable.
        freshGroups <- redis.xInfoGroups(key)
        freshGroup = freshGroups.find(_.name == group).getOrElse(fail(s"group $group not found in $freshGroups"))
        _ <- IO(assertEquals(freshGroup.consumers, 0L))
        _ <- IO(assertEquals(freshGroup.pending, 0L))
        _ <- IO(assertEquals(freshGroup.entriesRead, None))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        _ <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        groupsAfterRead <- redis.xInfoGroups(key)
        _ <- IO(assertEquals(groupsAfterRead.map(_.name).toSet, Set(group, other)))
        groupAfterRead = groupsAfterRead.find(_.name == group).getOrElse(fail("group not found"))
        _ <- IO(assertEquals(groupAfterRead.consumers, 1L))
        _ <- IO(assertEquals(groupAfterRead.pending, 1L))
        _ <- IO(assertEquals(groupAfterRead.lastDeliveredId, id1))
        _ <- IO(assertEquals(groupAfterRead.entriesRead, Some(1L)))
        consumers <- redis.xInfoConsumers(key, group)
        _ <- IO(assertEquals(consumers.map(_.name), List("info-consumer-1")))
        _ <- IO(assertEquals(consumers.head.pending, 1L))
        _ <- IO(assert(consumers.head.idle >= 0.millis, "idle time should be non-negative"))
        otherConsumers <- redis.xInfoConsumers(key, other)
        _ <- IO(assertEquals(otherConsumers, List.empty))
        _ <- redis.del(key)
      } yield ()
    }
  }

  test("consumer groups: xAckDel acknowledges and deletes in one call") {
    val key   = "test-cg-ackdel"
    val group = "test-cg-ackdel-group"
    val c1    = StreamConsumer(group, "ackdel-consumer")

    withRedis { redis =>
      for {
        _ <- redis.del(key)
        _ <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        id2 <- redis.xAdd(key, Map("b" -> "2"))
        _ <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        // KeepReferences (the default): the entry is removed from the stream but XACK still succeeds.
        deleted <- redis.xAckDel(key, group, StreamDeletionPolicy.KeepReferences, id1.value)
        _ <- IO(assertEquals(deleted, List(StreamEntryDeletionResult.Deleted)))
        len <- redis.xLen(key)
        _ <- IO(assertEquals(len, 1L))
        pendingAfter <- redis.xPending(key, group)
        _ <- IO(assertEquals(pendingAfter.count, 1L), "id1 should no longer be pending after xAckDel")
        // Re-running against an id that's already gone reports NotFound rather than failing.
        deletedAgain <- redis.xAckDel(key, group, StreamDeletionPolicy.KeepReferences, id1.value)
        _ <- IO(assertEquals(deletedAgain, List(StreamEntryDeletionResult.NotFound)))
        // DeleteReferences explicitly: same effect as above for a still-pending id, but also clears the PEL entry.
        deletedWithPolicy <- redis.xAckDel(key, group, StreamDeletionPolicy.DeleteReferences, id2.value)
        _ <- IO(assertEquals(deletedWithPolicy, List(StreamEntryDeletionResult.Deleted)))
        pendingFinal <- redis.xPending(key, group)
        _ <- IO(assertEquals(pendingFinal.count, 0L))
        _ <- redis.del(key)
      } yield ()
    }
  }

  test("consumer groups: xNack releases a message back to the PEL without acking it") {
    val key   = "test-cg-nack"
    val group = "test-cg-nack-group"
    val c1    = StreamConsumer(group, "nack-consumer")

    withRedis { redis =>
      for {
        _ <- redis.del(key)
        _ <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        _ <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        pendingBefore <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(pendingBefore.map(_.redeliveryCount), List(1L)))
        // FAIL leaves the delivery counter unchanged but the message remains pending (not acked/deleted).
        affected <- redis.xNack(key, group, XNackMode.Fail, id1.value)
        _ <- IO(assertEquals(affected, 1L))
        pendingAfterFail <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(pendingAfterFail.map(_.id), List(id1)))
        _ <- IO(assertEquals(pendingAfterFail.map(_.redeliveryCount), List(1L)))
        // SILENT decrements the delivery counter by one.
        _ <- redis.xNack(key, group, XNackMode.Silent, id1.value)
        pendingAfterSilent <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _ <- IO(assertEquals(pendingAfterSilent.map(_.redeliveryCount), List(0L)))
        // Nacking an id that isn't pending affects nothing.
        affectedMissing <- redis.xNack(key, group, XNackMode.Fail, "0-1")
        _ <- IO(assertEquals(affectedMissing, 0L))
        _ <- redis.del(key)
      } yield ()
    }
  }
}
