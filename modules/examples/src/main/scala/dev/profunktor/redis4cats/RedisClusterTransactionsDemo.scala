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

package dev.profunktor.redis4cats

import cats.effect.{ IO, Resource }
import cats.implicits._
import dev.profunktor.redis4cats.algebra.RedisCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.domain.RedisClusterClient
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.transactions._

object RedisClusterTransactionsDemo extends LoggerIOApp {

  import Demo._

  def program(implicit log: Log[IO]): IO[Unit] = {
    val key1 = "test1"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(putStrLn(s"Not found key: $key"))(s => putStrLn(s))

    val commandsApi: Resource[IO, (RedisClusterClient, RedisCommands[IO, String, String])] =
      for {
        uri <- Resource.liftF(RedisURI.make[IO](redisClusterURI))
        client <- RedisClusterClient[IO](uri)
        redis <- Redis.cluster[IO, String, String](client, stringCodec)
      } yield client -> redis

    commandsApi
      .use {
        case (client, cmd) =>
          val nodeCmdResource =
            for {
              _ <- Resource.liftF(cmd.set(key1, "empty"))
              nodeId <- Resource.liftF(RedisClusterClient.nodeId[IO](client, key1))
              nodeCmd <- Redis.clusterByNode[IO, String, String](client, stringCodec, nodeId)
            } yield nodeCmd

          // Transaction runs in a single shard, where "key1" is stored
          nodeCmdResource.use { nodeCmd =>
            val tx = RedisTransaction(nodeCmd)

            val getter = cmd.get(key1).flatTap(showResult(key1))
            val setter = cmd.set(key1, "foo").start

            val failedSetter =
              cmd.set(key1, "qwe").start *>
                IO.raiseError(new Exception("boom"))

            val tx1 = tx.run(setter)
            val tx2 = tx.run(failedSetter)

            getter *> tx1 *> tx2.attempt *> getter.void
          }
      }

  }

}
