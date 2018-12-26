fs2-redis
=========

[![Build Status](https://travis-ci.org/gvolpe/fs2-redis.svg?branch=master)](https://travis-ci.org/gvolpe/fs2-redis)
[![Gitter Chat](https://badges.gitter.im/fs2-redis/fs2-redis.svg)](https://gitter.im/fs2-redis/fs2-redis)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.gvolpe/fs2-redis-effects_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Cfs2-redis-effects) <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

Redis stream-based client built on top of [Cats Effect](https://typelevel.org/cats-effect/), [Fs2](http://fs2.io/) and the async java client [Lettuce](https://lettuce.io/).

`fs2-redis` defines two types of API: one Stream-based using [Fs2](https://functional-streams-for-scala.github.io/fs2/) and another Effect-based using [Cats Effect](https://typelevel.org/cats-effect/).

### Streams

- [PubSub API](https://redis.io/topics/pubsub) implemented on top of `fs2` streams.
- [Streams API](https://redis.io/topics/streams-intro) experimental API, subject to changes (WIP).
  + High-level API offers `read` and `append` using the underlying commands `XREAD` and `XADD` respectively.
  + Consumer Groups are yet not implemented.

### Effects

- [Connection API](https://redis.io/commands#connection): `ping`
- [Geo API](https://redis.io/commands#geo): `geoadd`, `geohash`, `geopos`, `geodist`, etc.
- [Hashes API](https://redis.io/commands#hash): `hgetall`, `hset`, `hdel`, `hincrby`, etc.
- [Lists API](https://redis.io/commands#list): `rpush`, `lrange`, `lpop`, etc.
- [Server API](https://redis.io/commands#server): `flushall`, etc.
- [Sets API](https://redis.io/commands#set): `sadd`, `scard`, `srem`, `spop`, etc.
- [Sorted Sets API](https://redis.io/commands#sorted_set): `zcount`, `zcard`, `zrangebyscore`, `zrank`, etc.
- [Strings API](https://redis.io/commands#string): `get`, `set`, `del`, `expire`, etc (includes some generic methods).

Other features are not considered at the moment but PRs and suggestions are very welcome.

## Dependencies

Add this to your `build.sbt` for the Effects API (depends on `cats-effect`):

```
libraryDependencies += "com.github.gvolpe" %% "fs2-redis-effects" % Version
```

And this for the Streams API (depends on `fs2` and `cats-effect`):

```
libraryDependencies += "com.github.gvolpe" %% "fs2-redis-streams" % Version
```

## LICENSE

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except in compliance with
the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
