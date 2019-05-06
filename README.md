redis4cats
==========

[![CircleCI](https://circleci.com/gh/profunktor/redis4cats.svg?style=svg)](https://circleci.com/gh/profunktor/redis4cats)
[![Gitter Chat](https://badges.gitter.im/profunktor.dev/redis4cats.svg)](https://gitter.im/profunktor.dev/redis4cats)
[![Maven Central](https://img.shields.io/maven-central/v/dev.profunktor/redis4cats-effects_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Credis4cats-effects) <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

Redis client built on top of [Cats Effect](https://typelevel.org/cats-effect/), [Fs2](http://fs2.io/) and the async java client [Lettuce](https://lettuce.io/).

`redis4cats` defines two types of API: the main one effect-based using [Cats Effect](https://typelevel.org/cats-effect/) and another one stream-based using [Fs2](http://fs2.io/).

### Effects

- [Connection API](https://redis.io/commands#connection): `ping`
- [Geo API](https://redis.io/commands#geo): `geoadd`, `geohash`, `geopos`, `geodist`, etc.
- [Hashes API](https://redis.io/commands#hash): `hgetall`, `hset`, `hdel`, `hincrby`, etc.
- [Lists API](https://redis.io/commands#list): `rpush`, `lrange`, `lpop`, etc.
- [Server API](https://redis.io/commands#server): `flushall`, etc.
- [Sets API](https://redis.io/commands#set): `sadd`, `scard`, `srem`, `spop`, etc.
- [Sorted Sets API](https://redis.io/commands#sorted_set): `zcount`, `zcard`, `zrangebyscore`, `zrank`, etc.
- [Strings API](https://redis.io/commands#string): `get`, `set`, `del`, `expire`, etc (includes some generic methods).

### Streams

- [PubSub API](https://redis.io/topics/pubsub) implemented on top of `fs2` streams.
- [Streams API](https://redis.io/topics/streams-intro) experimental API, subject to changes (WIP).
  + High-level API offers `read` and `append` using the underlying commands `XREAD` and `XADD` respectively.
  + Consumer Groups are yet not implemented.

Other features are not considered at the moment but PRs and suggestions are very welcome.

## Dependencies

Add this to your `build.sbt` for the Effects API (depends on `cats-effect`):

```
libraryDependencies += "dev.profunktor" %% "redis4cats-effects" % Version
```

And this for the Streams API (depends on `fs2` and `cats-effect`):

```
libraryDependencies += "dev.profunktor" %% "redis4cats-streams" % Version
```

Note: previous artifacts `<= 0.8.0-RC1` were published using the `com.github.gvolpe` group id (see [migration
guide](https://github.com/profunktor/redis4cats/wiki/Migration-guide-(Vim))).

### Log4cats support

`redis4cats` needs a logger for internal use and provides instances for `log4cats`. It is the recommended logging library:

```
libraryDependencies += "dev.profunktor" %% "redis4cats-log4cats" % Version
```

## Running the tests locally

Either change the `startContainer` boolean in the `DockerRedis.scala` file or start the `redis` client manually using `docker`:

```bash
# single server
docker run -p 6379:6379 redis:5.0.0

# cluster server
docker run -p 30001:30001 -p 30002:30002 -p 30003:30003 -p 30004:30004 -p 30005:30005 -p 30006:30006 m0stwanted/redis-cluster:latest
```

## Code of Conduct

See the [Code of Conduct](https://redis4cats.profunktor.dev/CODE_OF_CONDUCT)

## LICENSE

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with
the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
