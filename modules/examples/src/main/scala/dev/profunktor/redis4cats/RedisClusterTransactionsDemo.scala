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

import cats.effect.{ IO, Resource }
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisClusterTransactionsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val key1 = "test1"

    val showResult: String => Option[String] => IO[Unit] = key =>
      _.fold(IO.println(s"Not found key: $key"))(s => IO.println(s))

    val commandsApi: Resource[IO, (RedisClusterClient, RedisCommands[IO, String, String])] =
      for {
        uri <- Resource.eval(RedisURI.make[IO](redisClusterURI))
        client <- RedisClusterClient[IO](uri)
        redis <- Redis[IO].fromClusterClient(client, stringCodec)()
      } yield client -> redis

    commandsApi
      .use {
        case (client, cmd) =>
          val nodeCmdResource =
            for {
              _ <- Resource.eval(cmd.set(key1, "empty"))
              nodeId <- Resource.eval(RedisClusterClient.nodeId[IO](client, key1))
              redis <- Redis[IO].fromClusterClientByNode(client, stringCodec, nodeId)()
            } yield redis

          // Transactions are only supported on a single node
          val notAllowed: IO[Unit] =
            cmd.multi
              .bracket(_ => cmd.set(key1, "nope") >> cmd.exec.void)(_ => cmd.discard)
              .recoverWith {
                case e: OperationNotSupported => IO.println(e)
              }
              .void

          notAllowed *>
            // Transaction runs in a single shard, where "key1" is stored
            nodeCmdResource.use { redis =>
              val getter = redis.get(key1).flatTap(showResult(key1))

              val tx1 = redis.transact_(List(redis.set(key1, "foo")))

              getter *> tx1 *> getter.void
            }
      }

  }

}
