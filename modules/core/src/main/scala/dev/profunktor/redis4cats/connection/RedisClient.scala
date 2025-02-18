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

import java.util.concurrent.TimeUnit

import cats.{ Apply, MonadThrow }
import cats.effect.kernel.{ Resource, Sync }
import cats.syntax.all._
import dev.profunktor.redis4cats.config.Redis4CatsConfig
import dev.profunktor.redis4cats.effect._
import io.lettuce.core.{ ClientOptions, RedisClient => JRedisClient, RedisURI => JRedisURI }

sealed abstract case class RedisClient private (underlying: JRedisClient, uri: RedisURI)

object RedisClient {

  private[redis4cats] def acquireAndRelease[F[_]: Apply: FutureLift: Log](
      uri: => RedisURI,
      opts: ClientOptions,
      config: Redis4CatsConfig
  ): (F[RedisClient], RedisClient => F[Unit]) = {
    val acquire: F[RedisClient] = FutureLift[F].delay {
      val jClient: JRedisClient =
        config.clientResources.fold(JRedisClient.create(uri.underlying))(JRedisClient.create(_, uri.underlying))
      jClient.setOptions(opts)
      new RedisClient(jClient, uri) {}
    }

    val release: RedisClient => F[Unit] = client =>
      Log[F].info(s"Releasing Redis connection: $uri") *>
        FutureLift[F]
          .lift(
            client.underlying.shutdownAsync(
              config.shutdown.quietPeriod.toNanos,
              config.shutdown.timeout.toNanos,
              TimeUnit.NANOSECONDS
            )
          )
          .void

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseWithoutUri[F[_]: FutureLift: Log: MonadThrow](
      opts: ClientOptions,
      config: Redis4CatsConfig
  ): F[(F[RedisClient], RedisClient => F[Unit])] =
    FutureLift[F]
      .delay(RedisURI.fromUnderlying(new JRedisURI()))
      .map(uri => acquireAndRelease(uri, opts, config))

  class RedisClientPartiallyApplied[F[_]: MkRedis: MonadThrow] {
    implicit val fl: FutureLift[F] = MkRedis[F].futureLift
    implicit val log: Log[F]       = MkRedis[F].log

    /** Creates a [[RedisClient]] with default options.
      *
      * Example:
      *
      * {{{
      * RedisClient[IO].from("redis://localhost")
      * }}}
      */
    def from(strUri: => String)(
        implicit F: Sync[F]
    ): Resource[F, RedisClient] =
      Resource.eval(RedisURI.make[F](strUri)).flatMap(this.fromUri(_))

    /** Creates a [[RedisClient]] with default options from a validated URI.
      *
      * Example:
      *
      * {{{
      * for {
      *   uri <- Resource.eval(RedisURI.make[F]("redis://localhost"))
      *   cli <- RedisClient[IO].fromUri(uri)
      * } yield cli
      * }}}
      *
      * You may prefer to use [[from]] instead, which takes a raw string.
      */
    def fromUri(uri: => RedisURI)(
        implicit F: Sync[F]
    ): Resource[F, RedisClient] =
      Resource.eval(Sync[F].delay(ClientOptions.create())).flatMap(this.custom(uri, _))

    /** Creates a [[RedisClient]] with the supplied options.
      *
      * Example:
      *
      * {{{
      * for {
      *   ops <- Resource.eval(Sync[F].delay(ClientOptions.create())) // configure timeouts, etc
      *   cli <- RedisClient[IO].withOptions("redis://localhost", ops)
      * } yield cli
      * }}}
      */
    def withOptions(
        strUri: => String,
        opts: ClientOptions
    ): Resource[F, RedisClient] =
      Resource.eval(RedisURI.make[F](strUri)).flatMap(this.custom(_, opts))

    /** Creates a [[RedisClient]] with the supplied options from a validated URI.
      *
      * Example:
      *
      * {{{
      * for {
      *   uri <- Resource.eval(RedisURI.make[F]("redis://localhost"))
      *   ops <- Resource.eval(Sync[F].delay(ClientOptions.create())) // configure timeouts, etc
      *   cli <- RedisClient[IO].custom(uri, ops)
      * } yield cli
      * }}}
      *
      * Additionally, it can take a [[dev.profunktor.redis4cats.config.Redis4CatsConfig]] to configure the shutdown
      * timeouts, for example. However, you don't need to worry about this in most cases.
      *
      * {{{
      * RedisClient[IO].custom(uri, ops, Redis4CatsConfig())
      * }}}
      *
      * If not supplied, sane defaults will be used.
      */
    def custom(
        uri: => RedisURI,
        opts: ClientOptions,
        config: Redis4CatsConfig = Redis4CatsConfig()
    ): Resource[F, RedisClient] = {
      val (acquire, release) = acquireAndRelease(uri, opts, config)
      Resource.make(acquire)(release)
    }
  }

  def apply[F[_]: MkRedis: MonadThrow]: RedisClientPartiallyApplied[F] = new RedisClientPartiallyApplied[F]

  def fromUnderlyingWithUri(underlying: JRedisClient, uri: RedisURI): RedisClient =
    new RedisClient(underlying, uri) {}

}
