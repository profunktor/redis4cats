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

import java.time.Instant

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import dev.profunktor.redis4cats.effect.Log.NoOp._
import dev.profunktor.redis4cats.effects._
import dev.profunktor.redis4cats.hlist._
import dev.profunktor.redis4cats.pipeline.RedisPipeline
import dev.profunktor.redis4cats.transactions.RedisTransaction
import io.lettuce.core.GeoArgs

import scala.concurrent.duration._

trait TestScenarios {

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
      _ <- IO(assert(x == 18374.9052))
      y <- cmd.geoPos(testKey, _RioDeJaneiro.value)
      _ <- IO(assert(y.contains(GeoCoordinate(-43.17289799451828, -22.906801071586663))))
      z <- cmd.geoRadius(testKey, GeoRadius(_Montevideo.lon, _Montevideo.lat, Distance(10000.0)), GeoArgs.Unit.km)
      _ <- IO(assert(z.toList.containsSlice(List(_BuenosAires.value, _Montevideo.value, _RioDeJaneiro.value))))
    } yield ()
  }

  def hashesScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey   = "foo"
    val testField = "bar"
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
      _ <- cmd.hDel(testKey, testField)
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
      _ <- cmd.rPush(testKey, "one", "two")
      last2 <- cmd.brPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(last2.contains((testKey, "two"))))
      first2 <- cmd.blPop(1.second, NonEmptyList.one(testKey))
      _ <- IO(assert(first2.contains((testKey, "one"))))
      t <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assert(t.isEmpty))
      _ <- cmd.rPush(testKey, "one", "two", "three")
      x <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assert(x == List("one", "two", "three")))
      y <- cmd.lLen(testKey)
      _ <- IO(assert(y.contains(3)))
      a <- cmd.lPop(testKey)
      _ <- IO(assert(a.contains("one")))
      b <- cmd.rPop(testKey)
      _ <- IO(assert(b.contains("three")))
      z <- cmd.lRange(testKey, 0, 10)
      _ <- IO(assert(z == List("two")))
    } yield ()
  }

  def setsScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey = "foos"
    for {
      x <- cmd.sMembers(testKey)
      _ <- IO(assert(x.isEmpty))
      _ <- cmd.sAdd(testKey, "set value")
      y <- cmd.sMembers(testKey)
      _ <- IO(assert(y.contains("set value")))
      o <- cmd.sCard(testKey)
      _ <- IO(assert(o == 1L))
      _ <- cmd.sRem("non-existing", "random")
      w <- cmd.sMembers(testKey)
      _ <- IO(assert(w.contains("set value")))
      _ <- cmd.sRem(testKey, "set value")
      z <- cmd.sMembers(testKey)
      _ <- IO(assert(z.isEmpty))
      t <- cmd.sCard(testKey)
      _ <- IO(assert(t == 0L))
    } yield ()
  }

  def sortedSetsScenario(cmd: RedisCommands[IO, String, Long]): IO[Unit] = {
    val testKey = "zztop"
    for {
      t <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
      _ <- IO(assert(t.isEmpty))
      _ <- cmd.zAdd(testKey, args = None, ScoreWithValue(Score(1), 1), ScoreWithValue(Score(3), 2))
      x <- cmd.zRevRangeByScore(testKey, ZRange(0, 2), limit = None)
      _ <- IO(assert(x == List(1)))
      y <- cmd.zCard(testKey)
      _ <- IO(assert(y.contains(2)))
      z <- cmd.zCount(testKey, ZRange(0, 1))
      _ <- IO(assert(z.contains(1)))
    } yield ()
  }

  def keysScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "key1"
    val key2 = "key2"
    for {
      x <- cmd.get(key1)
      _ <- IO(assert(x.isEmpty))
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
      scan0 <- cmd.scan
      _ <- IO(assert(scan0.cursor == "0" && scan0.keys.sorted == List(key1, key2)))
      scan1 <- cmd.scan(ScanArgs(1))
      _ <- IO(assert(scan1.cursor != "0" && scan1.keys.length == 1))
      scan2 <- cmd.scan(scan1.cursor.toLong, ScanArgs("key*"))
      _ <- IO(
            assert(
              scan2.cursor == "0" && (scan1.keys ++ scan2.keys).sorted == List(key1, key2)
            )
          )
      exist4 <- cmd.exists(key1, key2, "_not_existing_key_")
      _ <- IO(assert(!exist4))
      _ <- cmd.del(key1)
      exist5 <- cmd.exists(key1)
      _ <- IO(assert(!exist5))
      a <- cmd.ttl("whatever+")
      _ <- IO(assert(a.isEmpty))
      b <- cmd.pttl("whatever+")
      _ <- IO(assert(b.isEmpty))
      _ <- cmd.set("f1", "bar")
      _ <- cmd.expire("f1", 10.seconds)
      c <- cmd.ttl("f1")
      _ <- IO(assert(c.isDefined))
      d <- cmd.pttl("f1")
      _ <- IO(assert(d.isDefined))
    } yield ()
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
    cmd.ping.flatMap(pong => IO(assert(pong === "PONG"))).void

  def serverScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] =
    for {
      _ <- cmd.mSet(Map("firstname" -> "Jack", "lastname" -> "Stuntman", "age" -> "35"))
      names <- cmd.keys("*name*").map(_.toSet)
      _ <- IO(assert(names == Set("firstname", "lastname")))
      age <- cmd.keys("a??")
      _ <- IO(assert(age == List("age")))
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

    val operations =
      cmd.set(key1, "osx") :: cmd.set(key2, "windows") :: cmd.get(key1) :: cmd.sIsMember("foo", "bar") ::
          cmd.set(key1, "nix") :: cmd.set(key2, "linux") :: cmd.get(key1) :: HNil

    RedisPipeline(cmd).exec(operations).map {
      case _ ~: _ ~: res1 ~: res2 ~: _ ~: _ ~: res3 ~: HNil =>
        assert(res1.contains("osx"))
        assert(res2 === false)
        assert(res3.contains("nix"))
      case tr =>
        assert(false, s"Unexpected result: $tr")
    }

  }

  def transactionScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val key1 = "test1"
    val key2 = "test2"

    val operations =
      cmd.set(key1, "osx") :: cmd.set(key2, "windows") :: cmd.get(key1) :: cmd.sIsMember("foo", "bar") ::
          cmd.set(key1, "nix") :: cmd.set(key2, "linux") :: cmd.get(key1) :: HNil

    RedisTransaction(cmd).exec(operations).map {
      case _ ~: _ ~: res1 ~: res2 ~: _ ~: _ ~: res3 ~: HNil =>
        assert(res1.contains("osx"))
        assert(res2 === false)
        assert(res3.contains("nix"))
      case tr =>
        assert(false, s"Unexpected result: $tr")
    }

  }

  def canceledTransactionScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val tx = RedisTransaction(cmd)

    val commands =
      cmd.set(s"tx-1", s"v1") :: cmd.set(s"tx-2", s"v2") :: cmd.set(s"tx-3", s"v3") :: HNil

    // Transaction should be canceled
    IO.race(tx.exec(commands), IO.unit) >>
      cmd.get("tx-1").map(x => assert(x.isEmpty)) // no keys written
  }

  def scriptsScenario(cmd: RedisCommands[IO, String, String]): IO[Unit] = {
    val statusScript =
      """
        |redis.call('set',KEYS[1],ARGV[1])
        |redis.call('del',KEYS[1])
        |return redis.status_reply('OK')""".stripMargin
    for {
      fortyTwo: Long <- cmd.eval("return 42", ScriptOutputType.Integer)
      _ <- IO(assert(fortyTwo === 42L))
      value: String <- cmd.eval("return 'Hello World'", ScriptOutputType.Value)
      _ <- IO(assert(value === "Hello World"))
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
      _ <- IO(assert(list === List("Let", "us", "have", "fun")))
      () <- cmd.eval(statusScript, ScriptOutputType.Status, List("test"), List("foo"))
      sha42 <- cmd.scriptLoad("return 42")
      fortyTwoSha: Long <- cmd.evalSha(sha42, ScriptOutputType.Integer)
      _ <- IO(assert(fortyTwoSha === 42L))
      shaStatusScript <- cmd.scriptLoad(statusScript)
      () <- cmd.evalSha(shaStatusScript, ScriptOutputType.Status, List("test"), List("foo", "bar"))
      exists <- cmd.scriptExists(sha42, "foobar")
      _ <- IO(assert(exists === List(true, false)))
      () <- cmd.scriptFlush
      exists2 <- cmd.scriptExists(sha42)
      _ <- IO(assert(exists2 === List(false)))
    } yield ()
  }

}
