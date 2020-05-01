/*
 * Copyright 2018-2020 ProfunKtor
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
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import dev.profunktor.redis4cats.algebra._
import dev.profunktor.redis4cats.effect.Log

object transactions {

  case class RedisTransaction[F[_]: Concurrent: Log, K, V](
      cmd: RedisCommands[F, K, V]
  ) {

    /***
      * Exclusively run Redis commands as part of a transaction.
      *
      * Every command needs to be forked (`.start`) to be sent to the server asynchronously.
      * After a transaction is complete, either successfully or with a failure, the spawned
      * fibers will be treated accordingly.
      *
      * It should not be used to run other computations, only Redis commands. Fail to do so
      * may end in unexpected results such as a dead lock.
      */
    def run(commands: F[Any]*): F[Unit] =
      Ref.of[F, List[Fiber[F, Any]]](List.empty).flatMap { fibers =>
        val tx =
          Resource.makeCase(cmd.multi) {
            case (_, ExitCase.Completed) =>
              cmd.exec *> F.info("Transaction completed")
            case (_, ExitCase.Error(e)) =>
              cmd.discard *> F.error(s"Transaction failed: ${e.getMessage}")
            case (_, ExitCase.Canceled) =>
              cmd.discard *> F.error("Transaction canceled")
          }

        val cancelFibers =
          fibers.get.flatMap(_.traverse(_.cancel).void)

        F.info("Transaction started") *>
          tx.use(_ => commands.toList.traverse(_.start).flatMap(fibers.set))
            .guarantee(cancelFibers)
      }
  }

  sealed trait RedisTransactionB[F[_], K, V, A] {
    def transact(commands: RedisCommands[F, K, V])(implicit F: Concurrent[F], L: Log[F]): F[A]

  }
  object RedisTransactionB {

    def builder[F[_], K, V]: Builder[F, K, V] = new Builder[F, K, V]

    /**
      * The Builder is a Partially Applied Constructor Allowing operations to fully infer.
     **/
    class Builder[F[_], K, V] {
      def operation[A](r: RedisCommands[F, K, V] => F[A]): RedisTransactionB[F, K, V, A] =
        RedisTransactionB.operation(r)
      def pure[A](a: A): RedisTransactionB[F, K, V, A] =
        RedisTransactionB.pure(a)
    }

    def operation[F[_], K, V, A](r: RedisCommands[F, K, V] => F[A]): RedisTransactionB[F, K, V, A] =
      Operation(r)

    def pure[F[_], K, V, A](a: A): RedisTransactionB[F, K, V, A] =
      Pure(a)

    def ap[F[_], K, V, A, B](
        ff: RedisTransactionB[F, K, V, A => B],
        fa: RedisTransactionB[F, K, V, A]
    ): RedisTransactionB[F, K, V, B] =
      Ap(
        ff.asInstanceOf[RedisTransactionInternal[F, K, V, A => B]], // Safe ONLY because we are PRIVATE
        fa.asInstanceOf[RedisTransactionInternal[F, K, V, A]]
      )

    private sealed trait RedisTransactionInternal[F[_], K, V, A] extends RedisTransactionB[F, K, V, A] {
      final def transact(cmd: RedisCommands[F, K, V])(implicit F: Concurrent[F], L: Log[F]): F[A] =
        (Resource.makeCase(cmd.multi) {
          case (_, ExitCase.Completed) => F.unit
          case (_, ExitCase.Error(e)) =>
            cmd.discard *> L.error(s"Transaction failed: ${e.getMessage}")
          case (_, ExitCase.Canceled) =>
            cmd.discard *> L.error("Transaction canceled")
        } >> Resource.liftF(L.info("Transaction started")) >> runInternal(cmd)).use { fib =>
          cmd.exec *>
            L.info("Transaction completed") *>
            fib.join
        }

      def runInternal(commands: RedisCommands[F, K, V])(implicit F: Concurrent[F], L: Log[F]): Resource[F, Fiber[F, A]]
    }

    def forkBackground[F[_]: Concurrent, A](fa: F[A]): Resource[F, Fiber[F, A]] =
      Resource.make(fa.start)(_.cancel)

    private final case class Pure[F[_], K, V, A](a: A) extends RedisTransactionInternal[F, K, V, A] {
      override def runInternal(
          commands: RedisCommands[F, K, V]
      )(implicit F: Concurrent[F], L: Log[F]): Resource[F, Fiber[F, A]] =
        Resource.pure[F, Fiber[F, A]](a.pure[Fiber[F, *]])
    }
    private final case class Operation[F[_], K, V, A](withCommands: RedisCommands[F, K, V] => F[A])
        extends RedisTransactionInternal[F, K, V, A] {
      override def runInternal(
          commands: RedisCommands[F, K, V]
      )(implicit F: Concurrent[F], L: Log[F]): Resource[F, Fiber[F, A]] =
        forkBackground(withCommands(commands))
    }

    private final case class Ap[F[_], K, V, A, B](
        f: RedisTransactionInternal[F, K, V, A => B],
        a: RedisTransactionInternal[F, K, V, A]
    ) extends RedisTransactionInternal[F, K, V, B] {

      override def runInternal(
          cmd: RedisCommands[F, K, V]
      )(implicit F: Concurrent[F], L: Log[F]): Resource[F, Fiber[F, B]] =
        for {
          ff: Fiber[F, A => B] <- f.runInternal(cmd)
          fa: Fiber[F, A] <- a.runInternal(cmd)
        } yield ff.ap(fa)
    }

    implicit def applicative[F[_], K, V]: cats.Applicative[RedisTransactionB[F, K, V, *]] =
      new cats.Applicative[RedisTransactionB[F, K, V, *]] {
        def ap[A, B](ff: RedisTransactionB[F, K, V, A => B])(
            fa: RedisTransactionB[F, K, V, A]
        ): RedisTransactionB[F, K, V, B]                 = RedisTransactionB.ap(ff, fa)
        def pure[A](x: A): RedisTransactionB[F, K, V, A] = RedisTransactionB.pure[F, K, V, A](x)
      }
  }

  object Example {
    final case class Model(a: Option[String], b: Option[String])
    def getModel[F[_]] =
      (
        RedisTransactionB.operation[F, String, String, Option[String]](_.get("foo")),
        RedisTransactionB.operation[F, String, String, Option[String]](_.get("bar"))
      ).mapN(Model.apply)

    def transaction[F[_]: Concurrent: Log](commands: RedisCommands[F, String, String]): F[Model] =
      getModel[F].transact(commands)
  }

}
