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

package com.github.gvolpe.fs2redis.interpreter.connection

import cats.effect.{Concurrent, Resource}
import cats.syntax.all._
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.{JRFuture, Log}
import fs2.Stream
import io.lettuce.core.{ReadFrom, RedisURI}
import io.lettuce.core.masterslave.{MasterSlave, StatefulRedisMasterSlaveConnection}

import scala.collection.JavaConverters._

// format: off
object Fs2RedisMasterSlave {

  private[fs2redis] def acquireAndRelease[F[_], K, V](client: Fs2RedisClient,
                                                      codec: Fs2RedisCodec[K, V],
                                                      readFrom: Option[ReadFrom],
                                                      uris: RedisURI*)
                                                     (implicit F: Concurrent[F], L: Log[F])
  : (F[Fs2RedisMasterSlaveConnection[K, V]], Fs2RedisMasterSlaveConnection[K, V] => F[Unit]) = {

    val acquire: F[Fs2RedisMasterSlaveConnection[K, V]] = {

      val connection: F[Fs2RedisMasterSlaveConnection[K, V]] =
        JRFuture.fromCompletableFuture[F, StatefulRedisMasterSlaveConnection[K, V]] {
          F.delay { MasterSlave.connectAsync[K, V](client.underlying, codec.underlying, uris.asJava) }
        }.map(DefaultRedisMasterSlaveConnection.apply)

      readFrom.fold(connection)(rf => connection.flatMap(c => F.delay(c.underlying.setReadFrom(rf)) *> c.pure[F]))
    }

    val release: Fs2RedisMasterSlaveConnection[K, V] => F[Unit] = connection =>
      L.info(s"Releasing Redis Master/Slave connection: ${connection.underlying}") *>
        JRFuture.fromCompletableFuture(F.delay(connection.underlying.closeAsync())).void

    (acquire, release)
  }

  def apply[F[_]: Concurrent: Log, K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
      readFrom: Option[ReadFrom] = None): Resource[F, Fs2RedisMasterSlaveConnection[K, V]] = {
    val (acquireClient, releaseClient) = Fs2RedisClient.acquireAndReleaseWithoutUri[F]
    Resource.make(acquireClient)(releaseClient).flatMap { client =>
      val (acquire, release) = acquireAndRelease(client, codec, readFrom, uris: _*)
      Resource.make(acquire)(release)
    }
  }

  def stream[F[_]: Concurrent: Log, K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
      readFrom: Option[ReadFrom] = None): Stream[F, Fs2RedisMasterSlaveConnection[K, V]] = 
    Stream.resource(apply(codec, uris: _*)(readFrom))
  
}
