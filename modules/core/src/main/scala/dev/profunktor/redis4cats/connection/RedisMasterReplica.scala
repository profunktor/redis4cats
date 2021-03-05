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

import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.JavaConversions._
import dev.profunktor.redis4cats.config.Redis4CatsConfig
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.{ JRFuture, Log, RedisExecutor }
import io.lettuce.core.masterreplica.{ MasterReplica, StatefulRedisMasterReplicaConnection }
import io.lettuce.core.{ ClientOptions, ReadFrom => JReadFrom }

/**
  * It encapsulates an underlying `MasterReplica` connection
  */
sealed abstract case class RedisMasterReplica[K, V] private (underlying: StatefulRedisMasterReplicaConnection[K, V])

object RedisMasterReplica {

  private[redis4cats] def acquireAndRelease[F[_]: Concurrent: ContextShift: RedisExecutor: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V],
      readFrom: Option[JReadFrom],
      uris: RedisURI*
  ): (F[RedisMasterReplica[K, V]], RedisMasterReplica[K, V] => F[Unit]) = {

    val acquire: F[RedisMasterReplica[K, V]] = {

      val connection: F[RedisMasterReplica[K, V]] =
        JRFuture
          .fromCompletableFuture[F, StatefulRedisMasterReplicaConnection[K, V]](
            F.delay {
              MasterReplica.connectAsync[K, V](client.underlying, codec.underlying, uris.map(_.underlying).asJava)
            }
          )
          .map(new RedisMasterReplica(_) {})

      readFrom.fold(connection)(rf => connection.flatMap(c => F.delay(c.underlying.setReadFrom(rf)) *> c.pure[F]))
    }

    val release: RedisMasterReplica[K, V] => F[Unit] = connection =>
      F.info(s"Releasing Redis Master/Replica connection: ${connection.underlying}") *>
          JRFuture.fromCompletableFuture(F.delay(connection.underlying.closeAsync())).void

    (acquire, release)
  }

  class MasterReplicaPartiallyApplied[F[_]: Concurrent: ContextShift: Log] {

    /**
      * Creates a [[RedisMasterReplica]]
      *
      * It will also create an underlying [[RedisClient]] with default client options to
      * establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * val conn: Resource[IO, RedisMasterReplica[String, String]] =
      *   Resource.liftF(RedisURI.make[IO](redisURI)).flatMap { uri =>
      *     RedisMasterReplica[IO].make(RedisCodec.Utf8, uri)(Some(ReadFrom.MasterPreferred))
      *   }
      * }}}
      */
    def make[K, V](
        codec: RedisCodec[K, V],
        uris: RedisURI*
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplica[K, V]] =
      Resource
        .liftF(F.delay(ClientOptions.create()))
        .flatMap(withOptions(codec, _, Redis4CatsConfig(), uris: _*)(readFrom))

    /**
      * Creates a [[RedisMasterReplica]] using the supplied client options
      *
      * It will also create an underlying [[RedisClient]] using the supplied client options
      * to establish connection with Redis.
      *
      * Example:
      *
      * {{{
      * val conn: Resource[IO, RedisMasterReplica[String, String]] =
      *   for {
      *     ops <- Resource.liftF(F.delay(ClientOptions.create()))
      *     uri <- Resource.liftF(RedisURI.make[IO](redisURI))
      *     mrc <- RedisMasterReplica[IO].withOptions(RedisCodec.Utf8, ops, uri)(Some(ReadFrom.MasterPreferred))
      *   } yield mrc
      * }}}
      */
    def withOptions[K, V](
        codec: RedisCodec[K, V],
        opts: ClientOptions,
        config: Redis4CatsConfig,
        uris: RedisURI*
    )(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplica[K, V]] =
      RedisExecutor.make[F].flatMap { implicit redisExecutor =>
        Resource.liftF(RedisClient.acquireAndReleaseWithoutUri[F](opts, config)).flatMap {
          case (acquireClient, releaseClient) =>
            Resource.make(acquireClient)(releaseClient).flatMap { client =>
              val (acquire, release) = acquireAndRelease(client, codec, readFrom, uris: _*)
              Resource.make(acquire)(release)
            }
        }
      }

  }

  def apply[F[_]: Concurrent: ContextShift: Log]: MasterReplicaPartiallyApplied[F] =
    new MasterReplicaPartiallyApplied[F]

  def fromUnderlying[K, V](underlying: StatefulRedisMasterReplicaConnection[K, V]) =
    new RedisMasterReplica[K, V](underlying) {}

}
