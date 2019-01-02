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

import cats._
import cats.implicits._
import org.scalacheck.{ Arbitrary, Prop }
import org.scalatest.FunSuite
import org.scalatest.prop.Checkers

import scala.util.Try

// Inspired by http://eed3si9n.com/herding-cats/Isomorphism.html
class IsoTests extends FunSuite with Checkers {
  import TestIsoInstances._

  def arrowsEquality[A: Arbitrary, B: Eq](f: A => B, g: A => B): Prop =
    Prop.forAll { a: A =>
      Eq[B].eqv(f(a), g(a))
    }

  // (iso.from compose iso.to) <-> identity[A]
  def isomorphism[A, B](implicit iso: Iso[A, B]): A => A =
    iso.from compose iso.to

  test("arrows equality") {
    check(arrowsEquality((_: Int) + 2, 2 + (_: Int)))
  }

  test("isomorphism") {
    check(arrowsEquality(isomorphism[Int, Double], identity[Int]))
    check(arrowsEquality(isomorphism[Int, Long], identity[Int]))
    check(arrowsEquality(isomorphism[Int, String], identity[Int]))
  }

}

object TestIsoInstances {

  implicit val intDoubleIso: Iso[Int, Double] =
    new Iso[Int, Double] {
      def to: Int => Double   = _.toDouble
      def from: Double => Int = _.toInt
    }

  implicit val intLongIso: Iso[Int, Long] =
    new Iso[Int, Long] {
      def to: Int => Long   = _.toLong
      def from: Long => Int = _.toInt
    }

  implicit val intStringIso: Iso[Int, String] =
    new Iso[Int, String] {
      def to: Int => String   = _.toString
      def from: String => Int = s => Try(s.toInt).getOrElse(0)
    }

}
