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

package dev.profunktor.redis4cats.connection

import cats.ApplicativeThrow
import cats.implicits.toBifunctorOps
import io.lettuce.core.{ RedisURI => JRedisURI }

import scala.util.Try
import scala.util.control.NoStackTrace

sealed abstract class RedisURI private (val underlying: JRedisURI)

object RedisURI {
  def make[F[_]: ApplicativeThrow](uri: => String): F[RedisURI] =
    ApplicativeThrow[F].catchNonFatal(new RedisURI(JRedisURI.create(uri)) {})

  def fromUnderlying(j: JRedisURI): RedisURI = new RedisURI(j) {}

  def fromString(uri: String): Either[InvalidRedisURI, RedisURI] =
    Try(JRedisURI.create(uri)).toEither.bimap(InvalidRedisURI(uri, _), new RedisURI(_) {})

  def unsafeFromString(uri: String): RedisURI = new RedisURI(JRedisURI.create(uri)) {}
}

final case class InvalidRedisURI(uri: String, throwable: Throwable) extends NoStackTrace {
  override def getMessage: String = Option(throwable.getMessage).getOrElse(s"Invalid Redis URI: $uri")
}
