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

package dev.profunktor.fs2redis.connection

import cats.effect.{ Concurrent, ContextShift, Resource, Sync }
import cats.syntax.all._
import dev.profunktor.fs2redis.domain._
import dev.profunktor.fs2redis.effect.{ JRFuture, Log }
import io.lettuce.core.masterslave.{ MasterSlave, StatefulRedisMasterSlaveConnection }
import io.lettuce.core.{ ReadFrom, RedisURI }

import scala.collection.JavaConverters._

object Fs2RedisMasterSlave {

  private[fs2redis] def acquireAndRelease[F[_]: Concurrent: ContextShift: Log, K, V](
      client: Fs2RedisClient,
      codec: Fs2RedisCodec[K, V],
      readFrom: Option[ReadFrom],
      uris: RedisURI*
  ): (F[Fs2RedisMasterSlaveConnection[K, V]], Fs2RedisMasterSlaveConnection[K, V] => F[Unit]) = {

    val acquire: F[Fs2RedisMasterSlaveConnection[K, V]] = {

      val connection: F[Fs2RedisMasterSlaveConnection[K, V]] =
        JRFuture
          .fromCompletableFuture[F, StatefulRedisMasterSlaveConnection[K, V]] {
            Sync[F].delay { MasterSlave.connectAsync[K, V](client.underlying, codec.underlying, uris.asJava) }
          }
          .map(DefaultRedisMasterSlaveConnection.apply)

      readFrom.fold(connection)(rf => connection.flatMap(c => Sync[F].delay(c.underlying.setReadFrom(rf)) *> c.pure[F]))
    }

    val release: Fs2RedisMasterSlaveConnection[K, V] => F[Unit] = connection =>
      Log[F].info(s"Releasing Redis Master/Slave connection: ${connection.underlying}") *>
        JRFuture.fromCompletableFuture(Sync[F].delay(connection.underlying.closeAsync())).void

    (acquire, release)
  }

  def apply[F[_]: Concurrent: ContextShift: Log, K, V](codec: Fs2RedisCodec[K, V], uris: RedisURI*)(
      readFrom: Option[ReadFrom] = None
  ): Resource[F, Fs2RedisMasterSlaveConnection[K, V]] =
    Resource.liftF(Fs2RedisClient.acquireAndReleaseWithoutUri[F]).flatMap {
      case (acquireClient, releaseClient) =>
        Resource.make(acquireClient)(releaseClient).flatMap { client =>
          val (acquire, release) = acquireAndRelease(client, codec, readFrom, uris: _*)
          Resource.make(acquire)(release)
        }
    }

}
