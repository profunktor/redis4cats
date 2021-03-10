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

package dev.profunktor.redis4cats.codecs.laws

import cats.laws._
import dev.profunktor.redis4cats.codecs.splits.SplitEpi

final case class SplitEpiLaws[A, B](
    epi: SplitEpi[A, B]
) {

  def identity(b: B): IsEq[B] =
    (epi.get compose epi.reverseGet)(b) <-> b

  def idempotence(a: A): IsEq[A] = {
    val f = epi.reverseGet compose epi.get
    f(a) <-> f(f(a))
  }

}
