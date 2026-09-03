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
import io.lettuce.core.{ GeoArgs, LMovemArgs, RedisCommandExecutionException, RedisException, ZAggregateArgs }
import munit.FunSuite

import java.time.Instant
import scala.concurrent.duration._

trait TestScenarios { self: FunSuite =>

  def locationScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val _BuenosAires  = GeoLocation(Longitude(-58.3816), Latitude(-34.6037), "Buenos Aires")
    val _RioDeJaneiro = GeoLocation(Longitude(-43.1729), Latitude(-22.9068), "Rio de Janeiro")
    val _Montevideo   = GeoLocation(Longitude(-56.164532), Latitude(-34.901112), "Montevideo")
    val _Tokyo        = GeoLocation(Longitude(139.6917), Latitude(35.6895), "Tokyo")

    val testKey = "{geosearch}:location"
    for {
      addCount1 <- redis.geoAdd(testKey, _BuenosAires)
      _ <- IO(assertEquals(addCount1, 1L))
      _ <- redis.geoAdd(testKey, _RioDeJaneiro)
      _ <- redis.geoAdd(testKey, _Montevideo)
      _ <- redis.geoAdd(testKey, _Tokyo)
      addCountExisting <- redis.geoAdd(testKey, _Tokyo)
      _ <- IO(assertEquals(addCountExisting, 0L)) // re-adding an existing member updates it, adds nothing new
      x <- redis.geoDist(testKey, _BuenosAires.value, _Tokyo.value, GeoArgs.Unit.km)
      _ <- IO(assertEquals(x, 18374.9052))
      y <- redis.geoPos(testKey, _RioDeJaneiro.value)
      _ <- IO(assert(y.contains(GeoCoordinate(-43.17289799451828, -22.906801071586663))))

      // geoSearch: FromCoordinates x ByRadius (plain Set[V] result)
      byCoordRadius <- redis.geoSearch(
                         testKey,
                         GeoSearchReference.FromCoordinates(_Montevideo.lon, _Montevideo.lat),
                         GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km)
                       )
      _ <- IO(
             assert(
               byCoordRadius.toList.containsSlice(List(_BuenosAires.value, _Montevideo.value, _RioDeJaneiro.value))
             )
           )

      // geoSearch: FromCoordinates x ByRadius, with GeoArgs (List[GeoSearchResult[V]] result)
      byCoordRadiusArgs <- redis.geoSearch(
                             testKey,
                             GeoSearchReference.FromCoordinates(_Montevideo.lon, _Montevideo.lat),
                             GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km),
                             GeoArgs.Builder.full()
                           )
      _ <- IO(assert(byCoordRadiusArgs.map(_.value).toSet == byCoordRadius))

      // geoSearch: FromMember x ByRadius
      byMemberRadius <- redis.geoSearch(
                          testKey,
                          GeoSearchReference.FromMember(_Montevideo.value),
                          GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km)
                        )
      _ <- IO(assertEquals(byMemberRadius, byCoordRadius))

      // geoSearch: FromMember x ByRadius, with GeoArgs
      byMemberRadiusArgs <- redis.geoSearch(
                              testKey,
                              GeoSearchReference.FromMember(_Montevideo.value),
                              GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km),
                              GeoArgs.Builder.full()
                            )
      _ <- IO(assertEquals(byMemberRadiusArgs.map(_.value).toSet, byCoordRadius))

      // geoSearch: FromCoordinates x ByBox (new capability, GEORADIUS never supported box search)
      byCoordBox <- redis.geoSearch(
                      testKey,
                      GeoSearchReference.FromCoordinates(_Montevideo.lon, _Montevideo.lat),
                      GeoSearchPredicate.ByBox(Distance(10000.0), Distance(10000.0), GeoArgs.Unit.km)
                    )
      _ <- IO(assert(byCoordBox.contains(_Montevideo.value)))

      // geoSearch: FromMember x ByBox (the combination with no legacy fallback)
      byMemberBox <- redis.geoSearch(
                       testKey,
                       GeoSearchReference.FromMember(_Montevideo.value),
                       GeoSearchPredicate.ByBox(Distance(10000.0), Distance(10000.0), GeoArgs.Unit.km)
                     )
      _ <- IO(assert(byMemberBox.contains(_Montevideo.value)))

      // asymmetric box (width != height)
      asymmetricBox <- redis.geoSearch(
                         testKey,
                         GeoSearchReference.FromCoordinates(_Montevideo.lon, _Montevideo.lat),
                         GeoSearchPredicate.ByBox(Distance(20000.0), Distance(1.0), GeoArgs.Unit.km)
                       )
      _ <- IO(assert(asymmetricBox.contains(_Montevideo.value)))

      // empty result set: a tiny radius far from everything
      emptyResult <- redis.geoSearch(
                       testKey,
                       GeoSearchReference.FromCoordinates(Longitude(0.0), Latitude(0.0)),
                       GeoSearchPredicate.ByRadius(Distance(1.0), GeoArgs.Unit.km)
                     )
      _ <- IO(assert(emptyResult.isEmpty))

      // FromMember referencing a non-existent member fails
      nonExistentMemberAttempt <-
        redis
          .geoSearch(
            testKey,
            GeoSearchReference.FromMember("does-not-exist"),
            GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km)
          )
          .attempt
      _ <- IO(assert(nonExistentMemberAttempt.isLeft))

      // geoSearchStore: FromCoordinates, storeDist = false
      storeCount1 <- redis.geoSearchStore(
                       "{geosearch}:location-store-1",
                       testKey,
                       GeoSearchReference.FromCoordinates(_Montevideo.lon, _Montevideo.lat),
                       GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km),
                       storeDist = false
                     )
      _ <- IO(assertEquals(storeCount1, 3L))
      storedMembers1 <- redis.zRange("{geosearch}:location-store-1", 0, -1)
      _ <- IO(assertEquals(storedMembers1.toSet, byCoordRadius))

      // geoSearchStore: FromCoordinates, storeDist = true
      storeCount2 <- redis.geoSearchStore(
                       "{geosearch}:location-store-2",
                       testKey,
                       GeoSearchReference.FromCoordinates(_Montevideo.lon, _Montevideo.lat),
                       GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km),
                       storeDist = true
                     )
      _ <- IO(assertEquals(storeCount2, 3L))

      // geoSearchStore: FromMember, storeDist = false, with GeoArgs (count = 1)
      storeCount3 <- redis.geoSearchStore(
                       "{geosearch}:location-store-3",
                       testKey,
                       GeoSearchReference.FromMember(_Montevideo.value),
                       GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km),
                       storeDist = false,
                       GeoStoreArgs(count = Some(1))
                     )
      _ <- IO(assertEquals(storeCount3, 1L))

      // geoSearchStore: FromMember, storeDist = true
      storeCount4 <- redis.geoSearchStore(
                       "{geosearch}:location-store-4",
                       testKey,
                       GeoSearchReference.FromMember(_Montevideo.value),
                       GeoSearchPredicate.ByRadius(Distance(10000.0), GeoArgs.Unit.km),
                       storeDist = true
                     )
      _ <- IO(assertEquals(storeCount4, 3L))

      // geoSearchStore: empty result stores nothing
      storeCountEmpty <- redis.geoSearchStore(
                           "{geosearch}:location-store-empty",
                           testKey,
                           GeoSearchReference.FromCoordinates(Longitude(0.0), Latitude(0.0)),
                           GeoSearchPredicate.ByRadius(Distance(1.0), GeoArgs.Unit.km),
                           storeDist = false
                         )
      _ <- IO(assertEquals(storeCountEmpty, 0L))
    } yield ()
  }

  def hashesScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey    = "foo"
    val testField  = "bar"
    val testField2 = "baz"
    val hScanKey   = "hash-test-data"
    val hScanMap =
      (Seq("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten") ++
        Seq("eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"))
        // Keep this comment to avoid ugly formatting.
        .iterator.zipWithIndex.map(_.map(String.valueOf(_: Int))).toMap
    val hScanMapR =
      hScanMap.view.filterKeys(_.contains('r')).toMap

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
      hSetCount <- redis.hSet(testKey, Map(testField -> "some value", testField2 -> "another value"))
      _ <- IO(assertEquals(hSetCount, 2L)) // both fields are new, per hDel above
      v <- redis.hGet(testKey, testField)
      _ <- IO(assert(v.contains("some value")))
      v <- redis.hGet(testKey, testField2)
      _ <- IO(assert(v.contains("another value")))
      _ <- redis.hExpire(testKey, 1.seconds, testField, testField2)
      time <- redis.hExpireTime(testKey, testField, testField2)
      _ <- IO(assert(time.forall(_.exists(ts => ts.isAfter(Instant.now())))))
      _ <- IO.sleep(1.seconds)
      v1 <- redis.hGet(testKey, testField)
      v2 <- redis.hGet(testKey, testField2)
      _ <- IO(assert(v1.isEmpty))
      _ <- IO(assert(v2.isEmpty))
      _ <- redis.hSet(testKey, Map(testField -> "some value", testField2 -> "another value"))
      _ <- redis.hExpireAt(testKey, Instant.now().plusSeconds(1), testField, testField2)
      unixTimeStampList <- redis.hExpireTime(testKey, testField, testField2)
      _ <- IO(assert(unixTimeStampList.forall(_.exists(x => x.isAfter(Instant.now())))))
      _ <- IO.sleep(1.seconds)
      v1 <- redis.hGet(testKey, testField)
      v2 <- redis.hGet(testKey, testField2)
      _ <- IO(assert(v1.isEmpty))
      _ <- IO(assert(v2.isEmpty))
      _ <- redis.hSet(testKey, Map(testField -> "some value", testField2 -> "another value"))
      _ <- redis.hExpireAt(testKey, Instant.now().plusSeconds(10), testField, testField2)
      _ <- redis.hPersist(testKey, testField, testField2)
      time <- redis.hExpireTime(testKey, testField, testField2)
      _ <- IO(assert(time.forall(_.isEmpty)))
      _ <- redis.hSet(testKey, Map(testField -> "Hello", testField2 -> "World"))
      _ <- redis.hGetDel(testKey, testField, testField2)
      res <- redis.hGet(testKey, testField)
      _ <- IO(assert(res.isEmpty))
      res2 <- redis.hGet(testKey, testField2)
      _ <- IO(assert(res2.isEmpty))
      _ <- redis.hSet(testKey, Map(testField -> "Hello", testField2 -> "World"))
      res <- redis.hGetEx(testKey, HGetExArgs.ExAt(Instant.now().plusSeconds(10)), testField, testField2)
      _ <- IO(assertEquals(res, List(Some("Hello"), Some("World"))))
      _ <- redis
             .httl(testKey, testField, testField2)
             .flatMap(ttls => IO(assert(ttls.forall(_.exists(_ > 0.seconds)))))
      // Setup data for hScan* method tests
      _ <- redis.hSet(hScanKey, hScanMap)
      // Test hScan without ScanArgs
      hScanMapRes <- genMapScan(hScanKey)(redis.hScan)(redis.hScan)
      _ <- IO(assertEquals(hScanMapRes, hScanMap))
      // Test hScanNoValues without ScanArgs
      hScanNovRes <- genKeyScan(hScanKey)(redis.hScanNoValues)(redis.hScanNoValues)
      _ <- IO(assertEquals(hScanNovRes.toSet, hScanMap.keySet))
      // Test hScan with ScanArgs
      hScanMapResR <-
        genMapScan(
          (hScanKey, ScanArgs("*r*", count = 3L))
        ) { case (k, a) => redis.hScan(k, a) } { case ((k, a), c) => redis.hScan(k, c, a) }
      _ <- IO(assertEquals(hScanMapResR, hScanMapR))
      // Test hScanNoValues with ScanArgs
      hScanNovResR <-
        genKeyScan(
          (hScanKey, ScanArgs("*r*", count = 5L))
        ) { case (k, a) => redis.hScanNoValues(k, a) } { case ((k, a), c) => redis.hScanNoValues(k, c, a) }
      _ <- IO(assertEquals(hScanNovResR.toSet, hScanMapR.keySet))
      // hRandField / hRandFieldWithValues
      hRandKey    = "hrand-test"
      hRandFields = Map("a" -> "1", "b" -> "2", "c" -> "3")
      _ <- redis.hSet(hRandKey, hRandFields)
      oneField <- redis.hRandField(hRandKey)
      _ <- IO(assert(oneField.exists(hRandFields.keySet.contains)))
      allFields <- redis.hRandField(hRandKey, 3)
      _ <- IO(assertEquals(allFields.toSet, hRandFields.keySet))
      _ <- IO(assertEquals(allFields.distinct.size, 3)) // count == cardinality, no repeats
      onePair <- redis.hRandFieldWithValues(hRandKey)
      _ <- IO(assert(onePair.exists { case (k, v) => hRandFields.get(k).contains(v) }))
      allPairs <- redis.hRandFieldWithValues(hRandKey, 3)
      _ <- IO(assertEquals(allPairs.toMap, hRandFields))
      missingField <- redis.hRandField("hrand-does-not-exist")
      _ <- IO(assertEquals(missingField, None))
      missingFieldList <- redis.hRandField("hrand-does-not-exist", 3)
      _ <- IO(assert(missingFieldList.isEmpty))
      missingPair <- redis.hRandFieldWithValues("hrand-does-not-exist")
      _ <- IO(assertEquals(missingPair, None))
      // hSetEx: plain (no args), and with existence/TTL args
      hSetExKey = "hsetex-test"
      hSetExPlain <- redis.hSetEx(hSetExKey, Map("x" -> "1", "y" -> "2"))
      _ <- IO(assertEquals(hSetExPlain, 1L)) // whole-operation success status, not a per-field count
      hSetExVal <- redis.hGet(hSetExKey, "x")
      _ <- IO(assertEquals(hSetExVal, Some("1")))
      hSetExNxFirst <- redis.hSetEx(hSetExKey, HSetExArgs(HSetExArg.Existence.Nx), Map("z" -> "3"))
      _ <- IO(assertEquals(hSetExNxFirst, 1L)) // "z" didn't exist yet, condition satisfied
      hSetExNxSecond <- redis.hSetEx(hSetExKey, HSetExArgs(HSetExArg.Existence.Nx), Map("z" -> "should-not-apply"))
      _ <- IO(assertEquals(hSetExNxSecond, 0L)) // "z" now exists, FNX condition fails
      hSetExUnchangedVal <- redis.hGet(hSetExKey, "z")
      _ <- IO(assertEquals(hSetExUnchangedVal, Some("3")))
      _ <- redis.hSetEx(hSetExKey, HSetExArgs(HSetExArg.Ttl.Ex(10.seconds)), Map("withTtl" -> "value"))
      hSetExTtl <- redis.httl(hSetExKey, "withTtl")
      _ <- IO(assert(hSetExTtl.forall(_.exists(_ > 0.seconds))))
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
      // lMove: all 4 direction combinations
      _ <- redis.rPush("{listmove}:lmove-src", "a", "b", "c")
      lmRL <- redis.lMove("{listmove}:lmove-src", "{listmove}:lmove-dst", LMoveSide.Right, LMoveSide.Left)
      _ <- IO(assertEquals(lmRL, Some("c")))
      lmDst1 <- redis.lRange("{listmove}:lmove-dst", 0, -1)
      _ <- IO(assertEquals(lmDst1, List("c")))
      lmLL <- redis.lMove("{listmove}:lmove-src", "{listmove}:lmove-dst", LMoveSide.Left, LMoveSide.Left)
      _ <- IO(assertEquals(lmLL, Some("a")))
      lmDst2 <- redis.lRange("{listmove}:lmove-dst", 0, -1)
      _ <- IO(assertEquals(lmDst2, List("a", "c")))
      lmLR <- redis.lMove("{listmove}:lmove-src", "{listmove}:lmove-dst", LMoveSide.Left, LMoveSide.Right)
      _ <- IO(assertEquals(lmLR, Some("b")))
      lmDst3 <- redis.lRange("{listmove}:lmove-dst", 0, -1)
      _ <- IO(assertEquals(lmDst3, List("a", "c", "b")))
      lmSrcEmpty <- redis.lRange("{listmove}:lmove-src", 0, -1)
      _ <- IO(assert(lmSrcEmpty.isEmpty))
      // rightRight + self-rotation (source == destination)
      _ <- redis.rPush("{listmove}:lmove-rot", "x", "y", "z")
      lmRR <- redis.lMove("{listmove}:lmove-rot", "{listmove}:lmove-rot", LMoveSide.Right, LMoveSide.Right)
      _ <- IO(assertEquals(lmRR, Some("z")))
      lmRotResult <- redis.lRange("{listmove}:lmove-rot", 0, -1)
      _ <- IO(assertEquals(lmRotResult, List("x", "y", "z")))
      // lMove on an empty source returns None
      lmEmpty <- redis.lMove("{listmove}:lmove-does-not-exist", "{listmove}:lmove-dst", LMoveSide.Right, LMoveSide.Left)
      _ <- IO(assertEquals(lmEmpty, None))
      // blMove: element available immediately
      _ <- redis.rPush("{listmove}:blmove-src", "one")
      blmImmediate <-
        redis.blMove(1.second, "{listmove}:blmove-src", "{listmove}:blmove-dst", LMoveSide.Right, LMoveSide.Left)
      _ <- IO(assertEquals(blmImmediate, Some("one")))
      // blMove: timeout expiry with no element available
      blmTimeout <- redis.blMove(
                      1.second,
                      "{listmove}:blmove-does-not-exist",
                      "{listmove}:blmove-dst",
                      LMoveSide.Right,
                      LMoveSide.Left
                    )
      _ <- IO(assertEquals(blmTimeout, None))

      // lMoveMany: UpTo, BULK ordering (preserves original order)
      _ <- redis.rPush("{listmove}:lmm-src", "a", "b", "c")
      lmmUpTo <- redis.lMoveMany(
                   "{listmove}:lmm-src",
                   "{listmove}:lmm-dst",
                   LMoveSide.Right,
                   LMoveSide.Left,
                   LMoveCount.UpTo(2, LMovemArgs.Ordering.BULK)
                 )
      _ <- IO(assertEquals(lmmUpTo, List("b", "c")))
      lmmDst <- redis.lRange("{listmove}:lmm-dst", 0, -1)
      _ <- IO(assertEquals(lmmDst, List("b", "c")))
      lmmSrcRemaining <- redis.lRange("{listmove}:lmm-src", 0, -1)
      _ <- IO(assertEquals(lmmSrcRemaining, List("a")))
      // lMoveMany: no count block behaves like a single-element move but returns a List
      lmmNoCount <- redis.lMoveMany("{listmove}:lmm-src", "{listmove}:lmm-dst", LMoveSide.Right, LMoveSide.Left)
      _ <- IO(assertEquals(lmmNoCount, List("a")))
      // lMoveMany: Exactly requesting more than available returns empty, moves nothing
      _ <- redis.rPush("{listmove}:lmm-exactly-src", "x")
      lmmExactlyShort <- redis.lMoveMany(
                           "{listmove}:lmm-exactly-src",
                           "{listmove}:lmm-exactly-dst",
                           LMoveSide.Right,
                           LMoveSide.Left,
                           LMoveCount.Exactly(2, LMovemArgs.Ordering.BULK)
                         )
      _ <- IO(assert(lmmExactlyShort.isEmpty))
      lmmExactlySrcUntouched <- redis.lRange("{listmove}:lmm-exactly-src", 0, -1)
      _ <- IO(assertEquals(lmmExactlySrcUntouched, List("x")))

      // blMoveMany: elements available immediately, with a count
      _ <- redis.rPush("{listmove}:blmm-src", "p", "q")
      blmmImmediate <- redis.blMoveMany(
                         1.second,
                         "{listmove}:blmm-src",
                         "{listmove}:blmm-dst",
                         LMoveSide.Right,
                         LMoveSide.Left,
                         LMoveCount.UpTo(2, LMovemArgs.Ordering.BULK)
                       )
      _ <- IO(assertEquals(blmmImmediate, List("p", "q")))
      // blMoveMany: timeout expiry, no count block
      blmmTimeout <- redis.blMoveMany(
                       1.second,
                       "{listmove}:blmm-does-not-exist",
                       "{listmove}:blmm-dst",
                       LMoveSide.Right,
                       LMoveSide.Left
                     )
      _ <- IO(assert(blmmTimeout.isEmpty))

      // lmPop: first non-empty of several keys
      _ <- redis.rPush("{listmove}:lmpop-b", "1", "2")
      lmPopResult <- redis.lmPop(NonEmptyList.of("{listmove}:lmpop-a", "{listmove}:lmpop-b"), LMoveSide.Left)
      _ <- IO(assertEquals(lmPopResult, Some(("{listmove}:lmpop-b", List("1")))))
      // lmPop: with an explicit count
      _ <- redis.rPush("{listmove}:lmpop-c", "3", "4", "5")
      lmPopCountResult <- redis.lmPop(NonEmptyList.one("{listmove}:lmpop-c"), LMoveSide.Left, 2)
      _ <- IO(assertEquals(lmPopCountResult, Some(("{listmove}:lmpop-c", List("3", "4")))))
      // lmPop: no key has elements
      lmPopEmpty <- redis.lmPop(NonEmptyList.one("{listmove}:lmpop-empty"), LMoveSide.Left)
      _ <- IO(assertEquals(lmPopEmpty, None))

      // blmPop: element available immediately
      _ <- redis.rPush("{listmove}:blmpop-a", "6")
      blmPopResult <- redis.blmPop(1.second, NonEmptyList.one("{listmove}:blmpop-a"), LMoveSide.Left)
      _ <- IO(assertEquals(blmPopResult, Some(("{listmove}:blmpop-a", List("6")))))
      // blmPop: timeout expiry
      blmPopTimeout <- redis.blmPop(1.second, NonEmptyList.one("{listmove}:blmpop-empty"), LMoveSide.Left)
      _ <- IO(assertEquals(blmPopTimeout, None))

      // lPos: basic position, and a missing element
      _ <- redis.rPush("lpos-key", "a", "b", "c", "b")
      lPosSingle <- redis.lPos("lpos-key", "b")
      _ <- IO(assertEquals(lPosSingle, Some(1L)))
      lPosMissing <- redis.lPos("lpos-key", "z")
      _ <- IO(assertEquals(lPosMissing, None))
      // lPos: RANK 2 skips to the second occurrence
      lPosRank <- redis.lPos("lpos-key", "b", LPosArgs(rank = Some(2)))
      _ <- IO(assertEquals(lPosRank, Some(3L)))
      // lPos: COUNT 0 returns every occurrence
      lPosCount <- redis.lPos("lpos-key", "b", 0L)
      _ <- IO(assertEquals(lPosCount, List(1L, 3L)))
      // lPos: COUNT 0 with MAXLEN limits how much of the list is scanned
      lPosCountArgs <- redis.lPos("lpos-key", "b", 0L, LPosArgs(maxLen = Some(2)))
      _ <- IO(assertEquals(lPosCountArgs, List(1L)))

      // lPop/rPop multi-pop
      _ <- redis.rPush("multipop-key", "1", "2", "3", "4")
      lPopMulti <- redis.lPop("multipop-key", 2)
      _ <- IO(assertEquals(lPopMulti, List("1", "2")))
      rPopMulti <- redis.rPop("multipop-key", 2)
      _ <- IO(assertEquals(rPopMulti, List("4", "3")))
    } yield ()
  }

  def setsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    val testKey  = "foos"
    val sScanKey = "set-test-data"
    val sScanSeq =
      Seq("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten") ++
        Seq("eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    val sScanSeqR =
      sScanSeq.filter(_.contains('r'))
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
      // Setup data for sScan* method tests
      _ <- redis.sAdd(sScanKey, sScanSeq: _*)
      // Test sScan without ScanArgs
      sScanSeqRes <- genValScan(sScanKey)(redis.sScan)(redis.sScan)
      _ <- IO(assertEquals(sScanSeqRes, sScanSeq))
      // Test sScan with ScanArgs
      sScanSeqResR <-
        genValScan(
          (sScanKey, ScanArgs("*r*", count = 3L))
        ) { case (k, a) => redis.sScan(k, a) } { case ((k, a), c) => redis.sScan(k, c, a) }
      _ <- IO(assertEquals(sScanSeqResR, sScanSeqR))
      _ <- redis.sAdd("{sunion}:sunion-a", "1", "2")
      _ <- redis.sAdd("{sunion}:sunion-b", "2", "3")
      unionCount <- redis.sUnionStore("{sunion}:sunion-dest", "{sunion}:sunion-a", "{sunion}:sunion-b")
      _ <- IO(assertEquals(unionCount, 3L))
      unionMembers <- redis.sMembers("{sunion}:sunion-dest")
      _ <- IO(assertEquals(unionMembers, Set("1", "2", "3")))
      // SUNIONCARD/SDIFFCARD/SINTERCARD ({1,2} vs {2,3}): union={1,2,3}, diff(a-b)={1}, inter={2}
      unionCard <- redis.sUnionCard("{sunion}:sunion-a", "{sunion}:sunion-b")
      _ <- IO(assertEquals(unionCard, 3L))
      unionCardLimited <- redis.sUnionCard(2L, "{sunion}:sunion-a", "{sunion}:sunion-b")
      _ <- IO(assertEquals(unionCardLimited, 2L))
      diffCard <- redis.sDiffCard("{sunion}:sunion-a", "{sunion}:sunion-b")
      _ <- IO(assertEquals(diffCard, 1L))
      interCard <- redis.sInterCard("{sunion}:sunion-a", "{sunion}:sunion-b")
      _ <- IO(assertEquals(interCard, 1L))
      interCardLimited <- redis.sInterCard(1L, "{sunion}:sunion-a", "{sunion}:sunion-b")
      _ <- IO(assertEquals(interCardLimited, 1L))
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
      _ <- redis.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2, scoreWithValue3)
      scores <- redis.zMScore(testKey, 1L, 2L, 3L, 4L)
      _ <- IO(assertEquals(scores, List(Some(1.0), Some(3.0), Some(5.0), None)))
      valuesCandidates = Set(scoreWithValue1, scoreWithValue2, scoreWithValue3)
      randomValue <- redis.zRandMember(testKey)
      _ <- IO(assert(randomValue.exists(valuesCandidates.map(_.value).contains)))
      randomValues <- redis.zRandMember(testKey, 2)
      _ <- IO(
             assert(
               randomValues.size == 2 && randomValues.forall(
                 valuesCandidates.map(_.value).contains
               ) && randomValues.distinct.size == 2
             )
           )
      randomValueWithScore <- redis.zRandMemberWithScores(testKey)
      _ <- IO(assert(randomValueWithScore.exists(valuesCandidates.contains)))
      randomValuesWithScore <- redis.zRandMemberWithScores(testKey, 2)
      _ <- IO(
             assert(
               randomValuesWithScore.size == 2 && randomValuesWithScore.forall(valuesCandidates.contains) &&
                 randomValuesWithScore.distinct.size == 2
             )
           )

      // zmPopMin/zmPopMax: pop from the first non-empty of several keys
      _ <- redis.del(testKey, otherTestKey)
      zmPopEmpty <- redis.zmPopMin(NonEmptyList.of(testKey, otherTestKey), 1)
      _ <- IO(assert(zmPopEmpty.isEmpty))
      _ <- redis.zAdd(otherTestKey, args = None, scoreWithValue1, scoreWithValue2, scoreWithValue3)
      zmPopMinResult <- redis.zmPopMin(NonEmptyList.of(testKey, otherTestKey), 2)
      _ <- IO(assertEquals(zmPopMinResult, Some((otherTestKey, List(scoreWithValue1, scoreWithValue2)))))
      zmPopMaxResult <- redis.zmPopMax(NonEmptyList.of(testKey, otherTestKey), 1)
      _ <- IO(assertEquals(zmPopMaxResult, Some((otherTestKey, List(scoreWithValue3)))))
      _ <- redis.zAdd(otherTestKey, args = None, scoreWithValue1)
      bzmPopMinResult <- redis.bzmPopMin(timeout, NonEmptyList.of(testKey, otherTestKey), 1)
      _ <- IO(assertEquals(bzmPopMinResult, Some((otherTestKey, List(scoreWithValue1)))))
      bzmPopEmptyResult <- redis.bzmPopMax(timeout, NonEmptyList.one(testKey), 1)
      _ <- IO(assert(bzmPopEmptyResult.isEmpty))

      // zInterCard: cardinality of intersection without materializing it
      _ <- redis.zAdd(testKey, args = None, scoreWithValue1, scoreWithValue2)
      _ <- redis.zAdd(otherTestKey, args = None, scoreWithValue1, scoreWithValue3)
      interCard <- redis.zInterCard(testKey, otherTestKey)
      _ <- IO(assertEquals(interCard, 1L))
      interCardLimited <- redis.zInterCard(0, testKey, otherTestKey)
      _ <- IO(assertEquals(interCardLimited, 1L))

      // zRangeStore/zRevRangeStore: by-rank range into a destination key
      rangeStoreCount <- redis.zRangeStore("{same_hash_slot}:range-store-dest", testKey, 0, -1)
      _ <- IO(assertEquals(rangeStoreCount, 2L))
      rangeStoreMembers <- redis.zRangeWithScores("{same_hash_slot}:range-store-dest", 0, -1)
      _ <- IO(assertEquals(rangeStoreMembers, List(scoreWithValue1, scoreWithValue2)))
      revRangeStoreCount <- redis.zRevRangeStore("{same_hash_slot}:rev-range-store-dest", testKey, 0, -1)
      _ <- IO(assertEquals(revRangeStoreCount, 2L))
      revRangeStoreMembers <- redis.zRangeWithScores("{same_hash_slot}:rev-range-store-dest", 0, -1)
      _ <- IO(assertEquals(revRangeStoreMembers, List(scoreWithValue1, scoreWithValue2)))

      // zRangeStoreByScore/zRevRangeStoreByScore
      rangeStoreByScoreCount <-
        redis.zRangeStoreByScore("{same_hash_slot}:range-store-score-dest", testKey, ZRange(0, 1), limit = None)
      _ <- IO(assertEquals(rangeStoreByScoreCount, 1L))
      rangeStoreByScoreMembers <- redis.zRangeWithScores("{same_hash_slot}:range-store-score-dest", 0, -1)
      _ <- IO(assertEquals(rangeStoreByScoreMembers, List(scoreWithValue1)))
      revRangeStoreByScoreCount <-
        redis.zRevRangeStoreByScore(
          "{same_hash_slot}:rev-range-store-score-dest",
          testKey,
          ZRange(0, 3),
          limit = Some(RangeLimit(0, 1))
        )
      _ <- IO(assertEquals(revRangeStoreByScoreCount, 1L))
      revRangeStoreByScoreMembers <- redis.zRangeWithScores("{same_hash_slot}:rev-range-store-score-dest", 0, -1)
      _ <- IO(assertEquals(revRangeStoreByScoreMembers, List(scoreWithValue2)))
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
      // reset
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
      // SORT / SORT_RO / SORT ... STORE
      _ <- redis.rPush("{keystest}:sortsrc", "3", "1", "2")
      sorted <- redis.sort("{keystest}:sortsrc")
      _ <- IO(assertEquals(sorted, List("1", "2", "3")))
      sortedDesc <- redis.sortReadOnly("{keystest}:sortsrc", SortArgs(order = Some(SortOrder.Desc)))
      _ <- IO(assertEquals(sortedDesc, List("3", "2", "1")))
      sortedLimit <- redis.sort(
                       "{keystest}:sortsrc",
                       SortArgs(order = Some(SortOrder.Asc), limit = Some(RangeLimit(0, 2)))
                     )
      _ <- IO(assertEquals(sortedLimit, List("1", "2")))
      sortStoreCount <-
        redis.sortStore("{keystest}:sortsrc", SortArgs(order = Some(SortOrder.Asc)), "{keystest}:sortdst")
      _ <- IO(assertEquals(sortStoreCount, 3L))
      sortedStored <- redis.lRange("{keystest}:sortdst", 0, -1)
      _ <- IO(assertEquals(sortedStored, List("1", "2", "3")))
      // RENAME / RENAMENX
      _ <- redis.set("{keystest}:renamesrc", "renamed value")
      _ <- redis.rename("{keystest}:renamesrc", "{keystest}:renamedst")
      renamedVal <- redis.get("{keystest}:renamedst")
      _ <- IO(assertEquals(renamedVal, Some("renamed value")))
      _ <- redis.set("{keystest}:renamenxsrc", "a")
      _ <- redis.set("{keystest}:renamenxexisting", "b")
      renameNxFailed <- redis.renameNx("{keystest}:renamenxsrc", "{keystest}:renamenxexisting")
      _ <- IO(assertEquals(renameNxFailed, false)) // destination already exists
      renameNxOk <- redis.renameNx("{keystest}:renamenxsrc", "{keystest}:renamenxdst")
      _ <- IO(assertEquals(renameNxOk, true))
      // EXPIRETIME / PEXPIRETIME
      _ <- redis.set("exptimekey", "v")
      noExpireTime <- redis.expireTime("exptimekey")
      _ <- IO(assert(noExpireTime.isEmpty)) // no TTL set yet
      _ <- redis.expire("exptimekey", 100.seconds)
      hasExpireTime <- redis.expireTime("exptimekey")
      _ <- IO(assert(hasExpireTime.exists(_.isAfter(Instant.now()))))
      hasPExpireTime <- redis.pExpireTime("exptimekey")
      _ <- IO(assert(hasPExpireTime.exists(_.isAfter(Instant.now()))))
      // TOUCH
      _ <- redis.set("touchkey1", "v")
      _ <- redis.set("touchkey2", "v")
      touchedCount <- redis.touch("touchkey1", "touchkey2", "touchkey-does-not-exist")
      _ <- IO(assertEquals(touchedCount, 2L))
      // OBJECT ENCODING / REFCOUNT / FREQ
      _ <- redis.set("objkey", "12345") // short numeric string -> "int" encoding
      encoding <- redis.objectEncoding("objkey")
      _ <- IO(assertEquals(encoding, Some("int")))
      refcount <- redis.objectRefcount("objkey")
      _ <- IO(assert(refcount >= 1L))
      // OBJECT FREQ requires an LFU maxmemory-policy; this test suite's Redis runs the default
      // (non-LFU) policy, so the real, correct behavior here is that it fails, not succeeds.
      freqAttempt <- redis.objectFreq("objkey").attempt
      _ <- IO(assert(freqAttempt.isLeft))
      // MIGRATE: Redis checks the source key's existence before ever attempting to reach the
      // destination, so a missing key deterministically returns NOKEY (false) regardless of
      // whether "no-such-host" is actually reachable - no second live instance needed.
      migratedMissing <- redis.migrate("no-such-host", 6379, "migratekey-does-not-exist", 0, 1.second)
      _ <- IO(assertEquals(migratedMissing, false))
      migratedMissingArgs <- redis.migrate(
                               "no-such-host",
                               6379,
                               0,
                               1.second,
                               MigrateArgs(keys = List("migratekey-does-not-exist"), keepSource = true)
                             )
      _ <- IO(assertEquals(migratedMissingArgs, false))
      // DELEX: value-based condition, both the holds and doesn't-hold branches are real assertions;
      // the digest-based branch can only safely assert the doesn't-match case, since computing the
      // real XXH3 digest to hit the matching branch isn't practical from a test.
      _ <- redis.set("delexkey", "v1")
      deletedOnMismatch <- redis.delex("delexkey", CompareCondition.ValueEqual("wrong-value"))
      _ <- IO(assertEquals(deletedOnMismatch, false))
      stillThere <- redis.exists("delexkey")
      _ <- IO(assert(stillThere))
      deletedOnDigestMismatch <- redis.delex("delexkey", CompareCondition.DigestEqual("0" * 16))
      _ <- IO(assertEquals(deletedOnDigestMismatch, false))
      deletedOnMatch <- redis.delex("delexkey", CompareCondition.ValueEqual("v1"))
      _ <- IO(assertEquals(deletedOnMatch, true))
      goneAfterDelex <- redis.exists("delexkey")
      _ <- IO(assert(!goneAfterDelex))
      _ <- redis.flushAll
    } yield ()
  }

  // MOVE requires multiple DBs, which Redis Cluster doesn't support (cluster mode has only DB 0) —
  // this is intentionally NOT part of keysScenario (which runs under both RedisSpec and
  // RedisClusterSpec); wire this into RedisSpec only.
  def keysMoveScenario(redis: RedisCommands[IO, String, String]): IO[Unit] =
    for {
      _ <- redis.set("movekey", "v")
      moved <- redis.move("movekey", 1)
      _ <- IO(assertEquals(moved, true))
      goneFromDb0 <- redis.exists("movekey")
      _ <- IO(assert(!goneFromDb0))
    } yield ()

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

  // Generic adapter for all scan commands where KeyScanCursor is used (scan, hScanNoValues).
  private def genKeyScan[A, K](args: A)(
      init: A => IO[KeyScanCursor[K]]
  )(
      next: (A, KeyScanCursor[K]) => IO[KeyScanCursor[K]]
  ): IO[List[K]] = {
    def loop(cur: KeyScanCursor[K]): IO[List[K]] =
      if (cur.isFinished)
        IO.pure(cur.keys)
      else
        next(args, cur).flatMap {
          loop(_).map(cur.keys ::: _)
        }

    init(args).flatMap(loop)
  }
  // Generic adapter for all scan commands where MapScanCursor is used (hScan).
  private def genMapScan[A, K, V](args: A)(
      init: A => IO[MapScanCursor[K, V]]
  )(
      next: (A, MapScanCursor[K, V]) => IO[MapScanCursor[K, V]]
  ): IO[Map[K, V]] = {
    def loop(cur: MapScanCursor[K, V]): IO[Map[K, V]] =
      if (cur.isFinished)
        IO.pure(cur.map)
      else
        next(args, cur).flatMap {
          loop(_).map(cur.map ++ _)
        }

    init(args).flatMap(loop)
  }
  // Generic adapter for all scan commands where ValueScanCursor is used (sScan).
  private def genValScan[A, V](args: A)(
      init: A => IO[ValueScanCursor[V]]
  )(
      next: (A, ValueScanCursor[V]) => IO[ValueScanCursor[V]]
  ): IO[List[V]] = {
    def loop(cur: ValueScanCursor[V]): IO[List[V]] =
      if (cur.isFinished)
        IO.pure(cur.values)
      else
        next(args, cur).flatMap {
          loop(_).map(cur.values ::: _)
        }

    init(args).flatMap(loop)
  }

  /** Does scan on all cluster nodes until all keys collected since order of scanned nodes can't be guaranteed
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
      andLen <- redis.bitOpAnd(thirdKey, key, secondKey)
      _ <- IO(assertEquals(andLen, 1L)) // both source keys are 1 byte long
      r <- redis.getBit(thirdKey, 0)
      _ <- IO(assertEquals(r, Some(1.toLong)))
      notLen <- redis.bitOpNot(thirdKey, key)
      _ <- IO(assertEquals(notLen, 1L)) // result length == source's own length
      r2 <- redis.getBit(thirdKey, 0)
      _ <- IO(assertEquals(r2, Some(0.toLong)))
      orLen <- redis.bitOpOr(thirdKey, key, secondKey)
      _ <- IO(assertEquals(orLen, 1L))
      r3 <- redis.getBit(thirdKey, 0)
      _ <- IO(assertEquals(r3, Some(1.toLong)))
      xorLen <- redis.bitOpXor(thirdKey, key, secondKey)
      _ <- IO(assertEquals(xorLen, 1L))
      _ <- redis.setBit("bitop-long", 20, 1) // 3 bytes long
      _ <- redis.setBit("bitop-short", 0, 1) // 1 byte long
      orDifferentLengths <- redis.bitOpOr("bitop-result", "bitop-long", "bitop-short")
      _ <- IO(assertEquals(orDifferentLengths, 3L)) // result length == longest source
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
      // New BITOP variants: keyA = bits {0,1} set, keyB = bits {1,2} set (bit1 shared, 0/2 each unique)
      _ <- redis.setBit("bitop-a", 0, 1)
      _ <- redis.setBit("bitop-a", 1, 1)
      _ <- redis.setBit("bitop-b", 1, 1)
      _ <- redis.setBit("bitop-b", 2, 1)
      diffLen <- redis.bitOpDiff("bitop-diff", "bitop-a", "bitop-b")
      _ <- IO(assertEquals(diffLen, 1L))
      diffBits <- List(0, 1, 2).traverse(redis.getBit("bitop-diff", _))
      _ <- IO(assertEquals(diffBits, List(Some(1L), Some(0L), Some(0L)))) // only bit0 (unique to A) survives
      diff1Len <- redis.bitOpDiff1("bitop-diff1", "bitop-a", "bitop-b")
      _ <- IO(assertEquals(diff1Len, 1L))
      diff1Bits <- List(0, 1, 2).traverse(redis.getBit("bitop-diff1", _))
      _ <- IO(assertEquals(diff1Bits, List(Some(0L), Some(0L), Some(1L)))) // only bit2 (unique to B) survives
      andOrLen <- redis.bitOpAndOr("bitop-andor", "bitop-a", "bitop-b")
      _ <- IO(assertEquals(andOrLen, 1L))
      andOrBits <- List(0, 1, 2).traverse(redis.getBit("bitop-andor", _))
      _ <- IO(assertEquals(andOrBits, List(Some(0L), Some(1L), Some(0L)))) // only bit1 (shared) survives
      oneLen <- redis.bitOpOne("bitop-one", "bitop-a", "bitop-b")
      _ <- IO(assertEquals(oneLen, 1L))
      oneBits <- List(0, 1, 2).traverse(redis.getBit("bitop-one", _))
      _ <- IO(assertEquals(oneBits, List(Some(1L), Some(0L), Some(1L)))) // bits in exactly one key: 0 and 2
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
      getDelMissing <- redis.getDel("keyToGetDel")
      _ <- IO(assert(getDelMissing.isEmpty))
      _ <- redis.set("keyToGetDel", "valueToGetDel")
      getDelExisting <- redis.getDel("keyToGetDel")
      _ <- IO(assert(getDelExisting.contains("valueToGetDel")))
      getDelAfter <- redis.get("keyToGetDel")
      _ <- IO(assert(getDelAfter.isEmpty))
      appendLen1 <- redis.append("append-key", "Hello")
      _ <- IO(assertEquals(appendLen1, 5L))
      appendLen2 <- redis.append("append-key", " World")
      _ <- IO(assertEquals(appendLen2, 11L)) // cumulative length
      appendVal <- redis.get("append-key")
      _ <- IO(assertEquals(appendVal, Some("Hello World")))
      setRangeLen <- redis.setRange("append-key", "Redis", 6)
      _ <- IO(assertEquals(setRangeLen, 11L))
      setRangeVal <- redis.get("append-key")
      _ <- IO(assertEquals(setRangeVal, Some("Hello Redis")))
      // LCS: the canonical Redis docs example ("ohmytext" vs "mynewtext" -> "mytext", len 6)
      _ <- redis.set("lcs-key1", "ohmytext")
      _ <- redis.set("lcs-key2", "mynewtext")
      lcsPlain <- redis.lcs("lcs-key1", "lcs-key2")
      _ <- IO(assertEquals(lcsPlain.matchString, Some("mytext")))
      _ <- IO(assertEquals(lcsPlain.len, 6L))
      _ <- IO(assert(lcsPlain.matches.isEmpty)) // idx not requested
      lcsLen <- redis.lcsLen("lcs-key1", "lcs-key2")
      _ <- IO(assertEquals(lcsLen, 6L))
      lcsIdx <- redis.lcsIdx("lcs-key1", "lcs-key2")
      _ <- IO(assertEquals(lcsIdx.matchString, None)) // idx mode doesn't return the match string
      _ <- IO(assertEquals(lcsIdx.len, 6L))
      _ <- IO(
             assertEquals(
               lcsIdx.matches,
               List(
                 LcsMatch(LcsMatchPosition(4, 7), LcsMatchPosition(5, 8), None),
                 LcsMatch(LcsMatchPosition(2, 3), LcsMatchPosition(0, 1), None)
               )
             )
           )
      lcsIdxMinLen <- redis.lcsIdx("lcs-key1", "lcs-key2", minMatchLen = Some(4))
      _ <- IO(assertEquals(lcsIdxMinLen.matches, List(LcsMatch(LcsMatchPosition(4, 7), LcsMatchPosition(5, 8), None))))
      lcsIdxWithMatchLen <- redis.lcsIdx("lcs-key1", "lcs-key2", withMatchLen = true)
      _ <- IO(
             assertEquals(
               lcsIdxWithMatchLen.matches,
               List(
                 LcsMatch(LcsMatchPosition(4, 7), LcsMatchPosition(5, 8), Some(4L)),
                 LcsMatch(LcsMatchPosition(2, 3), LcsMatchPosition(0, 1), Some(2L))
               )
             )
           )
      // msetEx: atomic multi-key SET with a shared TTL
      msetExOk <-
        redis.msetEx(Map("msetex-a" -> "1", "msetex-b" -> "2"), MSetExArgs(ttl = Some(MSetExTtl.Ex(10.seconds))))
      _ <- IO(assert(msetExOk))
      msetExVals <- redis.mGet(Set("msetex-a", "msetex-b"))
      _ <- IO(assertEquals(msetExVals, Map("msetex-a" -> "1", "msetex-b" -> "2")))
      msetExTtl <- redis.ttl("msetex-a")
      _ <- IO(assert(msetExTtl.nonEmpty))
      // msetEx with NX: fails atomically (no key written) if any target key already exists
      msetExNxBlocked <-
        redis.msetEx(Map("msetex-a" -> "3", "msetex-c" -> "4"), MSetExArgs(existence = Some(SetArg.Existence.Nx)))
      _ <- IO(assert(!msetExNxBlocked))
      msetExCAfter <- redis.get("msetex-c")
      _ <- IO(assert(msetExCAfter.isEmpty)) // the whole op was rejected, not just the conflicting key
      // incrEx: plain form behaves like INCR on a fresh key
      incrExPlain <- redis.incrEx("increx-key")
      _ <- IO(assertEquals(incrExPlain, IncrexResult(1L, 1L)))
      incrExBy5 <- redis.incrEx("increx-key", 5L, IncrexArgs())
      _ <- IO(assertEquals(incrExBy5, IncrexResult(6L, 5L)))
      // saturate: 6 + 100 would exceed upperBound=10, so the result clamps and the applied increment differs
      incrExSaturated <- redis.incrEx("increx-key", 100L, IncrexArgs(upperBound = Some(10L), saturate = true))
      _ <- IO(assertEquals(incrExSaturated, IncrexResult(10L, 4L)))
      _ <- redis.incrEx("increx-key-ttl", 1L, IncrexArgs(ttl = Some(IncrexTtl.Ex(10.seconds))))
      incrExTtl <- redis.ttl("increx-key-ttl")
      _ <- IO(assert(incrExTtl.nonEmpty))
      incrExFloatRes <- redis.incrExFloat("increx-float-key", 1.5, IncrexFloatArgs())
      _ <- IO(assertEquals(incrExFloatRes, IncrexResult(1.5, 1.5)))
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
      echoed <- redis.echo("hello redis4cats")
      _ <- IO(assertEquals(echoed, "hello redis4cats"))
      role <- redis.role
      _ <- IO(assert(role.isInstanceOf[RedisRole.Master], s"expected Master in the single-node test setup, got $role"))
      waited <- redis.waitForReplication(numReplicas = 0, timeout = 100.millis)
      _ <- IO(assert(waited >= 0L))
    } yield ()
  }

  // READONLY/READWRITE are cluster-only commands - rejected with "ERR This instance
  // has cluster support disabled" on a standalone instance - so they're kept out of
  // connectionScenario (shared by RedisSpec and RedisClusterSpec) and exercised only
  // from the cluster suite.
  def connectionClusterScenario(redis: RedisCommands[IO, String, String]): IO[Unit] =
    for {
      _ <- redis.readOnly
      _ <- redis.readWrite
    } yield ()

  def aclScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    import dev.profunktor.redis4cats.effects.AclSetUserRule._
    val user = "redis4cats-acl-test"
    val rules =
      List[dev.profunktor.redis4cats.effects.AclSetUserRule](
        On,
        AddPassword("s3cret"),
        NoCommands,
        AddCommand(RawCommand("get")),
        AddCategory(AclCategory.Read),
        KeyPattern("app:*"),
        ChannelPattern("news.*")
      )
    // make sure a previous failed run doesn't leave the user behind, and clean up even if an assertion fails
    redis.aclDelUser(user) >> {
      val scenario = for {
        who <- redis.aclWhoAmI
        _ <- IO(assertEquals(who, "default"))
        cats <- redis.aclCat
        _ <- IO(assert(cats.contains(AclCategory.Read) && cats.contains(AclCategory.Write), s"categories: $cats"))
        readCmds <- redis.aclCat(AclCategory.Read)
        _ <- IO(assert(readCmds.contains("get"), s"read commands: $readCmds"))
        unknownCmd <- redis.aclSetUser(user, List(AddCommand(RawCommand("nope-not-a-cmd")))).attempt
        _ <- IO(assert(unknownCmd.left.exists(_.isInstanceOf[AclError.UnknownCommand]), s"unknown cmd: $unknownCmd"))
        pass <- redis.aclGenPass
        _ <- IO(assert(pass.length == 64, s"genpass: $pass"))
        shortPass <- redis.aclGenPass(32)
        _ <- IO(assert(shortPass.nonEmpty))
        _ <- redis.aclSetUser(user, rules)
        users <- redis.aclUsers
        _ <- IO(assert(users.contains(user), s"users: $users"))
        got <- redis.aclGetUser(user)
        _ <- IO(assert(got.exists(_.flags.contains("on")), s"getuser: $got"))
        _ <- IO(assert(got.exists(_.keys.contains("app:*")), s"getuser keys: ${got.map(_.keys)}"))
        _ <- IO(assert(got.exists(_.commands.contains("+get")), s"getuser commands: ${got.map(_.commands)}"))
        allowedDryRun <- redis.aclDryRun("default", "get", "somekey")
        _ <- IO(assertEquals(allowedDryRun, AclDryRunResult.Allowed))
        deniedDryRun <- redis.aclDryRun(user, "set", "somekey", "someval")
        _ <- IO(
               assert(
                 PartialFunction.cond(deniedDryRun) { case AclDryRunResult.Denied(_) => true },
                 s"dry run: $deniedDryRun"
               )
             )
        missing <- redis.aclGetUser("definitely-not-a-user")
        _ <- IO(assertEquals(missing, None))
        list <- redis.aclList
        _ <- IO(assert(list.exists(_.startsWith("user default")), s"list: $list"))
        deleted <- redis.aclDelUser(user)
        _ <- IO(assertEquals(deleted, 1L))
        usersAfter <- redis.aclUsers
        _ <- IO(assert(!usersAfter.contains(user)))
        _ <- redis.aclLogReset
        clearedLog <- redis.aclLog
        _ <- IO(assert(clearedLog.isEmpty, s"log should be empty right after reset: $clearedLog"))
        // a denied authentication generates a known ACL LOG entry (reason "auth")
        _ <- redis.auth(user, "wrong-password").attempt
        log <- redis.aclLog
        _ <- IO(assert(log.exists(_.get("reason").contains("auth")), s"log: $log"))
      } yield ()
      scenario.guarantee(redis.aclDelUser(user).void)
    }
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
      _ <- redis.slowLogReset
      // force every command to log, so PING deterministically produces a SLOWLOG entry
      originalSlowlogThreshold <- redis.configGet("slowlog-log-slower-than")
      _ <- redis.configSet("slowlog-log-slower-than", "0")
      _ <- redis.ping
      slowLogEntries <- redis.slowLogGet
      _ <- IO(assert(slowLogEntries.nonEmpty))
      _ <- IO(assert(slowLogEntries.head.args.headOption.exists(_.equalsIgnoreCase("ping"))))
      slowLogEntriesLimited <- redis.slowLogGet(1)
      _ <- IO(assertEquals(slowLogEntriesLimited.size, 1))
      _ <- originalSlowlogThreshold
             .get("slowlog-log-slower-than")
             .traverse_(v => redis.configSet("slowlog-log-slower-than", v))
      _ <- redis.slowLogReset
      commandCount <- redis.commandCount
      _ <- IO(assert(commandCount > 0))
      allCommands <- redis.command
      _ <- IO(assert(allCommands.exists(_.name == "get")))
      pingInfo <- redis.commandInfo("ping")
      _ <- IO(assert(pingInfo.exists(c => c.name == "ping" && c.flags.contains(CommandFlag.Fast))))
      time <- redis.time
      _ <- IO(assert(time.epochSecond > 0 && time.microseconds >= 0 && time.microseconds < 1000000))
      // config
      originalSamples <- redis.configGet("maxmemory-samples")
      _ <- redis.configSet("maxmemory-samples", "3")
      updatedSamples <- redis.configGet("maxmemory-samples")
      _ <- IO(assertEquals(updatedSamples.get("maxmemory-samples"), Some("3")))
      _ <- originalSamples.get("maxmemory-samples").traverse_(v => redis.configSet("maxmemory-samples", v))
      _ <- redis.configResetStat
      // CONFIG REWRITE errors when the server wasn't started with a config file, which is how
      // the test containers run — exercised for coverage, outcome not asserted either way.
      _ <- redis.configRewrite.attempt.void
      // client admin
      clients <- redis.clientList
      _ <- IO(assert(clients.nonEmpty))
      ownId <- redis.getClientId()
      clientsById <- redis.clientList(ClientListArgs.ByIds(List(ownId)))
      _ <- IO(assert(clientsById.nonEmpty))
      // CLIENT KILL by bogus single address errors ("No such client"); by filter args (a
      // non-existent id) just reports zero matches, which is the safe form to assert on.
      _ <- redis.clientKill("255.255.255.255:1").attempt.void
      killedByFilter <- redis.clientKill(KillArgs(id = Some(Long.MaxValue)))
      _ <- IO(assertEquals(killedByFilter, 0L))
      unblocked <- redis.clientUnblock(Long.MaxValue, UnblockType.Timeout)
      _ <- IO(assertEquals(unblocked, 0L))
      redir <- redis.clientGetRedir
      _ <- IO(assert(redir.isValidLong))
      // CLIENT CACHING requires CLIENT TRACKING to already be on with OPTIN/OPTOUT - now that
      // clientTracking is wrapped, this is a real success path rather than an expected error.
      _ <- redis.clientTracking(ClientTrackingArgs(enabled = true, optIn = true))
      trackingInfo <- redis.clientTrackingInfo
      _ <- IO(assert(trackingInfo.flags.contains(TrackingFlag.On)))
      _ <- IO(assert(trackingInfo.flags.contains(TrackingFlag.OptIn)))
      _ <- redis.clientCaching(true)
      _ <- redis.clientTracking(ClientTrackingArgs(enabled = false))
      offTrackingInfo <- redis.clientTrackingInfo
      _ <- IO(assert(offTrackingInfo.flags.contains(TrackingFlag.Off)))
      _ <- redis.clientNoTouch(true)
      _ <- redis.clientNoTouch(false)
      _ <- redis.clientNoEvict(true)
      _ <- redis.clientNoEvict(false)
      // maintenance
      existingKeyUsage <- redis.memoryUsage("age")
      _ <- IO(assert(existingKeyUsage.exists(_ > 0)))
      missingKeyUsage <- redis.memoryUsage("no-such-key-for-memory-usage")
      _ <- IO(assert(missingKeyUsage.isEmpty))
      // save/bgSave/bgRewriteAof all hold Redis's single persistence lock — calling more than
      // one back-to-back reliably errors with "Background save already in progress" on a fresh
      // fork. Only bgSave is exercised here as representative coverage.
      _ <- redis.bgSave
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
        .recoverWith { case e =>
          fail(s"[Error] - ${e.getMessage}")
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
          (redis.get(key2), redis.get(key3)).mapN { case (x, y) =>
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
      // SCRIPT KILL only succeeds while a script is actually blocking the server; with nothing
      // running, Redis rejects it — that's the realistic case to assert, not a happy path we can't
      // safely trigger from a single-threaded test without actually hanging the server.
      killAttempt <- redis.scriptKill.attempt
      _ <- IO(
             assert(
               killAttempt.left.exists { ex =>
                 ex.isInstanceOf[RedisCommandExecutionException] &&
                 ex.getMessage.startsWith("NOTBUSY")
               }
             )
           )
    } yield ()
  }

  def scriptingLuaExtensionsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    import dev.profunktor.redis4cats.extensions.luaScripting._

    for {
      // hsetAndExpire.lua is an example script
      hsetAndExpire <- LuaScript.loadFromResources[IO](redis)("hsetAndExpire.lua")

      _ <- redis.hGet(key = "luaExt", field = "x").map(assertEquals(_, None))
      _ <- redis
             .evalLua(
               hsetAndExpire,
               ScriptOutputType.Integer[String],
               keys = List("luaExt"),
               values = List("x", "42", "10")
             )
             .map(assertEquals(_, 1L, "1 field, 'x', should be set for key=luaExt"))
      _ <- redis.hGet(key = "luaExt", field = "x").map(assertEquals(_, "42".some))
      firstTtl <- redis.ttl("luaExt")
      _ <- IO(assert(firstTtl.map(_.toSeconds).exists(ttl => ttl > 0 && ttl <= 10)))

      _ <- redis
             .evalLua(
               hsetAndExpire,
               ScriptOutputType.Integer[String],
               keys = List("luaExt"),
               values = List("y", "84", "20")
             )
             .map(assertEquals(_, 1L, "1 field, 'y', should be set for key=luaExt"))
      _ <- redis.hGet(key = "luaExt", field = "y").map(assertEquals(_, "84".some))
      secondTtl <- redis.ttl("luaExt")
      _ <- IO(assert(secondTtl.map(_.toSeconds).exists(ttl => ttl > 0 && ttl <= 20)))
      _ <- IO(assert(firstTtl < secondTtl))
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
      _ <- redis.functionLoad(myFunc).recover { case _: RedisCommandExecutionException => "" }
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

  def streamsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] =
    for {
      // Empty stream
      len <- redis.xLen("testStream")
      _ <- IO(assert(len == 0, "empty stream should have no length"))
      messages <- redis.xRange("testStream", start = XRangePoint.Unbounded, end = XRangePoint.Unbounded)
      _ <- IO(assert(messages.isEmpty, "empty stream should have no messages"))
      messages <- redis.xRevRange("testStream", start = XRangePoint.Unbounded, end = XRangePoint.Unbounded)
      _ <- IO(assert(messages.isEmpty, "empty stream should have no messages"))
      messages <- redis.xRead(Set(XReadOffsets.All("testStream")))
      _ <- IO(assert(messages.isEmpty, "empty stream should have no messages"))

      // Write to stream
      messageId1 <- redis.xAdd("testStream", body = Map("1" -> "a"))
      messageId2 <- redis.xAdd("testStream", body = Map("2" -> "b"))
      messageId3 <- redis.xAdd("testStream", body = Map("3" -> "c"))
      messageId4 <- redis.xAdd("testStream", body = Map("4" -> "d"))
      message1 = StreamMessage(messageId1, "testStream", Map("1" -> "a"))
      message4 = StreamMessage(messageId4, "testStream", Map("4" -> "d"))
      allMessages = List(
                      message1,
                      StreamMessage(messageId2, "testStream", Map("2" -> "b")),
                      StreamMessage(messageId3, "testStream", Map("3" -> "c")),
                      message4
                    )
      _ = allMessages

      // Read from stream
      len <- redis.xLen("testStream")
      _ <- IO(assert(len == 4, "stream should have 4 entries"))
      messages <- redis.xRange("testStream", start = XRangePoint.Unbounded, end = XRangePoint.Unbounded)
      _ <- IO(assert(messages === allMessages))
      messages <- redis.xRevRange("testStream", start = XRangePoint.Unbounded, end = XRangePoint.Unbounded)
      _ <- IO(assert(messages === allMessages.reverse))
      messages <- redis.xRead(Set(XReadOffsets.All("testStream")))
      _ <- IO(assert(messages === allMessages))
      messages <- redis.xRange(
                    key = "testStream",
                    start = XRangePoint.Exclusive(messageId3.value),
                    end = XRangePoint.Unbounded
                  )
      _ <- IO(assert(messages === List(message4), "Only message 4 should be visible when reading after message 3"))
      messages <- redis.xRead(Set(XReadOffsets.Custom("testStream", messageId3.value)))
      _ <- IO(assert(messages === List(message4), "Only message 4 should be visible when reading after message 3"))
      messages <- redis.xRead(Set(XReadOffsets.Latest("testStream")))
      _ <- IO(assert(messages.isEmpty, "no messages when reading after the last message"))

      // XINFO STREAM on a non-empty stream
      info <- redis.xInfoStream("testStream")
      _ <- IO(assertEquals(info.length, 4L))
      _ <- IO(assertEquals(info.groups, 0L))
      _ <- IO(assertEquals(info.lastGeneratedId, messageId4))
      _ <- IO(assertEquals(info.entriesAdded, 4L))
      _ <- IO(assertEquals(info.firstEntry, Some(message1)))
      _ <- IO(assertEquals(info.lastEntry, Some(message4)))

      // XCFGSET - idempotent-publish config takes effect and is reflected back by XINFO STREAM
      _ <- redis.xCfgSet("testStream", XCfgSetArgs(idempotencyMaxSize = Some(500), idempotencyDuration = Some(60000)))
      infoAfterCfgSet <- redis.xInfoStream("testStream")
      _ <- IO(assertEquals(infoAfterCfgSet.extra.get("idmp-maxsize"), Some("500")))
      _ <- IO(assertEquals(infoAfterCfgSet.extra.get("idmp-duration"), Some("60000")))

      // XDELEX - like XDEL, but with control over consumer-group PEL references (irrelevant here, no groups yet)
      delExResult <- redis.xDelEx("testStream", StreamDeletionPolicy.KeepReferences, messageId1.value)
      _ <- IO(assertEquals(delExResult, List(StreamEntryDeletionResult.Deleted)))
      delExMissing <- redis.xDelEx("testStream", StreamDeletionPolicy.KeepReferences, messageId1.value)
      _ <- IO(assertEquals(delExMissing, List(StreamEntryDeletionResult.NotFound)))
      len <- redis.xLen("testStream")
      _ <- IO(assert(len == 3, "stream should have 3 entries after xdelex"))

      // Delete from stream
      _ <- redis.xTrim("testStream", XTrimArgs(XTrimArgs.Strategy.MAXLEN(2)))
      len <- redis.xLen("testStream")
      _ <- IO(assert(len == 2, "stream should have 2 entries after xtrim"))
      _ <- redis.xDel("testStream", messageId3.value, messageId4.value)
      len <- redis.xLen("testStream")
      _ <- IO(assert(len == 0, "stream should have no entries after xdel remaining "))

      // XINFO STREAM on an empty (but existing) stream: first/last entry are absent
      emptyInfo <- redis.xInfoStream("testStream")
      _ <- IO(assertEquals(emptyInfo.length, 0L))
      _ <- IO(assertEquals(emptyInfo.firstEntry, None))
      _ <- IO(assertEquals(emptyInfo.lastEntry, None))
    } yield ()

  def publishAndStatsScenario(redis: RedisCommands[IO, String, String]): IO[Unit] = {
    import dev.profunktor.redis4cats.effect.Log.NoOp._

    val channel1     = RedisChannel("test-publish-channel-1")
    val channel2     = RedisChannel("test-publish-channel-2")
    val shardChannel = RedisChannel("test-shard-channel")

    RedisClient[IO].from("redis://localhost").use { client =>
      PubSub.mkPubSubConnection[IO, String, String](client, RedisCodec.Utf8).use { pubSub =>
        for {
          // Test publish when no subscribers exist
          count0 <- redis.publish(channel1, "hello")
          _ <- IO(assertEquals(count0, 0L, "publish should return 0 when no subscribers exist"))

          // Start subscription to channel1
          subscription1 <- pubSub.subscribe(channel1).compile.drain.start
          _ <- IO.sleep(200.millis) // Wait for subscription to be established

          // Test publish with one subscriber
          count1 <- redis.publish(channel1, "message1")
          _ <- IO(assertEquals(count1, 1L, "publish should return 1 when one subscriber exists"))

          // Test numPat - should be 0 since we have no pattern subscriptions
          patCount <- redis.numPat
          _ <- IO(assertEquals(patCount, 0L, "numPat should be 0 when no pattern subscriptions exist"))

          // Test pubSubChannels - list all active channels
          channels <- redis.pubSubChannels
          _ <- IO(assert(channels.contains(channel1), "pubSubChannels should include channel1"))

          // Test pubSubSubscriptions for a single channel
          sub1Option <- redis.pubSubSubscriptions(channel1)
          _ <- IO(assert(sub1Option.exists(_.number == 1L), "channel1 should have 1 subscriber"))

          // Test pubSubSubscriptions for multiple channels
          subs <- redis.pubSubSubscriptions(List(channel1, channel2))
          _ <-
            IO(assert(subs.exists(s => s.channel == channel1 && s.number == 1L), "channel1 should have 1 subscriber"))
          _ <-
            IO(assert(subs.exists(s => s.channel == channel2 && s.number == 0L), "channel2 should have 0 subscribers"))

          // Test numSub - returns subscription info for specified channels (requires NonEmptyList)
          numSubResult <- redis.numSub(NonEmptyList.of(channel1, channel2))
          _ <- IO(
                 assert(
                   numSubResult.exists(s => s.channel == channel1 && s.number == 1L),
                   "numSub should include channel1 with 1 subscriber"
                 )
               )
          _ <- IO(
                 assert(
                   numSubResult.exists(s => s.channel == channel2 && s.number == 0L),
                   "numSub should include channel2 with 0 subscribers"
                 )
               )

          // Start another subscription to channel1
          subscription2 <- pubSub.subscribe(channel1).compile.drain.start
          _ <- IO.sleep(200.millis) // Wait for subscription to be established

          // Test publish with two subscribers
          count2 <- redis.publish(channel1, "message2")
          _ <-
            IO(
              assertEquals(
                count2,
                1L,
                "publish should still return 1 (Redis counts unique subscriptions, not consumers)"
              )
            )

          // Subscribe to channel2
          subscription3 <- pubSub.subscribe(channel2).compile.drain.start
          _ <- IO.sleep(200.millis)

          // Test pubSubChannels again
          channels2 <- redis.pubSubChannels
          _ <-
            IO(assert(channels2.contains(channel1) && channels2.contains(channel2), "both channels should be active"))

          // Test pubSubSubscriptions for both channels
          subs2 <- redis.pubSubSubscriptions(List(channel1, channel2))
          _ <-
            IO(assert(subs2.exists(s => s.channel == channel1 && s.number == 1L), "channel1 should have 1 subscriber"))
          _ <-
            IO(assert(subs2.exists(s => s.channel == channel2 && s.number == 1L), "channel2 should have 1 subscriber"))

          // Test spublish (shard publish)
          // Note: In a non-cluster setup, spublish should behave similarly to publish
          shardCount <- redis.spublish(shardChannel, "shard-message").attempt
          _ <- IO(
                 assert(
                   shardCount.isRight || shardCount.left
                     .exists(_.getMessage.contains("only supported in cluster mode")),
                   "spublish should work or fail with cluster-only error"
                 )
               )

          // Test pubSubShardChannels
          shardChannels <- redis.pubSubShardChannels.attempt
          _ <- IO(
                 assert(
                   shardChannels.isRight || shardChannels.left.exists(
                     _.getMessage.contains("only supported in cluster mode")
                   ),
                   "pubSubShardChannels should work or fail with cluster-only error"
                 )
               )

          // Test shardNumSub
          shardSubs <- redis.shardNumSub(List(shardChannel)).attempt
          _ <- IO(
                 assert(
                   shardSubs.isRight || shardSubs.left.exists(_.getMessage.contains("only supported in cluster mode")),
                   "shardNumSub should work or fail with cluster-only error"
                 )
               )

          // Clean up subscriptions
          _ <- subscription1.cancel
          _ <- subscription2.cancel
          _ <- subscription3.cancel
          _ <- IO.sleep(100.millis)

          // Verify channels are no longer active after unsubscribe
          channels3 <- redis.pubSubChannels
          _ <- IO(
                 assert(
                   !channels3.contains(channel1) && !channels3.contains(channel2),
                   "channels should not be active after canceling subscriptions"
                 )
               )
        } yield ()
      }
    }
  }

}
