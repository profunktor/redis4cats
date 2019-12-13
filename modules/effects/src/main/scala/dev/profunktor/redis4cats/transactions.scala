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

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.effect.Log

object transactions {

  /** Old System, Perhaps Deprecated */
  case class RedisTransaction[F[_]: Log: Sync, K, V](
      cmd: RedisCommands[F, K, V]
  ) {
    def run[A](fa: F[A]): F[A] =
      Log[F].info("Transaction started") *>
        cmd.multi.bracketCase(_ => fa) {
          case (_, ExitCase.Completed) => cmd.exec *> Log[F].info("Transaction completed")
          case (_, ExitCase.Error(e))  => cmd.discard *> Log[F].error(s"Transaction failed: ${e.getMessage}")
          case (_, ExitCase.Canceled)  => cmd.discard *> Log[F].error("Transaction canceled")
        }
  }

  private def transactionResource[F[_]: Log: Bracket[*[_], Throwable], K, V](cmd: RedisCommands[F, K, V]): Resource[F, Unit] = 
    for {
      _ <- Resource.liftF(Log[F].info("Transaction started"))
      out <- Resource.makeCase(cmd.multi){
        case (_, ExitCase.Completed) => cmd.exec *> Log[F].info("Transaction completed")
        case (_, ExitCase.Error(e))  => 
          cmd.discard *> Log[F].error(s"Transaction failed: ${e.getMessage}")
        case (_, ExitCase.Canceled)  =>
          cmd.discard *>  Log[F].error("Transaction canceled")
      }
    } yield out

  private def safeFiber[F[_]: Concurrent, A](fa: F[A]): Resource[F, Fiber[F,A]] = 
    Resource.makeCase(fa.start){
      case (_, ExitCase.Completed) => Sync[F].unit
      case (f, ExitCase.Error(_)) => f.cancel
      case (f, ExitCase.Canceled) => f.cancel
    }

  /** This Can be expanded to arbitrary arity with shapeless or macros **/

  def runTransaction[F[_]: Log: Concurrent, K, V, A](
    fa: RedisCommands[F, K, V] => F[A]
  )(cmd: RedisCommands[F, K, V]): F[A] = {
    transactionResource(cmd) *>
    safeFiber(fa(cmd))
  }.use(_.pure[F])
    .bracket(_.join)(_.cancel)

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B]
  )(cmd: RedisCommands[F, K, V]): F[(A, B)] = {
    transactionResource(cmd) *> 
    (safeFiber(fa(cmd)), safeFiber(fb(cmd))).tupled
  }.use(_.pure[F])
    .bracket{case (fa, fb) => (fa.join, fb.join).tupled}{ case (fa, fb) => (fa.cancel, fb.cancel).tupled.void}

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B, C](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B],
    fc: RedisCommands[F, K, V] => F[C]
  )(cmd: RedisCommands[F, K, V]): F[(A, B, C)] = {
    transactionResource(cmd) *> 
    (
      safeFiber(fa(cmd)),
      safeFiber(fb(cmd)),
      safeFiber(fc(cmd))
    ).tupled
  }.use(_.pure[F])
    .bracket{
      case (fa, fb, fc) => (fa.join, fb.join, fc.join).tupled
    }{ 
      case (fa, fb, fc) => (fa.cancel, fb.cancel, fc.cancel).tupled.void
    }

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B, C, D](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B],
    fc: RedisCommands[F, K, V] => F[C],
    fd: RedisCommands[F, K, V] => F[D],
  )(cmd: RedisCommands[F, K, V]): F[(A, B, C, D)] = {
    transactionResource(cmd) *> 
    (
      safeFiber(fa(cmd)),
      safeFiber(fb(cmd)),
      safeFiber(fc(cmd)),
      safeFiber(fd(cmd)),
    ).tupled
  }.use(_.pure[F])
    .bracket{
      case (fa, fb, fc, fd) => (fa.join, fb.join, fc.join, fd.join).tupled
    }{ 
      case (fa, fb, fc, fd) => (fa.cancel, fb.cancel, fc.cancel, fd.join).tupled.void
    }

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B, C, D, E](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B],
    fc: RedisCommands[F, K, V] => F[C],
    fd: RedisCommands[F, K, V] => F[D],
    fe: RedisCommands[F, K, V] => F[E],
  )(cmd: RedisCommands[F, K, V]): F[(A, B, C, D, E)] = {
    transactionResource(cmd) *> 
    (
      safeFiber(fa(cmd)),
      safeFiber(fb(cmd)),
      safeFiber(fc(cmd)),
      safeFiber(fd(cmd)),
      safeFiber(fe(cmd)),
    ).tupled
  }.use(_.pure[F])
    .bracket{
      case (fa, fb, fc, fd, fe) => (fa.join, fb.join, fc.join, fd.join, fe.join).tupled
    }{ 
      case (fa, fb, fc, fd, fe) => (fa.cancel, fb.cancel, fc.cancel, fd.join, fe.join).tupled.void
    }

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B, C, D, E, G](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B],
    fc: RedisCommands[F, K, V] => F[C],
    fd: RedisCommands[F, K, V] => F[D],
    fe: RedisCommands[F, K, V] => F[E],
    fg: RedisCommands[F, K, V] => F[G],
  )(cmd: RedisCommands[F, K, V]): F[(A, B, C, D, E, G)] = {
    transactionResource(cmd) *> 
    (
      safeFiber(fa(cmd)),
      safeFiber(fb(cmd)),
      safeFiber(fc(cmd)),
      safeFiber(fd(cmd)),
      safeFiber(fe(cmd)),
      safeFiber(fg(cmd)),
    ).tupled
  }.use(_.pure[F])
    .bracket{
      case (fa, fb, fc, fd, fe, fg) => (fa.join, fb.join, fc.join, fd.join, fe.join, fg.join).tupled
    }{ 
      case (fa, fb, fc, fd, fe, fg) => (fa.cancel, fb.cancel, fc.cancel, fd.cancel, fe.cancel, fg.cancel).tupled.void
    }

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B, C, D, E, G, H](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B],
    fc: RedisCommands[F, K, V] => F[C],
    fd: RedisCommands[F, K, V] => F[D],
    fe: RedisCommands[F, K, V] => F[E],
    fg: RedisCommands[F, K, V] => F[G],
    fh: RedisCommands[F, K, V] => F[H],
  )(cmd: RedisCommands[F, K, V]): F[(A, B, C, D, E, G, H)] = {
    transactionResource(cmd) *> 
    (
      safeFiber(fa(cmd)),
      safeFiber(fb(cmd)),
      safeFiber(fc(cmd)),
      safeFiber(fd(cmd)),
      safeFiber(fe(cmd)),
      safeFiber(fg(cmd)),
      safeFiber(fh(cmd)),
    ).tupled
  }.use(_.pure[F])
    .bracket{
      case (fa, fb, fc, fd, fe, fg, fh) => (fa.join, fb.join, fc.join, fd.join, fe.join, fg.join, fh.join).tupled
    }{ 
      case (fa, fb, fc, fd, fe, fg, fh) => (fa.cancel, fb.cancel, fc.cancel, fd.cancel, fe.cancel, fg.cancel, fh.cancel).tupled.void
    }

  def runTransaction[F[_]: Log: Concurrent, K, V, A, B, C, D, E, G, H, I](
    fa: RedisCommands[F, K, V] => F[A],
    fb: RedisCommands[F, K, V] => F[B],
    fc: RedisCommands[F, K, V] => F[C],
    fd: RedisCommands[F, K, V] => F[D],
    fe: RedisCommands[F, K, V] => F[E],
    fg: RedisCommands[F, K, V] => F[G],
    fh: RedisCommands[F, K, V] => F[H],
    fi: RedisCommands[F, K, V] => F[I],
  )(cmd: RedisCommands[F, K, V]): F[(A, B, C, D, E, G, H, I)] = {
    transactionResource(cmd) *> 
    (
      safeFiber(fa(cmd)),
      safeFiber(fb(cmd)),
      safeFiber(fc(cmd)),
      safeFiber(fd(cmd)),
      safeFiber(fe(cmd)),
      safeFiber(fg(cmd)),
      safeFiber(fh(cmd)),
      safeFiber(fi(cmd)),
    ).tupled
  }.use(_.pure[F])
    .bracket{
      case (fa, fb, fc, fd, fe, fg, fh, fi) => (fa.join, fb.join, fc.join, fd.join, fe.join, fg.join, fh.join, fi.join).tupled
    }{ 
      case (fa, fb, fc, fd, fe, fg, fh, fi) => (fa.cancel, fb.cancel, fc.cancel, fd.cancel, fe.cancel, fg.cancel, fh.cancel, fi.cancel).tupled.void
    }


}
