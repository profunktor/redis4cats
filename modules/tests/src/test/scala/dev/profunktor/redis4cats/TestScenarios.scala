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

import java.time.Instant
import java.util.concurrent.TimeoutException

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.data.KeyScanCursor
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.pipeline.{ PipelineError, RedisPipeline }
import dev.profunktor.redis4cats.transactions.RedisTransaction
import io.lettuce.core.GeoArgs
import munit.FunSuite

import scala.concurrent.duration._

trait TestScenarios { self: FunSuite =>

  implicit def cs: ContextShift[IO]
  implicit def timer: Timer[IO]

  def locationScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val _BuenosAires  = GeoLocation(Longitude(-58.3816), Latitude(-34.6037), "Buenos Aires")
    val _RioDeJaneiro = GeoLocation(Longitude(-43.1729), Latitude(-22.9068), "Rio de Janeiro")
    val _Montevideo   = GeoLocation(Longitude(-56.164532), Latitude(-34.901112), "Montevideo")
    val _Tokyo        = GeoLocation(Longitude(139.6917), Latitude(35.6895), "Tokyo")

    val testKey = "location"
    for {
      _ <- cmd.geoAdd(testKey, _BuenosAires)
      _ <- cmd.geoAdd(testKey, _RioDeJaneiro)
      _ <- cmd.geoAdd(testKey, _Montevideo)
      _ <- cmd.geoAdd(testKey, _Tokyo)
      x <- cmd.geoDist(testKey, _BuenosAires.value, _Tokyo.value, GeoArgs.Unit.km)
      _ <- IO(assertEquals(x, 18374.9052))
      y <- cmd.geoPos(testKey, _RioDeJaneiro.value)
      _ <- IO(assert(y.contains(GeoCoordinate(-43.17289799451828, -22.906801071586663))))
      z <- cmd.geoRadius(testKey, GeoRadius(_Montevideo.lon, _Montevideo.lat, Distance(10000.0)), GeoArgs.Unit.km)
      _ <- IO(assert(z.toList.containsSlice(List(_BuenosAires.value, _Montevideo.value, _RioDeJaneiro.value))))
    } yield ()
  }

  def hashesScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey    = "foo"
    val testField  = "bar"
    val testField2 = "baz"
    for {
      x <- cmd.hGet(testKey, testField)
      _ <- IO(assert(x.isEmpty))
      isSet1 <- cmd.hSetNx(testKey, testField, "some value")
      _ <- IO(assert(isSet1))
      y <- cmd.hGet(testKey, testField)
      _ <- IO(assert(y.contains("some value")))
      isSet2 <- cmd.hSetNx(testKey, testField, "should not happen")
      _ <- IO(assert(!isSet2))
      w <- cmd.hGet(testKey, testField)
      _ <- IO(assert(w.contains("some value")))
      w <- cmd.hmGet(testKey, testField, testField2)
      _ <- IO(assertEquals(w, Map(testField -> "some value")))
      d <- cmd.hDel(testKey, testField)
      _ <- IO(assertEquals(d, 1L))
      z <- cmd.hGet(testKey, testField)
      _ <- IO(assert(z.isEmpty))
    } yield ()
  }

  def listsScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey = "listos"
    for {
      first1 <- cmd.blPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(first1.isEmpty))
      last1 <- cmd.brPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(last1.isEmpty))
      pLength1 <- cmd.rPush(testKey, "one", "two")
      _ <- IO(assert(pLength1 === 2))
      last2 <- cmd.brPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(last2.contains((testKey, "two"))))
      first2 <- cmd.blPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(first2.contains((testKey, "one"))))
      t <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assert(t.isEmpty))
      pLength2 <- cmd.rPush(testKey, "one", "two", "three")
      _ <- IO(assert(pLength2 === 3))
      x <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(x, List("one", "two", "three")))
      y <- cmd.lLen(testKey)
      _ <- IO(assert(y.contains(3)))
      a <- cmd.lPop(testKey)
      _ <- IO(assert(a.contains("one")))
      b <- cmd.rPop(testKey)
      _ <- IO(assert(b.contains("three")))
      z <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(z, List("two")))
      c <- cmd.lInsertAfter(testKey, "two", "three")
      _ <- IO(assertEquals(c, 2L))
      d <- cmd.lInsertBefore(testKey, "n/a", "one")
      _ <- IO(assertEquals(d, -1L))
      e <- cmd.lInsertBefore(testKey, "two", "one")
      _ <- IO(assertEquals(e, 3L))
      f <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(f, List("one", "two", "three")))
      g <- cmd.lRem(testKey, 0, "one")
      _ <- IO(assertEquals(g, 1L))
      _ <- cmd.lSet(testKey, 1, "four")
      _ <- cmd.lTrim(testKey, 1, 2)
      h <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assertEquals(h, List("four")))
    } yield ()
  }

  def setsScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey = "foos"
    for {
      x <- cmd.sMembers(testKey)
      _ <- IO(assert(x.isEmpty))
      a <- cmd.sAdd(testKey, "set value")
      _ <- IO(assertEquals(a, 1L))
      b <- cmd.sAdd(testKey, "set value")
      _ <- IO(assertEquals(b, 0L))
      y <- cmd.sMembers(testKey)
      _ <- IO(assert(y.contains("set value")))
      o <- cmd.sCard(testKey)
      _ <- IO(assertEquals(o, 1L))
      _ <- cmd.sRem("non-existing", "random")
      w <- cmd.sMembers(testKey)
      _ <- IO(assert(w.contains("set value")))
      _ <- cmd.sRem(testKey, "set value")
      z <- cmd.sMembers(testKey)
      _ <- IO(assert(z.isEmpty))
      t <- cmd.sCard(testKey)
      _ <- IO(assertEquals(t, 0L))
    } yield ()
  }

  def sortedSetsScenario(cmd: RedisCommands[IO, String, Long]): IO[Unit] = {
    val testKey         = "zztop"
    val scoreWithValue1 = ScoreWithValue(Score(1), 1L)
    val scoreWithValue2 = ScoreWithValue(Score(3), 2L)
    val timeout         = 1.second
    for {
      minPop1 <- cmd.zPopMin(testKey, 1)
      _ <- IO(assert(minPop1.isEmpty))
      maxPop1 <- cmd.zPopMax(testKey, 1)
      _ <- IO(assert(maxPop1.isEmpty))
      minBPop1 <- cmd.bzPopMin(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(minBPop1.isEmpty))
      maxBPop1 <- cmd.bzPopMax(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(maxBPop1.isEmpty))
      t <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
      _ <- IO(assert(t.isEmpty))
      add2 <- cmd.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      _ <- IO(assertEquals(add2, 2L))
      minPop2 <- cmd.zPopMin(testKey, 1)
      _ <- IO(assertEquals(minPop2, List(scoreWithValue1)))
      maxPop2 <- cmd.zPopMax(testKey, 1)
      _ <- IO(assertEquals(maxPop2, List(scoreWithValue2)))
      _ <- cmd.zCard(testKey).map(card => assert(card.contains(0)))
      _ <- cmd.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      minBPop2 <- cmd.bzPopMin(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(minBPop2.contains((testKey, scoreWithValue1))))
      maxBPop2 <- cmd.bzPopMax(timeout, NonEmptyList.one(testKey))
      _ <- IO(assert(maxBPop2.contains((testKey, scoreWithValue2))))
      _ <- cmd.zCard(testKey).map(card => assert(card.contains(0)))
      _ <- cmd.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      x <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
      _ <- IO(assertEquals(x, List(1L)))
      y <- cmd.zCard(testKey)
      _ <- IO(assert(y.contains(2)))
      z <- cmd.zCount(testKey, ZRange(0, 1))
      _ <- IO(assert(z.contains(1)))
      r <- cmd.zRemRangeByScore(testKey, ZRange(1, 3))
      _ <- IO(assertEquals(r, 2L))
    } yield ()
  }

  def keysScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "key1"
    val key2 = "key2"
    for {
      x <- cmd.get(key1)
      _ <- IO(assertEquals(x, None))
      exist1 <- cmd.exists(key1)
      _ <- IO(assert(!exist1))
      idletime1 <- cmd.objectIdletime(key1)
      _ <- IO(assert(idletime1.isEmpty))
      _ <- cmd.set(key1, "some value")
      exist2 <- cmd.exists(key1)
      _ <- IO(assert(exist2))
      idletime2 <- cmd.objectIdletime(key1)
      _ <- IO(assert(idletime2.isDefined))
      _ <- cmd.mSet(Map(key2 -> "some value 2"))
      exist3 <- cmd.exists(key1, key2)
      _ <- IO(assert(exist3))
      exist4 <- cmd.exists(key1, key2, "_not_existing_key_")
      _ <- IO(assert(!exist4))
      g <- cmd.del(key1)
      _ <- IO(assertEquals(g, 1L))
      exist5 <- cmd.exists(key1)
      _ <- IO(assert(!exist5))
      a <- cmd.ttl("whatever+")
      _ <- IO(assert(a.isEmpty))
      b <- cmd.pttl("whatever+")
      _ <- IO(assert(b.isEmpty))
      _ <- cmd.set("f1", "bar")
      h <- cmd.expire("f1", 10.seconds)
      _ <- IO(assertEquals(h, true))
      c <- cmd.ttl("f1")
      _ <- IO(assert(c.nonEmpty))
      d <- cmd.pttl("f1")
      _ <- IO(assert(d.nonEmpty))
      _ <- cmd.set("f2", "yay")
      i <- cmd.expire("f2", 50.millis)
      _ <- IO(assertEquals(i, true))
      e <- cmd.ttl("f2")
      _ <- IO(assert(e.nonEmpty))
      _ <- IO.sleep(50.millis)
      f <- cmd.ttl("f2")
      _ <- IO(assert(f.isEmpty))
      j <- cmd.expire("_not_existing_key_", 50.millis)
      _ <- IO(assertEquals(j, false))
      _ <- cmd.del("f1")
    } yield ()
  }

  def scanScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val keys = (1 until 10).map("key" + _).sorted.toList
    for {
      _ <- cmd.mSet(keys.map(key => (key, key + "#value")).toMap)
      scan0 <- cmd.scan
      _ <- IO(assertEquals(scan0.cursor, "0"))
      _ <- IO(assertEquals(scan0.keys.sorted, keys))
      scan1 <- cmd.scan(ScanArgs(1))
      _ <- IO(assert(scan1.keys.nonEmpty, "read at least something but no hard requirement"))
      _ <- IO(assert(scan1.keys.size < keys.size, "but read less than all of them"))
      scan2 <- cmd.scan(scan1, ScanArgs("key*"))
      _ <- IO(assertEquals(scan2.cursor, "0"))
      _ <- IO(assertEquals((scan1.keys ++ scan2.keys).sorted, keys, "read to the end in result"))
    } yield ()
  }

  def clusterScanScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val keys = (1 to 10).map("key" + _).sorted.toList
    for {
      _ <- cmd.mSet(keys.map(key => (key, key + "#value")).toMap)
      (keys0, iterations0) <- clusterScan(cmd, args = None)
      _ <- IO(assertEquals(keys0.sorted, keys))
      (keys1, iterations1) <- clusterScan(cmd, args = Some(ScanArgs("key*")))
      _ <- IO(assertEquals(keys1.sorted, keys))
      _ <- IO(assertEquals(iterations1, iterations0))
      (keys2, iterations2) <- clusterScan(cmd, args = Some(ScanArgs(1)))
      _ <- IO(assertEquals(keys2.sorted, keys))
      _ <- IO(assert(iterations2 > iterations0, "made more iterations because of limit"))
    } yield ()
  }

  type Iterations = Int

  /**
    * Does scan on all cluster nodes until all keys collected since order of scanned nodes can't be guaranteed
    */
  private def clusterScan(
      cmd: RedisCommands[IO, String, String],
      args: Option[ScanArgs]
  ): IO[(List[String], Iterations)] = {
    def scanRec(previous: KeyScanCursor[String], acc: List[String], cnt: Int): IO[(List[String], Iterations)] =
      if (previous.isFinished) IO.pure((previous.keys ++ acc, cnt))
      else
        args.fold(cmd.scan(previous))(cmd.scan(previous, _)).flatMap {
          scanRec(_, previous.keys ++ acc, cnt + 1)
        }

    args.fold(cmd.scan)(cmd.scan).flatMap(scanRec(_, List.empty, 0))
  }

  def stringsScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key = "test"
    for {
      x <- cmd.get(key)
      _ <- IO(assert(x.isEmpty))
      isSet1 <- cmd.setNx(key, "some value")
      _ <- IO(assert(isSet1))
      y <- cmd.get(key)
      _ <- IO(assert(y.contains("some value")))
      isSet2 <- cmd.setNx(key, "should not happen")
      _ <- IO(assert(!isSet2))
      isSet3 <- cmd.mSetNx(Map("multikey1" -> "someVal1", "multikey2" -> "someVal2"))
      _ <- IO(assert(isSet3))
      isSet4 <- cmd.mSetNx(Map("multikey1" -> "someVal0", "multikey3" -> "someVal3"))
      _ <- IO(assert(!isSet4))
      val1 <- cmd.get("multikey1")
      _ <- IO(assert(val1.contains("someVal1")))
      val3 <- cmd.get("multikey3")
      _ <- IO(assert(val3.isEmpty))
      isSet5 <- cmd.mSetNx(Map("multikey1" -> "someVal1", "multikey2" -> "someVal2"))
      _ <- IO(assert(!isSet5))
      w <- cmd.get(key)
      _ <- IO(assert(w.contains("some value")))
      isSet6 <- cmd.set(key, "some value", SetArgs(SetArg.Existence.Nx))
      _ <- IO(assert(!isSet6))
      isSet7 <- cmd.set(key, "some value 2", SetArgs(SetArg.Existence.Xx))
      _ <- IO(assert(isSet7))
      val4 <- cmd.get(key)
      _ <- IO(assert(val4.contains("some value 2")))
      _ <- cmd.del(key)
      isSet8 <- cmd.set(key, "some value", SetArgs(SetArg.Existence.Xx))
      _ <- IO(assert(!isSet8))
      isSet9 <- cmd.set(key, "some value", SetArgs(SetArg.Existence.Nx))
      _ <- IO(assert(isSet9))
      val5 <- cmd.get(key)
      _ <- IO(assert(val5.contains("some value")))
      isSet10 <- cmd.set(key, "some value 2", SetArgs(None, None))
      _ <- IO(assert(isSet10))
      val6 <- cmd.get(key)
      _ <- IO(assert(val6.contains("some value 2")))
      _ <- cmd.del(key)
      z <- cmd.get(key)
      _ <- IO(assert(z.isEmpty))
    } yield ()
  }

  def stringsClusterScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key = "test"
    for {
      x <- cmd.get(key)
      _ <- IO(assert(x.isEmpty))
      isSet1 <- cmd.setNx(key, "some value")
      _ <- IO(assert(isSet1))
      y <- cmd.get(key)
      _ <- IO(assert(y.contains("some value")))
      isSet2 <- cmd.setNx(key, "should not happen")
      _ <- IO(assert(!isSet2))
      w <- cmd.get(key)
      _ <- IO(assert(w.contains("some value")))
      _ <- cmd.del(key)
      z <- cmd.get(key)
      _ <- IO(assert(z.isEmpty))
    } yield ()
  }

  def connectionScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] =
    cmd.ping.flatMap(pong => IO(assertEquals(pong, "PONG"))).void

  def serverScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] =
    for {
      _ <- cmd.mSet(Map("firstname" -> "Jack", "lastname" -> "Stuntman", "age" -> "35"))
      names <- cmd.keys("*name*").map(_.toSet)
      _ <- IO(assertEquals(names, Set("firstname", "lastname")))
      age <- cmd.keys("a??")
      _ <- IO(assertEquals(age, List("age")))
      info <- cmd.info
      _ <- IO(assert(info.contains("role")))
      dbsize <- cmd.dbsize
      _ <- IO(assert(dbsize > 0))
      lastSave <- cmd.lastSave
      _ <- IO(assert(lastSave.isBefore(Instant.now)))
      slowLogLen <- cmd.slowLogLen
      _ <- IO(assert(slowLogLen.isValidLong))
    } yield ()

  def pipelineScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "testp1"
    val key2 = "testp2"
    val key3 = "testp3"

    val operations =
      cmd.set(key1, "osx") :: cmd.get(key3) :: cmd.set(key2, "linux") :: cmd.sIsMember("foo", "bar") :: HNil

    val runPipeline =
      RedisPipeline(cmd)
        .filterExec(operations)
        .map {
          case res1 ~: res2 ~: HNil =>
            assertEquals(res1, Some("3"))
            assert(!res2)
        }
        .onError {
          case PipelineError       => fail("[Error] - Pipeline failed")
          case _: TimeoutException => fail("[Error] - Timeout")
        }

    for {
      _ <- cmd.set(key3, "3")
      _ <- runPipeline
      v1 <- cmd.get(key1)
      v2 <- cmd.get(key2)
    } yield {
      assertEquals(v1, Some("osx"))
      assertEquals(v2, Some("linux"))
    }
  }

  def transactionScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "txtest1"
    val val1 = "osx"
    val key2 = "txtest2"
    val val2 = "windows"
    val key3 = "txtest3"
    val val3 = "linux"
    val del1 = "deleteme"

    val operations = cmd.set(key2, val2) :: cmd.get(key1) :: cmd.set(key3, val3) :: cmd.del(del1) :: HNil

    cmd.set(del1, "foo") >> cmd.set(key1, val1) >>
      RedisTransaction(cmd)
        .filterExec(operations)
        .map {
          case res1 ~: res2 ~: HNil =>
            assertEquals(res1, Some(val1))
            assertEquals(res2, 1L)
        }
        .flatMap { _ =>
          (cmd.get(key2), cmd.get(key3)).mapN {
            case (x, y) =>
              assertEquals(x, Some(val2))
              assertEquals(y, Some(val3))
          }
        }
  }

  def transactionDoubleSetScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key = "txtest-double-set"

    val operations = cmd.set(key, "osx") :: cmd.set(key, "nix") :: HNil

    for {
      _ <- RedisTransaction(cmd).exec(operations).void
      v <- cmd.get(key)
    } yield assert(v.contains("osx") || v.contains("nix"))
  }

  def canceledTransactionScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "tx-1"
    val key2 = "tx-2"
    val tx   = RedisTransaction(cmd)

    val commands = cmd.set(key1, "v1") :: cmd.set(key2, "v2") :: cmd.set("tx-3", "v3") :: HNil

    // We race it with a plain `IO.unit` so the transaction may or may not start at all but the result should be the same
    IO.race(tx.exec(commands), IO.unit) >> cmd.get(key1).map(assertEquals(_, None)) // no keys written
  }

  def scriptsScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val statusScript =
      """
        |redis.call('set',KEYS[1],ARGV[1])
        |redis.call('del',KEYS[1])
        |return redis.status_reply('OK')""".stripMargin
    for {
      fortyTwo: Long <- cmd.eval("return 42", ScriptOutputType.Integer)
      _ <- IO(assertEquals(fortyTwo, 42L))
      value: String <- cmd.eval("return 'Hello World'", ScriptOutputType.Value)
      _ <- IO(assertEquals(value, "Hello World"))
      bool: Boolean <- cmd.eval("return true", ScriptOutputType.Boolean, List("Foo"))
      _ <- IO(assert(bool))
      list: List[String] <- cmd.eval(
                             "return {'Let', 'us', ARGV[1], ARGV[2]}",
                             ScriptOutputType.Multi,
                             Nil,
                             List(
                               "have",
                               "fun"
                             )
                           )
      _ <- IO(assertEquals(list, List("Let", "us", "have", "fun")))
      _ <- cmd.eval(statusScript, ScriptOutputType.Status, List("test"), List("foo"))
      sha42 <- cmd.scriptLoad("return 42")
      fortyTwoSha: Long <- cmd.evalSha(sha42, ScriptOutputType.Integer)
      _ <- IO(assertEquals(fortyTwoSha, 42L))
      shaStatusScript <- cmd.scriptLoad(statusScript)
      _ <- cmd.evalSha(shaStatusScript, ScriptOutputType.Status, List("test"), List("foo", "bar"))
      exists <- cmd.scriptExists(sha42, "foobar")
      _ <- IO(assertEquals(exists, List(true, false)))
      shaStatusDigest <- cmd.digest(statusScript)
      _ <- IO(assertEquals(shaStatusScript, shaStatusDigest))
      _ <- cmd.scriptFlush
      exists2 <- cmd.scriptExists(sha42)
      _ <- IO(assertEquals(exists2, List(false)))
    } yield ()
  }

  def hyperloglogScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key  = "hll"
    val key2 = "hll2"
    val key3 = "hll3"
    for {
      x <- cmd.get(key)
      _ <- IO(assert(x.isEmpty))
      c1 <- cmd.pfCount(key)
      _ <- IO(assertEquals(c1, 0L))
      _ <- cmd.pfAdd(key, "a", "b", "c")
      c2 <- cmd.pfCount(key)
      _ <- IO(assert(c2 > 0, "hyperloglog should think it has more than 0 items in"))
      _ <- cmd.pfAdd(key2, "a", "b", "c")
      c3 <- cmd.pfCount(key2)
      _ <- IO(assert(c3 > 0, "second hyperloglog should think it has more than 0 items in"))
      _ <- cmd.pfMerge(key3, key2, key)
      c4 <- cmd.pfCount(key3)
      _ <- IO(assert(c4 > 0, "merged hyperloglog should think it has more than 0 items in"))
    } yield ()
  }
}
