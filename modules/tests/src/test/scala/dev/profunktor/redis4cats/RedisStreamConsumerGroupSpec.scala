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
        _   <- redis.del(key)
        _   <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        id2 <- redis.xAdd(key, Map("b" -> "2"))
        // Read new (never-delivered) messages as consumer-1.
        read1 <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        _     <- IO(assertEquals(read1.map(_.id), List(id1, id2)))
        // Both messages are now in the group's Pending Entries List, owned by consumer-1.
        pending <- redis.xPending(key, group)
        _       <- IO(assertEquals(pending.count, 2L))
        _       <- IO(assertEquals(pending.minId, Some(id1)))
        _       <- IO(assertEquals(pending.maxId, Some(id2)))
        _       <- IO(assertEquals(pending.consumers, Map("consumer-1" -> 2L)))
        // Acknowledge the first message.
        acked           <- redis.xAck(key, group, id1.value)
        _               <- IO(assertEquals(acked, 1L))
        pendingAfterAck <- redis.xPending(key, group)
        _               <- IO(assertEquals(pendingAfterAck.count, 1L))
        // Extended XPENDING form returns one entry, still owned by consumer-1.
        details <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _       <- IO(assertEquals(details.map(_.id), List(id2)))
        _       <- IO(assertEquals(details.map(_.consumer), List("consumer-1")))
        // Claim the remaining message for consumer-2 (minIdleTime 0 makes it immediately claimable).
        claimed <- redis.xClaim(key, c2, XClaimArgs(minIdleTime = 0.millis), id2.value)
        _       <- IO(assertEquals(claimed.map(_.id), List(id2)))
        reowned <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _       <- IO(assertEquals(reowned.map(_.consumer), List("consumer-2")))
        // Create then delete an idle consumer.
        created  <- redis.xGroupCreateConsumer(key, StreamConsumer(group, "consumer-3"))
        _        <- IO(assert(created, "consumer-3 should be newly created"))
        delCount <- redis.xGroupDelConsumer(key, StreamConsumer(group, "consumer-3"))
        _        <- IO(assertEquals(delCount, 0L))
        // Destroy the group.
        destroyed <- redis.xGroupDestroy(key, group)
        _         <- IO(assert(destroyed, "group should have been destroyed"))
        _         <- redis.del(key)
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
        _   <- redis.del(key)
        _   <- redis.xGroupCreate(key, group, offset = "0", args = XGroupCreateArgs(mkStream = true))
        id1 <- redis.xAdd(key, Map("a" -> "1"))
        _   <- redis.xReadGroup(c1, XReadOffsets.custom(">", key))
        result <- redis.xAutoClaim(key, XAutoClaimArgs(consumer = c2, minIdleTime = 0.millis))
        _      <- IO(assertEquals(result.messages.map(_.id), List(id1)))
        owners <- redis.xPending(key, group, XRangePoint.Unbounded, XRangePoint.Unbounded, count = 10L)
        _      <- IO(assertEquals(owners.map(_.consumer), List("c2")))
        _      <- redis.del(key)
      } yield ()
    }
  }
}
