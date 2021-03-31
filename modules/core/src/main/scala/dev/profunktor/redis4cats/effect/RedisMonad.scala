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

package dev.profunktor.redis4cats.effect

import cats.effect._
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisClusterClient, RedisURI }
import dev.profunktor.redis4cats.config.Redis4CatsConfig
import io.lettuce.core.ClientOptions
import scala.annotation.implicitNotFound

@implicitNotFound(
  "RedisMonad instance not found. You can summon one by having instances for either cats.effect.Async and dev.profunktor.redis4cats.effects.Log in scope"
)
trait RedisMonad[F[_]] {
  def clientFrom(strUri: => String): Resource[F, RedisClient]
  def clientFromUri(uri: => RedisURI): Resource[F, RedisClient]
  def clientWithOptions(strUri: => String, opts: ClientOptions): Resource[F, RedisClient]
  def clientCustom(
      uri: => RedisURI,
      opts: ClientOptions,
      config: Redis4CatsConfig = Redis4CatsConfig()
  ): Resource[F, RedisClient]

  def clusterClient(uri: RedisURI*): Resource[F, RedisClusterClient]

  def newExecutor: Resource[F, RedisExecutor[F]]
  def futureLift: FutureLift[F]
  def log: Log[F]
}

object RedisMonad {
  def apply[F[_]: RedisMonad]: RedisMonad[F] = implicitly

  implicit def forAsync[F[_]: Async: Log]: RedisMonad[F] =
    new RedisMonad[F] {
      def clientFrom(strUri: => String): Resource[F, RedisClient] =
        RedisClient[F].from(strUri)

      def clientFromUri(uri: => RedisURI): Resource[F, RedisClient] =
        RedisClient[F].fromUri(uri)

      def clientWithOptions(strUri: => String, opts: ClientOptions): Resource[F, RedisClient] =
        RedisClient[F].withOptions(strUri, opts)

      def clientCustom(
          uri: => RedisURI,
          opts: ClientOptions,
          config: Redis4CatsConfig = Redis4CatsConfig()
      ): Resource[F, RedisClient] =
        RedisClient[F].custom(uri, opts, config)

      def clusterClient(uri: RedisURI*): Resource[F, RedisClusterClient] =
        RedisClusterClient[F](uri: _*)

      def newExecutor: Resource[F, RedisExecutor[F]] =
        RedisExecutor.make[F]

      def futureLift: FutureLift[F] = implicitly

      def log: Log[F] = implicitly
    }

}
