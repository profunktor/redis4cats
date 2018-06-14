package com.github.gvolpe.fs2redis.algebra

import scala.concurrent.duration.FiniteDuration

trait BasicConnection[F[_]] {
  def connect
}

trait BasicCommands[F[_], K, V] extends Expiration[F, K, V] {
  def get(k: K): F[Option[V]]
  def set(k: K, v: V): F[Unit]
  def setnx(k: K, v: V): F[Unit]
  def del(k: K): F[Unit]
}

trait Expiration[F[_], K, V] {
  def setex(k: K, v: V, seconds: FiniteDuration): F[Unit]
  def expire(k: K, seconds: FiniteDuration): F[Unit]
}