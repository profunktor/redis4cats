package com.github.gvolpe.fs2redis.interpreter

import cats.effect.{Concurrent, Resource}
import cats.syntax.apply._
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.BasicCommands
import com.github.gvolpe.fs2redis.model.{Fs2RedisClient, Fs2RedisCodec}
import com.github.gvolpe.fs2redis.util.JRFuture
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

import scala.concurrent.duration.FiniteDuration

object Fs2Redis {

  def apply[F[_], K, V](client: Fs2RedisClient,
                        codec: Fs2RedisCodec[K, V],
                        uri: RedisURI)(implicit F: Concurrent[F]): Resource[F, Fs2Redis[F, K, V]] = {
    val acquire = JRFuture.fromConnectionFuture {
      F.delay(client.underlying.connectAsync[K, V](codec.underlying, uri))
    }
    def release(c: Fs2Redis[F, K, V]): F[Unit] =
      JRFuture.fromCompletableFuture(F.delay(c.client.closeAsync())) *>
        F.delay(s"Releasing commands connection: ${client.underlying}")

    Resource.make(acquire.map(c => new Fs2Redis(c)))(release)
  }

}

private[fs2redis] class Fs2Redis[F[_], K, V](val client: StatefulRedisConnection[K, V])(implicit F: Concurrent[F]) extends BasicCommands[F, K, V] {

  override def get(k: K): F[Option[V]] =
    JRFuture {
      F.delay(client.async().get(k))
    }.map(Option.apply)

  override def set(k: K, v: V): F[Unit] =
    JRFuture {
      F.delay(client.async().set(k, v))
    }.void

  override def setnx(k: K, v: V): F[Unit] =
    JRFuture {
      F.delay(client.async().setnx(k, v))
    }.void

  override def del(k: K): F[Unit] =
    JRFuture {
      F.delay(client.async().del(k))
    }.void

  override def setex(k: K, v: V, seconds: FiniteDuration): F[Unit] =
    JRFuture {
      F.delay(client.async().setex(k, seconds.toSeconds, v))
    }.void

  override def expire(k: K, seconds: FiniteDuration): F[Unit] =
    JRFuture {
      F.delay(client.async().expire(k, seconds.toSeconds))
    }.void

}
