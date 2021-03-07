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

class HListSpec extends FunSuite {

  test("HList and Witness") {
    def proof[T <: HList, R <: HList](xs: T)(implicit w: Witness.Aux[T, R]): R =
      xs.asInstanceOf[w.R] // can return anything, we only care about the types here

    val actions = IO.unit :: IO.pure("hi") :: HNil

    proof(actions): Unit :: String :: HNil

    compileErrors("proof(actions): Unit :: Int :: HNil")
  }

  test("Unapply HLists (deconstruct)") {
    val hl = () :: "hi" :: 123 :: true :: 's' :: 55 :: HNil

    val u ~: s ~: n1 ~: b ~: c ~: n2 ~: HNil = hl

    assert(u.isInstanceOf[Unit])
    assert(s.isInstanceOf[String])
    assert(n1.isInstanceOf[Int])
    assert(b.isInstanceOf[Boolean])
    assert(c.isInstanceOf[Char])
    assert(n2.isInstanceOf[Int])
  }

  test("Filter out values") {
    val unit = ()
    val hl   = unit :: "hi" :: 33 :: unit :: false :: 's' :: unit :: HNil

    val s ~: n ~: b ~: c ~: HNil = hl.filterUnit

    assert(s.isInstanceOf[String])
    assert(n.isInstanceOf[Int])
    assert(b.isInstanceOf[Boolean])
    assert(c.isInstanceOf[Char])
  }

  test("Conversion from standard list") {
    val lt = List("a", "b", "c")
    val hl = "a" :: "b" :: "c" :: HNil
    assertEquals[HList, HList](hl, HList.fromList(lt))
    assertEquals(hl.size, lt.size)

    val el = List.empty[Int]
    assertEquals[HList, HList](HNil, HList.fromList(el))
    assertEquals(HNil.size, el.size)
  }

}
