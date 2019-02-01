/*
 * Copyright 2018-2019 Gabriel Volpe
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

package com.github.gvolpe.fs2redis

import io.lettuce.core.GeoArgs

object effects {

  case class Distance(value: Double) extends AnyVal
  case class GeoHash(value: Long) extends AnyVal
  case class Latitude(value: Double) extends AnyVal
  case class Longitude(value: Double) extends AnyVal

  case class GeoLocation[V](lon: Longitude, lat: Latitude, value: V)
  case class GeoRadius(lon: Longitude, lat: Latitude, dist: Distance)

  case class GeoCoordinate(x: Double, y: Double)
  case class GeoRadiusResult[V](value: V, dist: Distance, hash: GeoHash, coordinate: GeoCoordinate)
  case class GeoRadiusKeyStorage[K](key: K, count: Long, sort: GeoArgs.Sort)
  case class GeoRadiusDistStorage[K](key: K, count: Long, sort: GeoArgs.Sort)

  case class Score(value: Double) extends AnyVal
  case class ScoreWithValue[V](score: Score, value: V)
  case class ZRange[V](start: V, end: V)
  case class RangeLimit(offset: Long, count: Long)

}
