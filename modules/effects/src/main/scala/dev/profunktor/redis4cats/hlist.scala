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

object hlist {

  type ::[H, T <: HList] = HCons[H, T]
  type HNil              = HNil.type

  sealed trait HList {
    type Prepend[A] <: HList
    def ::[A](a: A): Prepend[A]

    def reverse: HList = {
      def go(res: HList, ys: HList): HList =
        ys match {
          case HNil        => res
          case HCons(h, t) => go(h :: res, t)
        }
      go(this, HNil)
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

  trait Witness[T] {
    type R <: HList
  }

  object Witness {
    type Aux[T0, R0] = Witness[T0] { type R = R0 }

    implicit val hnil: Witness.Aux[HNil, HNil] =
      new Witness[HNil] { type R = HNil }

    implicit def hcons[F[_], A, T <: HList](implicit w: Witness[T]): Witness.Aux[HCons[F[A], T], HCons[A, w.R]] =
      new Witness[HCons[F[A], T]] { type R = HCons[A, w.R] }
  }

}
