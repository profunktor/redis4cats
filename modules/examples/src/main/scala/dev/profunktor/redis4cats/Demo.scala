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

import cats.effect.IO
import dev.profunktor.redis4cats.codecs.Codecs
import dev.profunktor.redis4cats.codecs.splits._
import dev.profunktor.redis4cats.data.RedisCodec

object Demo {

  val redisURI: String                        = "redis://localhost"
  val redisClusterURI: String                 = "redis://localhost:30001"
  val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8
  val longCodec: RedisCodec[String, Long]     = Codecs.derive(stringCodec, stringLongEpi)

  def putStrLn[A](a: A): IO[Unit] = IO(println(a))

}
