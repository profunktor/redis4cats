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
      def go(ys: HList, res: HList): HList =
        ys match {
          case HNil        => res
          case HCons(h, t) => go(t, h :: res)
        }
      go(this, HNil)
    }

    def size: Int = {
      def go(ys: HList, acc: Int): Int =
        ys match {
          case HNil        => acc
          case HCons(_, t) => go(t, acc + 1)
        }
      go(this, 0)
    }
  }

  final case class HCons[+H, +Tail <: HList](head: H, tail: Tail) extends HList
  case object HNil extends HList

  object HList {
    def fromList[A](list: List[A]): HList = {
      def go(ys: List[A], res: HList): HList =
        ys match {
          case Nil      => res
          case (h :: t) => go(t, h :: res)
        }
      go(list, HNil).reverse
    }

    implicit class HListOps[T <: HList](t: T) {
      def filterUnit[R <: HList](implicit w: Filter.Aux[T, R]): R = {
        def go(ys: HList, res: HList): HList =
          ys match {
            case HNil                                => res
            case HCons(h, t) if h.isInstanceOf[Unit] => go(t, res)
            case HCons(h, t)                         => go(t, h :: res)
          }
        go(t, HNil).reverse.asInstanceOf[w.R]
      }
    }
  }

  object ~: {
    def unapply[H, T <: HList](l: H :: T): Some[(H, T)] = Some((l.head, l.tail))
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

  /*
   * It represents a relationship between a raw list and a
   * filtered one. Mainly used to filter out values of type Unit.
   */
  sealed trait Filter[T <: HList] {
    type R <: HList
  }

  object Filter {
    type Aux[T0 <: HList, R0 <: HList] = Filter[T0] { type R = R0 }

    implicit val hnil: Filter.Aux[HNil, HNil] =
      new Filter[HNil] { type R = HNil }

    implicit def hconsUnit[T <: HList](implicit w: Filter[T]): Filter.Aux[HCons[Unit, T], w.R] =
      new Filter[HCons[Unit, T]] { type R = w.R }

    implicit def hconsNotUnit[A: =!=[Unit, *], T <: HList](implicit w: Filter[T]): Filter.Aux[HCons[A, T], A :: w.R] =
      new Filter[HCons[A, T]] { type R = A :: w.R }
  }

  /**
    * Type inequality
    *
    * Credits: https://stackoverflow.com/a/6929051
    */
  sealed class =!=[A, B]

  object =!= extends NEqualLowPriority {
    implicit def nequal[A, B]: =!=[A, B] = new =!=[A, B]
  }

  trait NEqualLowPriority {
    implicit def equal[A]: =!=[A, A] = sys.error("should not be called")
  }

}
