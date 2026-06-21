/*
 * Copyright 2018-2025 ProfunKtor
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

import cats.syntax.all._
import dev.profunktor.redis4cats.JavaConversions._
import dev.profunktor.redis4cats.effects.{ AclError, AclSelector, AclUser }

/** Pure decoders for Lettuce's untyped ACL replies (`java.util.List<Object>`).
  *
  * The raw Java reply is converted exactly once, at [[toResp]], into a typed [[Resp]] tree; everything downstream works
  * on that ADT with no `Any` and an explicit error channel. Total functions, no exceptions.
  */
private[redis4cats] object AclDecoder {

  private sealed trait Resp
  private object Resp {
    final case class Bulk(value: String) extends Resp
    final case class Arr(values: List[Resp]) extends Resp
  }

  /** The single boundary that touches Lettuce's untyped reply. With a UTF-8 codec every element is a bulk string or a
    * (possibly nested) array; anything else is a decoding failure rather than a silent coercion.
    */
  private def toResp(o: AnyRef): Either[AclError, Resp] =
    o match {
      case null      => Left(AclError.DecodingFailure("Unexpected null element in ACL reply"))
      case s: String => Right(Resp.Bulk(s))
      case l: java.util.List[_] =>
        l.asScala.toList.traverse(e => toResp(e.asInstanceOf[AnyRef])).map(rs => Resp.Arr(rs))
      case other =>
        Left(AclError.DecodingFailure(s"Unexpected ACL reply element of type ${other.getClass.getName}"))
    }

  private def bulk(r: Resp): Either[AclError, String] =
    r match {
      case Resp.Bulk(s) => Right(s)
      case _: Resp.Arr  => Left(AclError.DecodingFailure("Expected a bulk string but got an array"))
    }

  /** A rule field (`commands`/`keys`/`channels`) is a single token on Redis 7+, but tolerate an array (older servers)
    * by joining with spaces.
    */
  private def ruleString(r: Resp): Either[AclError, String] =
    r match {
      case Resp.Bulk(s)    => Right(s)
      case Resp.Arr(items) => items.traverse(bulk).map(_.mkString(" "))
    }

  private def strings(r: Resp): Either[AclError, List[String]] =
    r match {
      case Resp.Arr(items) => items.traverse(bulk)
      case Resp.Bulk(s)    => Right(List(s))
    }

  /** Group a flat `[k, v, k, v, ...]` array into a field map; keys must be bulk strings. */
  private def fields(items: List[Resp]): Either[AclError, Map[String, Resp]] = {
    def go(rem: List[Resp], acc: List[(String, Resp)]): Either[AclError, List[(String, Resp)]] =
      rem match {
        case Nil                       => Right(acc.reverse)
        case Resp.Bulk(k) :: v :: tail => go(tail, (k -> v) :: acc)
        case Resp.Bulk(k) :: Nil       => Left(AclError.DecodingFailure(s"Dangling ACL field '$k' with no value"))
        case _ :: _                    => Left(AclError.DecodingFailure("ACL field name was not a bulk string"))
      }
    go(items, Nil).map(_.toMap)
  }

  private def selector(r: Resp): Either[AclError, AclSelector] =
    r match {
      case Resp.Arr(items) =>
        fields(items).flatMap { f =>
          for {
            commands <- ruleString(f.getOrElse("commands", Resp.Bulk("")))
            keys <- ruleString(f.getOrElse("keys", Resp.Bulk("")))
            channels <- ruleString(f.getOrElse("channels", Resp.Bulk("")))
          } yield AclSelector(commands, keys, channels)
        }
      case _: Resp.Bulk => Left(AclError.DecodingFailure("ACL selector was not an array"))
    }

  /** Decode an `ACL GETUSER` reply.
    *
    * `None` means the user does not exist — and ONLY that. Redis replies nil for a missing user, which Lettuce surfaces
    * as a `null` reply, an empty list, or a single `null` element; those are the sole sources of `None`. A present
    * reply is expected to carry a `flags` field (every real user is `on`/`off`); its absence is treated as a structural
    * failure rather than silently reported as a missing user, so malformation and absence stay distinct.
    */
  def decodeUser(raw: java.util.List[Object]): Either[AclError, Option[AclUser]] =
    if (raw == null || raw.asScala.forall(_ == null)) Right(None)
    else
      toResp(raw).flatMap {
        case Resp.Arr(items) =>
          fields(items).flatMap { f =>
            for {
              flags <- f
                         .get("flags")
                         .toRight(AclError.DecodingFailure("ACL GETUSER reply had no 'flags' field"))
                         .flatMap(strings)
              passwords <- f.get("passwords").fold[Either[AclError, List[String]]](Right(Nil))(strings)
              commands <- ruleString(f.getOrElse("commands", Resp.Bulk("")))
              keys <- ruleString(f.getOrElse("keys", Resp.Bulk("")))
              channels <- ruleString(f.getOrElse("channels", Resp.Bulk("")))
              selectors <- f.get("selectors") match {
                             case None                 => Right(List.empty[AclSelector])
                             case Some(Resp.Arr(sels)) => sels.traverse(selector)
                             case Some(_) =>
                               Left(AclError.DecodingFailure("ACL GETUSER 'selectors' field was not an array"))
                           }
            } yield Some(AclUser(flags, passwords, commands, keys, channels, selectors))
          }
        case _: Resp.Bulk => Left(AclError.DecodingFailure("ACL GETUSER reply was not an array"))
      }

  /** Decode an `ACL LOG` reply into field/value maps. Scalar values (strings, numbers, booleans) are rendered to their
    * textual form, matching how Redis presents the diagnostic log.
    */
  def decodeLog(entries: java.util.List[java.util.Map[String, Object]]): Either[AclError, List[Map[String, String]]] =
    entries.asScala.toList.traverse { entry =>
      entry.asScala.toList.traverse { case (k, v) => scalar(v).map(k -> _) }.map(_.toMap)
    }

  private def scalar(o: Object): Either[AclError, String] =
    o match {
      case null                 => Left(AclError.DecodingFailure("Unexpected null value in ACL log entry"))
      case s: String            => Right(s)
      case n: java.lang.Number  => Right(n.toString)
      case b: java.lang.Boolean => Right(b.toString)
      case other =>
        Left(AclError.DecodingFailure(s"Unexpected ACL log value of type ${other.getClass.getName}"))
    }
}
