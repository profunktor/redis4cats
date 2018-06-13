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

package com.github.gvolpe.fs2redis.interpreter

import cats.effect.Concurrent
import cats.syntax.functor._
import com.github.gvolpe.fs2redis.algebra.{PubSubConnection, PublisherCommands, SubscriberCommands}
import com.github.gvolpe.fs2redis.model._
import com.github.gvolpe.fs2redis.util.JRFuture
import fs2.Stream
import fs2.async.mutable
import io.lettuce.core.RedisURI
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import io.lettuce.core.pubsub.{RedisPubSubListener, StatefulRedisPubSubConnection}

class Fs2PubSub[F[_]](client: Fs2RedisClient)(implicit F: Concurrent[F]) extends PubSubConnection[Stream[F, ?]] {

  private def defaultListener[K, V](queue: mutable.Queue[F, V]) =
    new RedisPubSubListener[K, V] {
      override def message(channel: K, message: V): Unit = println(s"$channel >> $message")
      override def message(pattern: K, channel: K, message: V): Unit = ()
      override def psubscribed(pattern: K, count: Long): Unit = ()
      override def subscribed(channel: K, count: Long): Unit = ()
      override def unsubscribed(channel: K, count: Long): Unit = ()
      override def punsubscribed(pattern: K, count: Long): Unit = ()
    }

  private[fs2redis] case class DefaultSubscriberCommands[K, V](queue: mutable.Queue[F, V], commands: RedisPubSubAsyncCommands[K, V]) extends SubscriberCommands[Stream[F, ?], K, V] {

    override def subscribe(channel: Fs2RedisChannel[K]): Stream[F, V] = {
      val subscription = JRFuture { F.delay(commands.subscribe(channel.value)) }.void
      Stream.eval_(subscription) ++ queue.dequeue
    }

    override def unsubscribe(channel: Fs2RedisChannel[K]): Stream[F, Unit] =
      Stream.eval {
        JRFuture { F.delay(commands.unsubscribe(channel.value)) }.void
      }

  }

  override def createSubscriber[K, V](codec: Fs2RedisCodec[K, V], uri: RedisURI): Stream[F, SubscriberCommands[Stream[F, ?], K, V]] = {
    val acquire: F[StatefulRedisPubSubConnection[K, V]] = JRFuture.fromConnectionFuture {
      F.delay(client.underlying.connectPubSubAsync(codec.underlying, uri))
    }

    def release(c: StatefulRedisPubSubConnection[K, V]): F[Unit] =
      JRFuture.fromCompletableFuture(F.delay(c.closeAsync())).void

    for {
      queue <- Stream.eval(fs2.async.boundedQueue[F, V](500))
      conn  <- Stream.bracket(acquire)(release)
      _     <- Stream.eval(F.delay(conn.addListener(defaultListener(queue))))
      subs  <- Stream.emit(DefaultSubscriberCommands[K, V](queue, conn.async()))
    } yield subs
  }

  override def createPublisher[K, V](codec: Fs2RedisCodec[K, V], uri: RedisURI): Stream[F, PublisherCommands[Stream[F, ?], K]] = ???

}
