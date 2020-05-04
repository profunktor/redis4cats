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
    def ::[A](a: A): HCons[A, this.type] = HCons(a, this)

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

  case class HCons[+H, +Tail <: HList](head: H, tail: Tail) extends HList
  case object HNil extends HList

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

  object ~: {
    def unapply[H, T <: HList](l: H :: T): Some[(H, T)] = Some((l.head, l.tail))
  }

}
