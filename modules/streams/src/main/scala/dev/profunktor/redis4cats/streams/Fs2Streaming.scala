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
package streams

import scala.concurrent.duration._

import cats.effect.kernel._
import cats.effect.kernel.implicits._
import cats._
import cats.syntax.all._
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.effect.{ FutureLift, Log, RedisExecutor }
import dev.profunktor.redis4cats.streams.data._
import fs2.Stream
import io.lettuce.core.{ ReadFrom => JReadFrom }

object RedisStream {

  def mkStreamingConnection[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Stream[F, Streaming[Stream[F, *], K, V]] =
    Stream.resource(mkStreamingConnectionResource(client, codec))

  def mkStreamingConnectionResource[F[_]: Async: Log, K, V](
      client: RedisClient,
      codec: RedisCodec[K, V]
  ): Resource[F, Streaming[Stream[F, *], K, V]] =
    RedisExecutor.make[F].flatMap { implicit redisExecutor =>
      val acquire = FutureLift[F]
        .liftConnectionFuture(
          Sync[F].delay(client.underlying.connectAsync[K, V](codec.underlying, client.uri.underlying))
        )
        .map(new RedisRawStreaming(_))

      val release: RedisRawStreaming[F, K, V] => F[Unit] = c =>
        FutureLift[F].liftCompletableFuture(Sync[F].delay(c.client.closeAsync())) *>
            Log[F].info(s"Releasing Streaming connection: ${client.uri.underlying}")

      Resource.make(acquire)(release).map(rs => new RedisStream(rs))
    }

  def mkMasterReplicaConnection[F[_]: Async: Log, K, V](
      codec: RedisCodec[K, V],
      uris: RedisURI*
  )(readFrom: Option[JReadFrom] = None): Stream[F, Streaming[Stream[F, *], K, V]] =
    Stream.resource(mkMasterReplicaConnectionResource(codec, uris: _*)(readFrom))

  def mkMasterReplicaConnectionResource[F[_]: Async: Log, K, V](
      codec: RedisCodec[K, V],
      uris: RedisURI*
  )(readFrom: Option[JReadFrom] = None): Resource[F, Streaming[Stream[F, *], K, V]] =
    RedisExecutor.make[F].flatMap { implicit redisExecutor =>
      RedisMasterReplica[F].make(codec, uris: _*)(readFrom).map { conn =>
        new RedisStream(new RedisRawStreaming(conn.underlying))
      }
    }

}

class RedisStream[F[_]: Async: Log, K, V](rawStreaming: RedisRawStreaming[F, K, V])
    extends Streaming[Stream[F, *], K, V] {

  private[streams] val nextOffset: K => XReadMessage[K, V] => StreamingOffset[K] =
    key => msg => StreamingOffset.Custom(key, msg.id.value)

  private[streams] val offsetsByKey: List[XReadMessage[K, V]] => Map[K, Option[StreamingOffset[K]]] =
    list => list.groupBy(_.key).map { case (k, values) => k -> values.lastOption.map(nextOffset(k)) }

  private val promiseAlreadyCompleted = new AssertionError("Promise already completed")

  // Joins or cancel fiberss correspondent to previous executed commands
  private def joinOrCancel[A](ys: List[Fiber[F, Throwable, A]], res: List[A])(isJoin: Boolean): F[List[A]] =
    ys match {
      case h :: t if isJoin =>
        h.joinWithNever.flatMap(x => joinOrCancel(t, x :: res)(isJoin))
      case h :: t =>
        h.cancel.flatMap(_ => joinOrCancel(t, res)(isJoin))
      case Nil => Applicative[F].pure(res)
    }

  // Forks every command in order
  private def runner[H](
      f: F[Unit],
      cmds: List[F[H]],
      res: List[Fiber[F, Throwable, H]]
  ): F[List[Fiber[F, Throwable, H]]] =
    cmds match {
      case Nil    => res.pure[F]
      case h :: t => (h, f).parTupled.map(_._1).start.flatMap(fb => runner(f, t, fb :: res))
    }

  override def append: Stream[F, XAddMessage[K, V]] => Stream[F, MessageId] =
    _.chunks
      .evalMap { chunk =>
        Deferred[F, Either[Throwable, List[MessageId]]].flatMap {
          case promise =>
            def cancelFibers[A](fibs: List[Fiber[F, Throwable, MessageId]])(err: Throwable): F[Unit] =
              joinOrCancel(fibs, List.empty)(false) >> promise
                    .complete(err.asLeft)
                    .ensure(promiseAlreadyCompleted)(identity)
                    .void

            def onErrorOrCancelation(fibs: List[Fiber[F, Throwable, MessageId]]): F[Unit] =
              cancelFibers(fibs)(new RuntimeException("XADD cancelled"))

            (Deferred[F, Unit], Ref.of[F, Int](0)).tupled
              .flatMap {
                case (gate, counter) =>
                  val commands = chunk.map(msg => rawStreaming.xAdd(msg.key, msg.body, msg.approxMaxlen))

                  // wait for commands to be scheduled
                  val synchronizer: F[Unit] =
                    counter.modify {
                      case n if n === (commands.size - 1) =>
                        n + 1 -> gate.complete(()).ensure(promiseAlreadyCompleted)(identity).void
                      case n => n + 1 -> Applicative[F].unit
                    }.flatten

                  Log[F].debug(s"XADD started with size: ${commands.size}") >>
                    (rawStreaming.disableAutoFlush >> runner(synchronizer, commands.toList, List.empty))
                      .bracketCase(_ => gate.get) {
                        case (fibs, Outcome.Succeeded(_)) =>
                          for {
                            _ <- Log[F].debug(s"XADD flushing ${commands.size} commands...")
                            _ <- rawStreaming.flushCommands
                            _ <- Log[F].debug(s"XADD awaiting all ${commands.size} commands...")
                            r <- joinOrCancel(fibs, List.empty)(true)
                            _ <- promise.complete(r.asRight).ensure(promiseAlreadyCompleted)(identity)
                          } yield ()
                        case (fibs, Outcome.Errored(e)) =>
                          Log[F].error(s"XADD failed: ${e.getMessage}") >> onErrorOrCancelation(fibs)
                        case (fibs, Outcome.Canceled()) =>
                          Log[F].error(s"XADD canceled") >> onErrorOrCancelation(fibs)
                      }
                      // .guarantee(rawStreaming.enableAutoFlush) >> promise.get.rethrow.timeout(3.seconds)
                      .guarantee(Applicative[F].unit) >> promise.get.rethrow.timeout(3.seconds)
              }
        }
      }
      .flatMap(Stream.emits)

  override def read(
      keys: Set[K],
      chunkSize: Int,
      initialOffset: K => StreamingOffset[K],
      block: Option[Duration] = Some(Duration.Zero),
      count: Option[Long] = None
  ): Stream[F, XReadMessage[K, V]] = {
    val initial = keys.map(k => k -> initialOffset(k)).toMap
    Stream.eval(Ref.of[F, Map[K, StreamingOffset[K]]](initial)).flatMap { ref =>
      (for {
        offsets <- Stream.eval(ref.get)
        list <- Stream.eval(rawStreaming.xRead(offsets.values.toSet, block, count))
        newOffsets = offsetsByKey(list).collect { case (key, Some(value)) => key -> value }.toList
        _ <- Stream.eval(newOffsets.map { case (k, v) => ref.update(_.updated(k, v)) }.sequence)
        result <- Stream.fromIterator[F](list.iterator, chunkSize)
      } yield result).repeat
    }
  }

}
