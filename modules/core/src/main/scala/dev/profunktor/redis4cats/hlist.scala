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

import scala.annotation.tailrec

/**
  * An heterogeneous list, mainly used to operate on transactions.
  *
  * Highly inspired by Shapeless machinery but very much lightweight.
  */
object hlist {

  type ::[H, T <: HList] = HCons[H, T]
  type HNil              = HNil.type

  sealed trait HList {
    type Prepend[A] <: HList
    def ::[A](a: A): Prepend[A]

    def reverse: HList = {
      @tailrec
      def go(res: HList, ys: HList): HList =
        ys match {
          case HNil        => res
          case HCons(h, t) => go(h :: res, t)
        }
      go(HNil, this)
    }
  }

  case class HCons[H, Tail <: HList](head: H, tail: Tail) extends HList {
    override type Prepend[A] = HCons[A, HCons[H, Tail]]
    override def ::[A](a: A): Prepend[A] = HCons(a, this)
  }

  case object HNil extends HList {
    override type Prepend[A] = HCons[A, HNil]
    override def ::[A](a: A): Prepend[A] = HCons(a, this)
  }

  /**
    * It witnesses a relationship between two HLists.
    *
    * The existing instances model a relationship between an HList comformed
    * of actions F[A] and results A. E.g.:
    *
    * {{{
    * val actions: IO[Unit] :: IO[String] :: HNil = IO.unit :: IO.pure("hi") :: HNil
    * val results: actions.R = () :: "hi" :: HNil
    * }}}
    *
    * A Witness[IO[Unit] :: IO[String] :: HNil] proves that its result type can
    * only be Unit :: String :: HNil.
    *
    * A Witness is sealed to avoid the creation of invalid instances.
    */
  sealed trait Witness[T <: HList] {
    type R <: HList
  }

  object Witness {
    type Aux[T0 <: HList, R0 <: HList] = Witness[T0] { type R = R0 }

    implicit val hnil: Witness.Aux[HNil, HNil] =
      new Witness[HNil] { type R = HNil }

    implicit def hcons[F[_], A, T <: HList](implicit w: Witness[T]): Witness.Aux[HCons[F[A], T], HCons[A, w.R]] =
      new Witness[HCons[F[A], T]] { type R = HCons[A, w.R] }
  }

  /**
    * Below is the `unapply` machinery to deconstruct HLists, useful for
    * mattern matching while eliminating the HNil value.
    *
    * Slightly adapted from Miles Sabin's code posted on SO.
    *
    * Source: https://stackoverflow.com/questions/18468606/extractor-for-a-shapeless-hlist-that-mimics-parser-concatenation
    */
  trait UnapplyRight[L <: HList] {
    type Out
    def apply(l: L): Out
  }

  trait LPUnapplyRight {
    type Aux[L <: HList, Out0] = UnapplyRight[L] { type Out = Out0 }
    implicit def unapplyHCons[H, T <: HList]: Aux[H :: T, Option[(H, T)]] =
      new UnapplyRight[H :: T] {
        type Out = Option[(H, T)]
        def apply(l: H :: T): Out = Option((l.head, l.tail))
      }
  }

  object UnapplyRight extends LPUnapplyRight {
    implicit def unapplyPair[H1, H2]: Aux[H1 :: H2 :: HNil, Option[(H1, H2)]] =
      new UnapplyRight[H1 :: H2 :: HNil] {
        type Out = Option[(H1, H2)]
        def apply(l: H1 :: H2 :: HNil): Out = Option((l.head, l.tail.head))
      }
  }

  object ~: {
    def unapply[L <: HList, Out <: Option[Any]](l: L)(implicit ua: UnapplyRight.Aux[L, Out]): Out = ua(l)
  }

}
