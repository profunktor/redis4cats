package com.github.gvolpe.fs2redis.interpreter

import cats.effect.Async
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.PublisherCommands
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream

class Fs2Publisher[F[_], K, V](channel: Fs2RedisChannel[K],
                               pubSubCommands: Fs2RedisPubSubCommands[K, V])
                              (implicit F: Async[F]) extends PublisherCommands[Stream[F, ?], V] {

  override def publish(message: V): Stream[F, V] => Stream[F, Unit] =
    _.evalMap { message =>
      JRFuture {
        F.delay(pubSubCommands.underlying.publish(channel.value, message))
      }.void
    }

}
