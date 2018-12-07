/*
 * Copyright 2018 Fs2 Redis
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

package com.github.gvolpe.fs2redis

import cats.effect.IO
import com.github.gvolpe.fs2redis.domain.DefaultRedisCodec
import com.github.gvolpe.fs2redis.effects._
import io.lettuce.core.GeoArgs
import org.scalatest.FunSuite

class Fs2RedisClusterSpec extends FunSuite with RedisClusterTest {

  test("cluster: geo api") {
    val _BuenosAires  = GeoLocation(Longitude(-58.3816), Latitude(-34.6037), "Buenos Aires")
    val _RioDeJaneiro = GeoLocation(Longitude(-43.1729), Latitude(-22.9068), "Rio de Janeiro")
    val _Montevideo   = GeoLocation(Longitude(-56.164532), Latitude(-34.901112), "Montevideo")
    val _Tokyo        = GeoLocation(Longitude(139.6917), Latitude(35.6895), "Tokyo")

    withRedisCluster { cmd =>
      val testKey = "location"
      for {
        _ <- cmd.geoAdd(testKey, _BuenosAires)
        _ <- cmd.geoAdd(testKey, _RioDeJaneiro)
        _ <- cmd.geoAdd(testKey, _Montevideo)
        _ <- cmd.geoAdd(testKey, _Tokyo)
        x <- cmd.geoDist(testKey, _BuenosAires.value, _Tokyo.value, GeoArgs.Unit.km)
        _ <- IO { assert(x == 18374.9052) }
        y <- cmd.geoPos(testKey, _RioDeJaneiro.value)
        _ <- IO { assert(y.contains(GeoCoordinate(-43.17289799451828, -22.906801071586663))) }
        z <- cmd.geoRadius(testKey, GeoRadius(_Montevideo.lon, _Montevideo.lat, Distance(10000.0)), GeoArgs.Unit.km)
        _ <- IO { assert(z.toList.containsSlice(List(_BuenosAires.value, _Montevideo.value, _RioDeJaneiro.value))) }
      } yield ()
    }
  }

  test("cluster: hashes api") {
    withRedisCluster { cmd =>
      val testKey   = "foo"
      val testField = "bar"
      for {
        x <- cmd.hGet(testKey, testField)
        _ <- IO { assert(x.isEmpty) }
        _ <- cmd.hSet(testKey, testField, "some value")
        y <- cmd.hGet(testKey, testField)
        _ <- IO { assert(y.contains("some value")) }
        _ <- cmd.hSetNx(testKey, testField, "should not happen")
        w <- cmd.hGet(testKey, testField)
        _ <- IO { assert(w.contains("some value")) }
        _ <- cmd.hDel(testKey, testField)
        z <- cmd.hGet(testKey, testField)
        _ <- IO { assert(z.isEmpty) }
      } yield ()
    }
  }

  test("cluster: lists api") {
    withRedisCluster { cmd =>
      val testKey = "listos"
      for {
        t <- cmd.lRange(testKey, 0, 10)
        _ <- IO { assert(t.isEmpty) }
        _ <- cmd.rPush(testKey, "one", "two", "three")
        x <- cmd.lRange(testKey, 0, 10)
        _ <- IO { assert(x == List("one", "two", "three")) }
        y <- cmd.lLen(testKey)
        _ <- IO { assert(y.contains(3)) }
        a <- cmd.lPop(testKey)
        _ <- IO { assert(a.contains("one")) }
        b <- cmd.rPop(testKey)
        _ <- IO { assert(b.contains("three")) }
        z <- cmd.lRange(testKey, 0, 10)
        _ <- IO { assert(z == List("two")) }
      } yield ()
    }
  }

  test("cluster: sets api") {
    withRedisCluster { cmd =>
      val testKey = "foos"
      for {
        x <- cmd.sMembers(testKey)
        _ <- IO { assert(x.isEmpty) }
        _ <- cmd.sAdd(testKey, "set value")
        y <- cmd.sMembers(testKey)
        _ <- IO { assert(y.contains("set value")) }
        o <- cmd.sCard(testKey)
        _ <- IO { assert(o == 1L) }
        _ <- cmd.sRem("non-existing", "random")
        w <- cmd.sMembers(testKey)
        _ <- IO { assert(w.contains("set value")) }
        _ <- cmd.sRem(testKey, "set value")
        z <- cmd.sMembers(testKey)
        _ <- IO { assert(z.isEmpty) }
        t <- cmd.sCard(testKey)
        _ <- IO { assert(t == 0L) }
      } yield ()
    }
  }

  test("cluster: sorted sets api") {
    withAbstractRedisCluster[Unit, String, Long] { cmd =>
      val testKey = "zztop"
      for {
        t <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
        _ <- IO { assert(t.isEmpty) }
        _ <- cmd.zAdd(testKey, args = None, ScoreWithValue(Score(1), 1), ScoreWithValue(Score(3), 2))
        x <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
        _ <- IO { assert(x == List(1)) }
        y <- cmd.zCard(testKey)
        _ <- IO { assert(y.contains(2)) }
        z <- cmd.zCount(testKey, ZRange(0, 1))
        _ <- IO { assert(z.contains(1)) }
      } yield ()
    }(DefaultRedisCodec(LongCodec))
  }

  test("strings api") {
    withRedisCluster { cmd =>
      val key = "test"
      for {
        x <- cmd.get(key)
        _ <- IO { assert(x.isEmpty) }
        _ <- cmd.set(key, "some value")
        y <- cmd.get(key)
        _ <- IO { assert(y.contains("some value")) }
        _ <- cmd.setNx(key, "should not happen")
        w <- cmd.get(key)
        _ <- IO { assert(w.contains("some value")) }
        _ <- cmd.del(key)
        z <- cmd.get(key)
        _ <- IO { assert(z.isEmpty) }
      } yield ()
    }
  }

}
