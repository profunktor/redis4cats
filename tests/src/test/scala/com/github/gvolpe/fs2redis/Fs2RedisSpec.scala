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

package com.github.gvolpe.fs2redis

import cats.effect.IO
import org.scalatest.FunSuite

class Fs2RedisSpec extends FunSuite with DockerRedis {

  test("strings api") {
    withRedis { cmd =>
      for {
        _ <- cmd.set("foo", "123")
        v <- cmd.get("foo")
        _ <- IO { assert(v.contains("123")) }
      } yield ()
    }
  }

  test("sets api") {
    withRedis { cmd =>
      for {
        _ <- cmd.hSet("foo", "bar", "123")
        v <- cmd.hGet("foo", "bar")
        _ <- IO { assert(v.contains("123")) }
      } yield ()
    }
  }

}
