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

import cats.effect.IO
import com.github.gvolpe.fs2redis.codecs.Codecs
import com.github.gvolpe.fs2redis.codecs.splits._
import com.github.gvolpe.fs2redis.domain.{ DefaultRedisCodec, Fs2RedisCodec }
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

object Demo {

  implicit val epi: SplitEpi[String, Long] = stringLongEpi

  val redisURI: RedisURI                         = RedisURI.create("redis://localhost")
  val redisClusterURI: RedisURI                  = RedisURI.create("redis://localhost:30001")
  val stringCodec: Fs2RedisCodec[String, String] = DefaultRedisCodec(StringCodec.UTF8)
  val longCodec: Fs2RedisCodec[String, Long]     = Codecs.derive[String, Long](stringCodec)

  def putStrLn[A](a: A): IO[Unit] = IO(println(a))

}
