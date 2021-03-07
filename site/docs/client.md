---
layout: docs
title:  "Client"
number: 2
position: 2
---

# Redis Client

`RedisClient` is the interface managing all the connections with Redis. We can establish a single-node or a cluster connection with it. A client can be re-used to establish as many connections as needed (recommended). However, if your use case is quite simple, you can opt for a default client to be created for you.

## Establishing connection

For all the effect-based APIs the process of acquiring a client and a commands connection is quite similar, and they all return a `Resource`.

Let's have a look at the following example, which acquires a connection to the `Strings API`:

```scala mdoc:silent
import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.log4cats._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8

val commandsApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    client <- RedisClient[IO].from("redis://localhost")
    redis  <- Redis[IO].fromClient(client, stringCodec)
  } yield redis
```

`Redis[IO].fromClient` returns a `Resource[IO, RedisCommands[IO, K, V]]`, but here we're downcasting to a more specific API. This is not necessary but it shows how you can have more control over what commands you want a specific function to have access to. For the `Strings API` is `StringCommands`, for `Sorted Sets API` is `SortedSetCommands`, and so on. For a complete list please take a look at the [algebras](https://github.com/profunktor/redis4cats/tree/master/modules/effects/src/main/scala/dev/profunktor/redis4cats/algebra).

Acquiring a connection using `fromClient`, we can share the same `RedisClient` to establish many different connections. If you don't need this, have a look at the following sections.

### Client configuration

When you create a `RedisClient`, it will use sane defaults for timeouts, auto-reconnection, etc. These defaults can be customized by providing a `io.lettuce.core.ClientOptions` as well as the `RedisURI`.

```scala mdoc:silent
import dev.profunktor.redis4cats.config._
import io.lettuce.core.{ ClientOptions, TimeoutOptions }
import java.time.Duration

val mkOpts: IO[ClientOptions] =
  IO {
    ClientOptions.builder()
     .autoReconnect(false)
     .pingBeforeActivateConnection(false)
     .timeoutOptions(
       TimeoutOptions.builder()
        .fixedTimeout(Duration.ofSeconds(10))
        .build()
     )
     .build()
  }

val api: Resource[IO, StringCommands[IO, String, String]] =
  for {
    opts   <- Resource.liftF(mkOpts)
    client <- RedisClient[IO].withOptions("redis://localhost", opts)
    redis  <- Redis[IO].fromClient(client, stringCodec)
  } yield redis
```

Furthermore, you can pass a customized `Redis4CatsConfig` to configure behaviour which isn't covered by `io.lettuce.core.ClientOptions`:

```scala mdoc:silent
import scala.concurrent.duration._

val config = Redis4CatsConfig().withShutdown(ShutdownConfig(1.seconds, 5.seconds))

val configuredApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(RedisURI.make[IO]("redis://localhost"))
    opts   <- Resource.liftF(mkOpts)
    client <- RedisClient[IO].custom(uri, opts, config)
    redis  <- Redis[IO].fromClient(client, stringCodec)
  } yield redis
```

## Single node connection

For those who only need a simple API access to Redis commands, there are a few ways to acquire a connection:

```scala mdoc:silent
val simpleApi: Resource[IO, StringCommands[IO, String, String]] =
  Redis[IO].simple("redis://localhost", RedisCodec.Ascii)
```

A simple connection with custom client options:

```scala mdoc:silent
val simpleOptsApi: Resource[IO, StringCommands[IO, String, String]] =
  Resource.liftF(IO(ClientOptions.create())).flatMap { opts =>
    Redis[IO].withOptions("redis://localhost", opts, RedisCodec.Ascii)
  }
```

Or the most common one:

```scala mdoc:silent
val utf8Api: Resource[IO, StringCommands[IO, String, String]] =
  Redis[IO].utf8("redis://localhost")
```

## Logger

In order to create a client and/or connection you must provide a `Log` instance that the library uses for internal logging. You could either use `log4cats` (recommended), one of the simpler instances such as `NoOp` and `Stdout`, or roll your own. `redis4cats` can derive an instance of `Log[F]` if there is an instance of `Logger[F]` in scope, just need to add the extra dependency `redis4cats-log4cats` and `import dev.profunktor.redis4cats.log4cats._`.

Take a look at the [examples](https://github.com/profunktor/redis4cats/blob/master/modules/examples/src/main/scala/dev/profunktor/redis4cats/LoggerIOApp.scala) to find out more.

### Disable logging

If you don't need logging at all, use the following import wherever a `Log` instance is required:

```scala
// Available for any `Applicative[F]`
import dev.profunktor.redis4cats.effect.Log.NoOp._
```

If you need simple logging to STDOUT for quick debugging, you can use the following one:

```scala
// Available for any `Sync[F]`
import dev.profunktor.redis4cats.effect.Log.Stdout._
```

## Standalone, Sentinel or Cluster

You can connect in any of these modes by either using `JRedisURI.create` or `JRedisURI.Builder`. More information
[here](https://github.com/lettuce-io/lettuce-core/wiki/Redis-URI-and-connection-details).

## Cluster connection

The process looks mostly like standalone connection but with small differences.

```scala mdoc:silent
val clusterApi: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri    <- Resource.liftF(RedisURI.make[IO]("redis://localhost:30001"))
    client <- RedisClusterClient[IO](uri)
    redis  <- Redis[IO].fromClusterClient(client, stringCodec)()
  } yield redis
```

You can also make it simple if you don't need to re-use the client.

```scala mdoc:silent
val clusterUtf8Api: Resource[IO, StringCommands[IO, String, String]] =
  Redis[IO].clusterUtf8("redis://localhost:30001")()
```

## Master / Replica connection

The process is a bit different. First of all, you don't need to create a `RedisClient`, it'll be created for you. All you need is `RedisMasterReplica` that exposes two different constructors as `Resource`.

```scala
def make[K, V](
    codec: RedisCodec[K, V],
    uris: RedisURI*
)(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplica[K, V]]
```

And a way to customize the underlying client options.

```scala
def withOptions[K, V](
    codec: RedisCodec[K, V],
    opts: ClientOptions,
    uris: RedisURI*
)(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplica[K, V]]
```

### Example using the Strings API

```scala mdoc:silent
import cats.effect.{IO, Resource}
import cats.implicits._
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.StringCommands
import dev.profunktor.redis4cats.connection.RedisMasterReplica
import dev.profunktor.redis4cats.data.ReadFrom

val commands: Resource[IO, StringCommands[IO, String, String]] =
  for {
    uri <- Resource.liftF(RedisURI.make[IO]("redis://localhost"))
    conn <- RedisMasterReplica[IO].make(RedisCodec.Utf8, uri)(ReadFrom.UpstreamPreferred.some)
    cmds <- Redis[IO].masterReplica(conn)
  } yield cmds

commands.use { cmd =>
  cmd.set("foo", "123") >> IO.unit  // do something
}
```

Find more information [here](https://github.com/lettuce-io/lettuce-core/wiki/Master-Replica#examples).
