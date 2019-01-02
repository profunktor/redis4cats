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

/**
  * Represents an isomorphism, or invertible arrow, between two types A and B.
  * */
trait Iso[A, B] {
  def to: A => B
  def from: B => A
}

object Iso {
  def apply[A, B](implicit ev: Iso[A, B]): Iso[A, B] = ev
}
