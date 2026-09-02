/*
 * Copyright 2018-2025 ProfunKtor
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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects._
import io.lettuce.core.GeoArgs

// format: off
trait GeoCommands[F[_], K, V] extends GeoGetter[F, K, V] with GeoSetter[F, K, V]

trait GeoGetter[F[_], K, V] {
  def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double]
  def geoHash(key: K, value: V, values: V*): F[List[Option[String]]]
  def geoPos(key: K, value: V, values: V*): F[List[GeoCoordinate]]
  def geoSearch(key: K, ref: GeoSearchReference[V], predicate: GeoSearchPredicate): F[Set[V]]
  def geoSearch(key: K, ref: GeoSearchReference[V], predicate: GeoSearchPredicate, args: GeoArgs): F[List[GeoSearchResult[V]]]
}

trait GeoSetter[F[_], K, V] {
  def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Long]
  def geoSearchStore(destination: K, key: K, ref: GeoSearchReference[V], predicate: GeoSearchPredicate, storeDist: Boolean): F[Long]
  def geoSearchStore(
      destination: K,
      key: K,
      ref: GeoSearchReference[V],
      predicate: GeoSearchPredicate,
      storeDist: Boolean,
      args: GeoStoreArgs
  ): F[Long]
}
