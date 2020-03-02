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

package dev.profunktor.redis4cats.codecs

import cats.tests.CatsSuite
import dev.profunktor.redis4cats.codecs.splits._

import scala.util.Try

class SplitMorphismTest extends CatsSuite {
  import TestSplitEpiInstances._

  checkAll("IntDoubleInt", SplitMonoTests(intDoubleMono).splitMono)
  checkAll("IntString", SplitMonoTests(intStringMono).splitMono)

  checkAll("DoubleInt", SplitEpiTests(doubleIntEpi).splitEpi)
  checkAll("StringDouble", SplitEpiTests(stringDoubleEpi).splitEpi)
  checkAll("StringLong", SplitEpiTests(stringLongEpi).splitEpi)
  checkAll("StringInt", SplitEpiTests(stringIntEpi).splitEpi)
}

object TestSplitEpiInstances {

  // Just proving that these form a split monomorphism and won't pass the laws of epimorphisms
  val intDoubleMono: SplitMono[Int, Double] =
    SplitMono(_.toDouble, _.toInt)

  val intStringMono: SplitMono[Int, String] =
    SplitMono(_.toString, s => Try(s.toInt).getOrElse(0))

  // Epimorphisms
  val doubleIntEpi: SplitEpi[Double, Int] =
    SplitEpi(s => Try(s.toInt).getOrElse(0), s => Try(s.toDouble).getOrElse(0))

}
