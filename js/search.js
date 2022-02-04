// When the user clicks on the search box, we want to toggle the search dropdown
function displayToggleSearch(e) {
  e.preventDefault();
  e.stopPropagation();

  closeDropdownSearch(e);
  
  if (idx === null) {
    console.log("Building search index...");
    prepareIdxAndDocMap();
    console.log("Search index built.");
  }
  const dropdown = document.querySelector("#search-dropdown-content");
  if (dropdown) {
    if (!dropdown.classList.contains("show")) {
      dropdown.classList.add("show");
    }
    document.addEventListener("click", closeDropdownSearch);
    document.addEventListener("keydown", searchOnKeyDown);
    document.addEventListener("keyup", searchOnKeyUp);
  }
}

//We want to prepare the index only after clicking the search bar
var idx = null
const docMap = new Map()

function prepareIdxAndDocMap() {
  const docs = [  
    {
      "title": "Code of Conduct",
      "url": "/CODE_OF_CONDUCT.html",
      "content": "Code of Conduct We are committed to providing a friendly, safe and welcoming environment for all, regardless of level of experience, gender, gender identity and expression, sexual orientation, disability, personal appearance, body size, race, ethnicity, age, religion, nationality, or other such characteristics. Everyone is expected to follow the Scala Code of Conduct when discussing the project on the available communication channels. If you are being harassed, please contact us immediately so that we can support you. ## Moderation For any questions, concerns, or moderation requests please contact a member of the project."
    } ,    
    {
      "title": "Bitmaps",
      "url": "/effects/bitmaps.html",
      "content": "Bitmaps API Purely functional interface for the Bitmaps API. List Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO val testKey = \"foo\" val testKey2 = \"bar\" val testKey3 = \"baz\" def putStrLn(str: String): IO[Unit] = IO(println(str)) commandsApi.use { cmd =&gt; // BitCommands[IO, String, String] for { a &lt;- cmd.setBit(testKey, 7, 1) _ &lt;- cmd.setBit(testKey2, 7, 0) _ &lt;- putStrLn(s\"Set as $a\") b &lt;- cmd.getBit(testKey, 6) _ &lt;- putStrLn(s\"Bit at offset 6 is $b\") _ &lt;- cmd.bitOpOr(testKey3, testKey, testKey2) _ &lt;- for { s1 &lt;- cmd.setBit(\"bitmapsarestrings\", 2, 1) s2 &lt;- cmd.setBit(\"bitmapsarestrings\", 3, 1) s3 &lt;- cmd.setBit(\"bitmapsarestrings\", 5, 1) s4 &lt;- cmd.setBit(\"bitmapsarestrings\", 10, 1) s5 &lt;- cmd.setBit(\"bitmapsarestrings\", 11, 1) s6 &lt;- cmd.setBit(\"bitmapsarestrings\", 14, 1) } yield s1 + s2 + s3 + s4 + s5 + s6 bf &lt;- cmd.bitField( \"inmap\", SetUnsigned(2, 1), SetUnsigned(3, 1), SetUnsigned(5, 1), SetUnsigned(10, 1), SetUnsigned(11, 1), SetUnsigned(14, 1), IncrUnsignedBy(14, 1) ) _ &lt;- putStrLn(s\"Via bitfield $bf\") } yield () }"
    } ,    
    {
      "title": "Client",
      "url": "/client.html",
      "content": "Redis Client RedisClient is the interface managing all the connections with Redis. We can establish a single-node or a cluster connection with it. A client can be re-used to establish as many connections as needed (recommended). However, if your use case is quite simple, you can opt for a default client to be created for you. Establishing connection For all the effect-based APIs the process of acquiring a client and a commands connection is quite similar, and they all return a Resource. Let’s have a look at the following example, which acquires a connection to the Strings API: import cats.effect.{IO, Resource} import dev.profunktor.redis4cats._ import dev.profunktor.redis4cats.algebra.StringCommands import dev.profunktor.redis4cats.connection._ import dev.profunktor.redis4cats.data.RedisCodec import dev.profunktor.redis4cats.log4cats._ import org.typelevel.log4cats.Logger import org.typelevel.log4cats.slf4j.Slf4jLogger implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO] val stringCodec: RedisCodec[String, String] = RedisCodec.Utf8 val commandsApi: Resource[IO, StringCommands[IO, String, String]] = RedisClient[IO] .from(\"redis://localhost\") .flatMap(Redis[IO].fromClient(_, stringCodec)) Redis[IO].fromClient returns a Resource[IO, RedisCommands[IO, K, V]], but here we’re downcasting to a more specific API. This is not necessary but it shows how you can have more control over what commands you want a specific function to have access to. For the Strings API is StringCommands, for Sorted Sets API is SortedSetCommands, and so on. For a complete list please take a look at the algebras. Acquiring a connection using fromClient, we can share the same RedisClient to establish many different connections. If you don’t need this, have a look at the following sections. Client configuration When you create a RedisClient, it will use sane defaults for timeouts, auto-reconnection, etc. These defaults can be customized by providing a io.lettuce.core.ClientOptions as well as the RedisURI. import dev.profunktor.redis4cats.config._ import io.lettuce.core.{ ClientOptions, TimeoutOptions } import java.time.Duration val mkOpts: IO[ClientOptions] = IO { ClientOptions.builder() .autoReconnect(false) .pingBeforeActivateConnection(false) .timeoutOptions( TimeoutOptions.builder() .fixedTimeout(Duration.ofSeconds(10)) .build() ) .build() } val api: Resource[IO, StringCommands[IO, String, String]] = for { opts &lt;- Resource.eval(mkOpts) client &lt;- RedisClient[IO].withOptions(\"redis://localhost\", opts) redis &lt;- Redis[IO].fromClient(client, stringCodec) } yield redis Furthermore, you can pass a customized Redis4CatsConfig to configure behaviour which isn’t covered by io.lettuce.core.ClientOptions: import scala.concurrent.duration._ val config = Redis4CatsConfig().withShutdown(ShutdownConfig(1.seconds, 5.seconds)) val configuredApi: Resource[IO, StringCommands[IO, String, String]] = for { uri &lt;- Resource.eval(RedisURI.make[IO](\"redis://localhost\")) opts &lt;- Resource.eval(mkOpts) client &lt;- RedisClient[IO].custom(uri, opts, config) redis &lt;- Redis[IO].fromClient(client, stringCodec) } yield redis Single node connection For those who only need a simple API access to Redis commands, there are a few ways to acquire a connection: val simpleApi: Resource[IO, StringCommands[IO, String, String]] = Redis[IO].simple(\"redis://localhost\", RedisCodec.Ascii) A simple connection with custom client options: val simpleOptsApi: Resource[IO, StringCommands[IO, String, String]] = Resource.eval(IO(ClientOptions.create())).flatMap { opts =&gt; Redis[IO].withOptions(\"redis://localhost\", opts, RedisCodec.Ascii) } Or the most common one: val utf8Api: Resource[IO, StringCommands[IO, String, String]] = Redis[IO].utf8(\"redis://localhost\") Logger In order to create a client and/or connection you must provide a Log instance that the library uses for internal logging. You could either use log4cats (recommended), one of the simpler instances such as NoOp and Stdout, or roll your own. redis4cats can derive an instance of Log[F] if there is an instance of Logger[F] in scope, just need to add the extra dependency redis4cats-log4cats and import dev.profunktor.redis4cats.log4cats._. Take a look at the examples to find out more. Disable logging If you don’t need logging at all, use the following import wherever a Log instance is required: // Available for any `Applicative[F]` import dev.profunktor.redis4cats.effect.Log.NoOp._ If you need simple logging to STDOUT for quick debugging, you can use the following one: // Available for any `Sync[F]` import dev.profunktor.redis4cats.effect.Log.Stdout._ Standalone, Sentinel or Cluster You can connect in any of these modes by either using JRedisURI.create or JRedisURI.Builder. More information here. Cluster connection The process looks mostly like standalone connection but with small differences. val clusterApi: Resource[IO, StringCommands[IO, String, String]] = for { uri &lt;- Resource.eval(RedisURI.make[IO](\"redis://localhost:30001\")) client &lt;- RedisClusterClient[IO](uri) redis &lt;- Redis[IO].fromClusterClient(client, stringCodec)() } yield redis You can also make it simple if you don’t need to re-use the client. val clusterUtf8Api: Resource[IO, StringCommands[IO, String, String]] = Redis[IO].clusterUtf8(\"redis://localhost:30001\")() Master / Replica connection The process is a bit different. First of all, you don’t need to create a RedisClient, it’ll be created for you. All you need is RedisMasterReplica that exposes two different constructors as Resource. def make[K, V]( codec: RedisCodec[K, V], uris: RedisURI* )(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplica[K, V]] And a way to customize the underlying client options. def withOptions[K, V]( codec: RedisCodec[K, V], opts: ClientOptions, uris: RedisURI* )(readFrom: Option[JReadFrom] = None): Resource[F, RedisMasterReplica[K, V]] Example using the Strings API import cats.effect.{IO, Resource} import cats.implicits._ import dev.profunktor.redis4cats.Redis import dev.profunktor.redis4cats.algebra.StringCommands import dev.profunktor.redis4cats.connection.RedisMasterReplica import dev.profunktor.redis4cats.data.ReadFrom val commands: Resource[IO, StringCommands[IO, String, String]] = for { uri &lt;- Resource.eval(RedisURI.make[IO](\"redis://localhost\")) conn &lt;- RedisMasterReplica[IO].make(RedisCodec.Utf8, uri)(ReadFrom.UpstreamPreferred.some) redis &lt;- Redis[IO].masterReplica(conn) } yield redis commands.use { redis =&gt; redis.set(\"foo\", \"123\") &gt;&gt; IO.unit // do something } Find more information here."
    } ,    
    {
      "title": "Codecs",
      "url": "/codecs.html",
      "content": "Codecs Redis is a key-value store, and as such, it is commonly used to store simple values in a “stringy” form. Redis4Cats parameterizes the type of keys and values, allowing you to provide the desired RedisCodec. The most common one is RedisCodec.Utf8 but there’s also a RedisCodec.Ascii and a RedisCodec.Bytes as well. You can also manipulate existing codecs. The RedisCodec object exposes a few functions for this purpose. Compression There are two functions available: deflate and gzip. Here’s an example using the latter: import dev.profunktor.redis4cats.data.RedisCodec RedisCodec.gzip(RedisCodec.Utf8) It manipulates an existing codec to add compression support. Encryption In the same spirit, there’s another function secure, which takes two extra arguments for encryption and decryption, respectively. These two extra arguments are of type CipherSupplier. You can either create your own or use the provided functions, which are effectful. import cats.effect._ import javax.crypto.spec.SecretKeySpec def mkCodec(key: SecretKeySpec): IO[RedisCodec[String, String]] = for { e &lt;- RedisCodec.encryptSupplier[IO](key) d &lt;- RedisCodec.decryptSupplier[IO](key) } yield RedisCodec.secure(RedisCodec.Utf8, e, d) Deriving codecs Redis4Cats defines a SplitEpi datatype, which stands for Split Epimorphism, as explained by Rob Norris at Scala eXchange 2018. It sounds more complicated than it actually is. Here’s its definition: final case class SplitEpi[A, B]( get: A =&gt; B, reverseGet: B =&gt; A ) extends (A =&gt; B) Under the dev.profunktor.redis4cats.codecs.splits._ package, you will find useful SplitEpi implementations for codecs. val stringDoubleEpi: SplitEpi[String, Double] = SplitEpi(s =&gt; Try(s.toDouble).getOrElse(0), _.toString) val stringLongEpi: SplitEpi[String, Long] = SplitEpi(s =&gt; Try(s.toLong).getOrElse(0), _.toString) val stringIntEpi: SplitEpi[String, Int] = SplitEpi(s =&gt; Try(s.toInt).getOrElse(0), _.toString) Given a SplitEpi, we can derive a RedisCodec from an existing one. For example: import dev.profunktor.redis4cats.codecs.Codecs import dev.profunktor.redis4cats.codecs.splits._ val longCodec: RedisCodec[String, Long] = Codecs.derive(RedisCodec.Utf8, stringLongEpi) This is the most common kind of derivation. That is, the one that operates on the value type V since keys are most of the time treated as strings. However, if you wish to derive a codec that also modifies the key type K, you can do it by supplying another SplitEpi instance for keys. import dev.profunktor.redis4cats.codecs.Codecs import dev.profunktor.redis4cats.codecs.splits._ import dev.profunktor.redis4cats.data.RedisCodec case class Keys(value: String) val keysSplitEpi: SplitEpi[String, Keys] = SplitEpi(Keys.apply, _.value) val newCodec: RedisCodec[Keys, Long] = Codecs.derive(RedisCodec.Utf8, keysSplitEpi, stringLongEpi) Json codecs In the same way we derived simple codecs, we could have one for Json, in case we are only storing values of a single type. For example, say we have the following algebraic data type (ADT): sealed trait Event object Event { case class Ack(id: Long) extends Event case class Message(id: Long, payload: String) extends Event case object Unknown extends Event } We can define a SplitEpi[String, Event] that handles the Json encoding and decoding in the following way: import dev.profunktor.redis4cats.codecs.splits.SplitEpi import dev.profunktor.redis4cats.effect.Log.NoOp._ import io.circe.generic.auto._ import io.circe.parser.{ decode =&gt; jsonDecode } import io.circe.syntax._ val eventSplitEpi: SplitEpi[String, Event] = SplitEpi[String, Event]( str =&gt; jsonDecode[Event](str).getOrElse(Event.Unknown), _.asJson.noSpaces ) We can then proceed to derive a RedisCodec[String, Event] from an existing one. import dev.profunktor.redis4cats.codecs.Codecs import dev.profunktor.redis4cats.data.RedisCodec val eventsCodec: RedisCodec[String, Event] = Codecs.derive(RedisCodec.Utf8, eventSplitEpi) Finally, we can put all the pieces together to acquire a RedisCommands[IO, String, Event]. import dev.profunktor.redis4cats.Redis import dev.profunktor.redis4cats.effect.Log.NoOp._ val eventsKey = \"events\" Redis[IO].simple(\"redis://localhost\", eventsCodec) .use { redis =&gt; for { x &lt;- redis.sCard(eventsKey) _ &lt;- IO(println(s\"Number of events: $x\")) _ &lt;- redis.sAdd(eventsKey, Event.Ack(1), Event.Message(23, \"foo\")) y &lt;- redis.sMembers(eventsKey) _ &lt;- IO(println(s\"Events: $y\")) } yield () } The full compiling example can be found here. Although it is possible to derive a Json codec in this way, it is mainly preferred to use a simple codec like RedisCodec.Utf8 and manage the encoding / decoding yourself (separation of concerns). In this way, you can have a single active Redis connection for more than one type of message."
    } ,    
    {
      "title": "Connection",
      "url": "/effects/connection.html",
      "content": "Connection API Purely functional interface for the Connection API. Connection Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO def putStrLn(str: String): IO[Unit] = IO(println(str)) commandsApi.use { redis =&gt; // ConnectionCommands[IO] redis.ping.flatMap(putStrLn) // \"pong\" }"
    } ,    
    {
      "title": "Geo",
      "url": "/effects/geo.html",
      "content": "Geo API Purely functional interface for the Geo API. Geo Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO import dev.profunktor.redis4cats.effects._ import io.lettuce.core.GeoArgs val testKey = \"location\" def putStrLn(str: String): IO[Unit] = IO(println(str)) val _BuenosAires = GeoLocation(Longitude(-58.3816), Latitude(-34.6037), \"Buenos Aires\") val _RioDeJaneiro = GeoLocation(Longitude(-43.1729), Latitude(-22.9068), \"Rio de Janeiro\") val _Montevideo = GeoLocation(Longitude(-56.164532), Latitude(-34.901112), \"Montevideo\") val _Tokyo = GeoLocation(Longitude(139.6917), Latitude(35.6895), \"Tokyo\") commandsApi.use { redis =&gt; // GeoCommands[IO, String, String] for { _ &lt;- redis.geoAdd(testKey, _BuenosAires) _ &lt;- redis.geoAdd(testKey, _RioDeJaneiro) _ &lt;- redis.geoAdd(testKey, _Montevideo) _ &lt;- redis.geoAdd(testKey, _Tokyo) x &lt;- redis.geoDist(testKey, _BuenosAires.value, _Tokyo.value, GeoArgs.Unit.km) _ &lt;- putStrLn(s\"Distance from ${_BuenosAires.value} to Tokyo: $x km\") y &lt;- redis.geoPos(testKey, _RioDeJaneiro.value) _ &lt;- putStrLn(s\"Geo Pos of ${_RioDeJaneiro.value}: ${y.headOption}\") z &lt;- redis.geoRadius(testKey, GeoRadius(_Montevideo.lon, _Montevideo.lat, Distance(10000.0)), GeoArgs.Unit.km) _ &lt;- putStrLn(s\"Geo Radius in 1000 km: $z\") } yield () }"
    } ,    
    {
      "title": "Hashes",
      "url": "/effects/hashes.html",
      "content": "Hashes API Purely functional interface for the Hashes API. Hash Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO val testKey = \"foo\" val testField = \"bar\" def putStrLn(str: String): IO[Unit] = IO(println(str)) val showResult: Option[String] =&gt; IO[Unit] = _.fold(putStrLn(s\"Not found key: $testKey | field: $testField\"))(s =&gt; putStrLn(s)) commandsApi.use { redis =&gt; // HashCommands[IO, String, String] for { x &lt;- redis.hGet(testKey, testField) _ &lt;- showResult(x) _ &lt;- redis.hSet(testKey, testField, \"some value\") y &lt;- redis.hGet(testKey, testField) _ &lt;- showResult(y) _ &lt;- redis.hSetNx(testKey, testField, \"should not happen\") w &lt;- redis.hGet(testKey, testField) _ &lt;- showResult(w) _ &lt;- redis.hDel(testKey, testField) z &lt;- redis.hGet(testKey, testField) _ &lt;- showResult(z) } yield () }"
    } ,    
    {
      "title": "Effects API",
      "url": "/effects/",
      "content": "Effects API The API that operates at the effect level F[_] on top of cats-effect. Bitmaps API Connection API Geo API Hashes API Lists API Scripting API Server API Sets API Sorted Sets API Strings API"
    } ,    
    {
      "title": "Streams API",
      "url": "/streams/",
      "content": "Streams API The API that operates at the stream level Stream[F[_], A] on top of fs2. PubSub: Simple, safe and pure functional streaming client to interact with Redis PubSub. Streams: High-level, safe and pure functional API on top of Redis Streams."
    } ,    
    {
      "title": "Home",
      "url": "/",
      "content": "redis4cats Redis client built on top of Cats Effect, Fs2 and the async Java client Lettuce. Quick Start import cats.effect._ import cats.implicits._ import dev.profunktor.redis4cats.Redis import dev.profunktor.redis4cats.effect.Log.Stdout._ object QuickStart extends IOApp.Simple { def run: IO[Unit] = Redis[IO].utf8(\"redis://localhost\").use { redis =&gt; for { _ &lt;- redis.set(\"foo\", \"123\") x &lt;- redis.get(\"foo\") _ &lt;- redis.setNx(\"foo\", \"should not happen\") y &lt;- redis.get(\"foo\") _ &lt;- IO(println(x === y)) // true } yield () } } The API is quite stable and heavily used in production. However, binary compatibility is not guaranteed across versions for now. If you like it, give it a ⭐ ! If you think we could do better, please let us know! Versions The 1.x.x series is built on Cats Effect 3 whereas the 0.x.x series is built on Cats Effect 2. Dependencies Add this to your build.sbt for the Effects API (depends on cats-effect): libraryDependencies += \"dev.profunktor\" %% \"redis4cats-effects\" % Version Add this for the Streams API (depends on fs2 and cats-effect): libraryDependencies += \"dev.profunktor\" %% \"redis4cats-streams\" % Version Log4cats support redis4cats needs a logger for internal use and provides instances for log4cats. It is the recommended logging library: libraryDependencies += \"dev.profunktor\" %% \"redis4cats-log4cats\" % Version Running the tests locally Start both a single Redis node and a cluster using docker-compose: &gt; docker-compose up &gt; sbt +test If you are trying to run cluster mode tests on macOS you might receive host not found errors. As a workaround add new environment variable in docker-compose.yml for RedisCluster: IP=0.0.0.0 The environment section should look like this: environment: - INITIAL_PORT=30001 - DEBUG=false - IP=0.0.0.0 Code of Conduct See the Code of Conduct LICENSE Licensed under the Apache License, Version 2.0 (the “License”); you may not use this project except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License."
    } ,    
    {
      "title": "Keys",
      "url": "/effects/keys.html",
      "content": "Keys API Purely functional interface for the Keys API. key Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO val key = \"users\" commandsApi.use { redis =&gt; // KeyCommands[IO, String] for { _ &lt;- redis.del(key) _ &lt;- redis.exists(key) _ &lt;- redis.expire(key, Duration(5, SECONDS)) } yield () }"
    } ,      
    {
      "title": "Lists",
      "url": "/effects/lists.html",
      "content": "Lists API Purely functional interface for the Lists API. List Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO val testKey = \"listos\" def putStrLn(str: String): IO[Unit] = IO(println(str)) commandsApi.use { redis =&gt; // ListCommands[IO, String, String] for { _ &lt;- redis.rPush(testKey, \"one\", \"two\", \"three\") x &lt;- redis.lRange(testKey, 0, 10) _ &lt;- putStrLn(s\"Range: $x\") y &lt;- redis.lLen(testKey) _ &lt;- putStrLn(s\"Length: $y\") a &lt;- redis.lPop(testKey) _ &lt;- putStrLn(s\"Left Pop: $a\") b &lt;- redis.rPop(testKey) _ &lt;- putStrLn(s\"Right Pop: $b\") z &lt;- redis.lRange(testKey, 0, 10) _ &lt;- putStrLn(s\"Range: $z\") } yield () }"
    } ,    
    {
      "title": "Pipelining",
      "url": "/pipelining.html",
      "content": "Pipelining Use pipelining to speed up your queries by having full control of commands flushing. By default Redis works in autoflush mode but it can be disabled to “pipeline” commands to the server without waiting for a response. And at any point in time you can “flush commands”. redis4cats provides a RedisPipeline utility that models this behavior with some guarantees described below: acquire: disable autoflush and send a bunch of commands defined as a custom HList. release: either flush commands on success or log error on failure / cancellation. guarantee: re-enable autoflush. Caveats ⚠️ Pipelining shares the same asynchronous implementation of transactions, meaning the order of the commands cannot be guaranteed. ⚠️ This statement means that given the following set of operations. val operations = redis.set(key1, \"osx\") :: redis.set(key2, \"linux\") :: redis.get(key1) :: redis.set(key1, \"bar\") :: redis.set(key2, \"foo\") :: redis.get(key1) :: HNil The result of those two get operations will not be deterministic. RedisPipeline usage The API for disabling / enabling autoflush and flush commands manually is available for you to use but since the pattern is so common it is recommended to just use RedisPipeline. You can create a pipeline by passing the commands API as a parameter and invoke the exec function (or filterExec) given the set of commands you wish to send to the server. Note that every command has to be forked (.start) because the commands need to be sent to the server in an asynchronous way but no response will be received until the commands are successfully flushed. Also, it is not possible to sequence commands (flatMap) that are part of a pipeline. Every command has to be atomic and independent of previous results. import cats.effect.IO import cats.implicits._ import dev.profunktor.redis4cats._ import dev.profunktor.redis4cats.hlist._ import dev.profunktor.redis4cats.pipeline._ import java.util.concurrent.TimeoutException def putStrLn(str: String): IO[Unit] = IO(println(str)) val key1 = \"testp1\" val key2 = \"testp2\" val key3 = \"testp3\" val showResult: String =&gt; Option[String] =&gt; IO[Unit] = key =&gt; _.fold(putStrLn(s\"Not found key: $key\"))(s =&gt; putStrLn(s\"$key: $s\")) commandsApi.use { redis =&gt; // RedisCommands[IO, String, String] val getters = redis.get(key1).flatTap(showResult(key1)) &gt;&gt; redis.get(key2).flatTap(showResult(key2)) &gt;&gt; redis.get(key3).flatTap(showResult(key3)) val operations = redis.set(key1, \"osx\") :: redis.get(key3) :: redis.set(key2, \"linux\") :: redis.sIsMember(\"foo\", \"bar\") :: HNil val runPipeline = RedisPipeline(redis) .filterExec(operations) .map { case res1 ~: res2 ~: HNil =&gt; assert(res1.contains(\"3\")) assert(!res2) } .onError { case PipelineError =&gt; putStrLn(\"[Error] - Pipeline failed\") case _: TimeoutException =&gt; putStrLn(\"[Error] - Timeout\") case e =&gt; putStrLn(s\"[Error] - $e\") } val prog = for { _ &lt;- redis.set(key3, \"3\") _ &lt;- runPipeline v1 &lt;- redis.get(key1) v2 &lt;- redis.get(key2) } yield { assert(v1.contains(\"osx\")) assert(v2.contains(\"linux\")) } getters &gt;&gt; prog &gt;&gt; getters &gt;&gt; putStrLn(\"keep doing stuff...\") } The filterExec function filters out values of type Unit, which are normally irrelevant. If you find yourself needing the Unit types to verify some behavior, use exec instead."
    } ,    
    {
      "title": "PubSub",
      "url": "/streams/pubsub.html",
      "content": "PubSub Simple, safe and pure functional streaming client to interact with Redis PubSub. Establishing a connection There are three options available in the PubSub interpreter: mkPubSubConnection: Whenever you need one or more subscribers and publishers / stats. mkSubscriberConnection: When all you need is one or more subscribers but no publishing / stats. mkPublisherConnection: When all you need is to publish / stats. Note: cluster support is not implemented yet. Subscriber trait SubscribeCommands[F[_], K, V] { def subscribe(channel: RedisChannel[K]): F[V] def unsubscribe(channel: RedisChannel[K]): F[Unit] } When using the PubSub interpreter the types will be Stream[F, V] and Stream[F, Unit] respectively. Publisher / PubSubStats trait PubSubStats[F[_], K] { def pubSubChannels: F[List[K]] def pubSubSubscriptions(channel: RedisChannel[K]): F[Subscription[K]] def pubSubSubscriptions(channels: List[RedisChannel[K]]): F[List[Subscription[K]]] } trait PublishCommands[F[_], K, V] extends PubSubStats[F, K] { def publish(channel: RedisChannel[K]): F[V] =&gt; F[Unit] } When using the PubSub interpreter the publish function will be defined as a Sink[F, V] that can be connected to a Stream[F, ?] source. PubSub example import cats.effect._ import cats.syntax.all._ import dev.profunktor.redis4cats.connection.RedisClient import dev.profunktor.redis4cats.data._ import dev.profunktor.redis4cats.pubsub.PubSub import dev.profunktor.redis4cats.log4cats._ import fs2.{Pipe, Stream} import org.typelevel.log4cats.Logger import org.typelevel.log4cats.slf4j.Slf4jLogger import scala.concurrent.duration._ import scala.util.Random object PubSubDemo extends IOApp.Simple { implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO] private val stringCodec = RedisCodec.Utf8 private val eventsChannel = RedisChannel(\"events\") private val gamesChannel = RedisChannel(\"games\") def sink(name: String): Pipe[IO, String, Unit] = _.evalMap(x =&gt; IO(println(s\"Subscriber: $name &gt;&gt; $x\"))) val program: Stream[IO, Unit] = for { client &lt;- Stream.resource(RedisClient[IO].from(\"redis://localhost\")) pubSub &lt;- Stream.resource(PubSub.mkPubSubConnection[IO, String, String](client, stringCodec)) sub1 = pubSub.subscribe(eventsChannel) sub2 = pubSub.subscribe(gamesChannel) pub1 = pubSub.publish(eventsChannel) pub2 = pubSub.publish(gamesChannel) _ &lt;- Stream( sub1.through(sink(\"#events\")), sub2.through(sink(\"#games\")), Stream.awakeEvery[IO](3.seconds) &gt;&gt; Stream.eval(IO(Random.nextInt(100).toString)).through(pub1), Stream.awakeEvery[IO](5.seconds) &gt;&gt; Stream.emit(\"Pac-Man!\").through(pub2), Stream.awakeDelay[IO](11.seconds) &gt;&gt; pubSub.unsubscribe(gamesChannel), Stream.awakeEvery[IO](6.seconds) &gt;&gt; pubSub .pubSubSubscriptions(List(eventsChannel, gamesChannel)) .evalMap(x =&gt; IO(println(x))) ).parJoin(6).void } yield () def run: IO[Unit] = program.compile.drain }"
    } ,    
    {
      "title": "Quick Start",
      "url": "/quickstart.html",
      "content": "Quick Start import cats.effect._ import cats.implicits._ import dev.profunktor.redis4cats.Redis import dev.profunktor.redis4cats.effect.Log.Stdout._ object QuickStart extends IOApp.Simple { def run: IO[Unit] = Redis[IO].utf8(\"redis://localhost\").use { redis =&gt; for { _ &lt;- redis.set(\"foo\", \"123\") x &lt;- redis.get(\"foo\") _ &lt;- redis.setNx(\"foo\", \"should not happen\") y &lt;- redis.get(\"foo\") _ &lt;- IO(println(x === y)) // true } yield () } } This is the simplest way to get up and running with a single-node Redis connection. To learn more about commands, clustering, pipelining and transactions, please have a look at the extensive documentation. You can continue reading about the different ways of acquiring a client and a connection here."
    } ,    
    {
      "title": "Scripting",
      "url": "/effects/scripting.html",
      "content": "Scripting API Purely functional interface for the Scripting API. Script Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO def putStrLn(str: String): IO[Unit] = IO(println(str)) commandsApi.use { redis =&gt; // ScriptCommands[IO, String, String] for { // returns a String according the value codec (the last type parameter of ScriptCommands) greeting &lt;- redis.eval(\"return 'Hello World'\", ScriptOutputType.Value) _ &lt;- putStrLn(s\"Greetings from Lua: $greeting\") } yield () } The return type depends on the ScriptOutputType you pass and needs to suite the result of the Lua script itself. Possible values are Integer, Value (for decoding the result using the value codec), Multi (for many values) and Status (maps to Unit in Scala). Scripts can be cached for better performance using scriptLoad and then executed via evalSha, see the redis docs for details."
    } ,      
    {
      "title": "Server",
      "url": "/effects/server.html",
      "content": "Server API Purely functional interface for the Server API. Server Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO def putStrLn(str: String): IO[Unit] = IO(println(str)) commandsApi.use { redis =&gt; // ServerCommands[IO] for { _ &lt;- redis.flushAll _ &lt;- redis.flushAllAsync } yield () }"
    } ,    
    {
      "title": "Sets",
      "url": "/effects/sets.html",
      "content": "Sets API Purely functional interface for the Sets API. Set Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO val testKey = \"foos\" def putStrLn(str: String): IO[Unit] = IO(println(str)) val showResult: Set[String] =&gt; IO[Unit] = x =&gt; putStrLn(s\"$testKey members: $x\") commandsApi.use { redis =&gt; // SetCommands[IO, String, String] for { x &lt;- redis.sMembers(testKey) _ &lt;- showResult(x) _ &lt;- redis.sAdd(testKey, \"set value\") y &lt;- redis.sMembers(testKey) _ &lt;- showResult(y) _ &lt;- redis.sCard(testKey).flatMap(s =&gt; putStrLn(s\"size: ${s.toString}\")) _ &lt;- redis.sRem(\"non-existing\", \"random\") w &lt;- redis.sMembers(testKey) _ &lt;- showResult(w) _ &lt;- redis.sRem(testKey, \"set value\") z &lt;- redis.sMembers(testKey) _ &lt;- showResult(z) _ &lt;- redis.sCard(testKey).flatMap(s =&gt; putStrLn(s\"size: ${s.toString}\")) } yield () }"
    } ,    
    {
      "title": "Sorted Sets",
      "url": "/effects/sortedsets.html",
      "content": "Sorted Sets API Purely functional interface for the Sorted Sets API. Sorted Set Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange} val testKey = \"zztop\" def putStrLn(str: String): IO[Unit] = IO(println(str)) commandsApi.use { redis =&gt; // SortedSetCommands[IO, String, Long] for { _ &lt;- redis.zAdd(testKey, args = None, ScoreWithValue(Score(1), 1), ScoreWithValue(Score(3), 2)) x &lt;- redis.zRevRangeByScore(testKey, ZRange(0, 2), limit = None) _ &lt;- putStrLn(s\"Score: $x\") y &lt;- redis.zCard(testKey) _ &lt;- putStrLn(s\"Size: $y\") z &lt;- redis.zCount(testKey, ZRange(0, 1)) _ &lt;- putStrLn(s\"Count: $z\") } yield () }"
    } ,    
    {
      "title": "Streams",
      "url": "/streams/streams.html",
      "content": "Streams (experimental) High-level, safe and pure functional API on top of Redis Streams. Establishing a connection There are two ways of establishing a connection using the RedisStream interpreter: Single connection def mkStreamingConnection[F[_], K, V]( client: RedisClient, codec: RedisCodec[K, V], uri: RedisURI ): Stream[F, Streaming[Stream[F, ?], K, V]] Master / Replica connection def mkMasterReplicaConnection[F[_], K, V](codec: RedisCodec[K, V], uris: RedisURI*)( readFrom: Option[ReadFrom] = None): Stream[F, Streaming[Stream[F, ?], K, V]] Cluster connection Not implemented yet. Streaming API At the moment there’s only two combinators: trait Streaming[F[_], K, V] { def append: F[XAddMessage[K, V]] =&gt; F[MessageId] def read(keys: Set[K], initialOffset: K =&gt; StreamingOffset[K] = StreamingOffset.All[K]): F[XReadMessage[K, V]] } append can be used as a Sink[F, StreamingMessage[K, V] and read(keys) as a source Stream[F, StreamingMessageWithId[K, V]. Note that Redis allows you to consume from multiple stream keys at the same time. Streaming Example import cats.effect.{IO, IOApp} import cats.syntax.all._ import dev.profunktor.redis4cats.connection.RedisClient import dev.profunktor.redis4cats.data._ import dev.profunktor.redis4cats.log4cats._ import dev.profunktor.redis4cats.streams.RedisStream import dev.profunktor.redis4cats.streams.data._ import fs2.Stream import org.typelevel.log4cats.Logger import org.typelevel.log4cats.slf4j.Slf4jLogger import scala.concurrent.duration._ import scala.util.Random object StreamingExample extends IOApp.Simple { implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO] val stringCodec = RedisCodec.Utf8 def putStrLn[A](a: A): IO[Unit] = IO(println(a)) val streamKey1 = \"demo\" val streamKey2 = \"users\" def randomMessage: Stream[IO, XAddMessage[String, String]] = Stream.eval { val rndKey = IO(Random.nextInt(1000).toString) val rndValue = IO(Random.nextString(10)) (rndKey, rndValue).parMapN { case (k, v) =&gt; XAddMessage(streamKey1, Map(k -&gt; v)) } } def run: IO[Unit] = { for { client &lt;- Stream.resource(RedisClient[IO].from(\"redis://localhost\")) streaming &lt;- RedisStream.mkStreamingConnection[IO, String, String](client, stringCodec) source = streaming.read(Set(streamKey1, streamKey2), chunkSize = 1) appender = streaming.append _ &lt;- Stream( source.evalMap(putStrLn(_)), Stream.awakeEvery[IO](3.seconds) &gt;&gt; randomMessage.through(appender) ).parJoin(2).void } yield () }.compile.drain }"
    } ,    
    {
      "title": "Strings",
      "url": "/effects/strings.html",
      "content": "Strings API Purely functional interface for the Strings API. String Commands usage Once you have acquired a connection you can start using it: import cats.effect.IO val usernameKey = \"users\" def putStrLn(str: String): IO[Unit] = IO(println(str)) val showResult: Option[String] =&gt; IO[Unit] = _.fold(putStrLn(s\"Not found key: $usernameKey\"))(s =&gt; putStrLn(s)) commandsApi.use { redis =&gt; // StringCommands[IO, String, String] for { x &lt;- redis.get(usernameKey) _ &lt;- showResult(x) _ &lt;- redis.set(usernameKey, \"gvolpe\") y &lt;- redis.get(usernameKey) _ &lt;- showResult(y) _ &lt;- redis.setNx(usernameKey, \"should not happen\") w &lt;- redis.get(usernameKey) _ &lt;- showResult(w) } yield () }"
    } ,    
    {
      "title": "Transactions",
      "url": "/transactions.html",
      "content": "Transactions Redis supports transactions via the MULTI, EXEC and DISCARD commands. redis4cats provides a RedisTransaction utility that models a transaction as a Resource. Caveats Note that every command has to be forked (.start) because the commands need to be sent to the server asynchronously and no response will be received until either an EXEC or a DISCARD command is sent. Both forking and sending the final command is handled by RedisTransaction. These are internals, though. All you need to care about is what commands you want to run as part of a transaction and handle the possible errors and retry logic. Concurrent transactions ⚠️ in order to run transactions concurrently, you’d need to acquire a connection per transaction (RedisCommands), as MULTI can not be called concurrently within the same connection. For such cases, it is recommended to share the same RedisClient. ⚠️ Working with transactions The most common way is to create a RedisTransaction once by passing the commands API as a parameter and invoke the exec function (or filterExec) every time you want to run the given commands as part of a new transaction. Every command has to be atomic and independent of previous Redis results, so it is not recommended to chain commands using flatMap. Below you can find a first example of transactional commands. import cats.effect.IO import cats.implicits._ import dev.profunktor.redis4cats._ import dev.profunktor.redis4cats.hlist._ import dev.profunktor.redis4cats.transactions._ import java.util.concurrent.TimeoutException def putStrLn(str: String): IO[Unit] = IO(println(str)) val key1 = \"test1\" val key2 = \"test2\" val key3 = \"test3\" val showResult: String =&gt; Option[String] =&gt; IO[Unit] = key =&gt; _.fold(Log[IO].info(s\"Key not found: $key\"))(s =&gt; Log[IO].info(s\"$key: $s\")) commandsApi.use { redis =&gt; // RedisCommands[IO, String, String] val tx = RedisTransaction(redis) val setters = redis.set(key2, \"delete_me\") &gt;&gt; redis.set(key3, \"foo\") val getters = redis.get(key1).flatTap(showResult(key1)) &gt;&gt; redis.get(key2).flatTap(showResult(key2)) // the commands type is fully inferred // IO[Unit] :: IO[Option[String]] :: IO[Unit] :: HNil val commands = redis.set(key1, \"foo\") :: redis.del(key2) :: redis.get(key3) :: HNil // the result type is inferred as well // Option[String] :: HNil val prog = tx.filterExec(commands) .flatMap { case res1 ~: res2 ~: HNil =&gt; putStrLn(s\"Key2 result: $res1\") &gt;&gt; putStrLn(s\"Key3 result: $res2\") } .onError { case TransactionAborted =&gt; Log[IO].error(\"[Error] - Transaction Aborted\") case TransactionDiscarded =&gt; Log[IO].error(\"[Error] - Transaction Discarded\") case _: TimeoutException =&gt; Log[IO].error(\"[Error] - Timeout\") case e =&gt; Log[IO].error(s\"[Error] - $e\") } setters &gt;&gt; getters &gt;&gt; prog &gt;&gt; getters.void } It should be exclusively used to run Redis commands as part of a transaction, not any other computations. Fail to do so, may result in unexpected behavior. Transactional commands may be discarded if something went wrong in between. The possible errors you may get are: TransactionDiscarded: The EXEC command failed and the transactional commands were discarded. TransactionAborted: The DISCARD command was triggered due to cancellation or other failure within the transaction. TimeoutException: The transaction timed out due to some unknown error. The filterExec function filters out values of type Unit, which are normally irrelevant. If you find yourself needing the Unit types to verify some behavior, use exec instead. How NOT to use transactions For example, the following transaction will result in a dead-lock: commandsApi.use { redis =&gt; val tx = RedisTransaction(redis) val getters = redis.get(key1).flatTap(showResult(key1)) *&gt; redis.get(key2).flatTap(showResult(key2)) val setters = tx.exec( redis.set(key1, \"foo\") :: redis.set(key2, \"bar\") :: redis.discard :: HNil ) getters *&gt; setters.void *&gt; getters.void } You should never pass a transactional command: MULTI, EXEC or DISCARD. These commands are made available in case you want to handle transactions manually, which you should do at your own risk. The following example will result in a successful transaction on Redis. Yet, the operation will end up raising the error passed as a command. commandsApi.use { redis =&gt; val tx = RedisTransaction(redis) val getters = redis.get(key1).flatTap(showResult(key1)) *&gt; redis.get(key2).flatTap(showResult(key2)) val failedTx = tx.exec( redis.set(key1, \"foo\") :: redis.set(key2, \"bar\") :: IO.raiseError(new Exception(\"boom\")) :: HNil ) getters *&gt; failedTx.void *&gt; getters.void } Optimistic locking Redis provides a mechanism called optimistic locking using check-and-set via the WATCH command. Quoting the Redis documentation: WATCHed keys are monitored in order to detect changes against them. If at least one watched key is modified before the EXEC command, the whole transaction aborts, and EXEC returns a Null reply to notify that the transaction failed. This library translates the Null reply as a TransactionDiscarded error raised in the effect type. E.g.: val mkRedis: Resource[IO, RedisCommands[IO, String, String]] = RedisClient[IO].from(\"redis://localhost\").flatMap { cli =&gt; Redis[IO].fromClient(cli, RedisCodec.Utf8) } def txProgram(v1: String, v2: String) = mkRedis .use { redis =&gt; val getters = redis.get(key1).flatTap(showResult(key1)) &gt;&gt; redis.get(key2).flatTap(showResult(key2)) &gt;&gt; redis.get(key2).flatTap(showResult(key3)) val operations = redis.set(key1, v1) :: redis.set(key2, v2) :: HNil val prog: IO[Unit] = RedisTransaction(redis) .exec(operations) .void .onError { case TransactionAborted =&gt; Log[IO].error(\"[Error] - Transaction Aborted\") case TransactionDiscarded =&gt; Log[IO].error(\"[Error] - Transaction Discarded\") case _: TimeoutException =&gt; Log[IO].error(\"[Error] - Timeout\") case e =&gt; Log[IO].error(s\"[Error] - $e\") } val watching = redis.watch(key1, key2) getters &gt;&gt; watching &gt;&gt; prog &gt;&gt; getters &gt;&gt; Log[IO].info(\"keep doing stuff...\") } Before executing the transaction, we invoke redis.watch(key1, key2). Next, let’s run two concurrent transactions: IO.race(txProgram(\"osx\", \"linux\"), txProgram(\"foo\", \"bar\")).void In this case, only the first transaction will be successful. The second one will be discarded. However, we want to eventually succeed most of the time, in which case we can retry a transaction until it succeeds (optimistic locking). def retriableTx: IO[Unit] = txProgram(\"foo\", \"bar\").recoverWith { case TransactionDiscarded =&gt; retriableTx }.uncancelable IO.race(txProgram(\"nix\", \"guix\"), retriableTx).void The first transaction will be successful, but ultimately, the second transaction will retry and set the values “foo” and “bar”. All these examples can be found under RedisTransactionsDemo and ConcurrentTransactionsDemo, respectively."
    }    
  ];

  idx = lunr(function () {
    this.ref("title");
    this.field("content");

    docs.forEach(function (doc) {
      this.add(doc);
    }, this);
  });

  docs.forEach(function (doc) {
    docMap.set(doc.title, doc.url);
  });
}

// The onkeypress handler for search functionality
function searchOnKeyDown(e) {
  const keyCode = e.keyCode;
  const parent = e.target.parentElement;
  const isSearchBar = e.target.id === "search-bar";
  const isSearchResult = parent ? parent.id.startsWith("result-") : false;
  const isSearchBarOrResult = isSearchBar || isSearchResult;

  if (keyCode === 40 && isSearchBarOrResult) {
    // On 'down', try to navigate down the search results
    e.preventDefault();
    e.stopPropagation();
    selectDown(e);
  } else if (keyCode === 38 && isSearchBarOrResult) {
    // On 'up', try to navigate up the search results
    e.preventDefault();
    e.stopPropagation();
    selectUp(e);
  } else if (keyCode === 27 && isSearchBarOrResult) {
    // On 'ESC', close the search dropdown
    e.preventDefault();
    e.stopPropagation();
    closeDropdownSearch(e);
  }
}

// Search is only done on key-up so that the search terms are properly propagated
function searchOnKeyUp(e) {
  // Filter out up, down, esc keys
  const keyCode = e.keyCode;
  const cannotBe = [40, 38, 27];
  const isSearchBar = e.target.id === "search-bar";
  const keyIsNotWrong = !cannotBe.includes(keyCode);
  if (isSearchBar && keyIsNotWrong) {
    // Try to run a search
    runSearch(e);
  }
}

// Move the cursor up the search list
function selectUp(e) {
  if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index) && (index > 0)) {
      const nextIndexStr = "result-" + (index - 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Move the cursor down the search list
function selectDown(e) {
  if (e.target.id === "search-bar") {
    const firstResult = document.querySelector("li[id$='result-0']");
    if (firstResult) {
      firstResult.firstChild.focus();
    }
  } else if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index)) {
      const nextIndexStr = "result-" + (index + 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Search for whatever the user has typed so far
function runSearch(e) {
  if (e.target.value === "") {
    // On empty string, remove all search results
    // Otherwise this may show all results as everything is a "match"
    applySearchResults([]);
  } else {
    const tokens = e.target.value.split(" ");
    const moddedTokens = tokens.map(function (token) {
      // "*" + token + "*"
      return token;
    })
    const searchTerm = moddedTokens.join(" ");
    const searchResults = idx.search(searchTerm);
    const mapResults = searchResults.map(function (result) {
      const resultUrl = docMap.get(result.ref);
      return { name: result.ref, url: resultUrl };
    })

    applySearchResults(mapResults);
  }

}

// After a search, modify the search dropdown to contain the search results
function applySearchResults(results) {
  const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
  if (dropdown) {
    //Remove each child
    while (dropdown.firstChild) {
      dropdown.removeChild(dropdown.firstChild);
    }

    //Add each result as an element in the list
    results.forEach(function (result, i) {
      const elem = document.createElement("li");
      elem.setAttribute("class", "dropdown-item");
      elem.setAttribute("id", "result-" + i);

      const elemLink = document.createElement("a");
      elemLink.setAttribute("title", result.name);
      elemLink.setAttribute("href", result.url);
      elemLink.setAttribute("class", "dropdown-item-link");

      const elemLinkText = document.createElement("span");
      elemLinkText.setAttribute("class", "dropdown-item-link-text");
      elemLinkText.innerHTML = result.name;

      elemLink.appendChild(elemLinkText);
      elem.appendChild(elemLink);
      dropdown.appendChild(elem);
    });
  }
}

// Close the dropdown if the user clicks (only) outside of it
function closeDropdownSearch(e) {
  // Check if where we're clicking is the search dropdown
  if (e.target.id !== "search-bar") {
    const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
    if (dropdown) {
      dropdown.classList.remove("show");
      document.documentElement.removeEventListener("click", closeDropdownSearch);
    }
  }
}
