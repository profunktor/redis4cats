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

package dev.profunktor.redis4cats.effect

import scala.annotation.implicitNotFound

import cats.effect.kernel._
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisClusterClient, RedisURI }
import dev.profunktor.redis4cats.config.Redis4CatsConfig
import dev.profunktor.redis4cats.tx.TxRunner
import io.lettuce.core.ClientOptions

/** MkRedis is a capability trait that abstracts over the creation of RedisClient, RedisClusterClient, among other
  * things.
  *
  * It serves the internal purpose to orchastrate creation of such instances while avoiding impure constraints such as
  * `Async` or `Sync`.
  *
  * Users only need a `MkRedis` constraint and `MonadThrow` to create a `Redis` instance.
  */
@implicitNotFound(
  "MkRedis instance not found. You can summon one by having instances for cats.effect.Async and dev.profunktor.redis4cats.effect.Log in scope"
)
sealed trait MkRedis[F[_]] {
  def clientFrom(strUri: => String): Resource[F, RedisClient]
  def clientFromUri(uri: => RedisURI): Resource[F, RedisClient]
  def clientWithOptions(strUri: => String, opts: ClientOptions): Resource[F, RedisClient]
  def clientCustom(
      uri: => RedisURI,
      opts: ClientOptions,
      config: Redis4CatsConfig = Redis4CatsConfig()
  ): Resource[F, RedisClient]

  def clusterClient(uri: RedisURI*): Resource[F, RedisClusterClient]

  private[redis4cats] def txRunner: Resource[F, TxRunner[F]]
  private[redis4cats] def futureLift: FutureLift[F]
  private[redis4cats] def log: Log[F]
  private[redis4cats] def availableProcessors: F[Int]
}

object MkRedis {
  def apply[F[_]: MkRedis]: MkRedis[F] = implicitly

  implicit def forAsync[F[_]: Async: Log]: MkRedis[F] =
    new MkRedis[F] {
      private implicit val implicitThis: MkRedis[F] = this

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

      private[redis4cats] def txRunner: Resource[F, TxRunner[F]] =
        TxExecutor.make[F].map(TxRunner.make[F])

      private[redis4cats] def futureLift: FutureLift[F] = implicitly

      private[redis4cats] def log: Log[F] = implicitly

      private[redis4cats] def availableProcessors: F[Int] = Async[F].blocking(Runtime.getRuntime.availableProcessors())
    }

}
