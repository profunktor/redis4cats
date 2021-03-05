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

package dev.profunktor.redis4cats.connection

import cats.ApplicativeError
import io.lettuce.core.{ RedisURI => JRedisURI }

sealed abstract case class RedisURI private (underlying: JRedisURI)

object RedisURI {
  def make[F[_]: ApplicativeError[*[_], Throwable]](uri: => String): F[RedisURI] =
    F.catchNonFatal(new RedisURI(JRedisURI.create(uri)) {})

  def fromUnderlying(j: JRedisURI): RedisURI = new RedisURI(j) {}
}
