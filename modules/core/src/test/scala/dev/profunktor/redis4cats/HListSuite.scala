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

import cats.effect.IO
import hlist._
import munit.FunSuite

class HListSuite extends FunSuite {

  def proof[T <: HList, R <: HList](xs: T)(implicit w: Witness.Aux[T, R]): R =
    xs.asInstanceOf[w.R] // can return anything, we only care about the types here

  test("HList and Witness") {
    val actions = IO.unit :: IO.pure("hi") :: HNil

    proof(actions): Unit :: String :: HNil

    compileErrors("proof(actions): Unit :: Int :: HNil")
  }

  test("Unapply HLists (deconstruct)") {
    val hl = () :: "hi" :: 123 :: true :: 's' :: 55 :: HNil

    val () ~: s ~: n1 ~: b ~: c ~: n2 ~: HNil = hl

    assert(s == "hi")
    assert(n1 == 123)
    assert(b == true)
    assert(c == 's')
    assert(n2 == 55)
  }

  test("Filter out values") {
    val unit = ()
    val hl   = unit :: "hi" :: 33 :: unit :: false :: 's' :: unit :: HNil

    val s ~: n ~: b ~: c ~: HNil = hl.filterUnit

    assert(s == "hi")
    assert(n == 33)
    assert(b == false)
    assert(c == 's')
  }

  test("Conversion from standard list") {
    val lt = List("a", "b", "c")
    val hl = "a" :: "b" :: "c" :: HNil
    assert(hl == HList.fromList(lt))
    assertEquals(hl.size, lt.size)

    val el = List.empty[Int]
    assert(HNil == HList.fromList(el))
    assertEquals(HNil.size, el.size)

    // Temporary hack to convert from dynamic list until we properly fix it
    val cmd = HList.fromList(List(IO.unit, IO.pure("hi"))).asInstanceOf[IO[Unit] :: IO[String] :: HNil]
    proof(cmd)
  }

}
