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

package dev.profunktor.redis4cats.codecs.splits

// Credits to Rob Norris (@tpolecat) -> https://skillsmatter.com/skillscasts/11626-keynote-pushing-types-and-gazing-at-the-stars
final case class SplitMono[A, B](
    get: A => B,
    reverseGet: B => A
) extends (A => B) {

  def apply(a: A): B = get(a)

  def reverse: SplitEpi[B, A] =
    SplitEpi(reverseGet, get)

  def andThen[C](f: SplitMono[B, C]): SplitMono[A, C] =
    SplitMono(get andThen f.get, f.reverseGet andThen reverseGet)

}
