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

package dev.profunktor.redis4cats.algebra

import dev.profunktor.redis4cats.effects.{ AclCategory, AclDryRunResult, AclSetUserRule, AclUser }

/** Redis ACL commands (`ACL ...`).
  *
  * Note: replies that are decoded into structured results (`aclGetUser`, `aclLog`) are read as UTF-8 text and therefore
  * assume the connection uses a string-decoding codec. On a connection whose value codec produces non-string values
  * (e.g. `Array[Byte]`), those two commands fail with an `AclError.DecodingFailure` rather than returning a partial
  * result.
  */
trait AclCommands[F[_]] extends AclManagement[F] with AclUserManagement[F]

trait AclManagement[F[_]] {

  /** `ACL WHOAMI` — the username of the current connection. */
  def aclWhoAmI: F[String]

  /** `ACL CAT` — the available command categories. */
  def aclCat: F[Set[AclCategory]]

  /** `ACL CAT <category>` — the command names in the given category (lowercase). */
  def aclCat(category: AclCategory): F[Set[String]]

  /** `ACL GENPASS` — a 256-bit pseudorandom password as a 64-char hex string. */
  def aclGenPass: F[String]

  /** `ACL GENPASS <bits>` — a pseudorandom password with the given number of bits of entropy. */
  def aclGenPass(bits: Int): F[String]

  /** `ACL LIST` — all configured users in the ACL-rules text format. */
  def aclList: F[List[String]]

  /** `ACL LOAD` — reload the ACLs from the configured ACL file. */
  def aclLoad: F[Unit]

  /** `ACL SAVE` — save the current ACLs to the configured ACL file. */
  def aclSave: F[Unit]

  /** `ACL LOG` — recent ACL security events, each as a field/value map. */
  def aclLog: F[List[Map[String, String]]]

  /** `ACL LOG <count>` — the most recent `count` ACL security events. */
  def aclLog(count: Int): F[List[Map[String, String]]]

  /** `ACL LOG RESET` — clear the ACL log. */
  def aclLogReset: F[Unit]
}

trait AclUserManagement[F[_]] {

  /** `ACL USERS` — the usernames of all configured users. */
  def aclUsers: F[List[String]]

  /** `ACL GETUSER <username>` — the user's rules, or `None` if the user does not exist. */
  def aclGetUser(username: String): F[Option[AclUser]]

  /** `ACL SETUSER <username> <rules...>` — create or modify a user, applying `rules` in order. */
  def aclSetUser(username: String, rules: List[AclSetUserRule]): F[Unit]

  /** `ACL DELUSER <username...>` — delete the given users, returning the number actually deleted. */
  def aclDelUser(username: String, usernames: String*): F[Long]

  /** `ACL DRYRUN <username> <command> [arg...]` — test whether `username` would be allowed to run `command` with the
    * given arguments, without actually running it.
    */
  def aclDryRun(username: String, command: String, args: String*): F[AclDryRunResult]
}
