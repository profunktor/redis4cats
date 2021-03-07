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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects._
import io.lettuce.core.GeoArgs

// format: off
trait GeoCommands[F[_], K, V] extends GeoGetter[F, K, V] with GeoSetter[F, K, V]

trait GeoGetter[F[_], K, V] {
  def geoDist(key: K, from: V, to: V, unit: GeoArgs.Unit): F[Double]
  def geoHash(key: K, values: V*): F[List[Option[String]]]
  def geoPos(key: K, values: V*): F[List[GeoCoordinate]]
  def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit): F[Set[V]]
  def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]]
  def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit): F[Set[V]]
  def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit, args: GeoArgs): F[List[GeoRadiusResult[V]]]
}

trait GeoSetter[F[_], K, V] {
  def geoAdd(key: K, geoValues: GeoLocation[V]*): F[Unit]
  def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit]
  def geoRadius(key: K, geoRadius: GeoRadius, unit: GeoArgs.Unit, storage: GeoRadiusDistStorage[K]): F[Unit]
  def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit, storage: GeoRadiusKeyStorage[K]): F[Unit]
  def geoRadiusByMember(key: K, value: V, dist: Distance, unit: GeoArgs.Unit, storage: GeoRadiusDistStorage[K]): F[Unit]
}
