/*
 * Copyright 2018-2019 Fs2 Redis
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

package com.github.gvolpe.fs2redis.codecs

import cats.tests.CatsSuite
import com.github.gvolpe.fs2redis.codecs.splits._

import scala.util.Try

class SplitMorphismTest extends CatsSuite {
  import TestSplitEpiInstances._

  checkAll("IntDouble", SplitEpiTests(intDoubleEpi).splitEpi)
  checkAll("IntLong", SplitEpiTests(intLongEpi).splitEpi)
  checkAll("IntString", SplitEpiTests(intStringEpi).splitEpi)
  checkAll("StringInt", SplitEpiTests(stringIntEpi).splitEpi)
}

object TestSplitEpiInstances {

  val intDoubleEpi: SplitEpi[Int, Double] =
    SplitEpi(_.toDouble, _.toInt)

  val intLongEpi: SplitEpi[Int, Long] =
    SplitEpi(_.toLong, _.toInt)

  val intStringEpi: SplitEpi[Int, String] =
    SplitEpi(_.toString, s => Try(s.toInt).getOrElse(0))

  val stringIntEpi: SplitEpi[String, Int] =
    SplitEpi(s => Try(s.toInt).getOrElse(0), _.toString)

}
