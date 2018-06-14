/*
 * Copyright 2018 Fs2 Redis
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

package com.github.gvolpe.fs2redis.interpreter

import cats.effect.Async
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.model.{DefaultRedisClient, Fs2RedisClient}
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import io.lettuce.core.{RedisClient, RedisURI}

object Fs2RedisClient {

  def apply[F[_]](uri: RedisURI)(implicit F: Async[F]): Stream[F, Fs2RedisClient] = {
    val acquire = F.delay { DefaultRedisClient(RedisClient.create(uri)) }
    val release: Fs2RedisClient => F[Unit] = client =>
      JRFuture.fromCompletableFuture {
        F.delay(client.underlying.shutdownAsync())
      }.void

    Stream.bracket(acquire)(release)
  }

}
