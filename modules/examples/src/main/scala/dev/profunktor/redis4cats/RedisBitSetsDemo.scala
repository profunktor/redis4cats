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
import dev.profunktor.redis4cats.algebra.BitCommandOperation.{ IncrUnsignedBy, SetUnsigned }
import dev.profunktor.redis4cats.effect.Log.NoOp._

object RedisBitSetsDemo extends LoggerIOApp {

  import Demo._

  val program: IO[Unit] = {
    val testKey = "bitsets"

    val bitsApi: Resource[IO, RedisCommands[IO, String, String]] = Redis[IO].utf8(redisURI)

    bitsApi.use { cmd =>
      for {
        _ <- cmd.del(testKey)
        a <- cmd.setBit(testKey, 7, 1)
        b <- cmd.setBit(testKey, 7, 0)
        _ <- putStrLn(s"Before $a after $b")
        cSet <- cmd.setBit(testKey, 6, 1)
        _ <- putStrLn(s"Setting offset 6 to $cSet")
        c <- cmd.getBit(testKey, 6)
        _ <- putStrLn(s"Bit at offset 6 is $c")
        batchSet <- for {
                     s1 <- cmd.setBit("bitmapsarestrings", 2, 1)
                     s2 <- cmd.setBit("bitmapsarestrings", 3, 1)
                     s3 <- cmd.setBit("bitmapsarestrings", 5, 1)
                     s4 <- cmd.setBit("bitmapsarestrings", 10, 1)
                     s5 <- cmd.setBit("bitmapsarestrings", 11, 1)
                     s6 <- cmd.setBit("bitmapsarestrings", 14, 1)
                   } yield s1 + s2 + s3 + s4 + s5 + s6
        _ <- putStrLn(s"Set multiple ${batchSet}")
        truth <- cmd.get("bitmapsarestrings")
        _ <- putStrLn(s"The answer to everything is $truth")
        bf <- cmd.bitField(
               "inmap",
               SetUnsigned(2, 1),
               SetUnsigned(3, 1),
               SetUnsigned(5, 1),
               SetUnsigned(10, 1),
               SetUnsigned(11, 1),
               SetUnsigned(14, 1),
               IncrUnsignedBy(14, 1)
             )
        _ <- putStrLn(s"Via bitfield $bf")
      } yield ()
    }
  }
}
