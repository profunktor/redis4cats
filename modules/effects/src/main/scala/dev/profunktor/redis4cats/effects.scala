/*
 * Copyright 2018-2019 ProfunKtor
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

import io.lettuce.core.{ GeoArgs, ScriptOutputType => JScriptOutputType }

import scala.concurrent.duration.FiniteDuration

object effects {

  final case class Distance(value: Double) extends AnyVal
  final case class GeoHash(value: Long) extends AnyVal
  final case class Latitude(value: Double) extends AnyVal
  final case class Longitude(value: Double) extends AnyVal

  final case class GeoLocation[V](lon: Longitude, lat: Latitude, value: V)
  final case class GeoRadius(lon: Longitude, lat: Latitude, dist: Distance)

  final case class GeoCoordinate(x: Double, y: Double)
  final case class GeoRadiusResult[V](value: V, dist: Distance, hash: GeoHash, coordinate: GeoCoordinate)
  final case class GeoRadiusKeyStorage[K](key: K, count: Long, sort: GeoArgs.Sort)
  final case class GeoRadiusDistStorage[K](key: K, count: Long, sort: GeoArgs.Sort)

  final case class Score(value: Double) extends AnyVal
  final case class ScoreWithValue[V](score: Score, value: V)
  final case class ZRange[V](start: V, end: V)
  final case class RangeLimit(offset: Long, count: Long)

  sealed trait ScriptOutputType {
    type Return[V]
    private[redis4cats] type Underlying[V]
    private[redis4cats] val outputType: JScriptOutputType
    private[redis4cats] def convert[V](in: Underlying[V]): Return[V]
  }
  object ScriptOutputType {
    import dev.profunktor.redis4cats.JavaConversions._

    case object Integer extends ScriptOutputType {
      type Return[V]                         = Long
      private[redis4cats] type Underlying[V] = java.lang.Long
      override private[redis4cats] val outputType                           = JScriptOutputType.INTEGER
      override private[redis4cats] def convert[_](in: java.lang.Long): Long = Long.box(in)
    }

    case object Value extends ScriptOutputType {
      type Return[V]                         = V
      private[redis4cats] type Underlying[V] = V
      override private[redis4cats] val outputType                               = JScriptOutputType.VALUE
      override private[redis4cats] def convert[V](in: Underlying[V]): Return[V] = in
    }

    case object Multi extends ScriptOutputType {
      type Return[V]                         = List[V]
      private[redis4cats] type Underlying[V] = java.util.List[V]
      override private[redis4cats] val outputType                                 = JScriptOutputType.MULTI
      override private[redis4cats] def convert[V](in: java.util.List[V]): List[V] = in.asScala.toList
    }

    case object Status extends ScriptOutputType {
      type Return[V]                         = Unit
      private[redis4cats] type Underlying[V] = String
      override private[redis4cats] val outputType                   = JScriptOutputType.STATUS
      override private[redis4cats] def convert[_](in: String): Unit = ()
    }
  }

  sealed trait SetArg
  object SetArg {
    sealed trait Existence extends SetArg
    object Existence {

      /** Only set key if it does not exist */
      case object Nx extends Existence

      /** Only set key if it already exists */
      case object Xx extends Existence
    }

    sealed trait Ttl extends SetArg
    object Ttl {

      /** Set Expiration in Millis */
      case class Px(duration: FiniteDuration) extends Ttl

      /** Set Expiration in Seconds */
      case class Ex(duration: FiniteDuration) extends Ttl
    }
  }
  case class SetArgs(existence: Option[SetArg.Existence], ttl: Option[SetArg.Ttl])
  object SetArgs {
    def apply(ex: SetArg.Existence): SetArgs                  = SetArgs(Some(ex), None)
    def apply(ttl: SetArg.Ttl): SetArgs                       = SetArgs(None, Some(ttl))
    def apply(ex: SetArg.Existence, ttl: SetArg.Ttl): SetArgs = SetArgs(Some(ex), Some(ttl))
  }
}
