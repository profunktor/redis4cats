/*
 * Copyright 2018-2020 ProfunKtor
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

import cats.implicits.catsSyntaxTuple3Parallel
import dev.profunktor.redis4cats.data.RedisChannel

import scala.concurrent.duration._

class RedisPubSubSpec extends Redis4CatsFunSuite(false) {
  private val channelFour = RedisChannel("channelFour")
  private val channelFive = RedisChannel("channelFive")

  test("subscribe to multiple channels") {
    withRedisPubSub { redis =>
      redis
        .subscribe(channelFour, channelFive)
        .take(3)
        .concurrently(fs2.Stream("one").through(redis.publish(channelFour)).delayBy(100.millis))
        .concurrently(fs2.Stream("two", "three").through(redis.publish(channelFive)).delayBy(100.millis))
        .compile
        .toVector
        .map(a => assertEquals(a.sorted, Vector("one", "two", "three").sorted))
    }
  }

  test("multiple subscriptions should get their own messages") {
    withRedisPubSub { redis =>
      val ch4 = redis.subscribe(channelFour).take(1).compile.toVector
      val ch5 = redis.subscribe(channelFive).take(2).compile.toVector
      val publisher = fs2
        .Stream("one")
        .repeatN(3)
        .through(redis.publish(channelFour))
        .concurrently(fs2.Stream("two", "three").repeatN(3).through(redis.publish(channelFive)))
        .delayBy(101.millis)
        .compile
        .drain

      for {
        (rc4, rc5) <- (ch4, ch5, publisher).parMapN((a, b, _) => a -> b)
      } yield {
        assertEquals(rc4, Vector("one"))
        assertEquals(rc5, Vector("two", "three"))
      }
    }
  }

  test("messages are not delivered after unsubscribing from a channel") {
    withRedisPubSub { redis =>
      redis
        .subscribe(channelFour)
        .groupWithin(100, 500.millis)
        .take(1)
        .concurrently(fs2.Stream("one", "two", "three").through(redis.publish(channelFour)).metered(100.millis))
        .concurrently(redis.unsubscribe(channelFour).delayBy(300.millis))
        .compile
        .toVector
        .map(a => assertEquals(a.flatMap(_.toVector).sorted, Vector("one", "two").sorted))
    }
  }
}
