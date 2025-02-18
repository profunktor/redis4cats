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

package dev.profunktor.redis4cats

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.algebra.BitCommandOperation.{ IncrUnsignedBy, SetUnsigned }
import dev.profunktor.redis4cats.algebra.BitCommands
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.tx._
import fs2.Stream
import io.lettuce.core.{ GeoArgs, RedisCommandExecutionException, RedisException, ZAggregateArgs }
import munit.FunSuite

import java.time.Instant
import scala.concurrent.duration._

trait TestScenarios { self: FunSuite =>

  def locationScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val _BuenosAires  = GeoLocation(Longitude(-58.3816), Latitude(-34.6037), "Buenos Aires")
    val _RioDeJaneiro = GeoLocation(Longitude(-43.1729), Latitude(-22.9068), "Rio de Janeiro")
    val _Montevideo   = GeoLocation(Longitude(-56.164532), Latitude(-34.901112), "Montevideo")
    val _Tokyo        = GeoLocation(Longitude(139.6917), Latitude(35.6895), "Tokyo")

    val testKey = "location"
    for {
      _ <- redis.geoAdd(testKey, _BuenosAires)
      _ <- redis.geoAdd(testKey, _RioDeJaneiro)
      _ <- redis.geoAdd(testKey, _Montevideo)
      _ <- redis.geoAdd(testKey, _Tokyo)
      x <- redis.geoDist(testKey, _BuenosAires.value, _Tokyo.value, GeoArgs.Unit.km)
      _ <- IO(assertEquals(x, 18374.9052))
      y <- redis.geoPos(testKey, _RioDeJaneiro.value)
      _ <- IO(assert(y.contains(GeoCoordinate(-43.17289799451828, -22.906801071586663))))
      z <- redis.geoRadius(testKey, GeoRadius(_Montevideo.lon, _Montevideo.lat, Distance(10000.0)), GeoArgs.Unit.km)
      _ <- IO(assert(z.toList.containsSlice(List(_BuenosAires.value, _Montevideo.value, _RioDeJaneiro.value))))
    } yield ()
  }

  def hashesScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey    = "foo"
    val testField  = "bar"
    val testField2 = "baz"
    for {
      x <- redis.hGet(testKey, testField)
      _ <- IO(assert(x.isEmpty))
      isSet1 <- redis.hSetNx(testKey, testField, "some value")
      _ <- IO(assert(isSet1))
      y <- redis.hGet(testKey, testField)
      _ <- IO(assert(y.contains("some value")))
      isSet2 <- redis.hSetNx(testKey, testField, "should not happen")
      _ <- IO(assert(!isSet2))
      w <- redis.hGet(testKey, testField)
      _ <- IO(assert(w.contains("some value")))
      w <- redis.hmGet(testKey, testField, testField2)
      _ <- IO(assertEquals(w, Map(testField -> "some value")))
      w <- redis.hmGet(testKey, testField)
      _ <- IO(assertEquals(w, Map(testField -> "some value")))
      d <- redis.hDel(testKey, testField)
      _ <- IO(assertEquals(d, 1L))
      z <- redis.hGet(testKey, testField)
      _ <- IO(assert(z.isEmpty))
      _ <- redis.hSet(testKey, Map(testField -> "some value", testField2 -> "another value"))
      v <- redis.hGet(testKey, testField)
      _ <- IO(assert(v.contains("some value")))
      v <- redis.hGet(testKey, testField2)
      _ <- IO(assert(v.contains("another value")))
    } yield ()
  }

  def listsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey = "listos"
    for {
      first1 <- redis.blPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(first1.isEmpty))
      last1 <- redis.brPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(last1.isEmpty))
      pLength1 <- redis.rPush(testKey, "one", "two")
      _ <- IO(assert(pLength1 === 2))
      last2 <- redis.brPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(last2.contains((testKey, "two"))))
      first2 <- redis.blPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(first2.contains((testKey, "one"))))
      t <- redis.lRange(testKey, 0, 10)
      _ <- IO(assert(t.isEmpty))
      pLength2 <- redis.rPush(testKey, "one", "two", "three")
      _ <- IO(assert(pLength2 === 3))
      x <- redis.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(x, List("one", "two", "three")))
      y <- redis.lLen(testKey)
      _ <- IO(assert(y == 3L))
      a <- redis.lPop(testKey)
      _ <- IO(assert(a.contains("one")))
      b <- redis.rPop(testKey)
      _ <- IO(assert(b.contains("three")))
      z <- redis.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(z, List("two")))
      c <- redis.lInsertAfter(testKey, "two", "three")
      _ <- IO(assertEquals(c, 2L))
      d <- redis.lInsertBefore(testKey, "n/a", "one")
      _ <- IO(assertEquals(d, -1L))
      e <- redis.lInsertBefore(testKey, "two", "one")
      _ <- IO(assertEquals(e, 3L))
      f <- redis.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(f, List("one", "two", "three")))
      g <- redis.lRem(testKey, 0, "one")
      _ <- IO(assertEquals(g, 1L))
      _ <- redis.lSet(testKey, 1, "four")
      _ <- redis.lTrim(testKey, 1, 2)
      h <- redis.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(h, List("four")))
    } yield ()
  }

  def setsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey = "foos"
    for {
      x <- redis.sMembers(testKey)
      _ <- IO(assert(x.isEmpty))
      a <- redis.sAdd(testKey, "set value")
      _ <- IO(assertEquals(a, 1L))
      b <- redis.sAdd(testKey, "set value")
      _ <- IO(assertEquals(b, 0L))
      y <- redis.sMembers(testKey)
      _ <- IO(assert(y.contains("set value")))
      o <- redis.sCard(testKey)
      _ <- IO(assertEquals(o, 1L))
      d <- redis.sRem("non-existing", "random")
      _ <- IO(assertEquals(d, 0L))
      w <- redis.sMembers(testKey)
      _ <- IO(assert(w.contains("set value")))
      d <- redis.sRem(testKey, "set value")
      _ <- IO(assertEquals(d, 1L))
      z <- redis.sMembers(testKey)
      _ <- IO(assert(z.isEmpty))
      t <- redis.sCard(testKey)
      _ <- IO(assertEquals(t, 0L))
      _ <- redis.sAdd(testKey, "value 1", "value 2")
      r <- redis.sMisMember(testKey, "value 1", "random", "value 2")
      _ <- IO(assertEquals(r, List(true, false, true)))
    } yield ()
  }

  def sortedSetsScenario(redis: RedisCommands[IO, String, Long]): IO[Unit] = {
    val testKey         = "{same_hash_slot}:zztop"
    val otherTestKey    = "{same_hash_slot}:sharp:dressed:man"
    val scoreWithValue1 = ScoreWithValue(Score(1), 1L)
    val scoreWithValue2 = ScoreWithValue(Score(3), 2L)
    val scoreWithValue3 = ScoreWithValue(Score(5), 3L)
    val timeout         = 1.second
    for {
      minPop1 <- redis.zPopMin(testKey, 1)
      _ <- IO(assert(minPop1.isEmpty))
      maxPop1 <- redis.zPopMax(testKey, 1)
      _ <- IO(assert(maxPop1.isEmpty))
      minBPop1 <- redis.bzPopMin(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(minBPop1.isEmpty))
      maxBPop1 <- redis.bzPopMax(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(maxBPop1.isEmpty))
      t <- redis.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
      _ <- IO(assert(t.isEmpty))
      add2 <- redis.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      _ <- IO(assertEquals(add2, 2L))
      minPop2 <- redis.zPopMin(testKey, 1)
      _ <- IO(assertEquals(minPop2, List(scoreWithValue1)))
      maxPop2 <- redis.zPopMax(testKey, 1)
      _ <- IO(assertEquals(maxPop2, List(scoreWithValue2)))
      _ <- redis.zCard(testKey).map(card => assert(card == 0L))
      _ <- redis.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      minBPop2 <- redis.bzPopMin(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(minBPop2.contains((testKey, scoreWithValue1))))
      maxBPop2 <- redis.bzPopMax(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(maxBPop2.contains((testKey, scoreWithValue2))))
      _ <- redis.zCard(testKey).map(card => assert(card == 0L))
      _ <- redis.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      x <- redis.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
      _ <- IO(assertEquals(x, List(1L)))
      y <- redis.zCard(testKey)
      _ <- IO(assert(y == 2L))
      z <- redis.zCount(testKey, ZRange(0, 1))
      _ <- IO(assert(z == 1L))
      _ <- redis.zAdd(otherTestKey, args = None, scoreWithValue1, scoreWithValue3)
      zUnion <- redis.zUnion(args = None, testKey, otherTestKey)
      _ <- IO(assertEquals(zUnion, List(1L, 2L, 3L)))
      aggregateArgs = ZAggregateArgs.Builder.sum().weights(10L, 20L)
      zUnionWithScoreAndArgs <- redis.zUnionWithScores(Some(aggregateArgs), testKey, otherTestKey)
      _ <- IO(
            assertEquals(
              zUnionWithScoreAndArgs,
              // scores for each element: 1 -> 10*1 + 20*1; 2 -> 10*3; 3 -> 20*5
              List(ScoreWithValue(Score(30), 1L), ScoreWithValue(Score(30), 2L), ScoreWithValue(Score(100), 3L))
            )
          )
      zInter <- redis.zInter(args = None, testKey, otherTestKey)
      _ <- IO(assertEquals(zInter, List(1L)))
      zDiff <- redis.zDiff(testKey, otherTestKey)
      _ <- IO(assertEquals(zDiff, List(2L)))
      r <- redis.zRemRangeByScore(testKey, ZRange(1, 3))
      _ <- IO(assertEquals(r, 2L))
    } yield ()
  }

  def keysScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1    = "key1"
    val key2    = "key2"
    val keyCopy = "{key1}Copy"
    for {
      x <- redis.get(key1)
      _ <- IO(assertEquals(x, None))
      exist1 <- redis.exists(key1)
      _ <- IO(assert(!exist1))
      idletime1 <- redis.objectIdletime(key1)
      _ <- IO(assert(idletime1.isEmpty))
      _ <- redis.set(key1, "some value")
      exist2 <- redis.exists(key1)
      _ <- IO(assert(exist2))
      rkey <- redis.randomKey
      _ <- IO(assert(rkey.forall(_ == key1)))
      dump <- redis.dump(key1)
      _ <- IO(assert(dump.nonEmpty))
      _ <- redis.restore(key1, dump.get, RestoreArgs().replace(true))
      restored <- redis.get(key1)
      _ <- IO(assertEquals(restored, Some("some value")))
      copy <- redis.copy(key1, keyCopy)
      _ <- IO(assertEquals(copy, true))
      _ <- redis.get(keyCopy).map(value => assert(value.contains("some value")))
      _ <- redis.del(keyCopy)
      idletime2 <- redis.objectIdletime(key1)
      _ <- IO(assert(idletime2.isDefined))
      _ <- redis.mSet(Map(key2 -> "some value 2"))
      exist3 <- redis.exists(key1, key2)
      _ <- IO(assert(exist3))
      exist4 <- redis.exists(key1, key2, "_not_existing_key_")
      _ <- IO(assert(!exist4))
      g <- redis.del(key1)
      _ <- IO(assertEquals(g, 1L))
      exist5 <- redis.exists(key1)
      _ <- IO(assert(!exist5))
      a <- redis.ttl("whatever+")
      _ <- IO(assert(a.isEmpty))
      b <- redis.pttl("whatever+")
      _ <- IO(assert(b.isEmpty))
      _ <- redis.set("f1", "bar")
      h <- redis.expire("f1", 10.seconds)
      _ <- IO(assertEquals(h, true))
      c <- redis.ttl("f1")
      _ <- IO(assert(c.nonEmpty))
      persisted <- redis.persist("f1")
      _ <- IO(assert(persisted))
      noTTL <- redis.ttl("f1")
      _ <- IO(assert(noTTL.isEmpty))
      //reset
      _ <- redis.expire("f1", 10.seconds)
      d <- redis.pttl("f1")
      _ <- IO(assert(d.nonEmpty))
      _ <- IO(assert(d.exists(_ <= 10.seconds)))
      _ <- redis.set("f2", "yay")
      i <- redis.expire("f2", 50.millis)
      _ <- IO(assertEquals(i, true))
      e <- redis.ttl("f2")
      _ <- IO(assert(e.nonEmpty))
      _ <- IO.sleep(50.millis)
      f <- redis.ttl("f2")
      _ <- IO(assertEquals(f, None))
      _ <- redis.set("f3", "yay")
      expiref3 <- redis.expire("f3", 50.millis)
      _ <- IO(assertEquals(expiref3, true))
      expiref3nx <- redis.expire("f3", 10.seconds, ExpireExistenceArg.Nx)
      _ <- IO(assertEquals(expiref3nx, false))
      ttlf3 <- redis.ttl("f3")
      _ <- IO(assert(ttlf3.nonEmpty))
      _ <- IO.sleep(50.millis)
      ttlf3AfterSleep <- redis.ttl("f3")
      _ <- IO(assert(ttlf3AfterSleep.isEmpty))
      j <- redis.expire("_not_existing_key_", 50.millis)
      _ <- IO(assertEquals(j, false))
      _ <- redis.del("f1")
      k <- redis.set("k", "", SetArgs(SetArg.Ttl.Ex(10.seconds)))
      _ <- IO(assertEquals(k, true))
      kTtl <- redis.ttl("k")
      _ <- IO(assert(kTtl.nonEmpty))
      _ <- redis.set("k", "v", SetArgs(SetArg.Ttl.Keep))
      kv <- redis.get("k")
      _ <- IO(assertEquals(kv, Some("v")))
      kTtl2 <- redis.ttl("k")
      _ <- IO(assert(kTtl2.nonEmpty))
      _ <- redis.unlink("k")
      tpe <- redis.typeOf("k")
      _ <- IO(assertEquals(tpe, None))
      _ <- redis.set("aV", "v")
      tpe2 <- redis.typeOf("aV")
      _ <- IO(assertEquals(tpe2, Some(RedisType.String)))
      _ <- redis.setBit("bits", 0, 1)
      bitSet <- redis.typeOf("bits")
      _ <- IO(assertEquals(bitSet, Some(RedisType.String)))
      _ <- redis.lPush("list", "v", "u")
      list <- redis.typeOf("list")
      _ <- IO(assertEquals(list, Some(RedisType.List)))
      // geospatial
      _ <- redis.geoAdd("geo", GeoLocation(Longitude(13.361389), Latitude(38.115556), "Palermo"))
      geo <- redis.typeOf("geo")
      _ <- IO(assertEquals(geo, Some(RedisType.SortedSet)))
      _ <- redis.flushAll
    } yield ()
  }

  def scanScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val keys = (1 until 10).map("key" + _).sorted.toList
    for {
      _ <- redis.mSet(keys.map(key => (key, key + "#value")).toMap)
      scan0 <- redis.scan
      _ <- IO(assertEquals(scan0.cursor, "0"))
      _ <- IO(assertEquals(scan0.keys.sorted, keys))
      scan00 <- redis.scan(KeyScanArgs(RedisType.Hash))
      _ <- IO(assertEquals(scan00.cursor, "0"))
      scan1 <- redis.scan(KeyScanArgs(RedisType.String, 1))
      _ <- IO(assert(scan1.keys.nonEmpty, "read at least something but no hard requirement"))
      _ <- IO(assert(scan1.keys.size < keys.size, "but read less than all of them"))
      scan2 <- redis.scan(scan1, KeyScanArgs("key*"))
      _ <- IO(assertEquals(scan2.cursor, "0"))
      _ <- IO(assertEquals((scan1.keys ++ scan2.keys).sorted, keys, "read to the end in result"))
    } yield ()
  }

  def clusterScanScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val keys = (1 to 10).map("key" + _).sorted.toList
    for {
      _ <- redis.mSet(keys.map(key => (key, key + "#value")).toMap)
      tp <- clusterScan(redis, args = None)
      (keys0, iterations0) = tp
      _ <- IO(assertEquals(keys0.sorted, keys))
      tp <- clusterScan(redis, args = Some(KeyScanArgs("key*")))
      (keys1, iterations1) = tp
      _ <- IO(assertEquals(keys1.sorted, keys))
      _ <- IO(assertEquals(iterations1, iterations0))
      tp <- clusterScan(redis, args = Some(KeyScanArgs(1)))
      (keys2, iterations2) = tp
      _ <- IO(assertEquals(keys2.sorted, keys))
      _ <- IO(assert(iterations2 > iterations0, "made more iterations because of limit"))
    } yield ()
  }

  type Iterations = Int

  /**
    * Does scan on all cluster nodes until all keys collected since order of scanned nodes can't be guaranteed
    */
  private def clusterScan(
      redis: RedisCommands[IO, String, String],
      args: Option[KeyScanArgs]
  ): IO[(List[String], Iterations)] = {
    def scanRec(previous: KeyScanCursor[String], acc: List[String], cnt: Int): IO[(List[String], Iterations)] =
      if (previous.isFinished) IO.pure((previous.keys ++ acc, cnt))
      else
        args.fold(redis.scan(previous))(redis.scan(previous, _)).flatMap {
          scanRec(_, previous.keys ++ acc, cnt + 1)
        }

    args.fold(redis.scan)(redis.scan).flatMap(scanRec(_, List.empty, 0))
  }

  def bitmapsScenario(redis: BitCommands[IO, String, String]): IO[Unit] = {
    val key       = "foo"
    val secondKey = "bar"
    val thirdKey  = "baz"
    for {
      _ <- redis.setBit(key, 0, 1)
      oneBit <- redis.getBit(key, 0)
      _ <- IO(assertEquals(oneBit, Some(1.toLong)))
      _ <- redis.setBit(key, 1, 1)
      bitLen <- redis.bitCount(key)
      _ <- IO(assertEquals(bitLen, 2.toLong))
      bitLen2 <- redis.bitCount(key, 0, 2)
      _ <- IO(assertEquals(bitLen2, 2.toLong))
      _ <- redis.setBit(key, 0, 1)
      _ <- redis.setBit(secondKey, 0, 1)
      _ <- redis.bitOpAnd(thirdKey, key, secondKey)
      r <- redis.getBit(thirdKey, 0)
      _ <- IO(assertEquals(r, Some(1.toLong)))
      _ <- redis.bitOpNot(thirdKey, key)
      r2 <- redis.getBit(thirdKey, 0)
      _ <- IO(assertEquals(r2, Some(0.toLong)))
      _ <- redis.bitOpOr(thirdKey, key, secondKey)
      r3 <- redis.getBit(thirdKey, 0)
      _ <- IO(assertEquals(r3, Some(1.toLong)))
      _ <- for {
            s1 <- redis.setBit(key, 2, 1)
            s2 <- redis.setBit(key, 3, 1)
            s3 <- redis.setBit(key, 5, 1)
            s4 <- redis.setBit(key, 10, 1)
            s5 <- redis.setBit(key, 11, 1)
            s6 <- redis.setBit(key, 14, 1)
          } yield s1 + s2 + s3 + s4 + s5 + s6
      k <- redis.getBit(key, 2)
      _ <- IO(assertEquals(k, Some(1.toLong)))
      _ <- redis.bitField(
            secondKey,
            SetUnsigned(2, 1),
            SetUnsigned(3, 1),
            SetUnsigned(5, 1),
            SetUnsigned(10, 1),
            SetUnsigned(11, 1),
            IncrUnsignedBy(14, 1)
          )
      bits <- 0.to(14).toList.traverse(offset => redis.getBit(secondKey, offset.toLong))
      number <- IO.pure(Integer.parseInt(bits.map(_.getOrElse(0L).toString).foldLeft("")(_ + _), 2))
      _ <- IO(assertEquals(number, 23065))
      pos <- redis.bitPos(key, state = false)
      _ <- IO(assertEquals(pos, 4.toLong))
    } yield ()
  }

  def stringsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val key = "test"
    for {
      x <- redis.get(key)
      _ <- IO(assert(x.isEmpty))
      isSet1 <- redis.setNx(key, "some value")
      _ <- IO(assert(isSet1))
      y <- redis.get(key)
      _ <- IO(assert(y.contains("some value")))
      isSet2 <- redis.setNx(key, "should not happen")
      _ <- IO(assert(!isSet2))
      isSet3 <- redis.mSetNx(Map("multikey1" -> "someVal1", "multikey2" -> "someVal2"))
      _ <- IO(assert(isSet3))
      isSet4 <- redis.mSetNx(Map("multikey1" -> "someVal0", "multikey3" -> "someVal3"))
      _ <- IO(assert(!isSet4))
      val1 <- redis.get("multikey1")
      _ <- IO(assert(val1.contains("someVal1")))
      val3 <- redis.get("multikey3")
      _ <- IO(assert(val3.isEmpty))
      isSet5 <- redis.mSetNx(Map("multikey1" -> "someVal1", "multikey2" -> "someVal2"))
      _ <- IO(assert(!isSet5))
      w <- redis.get(key)
      _ <- IO(assert(w.contains("some value")))
      isSet6 <- redis.set(key, "some value", SetArgs(SetArg.Existence.Nx))
      _ <- IO(assert(!isSet6))
      isSet7 <- redis.set(key, "some value 2", SetArgs(SetArg.Existence.Xx))
      _ <- IO(assert(isSet7))
      val4 <- redis.get(key)
      _ <- IO(assert(val4.contains("some value 2")))
      _ <- redis.del(key)
      isSet8 <- redis.set(key, "some value", SetArgs(SetArg.Existence.Xx))
      _ <- IO(assert(!isSet8))
      isSet9 <- redis.set(key, "some value", SetArgs(SetArg.Existence.Nx))
      _ <- IO(assert(isSet9))
      val5 <- redis.get(key)
      _ <- IO(assert(val5.contains("some value")))
      isSet10 <- redis.set(key, "some value 2", SetArgs(None, None))
      _ <- IO(assert(isSet10))
      val6 <- redis.get(key)
      _ <- IO(assert(val6.contains("some value 2")))
      _ <- redis.del(key)
      z <- redis.get(key)
      _ <- IO(assert(z.isEmpty))
      isSet11 <- redis.set("keyToExpire", "value", SetArgs(SetArg.Existence.Nx))
      _ <- IO(assert(isSet11))
      kttl1 <- redis.ttl("keyToExpire")
      _ <- IO(assert(kttl1.isEmpty))
      val7 <- redis.getEx("keyToExpire", GetExArg.Ex(10.seconds))
      _ <- IO(assert(val7.contains("value")))
      kttl2 <- redis.ttl("keyToExpire")
      _ <- IO(kttl2.nonEmpty)
      _ <- redis.getEx("keyToExpire", GetExArg.Persist)
      kttl3 <- redis.ttl("keyToExpire")
      _ <- IO(kttl3.isEmpty)
      _ <- redis.getEx("keyToExpire", GetExArg.ExAt(Instant.now().plusSeconds(10)))
      kttl4 <- redis.ttl("keyToExpire")
      _ <- IO(kttl4.nonEmpty)
      _ <- redis.del("keyToExpire")
    } yield ()
  }

  def stringsClusterScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val key = "test"
    for {
      x <- redis.get(key)
      _ <- IO(assert(x.isEmpty))
      isSet1 <- redis.setNx(key, "some value")
      _ <- IO(assert(isSet1))
      y <- redis.get(key)
      _ <- IO(assert(y.contains("some value")))
      isSet2 <- redis.setNx(key, "should not happen")
      _ <- IO(assert(!isSet2))
      w <- redis.get(key)
      _ <- IO(assert(w.contains("some value")))
      _ <- redis.del(key)
      z <- redis.get(key)
      _ <- IO(assert(z.isEmpty))
    } yield ()
  }

  def connectionScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val clientName = "hello_world"
    for {
      pong <- redis.ping
      _ <- IO(assertEquals(pong, "PONG"))
      oldClientName <- redis.getClientName()
      _ <- IO(assertEquals(oldClientName, None))
      res <- redis.setClientName(clientName)
      _ <- IO(assert(res, s"Failed to set client name: '$clientName'"))
      newClientName <- redis.getClientName()
      _ <- IO(assertEquals(newClientName, Some(clientName)))
      _ <- redis.getClientId()
      info <- redis.getClientInfo
      _ <- IO(assert(info.nonEmpty))
      success <- redis.setLibName("redis4cats")
      _ <- IO(assert(success))
      success <- redis.setLibVersion("0.10.0")
      _ <- IO(assert(success))
      info <- redis.getClientInfo
      _ <- IO(assert(info.get("lib-name").contains("redis4cats")))
      _ <- IO(assert(info.get("lib-ver").contains("0.10.0")))
    } yield ()
  }

  def serverScenario(redis: RedisCommands[IO, String, String]): IO[Unit] =
    for {
      _ <- redis.mSet(Map("firstname" -> "Jack", "lastname" -> "Stuntman", "age" -> "35"))
      names <- redis.keys("*name*").map(_.toSet)
      _ <- IO(assertEquals(names, Set("firstname", "lastname")))
      age <- redis.keys("a??")
      _ <- IO(assertEquals(age, List("age")))
      info <- redis.info
      _ <- IO(assert(info.contains("role")))
      dbsize <- redis.dbsize
      _ <- IO(assert(dbsize > 0))
      lastSave <- redis.lastSave
      _ <- IO(assert(lastSave.isBefore(Instant.now)))
      slowLogLen <- redis.slowLogLen
      _ <- IO(assert(slowLogLen.isValidLong))
    } yield ()

  def pipelineScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "testp1"
    val key2 = "testp2"
    val key3 = "testp3"

    val ops = (store: TxStore[IO, String, Option[String]]) =>
      List(
        redis.set(key1, "osx"),
        redis.get(key3).flatMap(store.set(key3)),
        redis.set(key2, "linux")
      )

    val runPipeline =
      redis
        .pipeline(ops)
        .map(kv => assertEquals(kv.get(key3).flatten, Some("3")))
        .recoverWith {
          case e => fail(s"[Error] - ${e.getMessage}")
        }

    for {
      _ <- redis.set(key3, "3")
      _ <- runPipeline
      v1 <- redis.get(key1)
      v2 <- redis.get(key2)
    } yield {
      assertEquals(v1, Some("osx"))
      assertEquals(v2, Some("linux"))
    }
  }

  def transactionScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "txtest1"
    val val1 = "osx"
    val key2 = "txtest2"
    val val2 = "windows"
    val key3 = "txtest3"
    val val3 = "linux"
    val del1 = "deleteme"

    val ops = (store: TxStore[IO, String, Option[String]]) =>
      List(
        redis.set(key2, val2),
        redis.get(key1).flatMap(store.set(s"$key1-v1")),
        redis.set(key3, val3),
        redis.del(del1).flatMap(x => store.set(s"$key1-v2")(Some(x.toString)))
      )

    redis.set(del1, "foo") >> redis.set(key1, val1) >>
      redis
        .transact(ops)
        .map { kv =>
          assertEquals(kv.get(s"$key1-v1").flatten, Some(val1))
          assertEquals(kv.get(s"$key1-v2").flatten, Some(1L.toString))
        }
        .flatMap { _ =>
          (redis.get(key2), redis.get(key3)).mapN {
            case (x, y) =>
              assertEquals(x, Some(val2))
              assertEquals(y, Some(val3))
          }
        }
  }

  def scriptsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val statusScript =
      """
        |redis.call('set',KEYS[1],ARGV[1])
        |redis.call('del',KEYS[1])
        |return redis.status_reply('OK')""".stripMargin
    for {
      fortyTwo <- redis.eval("return 42", ScriptOutputType.Integer)
      _ <- IO(assertEquals(fortyTwo, 42L))
      value <- redis.eval("return 'Hello World'", ScriptOutputType.Value)
      _ <- IO(assertEquals(value, "Hello World"))
      bool <- redis.eval("return true", ScriptOutputType.Boolean, List("Foo"))
      _ <- IO(assert(bool))
      list <- redis.eval(
               "return {'Let', 'us', ARGV[1], ARGV[2]}",
               ScriptOutputType.Multi,
               Nil,
               List(
                 "have",
                 "fun"
               )
             )
      _ <- IO(assertEquals(list, List("Let", "us", "have", "fun")))
      boolReadOnly <- redis.evalReadOnly("return true", ScriptOutputType.Boolean, List("Foo"))
      _ <- IO(assert(boolReadOnly))
      _ <- redis.eval(statusScript, ScriptOutputType.Status, List("test"), List("foo"))
      either <- redis.evalReadOnly(statusScript, ScriptOutputType.Status, List("test"), List("foo")).attempt
      _ <- IO(
            assert(
              either.left.exists { ex =>
                ex.isInstanceOf[RedisCommandExecutionException] &&
                ex.getMessage.startsWith("ERR Write commands are not allowed from read-only scripts")
              }
            )
          )
      sha42 <- redis.scriptLoad("return 42")
      fortyTwoSha <- redis.evalSha(sha42, ScriptOutputType.Integer)
      _ <- IO(assertEquals(fortyTwoSha, 42L))
      fortyTwoShaReadOnly <- redis.evalShaReadOnly(sha42, ScriptOutputType.Integer)
      _ <- IO(assertEquals(fortyTwoShaReadOnly, 42L))
      shaStatusScript <- redis.scriptLoad(statusScript)
      _ <- redis.evalSha(shaStatusScript, ScriptOutputType.Status, List("test"), List("foo", "bar"))
      exists <- redis.scriptExists(sha42, "foobar")
      _ <- IO(assertEquals(exists, List(true, false)))
      shaStatusDigest <- redis.digest(statusScript)
      _ <- IO(assertEquals(shaStatusScript, shaStatusDigest))
      _ <- redis.scriptFlush
      exists2 <- redis.scriptExists(sha42)
      _ <- IO(assertEquals(exists2, List(false)))
    } yield ()
  }

  def functionsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val myFunc =
      """#!lua name=mylib
        | redis.register_function('myfunc', function(keys, args) return args[1] end)
        | """.stripMargin

    val myEcho =
      """#!lua name=mylib_2
        | local function my_echo(keys, args)
        |   return args[1]
        | end
        | redis.register_function{function_name='my_echo',callback=my_echo, flags={ 'no-writes' }}
        | """.stripMargin

    for {
      _ <- redis.functionFlush(FlushMode.Sync)
      _ <- redis.functionLoad(myFunc)
      _ <- redis.functionLoad(myFunc).recover({ case _: RedisCommandExecutionException => "" })
      _ <- redis.functionLoad(myFunc, replace = true)
      fcallResult <- redis.fcall("myfunc", ScriptOutputType.Status, List("key"), List("Hello"))
      _ <- IO(assertEquals(fcallResult, "Hello"))
      _ <- redis.functionFlush(FlushMode.Sync)
      _ <- redis.functionLoad(myEcho)
      fcallReadOnlyResult <- redis.fcallReadOnly("my_echo", ScriptOutputType.Status, List("key"), List("Hello"))
      _ <- IO(assertEquals(fcallReadOnlyResult, "Hello"))
      _ <- redis.functionFlush(FlushMode.Sync)
      _ <- redis.functionLoad(myFunc)
      dump <- redis.functionDump()
      _ <- redis.functionFlush(FlushMode.Sync)
      _ <- redis.functionRestore(dump)
      fcallRestoreResult <- redis.fcall("myfunc", ScriptOutputType.Status, List("key"), List("Hello"))
      _ <- IO(assertEquals(fcallRestoreResult, "Hello"))
      _ <- redis.functionFlush(FlushMode.Sync)
      listResult <- redis.functionList()
      _ = assertEquals(listResult.size, 0)
      _ <- redis.functionLoad(myFunc)
      _ <- redis.functionLoad(myEcho)
      listResult <- redis.functionList()
      _ = assertEquals(listResult.size, 2)
    } yield ()
  }

  def hyperloglogScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val key  = "hll"
    val key2 = "hll2"
    val key3 = "hll3"
    for {
      x <- redis.get(key)
      _ <- IO(assert(x.isEmpty))
      c1 <- redis.pfCount(key)
      _ <- IO(assertEquals(c1, 0L))
      _ <- redis.pfAdd(key, "a", "b", "c")
      c2 <- redis.pfCount(key)
      _ <- IO(assert(c2 > 0, "hyperloglog should think it has more than 0 items in"))
      _ <- redis.pfAdd(key2, "a", "b", "c")
      c3 <- redis.pfCount(key2)
      _ <- IO(assert(c3 > 0, "second hyperloglog should think it has more than 0 items in"))
      _ <- redis.pfMerge(key3, key2, key)
      c4 <- redis.pfCount(key3)
      _ <- IO(assert(c4 > 0, "merged hyperloglog should think it has more than 0 items in"))
    } yield ()
  }

  def keyPatternSubScenario(client: RedisClient): IO[Unit] = {
    import dev.profunktor.redis4cats.effect.Log.NoOp._

    val pattern = "__keyevent*__:*"
    val key     = "somekey"

    def resources(finalizer: Stream[IO, Boolean]) =
      for {
        commands <- Redis[IO].fromClient(client, RedisCodec.Utf8)
        gate <- Resource.eval(IO.deferred[RedisPatternEvent[String, String]])
        i = Stream.eval(gate.get.as(true))
        sub <- PubSub
                .mkSubscriberConnection[IO, String, String](client, RedisCodec.Utf8)
                .withFinalizer(finalizer.combine(i))
        stream <- Resource.pure(sub.psubscribe(RedisPattern(pattern)))
        s1 = stream
          .evalMap(gate.complete(_).void)
          .interruptWhen(i)
        s2 = Stream
          .eval(commands.setEx(key, "", 1.second))
          .meteredStartImmediately(2.seconds)
          .interruptWhen(i)
        _ <- Resource.eval(Stream(s1, s2).parJoin(2).compile.drain)
        fe <- Resource.eval(gate.get)
      } yield fe

    IO.deferred[Boolean].flatMap { finalizer =>
      resources(Stream.eval(finalizer.get))
        .use { result =>
          IO(
            assert(
              result == RedisPatternEvent(pattern, "__keyevent@0__:expired", key),
              s"Unexpected result $result"
            )
          ) <* finalizer.complete(true)
        }
        .recover { case _: RedisException => () } // forcing connection to close raises this exception
    }
  }

  def channelPatternSubScenario(client: RedisClient): IO[Unit] = {
    import dev.profunktor.redis4cats.effect.Log.NoOp._

    val pattern = "f*"
    val channel = "foo"
    val message = "somemessage"

    def resources(finalizer: Stream[IO, Boolean]) =
      for {
        gate <- Resource.eval(IO.deferred[RedisPatternEvent[String, String]])
        i = Stream.eval(gate.get.as(true))
        pubsub <- PubSub
                   .mkPubSubConnection[IO, String, String](client, RedisCodec.Utf8)
                   .withFinalizer(finalizer.combine(i))
        stream <- Resource.pure(pubsub.psubscribe(RedisPattern(pattern)))
        s1 = stream.evalMap(gate.complete(_)).interruptWhen(i)
        s2 = Stream
          .awakeEvery[IO](100.milli)
          .as(message)
          .through(pubsub.publish(RedisChannel(channel)))
          .recover { case _: RedisException => 0L }
          .interruptWhen(i)
        _ <- Resource.eval(Stream(s1, s2).parJoin(2).compile.drain)
        fe <- Resource.eval(gate.get)
      } yield fe

    IO.deferred[Boolean].flatMap { finalizer =>
      resources(Stream.eval(finalizer.get))
        .use { result =>
          IO(
            assert(
              result == RedisPatternEvent(pattern, channel, message),
              s"Unexpected result $result"
            )
          ) <* finalizer.complete(true)
        }
        .recover { case _: RedisException => () } // forcing connection to close raises this exception
    }
  }

}
