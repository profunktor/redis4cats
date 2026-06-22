---
layout: docs
title:  "ACL"
number: 16
---

# ACL API

Purely functional interface for the [ACL API](https://redis.io/commands#server) (`ACL ...`), used to
inspect and manage Redis [Access Control Lists](https://redis.io/docs/management/security/acl/) —
users, their passwords, and the commands, keys and channels they are allowed to touch.

The algebra is `AclCommands[F]` (note: it has no key/value type parameters, since ACL operates on
usernames and rule strings rather than your codec's `K`/`V`). It is the union of two smaller algebras:
`AclManagement[F]` (server-wide: `WHOAMI`, `CAT`, `GENPASS`, `LIST`, `LOAD`, `SAVE`, `LOG`) and
`AclUserManagement[F]` (per-user: `USERS`, `GETUSER`, `SETUSER`, `DELUSER`).

```scala mdoc:invisible
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.AclCommands
import dev.profunktor.redis4cats.data._
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val commandsApi: Resource[IO, AclCommands[IO]] = {
  Redis[IO].fromClient[String, String](null, null.asInstanceOf[RedisCodec[String, String]]).widen[AclCommands[IO]]
}
```

### ACL Commands usage

Once you have acquired a connection you can start using it:

```scala mdoc:silent
import dev.profunktor.redis4cats.effects.{ AclCategory, RawCommand }
import dev.profunktor.redis4cats.effects.AclSetUserRule._

commandsApi.use { redis => // AclCommands[IO]
  for {
    _     <- redis.aclWhoAmI                // the current connection's user, e.g. "default"
    _     <- redis.aclCat                   // Set[AclCategory] — the available categories
    _     <- redis.aclCat(AclCategory.Read) // Set[String] — command names in the "read" category
    _     <- redis.aclSetUser(
               "alice",
               List(
                 On,
                 AddPassword("s3cret"),
                 NoCommands,
                 AddCommand(RawCommand("get")),
                 AddCategory(AclCategory.Read),
                 KeyPattern("app:*"),
                 ChannelPattern("news.*")
               )
             )
    _     <- redis.aclUsers                 // List[String] — all configured usernames
    alice <- redis.aclGetUser("alice")      // Option[AclUser]
    _     <- redis.aclDelUser("alice")      // Long — number of users actually deleted
  } yield alice
}
```

### Creating and modifying users with `SETUSER`

`aclSetUser` takes the username and an **ordered** `List[AclSetUserRule]`; the rules are applied in
order, exactly like the arguments to the `ACL SETUSER` command. The rule type is a closed sum type, so
illegal combinations are caught at compile time rather than as a runtime error from Redis:

| Rule | `ACL SETUSER` equivalent |
| --- | --- |
| `On` / `Off` | `on` / `off` |
| `Reset` | `reset` |
| `AddPassword(p)` / `RemovePassword(p)` | `>p` / `<p` |
| `AddHashedPassword(h)` / `RemoveHashedPassword(h)` | `#h` / `!h` |
| `NoPass` / `ResetPass` | `nopass` / `resetpass` |
| `AllKeys` / `ResetKeys` / `KeyPattern(p)` | `allkeys` (`~*`) / `resetkeys` / `~p` |
| `AllChannels` / `ResetChannels` / `ChannelPattern(p)` | `allchannels` (`&*`) / `resetchannels` / `&p` |
| `AllCommands` / `NoCommands` | `allcommands` (`+@all`) / `nocommands` (`-@all`) |
| `AddCommand(c)` / `RemoveCommand(c)` | `+c` / `-c` |
| `AddCategory(cat)` / `RemoveCategory(cat)` | `+@cat` / `-@cat` |

A few notes on the typed arguments:

- **`KeyPattern`/`ChannelPattern`** take the bare glob (e.g. `"app:*"`, `"news.*"`); the `~`/`&`
  prefix is added for you.
- **`AddCategory`/`RemoveCategory`** take an `AclCategory` — a sealed enumeration of the categories
  Redis understands (`AclCategory.Read`, `AclCategory.Write`, `AclCategory.Dangerous`, …), so a typo
  cannot compile.
- **`AddCommand`/`RemoveCommand`** take a `RawCommand(name)`. The set of Redis commands is open
  (modules and new versions add commands), so it is modelled as a string newtype rather than a closed
  enum. A name the driver does not recognise fails with `AclError.UnknownCommand` when the rule is
  applied — it never throws.

### Reading a user with `GETUSER`

`aclGetUser` returns `Option[AclUser]`. `None` means **the user does not exist** — and only that;
a malformed reply fails instead (see *Errors* below). `AclUser` mirrors the fields Redis reports:

```scala
final case class AclUser(
    flags: List[String],     // e.g. List("on", "nopass")
    passwords: List[String], // SHA-256 hashes of the user's passwords
    commands: String,        // the command rule string, e.g. "-@all +get +@read"
    keys: String,            // the key rule string, e.g. "~app:*"
    channels: String,        // the channel rule string, e.g. "&news.*"
    selectors: List[AclSelector]
)

// Redis 7+ selectors, each its own commands/keys/channels rule strings
final case class AclSelector(commands: String, keys: String, channels: String)
```

The `commands`/`keys`/`channels` fields are the rule strings exactly as Redis renders them (so the
`~`/`&`/`+`/`-` prefixes are present here, on the way *out* — unlike the bare patterns you pass *in*).

### Categories and commands

`aclCat` returns the set of available categories as `AclCategory` values; `aclCat(category)` returns the
command names (lowercase) that belong to a category:

```scala mdoc:silent
commandsApi.use { redis =>
  for {
    categories <- redis.aclCat               // Set[AclCategory]
    readCmds   <- redis.aclCat(AclCategory.Read) // Set[String], e.g. contains "get"
  } yield (categories, readCmds)
}
```

### The ACL log

`aclLog` returns recent ACL security events (auth failures, denied commands/keys/channels) as
field/value maps; `aclLogReset` clears it:

```scala mdoc:silent
commandsApi.use { redis =>
  redis.aclLogReset >> redis.aclLog // List[Map[String, String]]
}
```

### Errors

ACL failures surface as `AclError` (a `Throwable`, raised into `F`), never as thrown exceptions from
argument building or silent coercions:

- `AclError.UnknownCommand(name)` — a `RawCommand` passed to `SETUSER` is not known to the driver.
- `AclError.DecodingFailure(msg)` — an `ACL GETUSER`/`ACL LOG` reply could not be decoded into the
  expected shape.

Because `aclGetUser`/`aclLog` decode replies as UTF-8 text, they assume the connection uses a
string-decoding codec. On a connection whose value codec produces non-string values (e.g.
`Array[Byte]`), these two commands fail with an `AclError.DecodingFailure` rather than returning a
partial result.

### See also

For supplying credentials when *connecting* (as opposed to managing users on the server), see
[Authentication](../client.html#authentication-credentials) on the Client page — in particular
`RedisCredentials.UsernameAndPassword`, which authenticates as a Redis 6+ ACL user.
