package com.github.gvolpe.fs2redis

import cats.effect.IO
import com.github.gvolpe.fs2redis.model.DefaultRedisCodec
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.StringCodec

object Demo {

  val redisURI    = RedisURI.create("redis://localhost")
  val stringCodec = DefaultRedisCodec(StringCodec.UTF8)

  def putStrLn(str: String): IO[Unit] = IO(println(str))

}
