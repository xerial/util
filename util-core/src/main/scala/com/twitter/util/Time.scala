/*
 * Copyright 2010 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.util

import java.io.Serializable
import java.time.ZonedDateTime
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * @define now
 *
 * The current time can be manipulated via [[Time.withTimeAt]],
 * [[Time.withCurrentTimeFrozen]], [[Time.withTimeFunction]] and
 * [[Time.sleep]].
 *
 * While `now` is not a "global" it is however properly propagated through
 * to other code via the standard usage of [[Local Locals]] throughout `util`.
 * Specifically, code using [[Future Futures]], [[FuturePool FuturePools]],
 * and [[MockTimer MockTimers]] will have their code see the manipulated
 * values.
 *
 * @define nowusage
 *
 * {{{
 *   val time = Time.fromMilliseconds(123456L)
 *   Time.withTimeAt(time) { timeControl =>
 *     assert(Time.now == time)
 *
 *     // you can control time via the `TimeControl` instance.
 *     timeControl.advance(2.seconds)
 *     FuturePool.unboundedPool {
 *       assert(Time.now == time + 2.seconds)
 *     }
 *   }
 * }}}
 */
trait TimeLikeOps[This <: TimeLike[This]] {

  /** The top value is the greatest possible value. It is akin to an infinity. */
  val Top: This

  /** The bottom value is the smallest possible value. */
  val Bottom: This

  /** An undefined value: behaves like `Double.NaN` */
  val Undefined: This

  /** The zero value */
  val Zero: This = fromNanoseconds(0)

  /**
   * An extractor for finite `This`, yielding its value in nanoseconds.
   *
   * {{{
   *   duration match {
   *     case Duration.Nanoseconds(ns) => ...
   *     case Duration.Top => ...
   *   }
   * }}}
   */
  object Nanoseconds {
    def unapply(x: This): Option[Long] =
      if (x.isFinite) Some(x.inNanoseconds) else None
  }

  /**
   * An extractor for finite TimeLikes; eg.:
   *
   * {{{
   *   duration match {
   *     case Duration.Finite(d) => ...
   *     case Duration.Top => ..
   * }}}
   */
  object Finite {
    def unapply(x: This): Option[This] =
      if (x.isFinite) Some(x) else None
  }

  /** Make a new `This` from the given number of nanoseconds */
  def fromNanoseconds(nanoseconds: Long): This

  def fromSeconds(seconds: Int): This = fromMilliseconds(1000L * seconds)

  def fromMinutes(minutes: Int): This = fromMilliseconds(60L * 1000L * minutes)

  def fromHours(hours: Int): This = fromMilliseconds(60L * 60L * 1000L * hours)

  def fromDays(days: Int): This = fromMilliseconds(24L * 3600L * 1000L * days)

  /**
   * Make a new `This` from the given number of seconds.
   * Because this method takes a Double, it can represent values less than a second.
   * Note however that there is some slop in floating-point conversion that
   * limits precision.  Currently we can assume at least microsecond precision.
   */
  def fromFractionalSeconds(seconds: Double): This =
    fromNanoseconds((1000L * 1000L * 1000L * seconds).toLong)

  def fromMilliseconds(millis: Long): This =
    if (millis > 9223372036854L) Top
    else if (millis < -9223372036854L) Bottom
    else fromNanoseconds(TimeUnit.MILLISECONDS.toNanos(millis))

  def fromMicroseconds(micros: Long): This =
    if (micros > 9223372036854775L) Top
    else if (micros < -9223372036854775L) Bottom
    else fromNanoseconds(TimeUnit.MICROSECONDS.toNanos(micros))
}

/**
 * A common trait for time-like values. It requires a companion
 * `TimeLikeOps` module. `TimeLike`s are finite, but they must always
 * have two sentinels: `Top` and `Bottom`. These act like positive
 * and negative infinities: Arithmetic involving them preserves their
 * values, and so on.
 *
 * `TimeLike`s are `Long`-valued nanoseconds, but have different
 * interpretations: `Duration`s measure the number of nanoseconds
 * between two points in time, while `Time` measure the number of
 * nanoseconds since the Unix epoch (1 January 1970 00:00:00 UTC).
 *
 * TimeLike behave like '''boxed''' java Double values with respect
 * to infinity and undefined values. In particular, this means that a
 * `TimeLike`'s `Undefined` value is comparable to other values. In
 * turn this means it can be used as keys in maps, etc.
 *
 * Overflows are also handled like doubles.
 */
trait TimeLike[This <: TimeLike[This]] extends Ordered[This] { self: This =>
  protected def ops: TimeLikeOps[This]

  /** The `TimeLike`'s value in nanoseconds. */
  def inNanoseconds: Long

  def inMicroseconds: Long = inNanoseconds / Duration.NanosPerMicrosecond
  def inMilliseconds: Long = inNanoseconds / Duration.NanosPerMillisecond
  def inLongSeconds: Long = inNanoseconds / Duration.NanosPerSecond
  def inSeconds: Int =
    if (inLongSeconds > Int.MaxValue) Int.MaxValue
    else if (inLongSeconds < Int.MinValue) Int.MinValue
    else inLongSeconds.toInt
  // Units larger than seconds safely fit into 32-bits when converting from a 64-bit nanosecond basis
  def inMinutes: Int = (inNanoseconds / Duration.NanosPerMinute).toInt
  def inHours: Int = (inNanoseconds / Duration.NanosPerHour).toInt
  def inDays: Int = (inNanoseconds / Duration.NanosPerDay).toInt
  def inMillis: Long = inMilliseconds // (Backwards compatibility)

  /**
   * Returns a value/`TimeUnit` pair; attempting to return coarser grained
   * values if possible (specifically: `TimeUnit.MINUTES`, `TimeUnit.SECONDS`
   * or `TimeUnit.MILLISECONDS`) before resorting to the default
   * `TimeUnit.NANOSECONDS`.
   */
  def inTimeUnit: (Long, TimeUnit) = inNanoseconds match {
    // allow for APIs that may treat TimeUnit differently if measured in very tiny units.
    case ns if ns % Duration.NanosPerMinute == 0 => (inMinutes, TimeUnit.MINUTES)
    case ns if ns % Duration.NanosPerSecond == 0 => (inSeconds, TimeUnit.SECONDS)
    case ns if ns % Duration.NanosPerMillisecond == 0 => (inMilliseconds, TimeUnit.MILLISECONDS)
    case ns => (ns, TimeUnit.NANOSECONDS)
  }

  /**
   * Adds `delta` to this `TimeLike`. Adding `Duration.Top` results
   * in the `TimeLike`'s `Top`, adding `Duration.Bottom` results in
   * the `TimeLike`'s `Bottom`.
   */
  def +(delta: Duration): This = {
    // adds a to b while taking care of long overflow
    def addNanos(a: Long, b: Long): This = {
      val c = a + b
      if (((a ^ c) & (b ^ c)) < 0)
        if (b < 0) ops.Bottom else ops.Top
      else ops.fromNanoseconds(c)
    }

    delta match {
      case Duration.Top => ops.Top
      case Duration.Bottom => ops.Bottom
      case Duration.Undefined => ops.Undefined
      case ns => addNanos(inNanoseconds, ns.inNanoseconds)
    }
  }

  def -(delta: Duration): This = this.+(-delta)

  /** Is this a finite TimeLike value? */
  def isFinite: Boolean

  /** Is this a finite TimeLike value and equals 0 */
  def isZero: Boolean = isFinite && this.inNanoseconds == 0

  /** The difference between the two `TimeLike`s */
  def diff(that: This): Duration

  /**
   * Rounds up to the nearest multiple of the given duration.  For example:
   * 127.seconds.ceiling(1.minute) => 3.minutes.  Taking the ceiling of a
   * Time object with duration greater than 1.hour can have unexpected
   * results because of timezones.
   */
  def ceil(increment: Duration): This = {
    val floored = floor(increment)
    if (this == floored) floored else floored + increment
  }

  /**
   * Rounds down to the nearest multiple of the given duration.  For example:
   * 127.seconds.floor(1.minute) => 2.minutes.  Taking the floor of a
   * Time object with duration greater than 1.hour can have unexpected
   * results because of timezones.
   */
  def floor(increment: Duration): This = (this, increment) match {
    case (num, ns) if num.isZero && ns.isZero => ops.Undefined
    case (num, ns) if num.isFinite && ns.isZero =>
      if (num.inNanoseconds < 0) ops.Bottom else ops.Top
    case (num, denom) if num.isFinite && denom.isFinite =>
      ops.fromNanoseconds((num.inNanoseconds / denom.inNanoseconds) * denom.inNanoseconds)
    case (self, n) if n.isFinite => self
    case (_, _) => ops.Undefined
  }

  def max(that: This): This =
    if ((this compare that) < 0) that else this

  def min(that: This): This =
    if ((this compare that) < 0) this else that

  def compare(that: This): Int =
    if ((that eq ops.Top) || (that eq ops.Undefined)) -1
    else if (that eq ops.Bottom) 1
    else if (inNanoseconds < that.inNanoseconds) -1
    else if (inNanoseconds > that.inNanoseconds) 1
    else 0

  /** Equality within `maxDelta` */
  def moreOrLessEquals(other: This, maxDelta: Duration): Boolean =
    (other ne ops.Undefined) && ((this == other) || (this diff other).abs <= maxDelta)
}

/**
 * Boxes for serialization. This has to be its own
 * toplevel object to remain serializable
 */
private[util] object TimeBox {
  case class Finite(nanos: Long) extends Serializable {
    private def readResolve(): Object = Time.fromNanoseconds(nanos)
  }

  case class Top() extends Serializable {
    private def readResolve(): Object = Time.Top
  }

  case class Bottom() extends Serializable {
    private def readResolve(): Object = Time.Bottom
  }

  case class Undefined() extends Serializable {
    private def readResolve(): Object = Time.Undefined
  }
}

/**
 * By using [[Time.now]] in your program instead of
 * `System.currentTimeMillis` unit tests are able to adjust
 * the current time to verify timeouts and other time-dependent
 * behavior, without calling `sleep`, and providing deterministic
 * tests.
 *
 * $now
 *
 * $nowusage
 *
 * @see [[Duration]] for intervals between two points in time.
 * @see [[Stopwatch]] for measuring elapsed time.
 * @see [[TimeFormat]] for converting to and from `String` representations.
 */
object Time extends TimeLikeOps[Time] {
  def fromNanoseconds(nanoseconds: Long): Time = new Time(nanoseconds)

  // This is needed for Java compatibility.
  override def fromFractionalSeconds(seconds: Double): Time = super.fromFractionalSeconds(seconds)
  override def fromSeconds(seconds: Int): Time = super.fromSeconds(seconds)
  override def fromMinutes(minutes: Int): Time = super.fromMinutes(minutes)
  override def fromMilliseconds(millis: Long): Time = super.fromMilliseconds(millis)
  override def fromMicroseconds(micros: Long): Time = super.fromMicroseconds(micros)

  private[this] val SystemClock = Clock.systemUTC()

  /**
   * Time `Top` is greater than any other definable time, and is
   * equal only to itself. It may be used as a sentinel value,
   * representing a time infinitely far into the future.
   */
  val Top: Time = new Time(Long.MaxValue) {
    override def toString: String = "Time.Top"

    override def compare(that: Time): Int =
      if (that eq Undefined) -1
      else if (that eq Top) 0
      else 1

    override def equals(other: Any): Boolean = other match {
      case t: Time => t eq this
      case _ => false
    }

    override def +(delta: Duration): Time = delta match {
      case Duration.Bottom | Duration.Undefined => Undefined
      case _ => this // Top or finite.
    }

    override def diff(that: Time): Duration = that match {
      case Top | Undefined => Duration.Undefined
      case other => Duration.Top
    }

    override def isFinite: Boolean = false

    private def writeReplace(): Object = TimeBox.Top()
  }

  /**
   * Time `Bottom` is smaller than any other time, and is equal only
   * to itself. It may be used as a sentinel value, representing a
   * time infinitely far in the past.
   */
  val Bottom: Time = new Time(Long.MinValue) {
    override def toString: String = "Time.Bottom"

    override def compare(that: Time): Int = if (this eq that) 0 else -1

    override def equals(other: Any): Boolean = other match {
      case t: Time => t eq this
      case _ => false
    }

    override def +(delta: Duration): Time = delta match {
      case Duration.Top | Duration.Undefined => Undefined
      case _ => this
    }

    override def diff(that: Time): Duration = that match {
      case Bottom | Undefined => Duration.Undefined
      case other => Duration.Bottom
    }

    override def isFinite: Boolean = false

    private def writeReplace(): Object = TimeBox.Bottom()
  }

  val Undefined: Time = new Time(0) {
    override def toString: String = "Time.Undefined"

    override def equals(other: Any): Boolean = other match {
      case t: Time => t eq this
      case _ => false
    }

    override def compare(that: Time): Int = if (this eq that) 0 else 1
    override def +(delta: Duration): Time = this
    override def diff(that: Time): Duration = Duration.Undefined
    override def isFinite: Boolean = false

    private def writeReplace(): Object = TimeBox.Undefined()
  }

  /**
   * Returns the current [[Time]].
   *
   * Note that returned values are '''not''' monotonic. This
   * means it is not suitable for measuring durations where
   * the clock cannot drift backwards. If monotonicity is desired
   * prefer a monotonic [[Stopwatch]].
   *
   * $now
   */
  def now: Time = {
    localGetTime() match {
      case None =>
        Time.fromMilliseconds(System.currentTimeMillis())
      case Some(f) =>
        f()
    }
  }

  /**
   * Returns the current [[Time]] with, at best, nanosecond precision.
   *
   * Note that nanosecond precision is only available in JDK versions
   * greater than JDK8. In JDK8 this API has the same precision as
   * [[Time#now]] and `System#currentTimeMillis`. In JDK9+ this will
   * change and all timestamps taken from this API will have nanosecond
   * resolution.
   *
   * Note that returned values are '''not''' monotonic. This
   * means it is not suitable for measuring durations where
   * the clock cannot drift backwards. If monotonicity is desired
   * prefer a monotonic [[Stopwatch]].
   *
   */
  def nowNanoPrecision: Time = {
    localGetTime() match {
      case None =>
        val rightNow = Instant.now(SystemClock)
        fromNanoseconds((rightNow.getEpochSecond * Duration.NanosPerSecond) + rightNow.getNano)
      case Some(f) =>
        f()
    }
  }

  /**
   * The unix epoch. Times are measured relative to this.
   */
  val epoch: Time = fromNanoseconds(0L)

  private val defaultFormat = new TimeFormat("yyyy-MM-dd HH:mm:ss Z")
  private val rssFormat = new TimeFormat("E, dd MMM yyyy HH:mm:ss Z")

  /**
   * Note, this should only ever be updated by methods used for testing.
   */
  private[util] val localGetTime: Local[scala.Function0[Time]] = new Local[() => Time]
  private[util] val localGetTimer: Local[MockTimer] = new Local[MockTimer]

  /**
   * Creates a [[Time]] instance of the given `Date`.
   */
  def apply(date: Date): Time = fromMilliseconds(date.getTime)

  /**
   * Creates a [[Time]] instance at the given `datetime` string in the
   * "yyyy-MM-dd HH:mm:ss Z" format.
   *
   * {{{
   * val time = Time.at("2019-05-12 00:00:00 -0800")
   * time.toString // 2019-05-12 08:00:00 +0000
   * }}}
   */
  def at(datetime: String): Time = defaultFormat.parse(datetime)

  /**
   * Execute body with the time function replaced by `timeFunction`
   * WARNING: This is only meant for testing purposes.  You can break it
   * with nested calls if you have an outstanding Future executing in a worker pool.
   */
  def withTimeFunction[A](timeFunction: => Time)(body: TimeControl => A): A = {
    @volatile var tf = () => timeFunction
    val tmr = new MockTimer

    Time.localGetTime.let(() => tf()) {
      Time.localGetTimer.let(tmr) {
        val timeControl = new TimeControl {
          def set(time: Time): Unit = {
            tf = () => time
            tmr.tick()
          }
          def advance(delta: Duration): Unit = {
            val newTime = tf() + delta
            /* Modifying the var here instead of resetting the local allows this method
             to work inside filters or between the creation and fulfillment of Promises.
             See BackupRequestFilterTest in Finagle as an example. */
            tf = () => newTime
            tmr.tick()
          }
        }

        body(timeControl)
      }
    }
  }

  /**
   * Runs the given body at a specified time.
   * Makes for simple, fast, predictable unit tests that are dependent on time.
   *
   * @note this intended for use in tests.
   *
   * $nowusage
   */
  def withTimeAt[A](time: Time)(body: TimeControl => A): A =
    withTimeFunction(time)(body)

  /**
   * Runs the given body at the current time.
   * Makes for simple, fast, predictable unit tests that are dependent on time.
   *
   * @note this intended for use in tests.
   *
   * $nowusage
   */
  def withCurrentTimeFrozen[A](body: TimeControl => A): A = {
    withTimeAt(Time.now)(body)
  }

  /**
   * Puts the currently executing thread to sleep for the given duration,
   * according to object Time.
   *
   * This is useful for testing.
   *
   * $nowusage
   */
  def sleep(duration: Duration): Unit = {
    localGetTimer() match {
      case None =>
        Thread.sleep(duration.inMilliseconds)
      case Some(timer) =>
        Await.result(Future.sleep(duration)(timer))
    }
  }

  /**
   * Returns the Time parsed from a string in RSS format. Eg: "Wed, 15 Jun 2005 19:00:00 GMT"
   */
  def fromRss(rss: String): Time = rssFormat.parse(rss)
}

trait TimeControl {
  def set(time: Time): Unit
  def advance(delta: Duration): Unit
}

/**
 * A thread-safe wrapper around a SimpleDateFormat object.
 *
 * The default timezone is UTC.
 */
class TimeFormat(
  pattern: String,
  locale: Option[Locale],
  timezone: TimeZone = TimeZone.getTimeZone("UTC")) {

  // jdk6 and jdk7 pick up the default locale differently in SimpleDateFormat,
  // so we can't rely on Locale.getDefault here.
  // Instead, we let SimpleDateFormat do the work for us above.
  /** Create a new TimeFormat with a given locale and the default timezone **/
  def this(pattern: String, locale: Option[Locale]) =
    this(pattern, locale, TimeZone.getTimeZone("UTC"))

  /** Create a new TimeFormat with a given timezone and the default locale **/
  def this(pattern: String, timezone: TimeZone) = this(pattern, None, timezone)

  /** Create a new TimeFormat with the default locale and timezone. **/
  def this(pattern: String) = this(pattern, None, TimeZone.getTimeZone("UTC"))

  private[this] val format = locale
    .map(TwitterDateFormat(pattern, _))
    .getOrElse(TwitterDateFormat(pattern))

  format.setTimeZone(timezone)

  def parse(str: String): Time = {
    // SimpleDateFormat is not thread-safe
    val date = format.synchronized(format.parse(str))
    if (date == null) {
      throw new Exception("Unable to parse date-time: " + str)
    } else {
      Time.fromMilliseconds(date.getTime())
    }
  }

  def format(time: Time): String = {
    format.synchronized(format.format(time.toDate))
  }
}

/**
 * An absolute point in time, represented as the number of
 * nanoseconds since the Unix epoch.
 *
 * @see [[Duration]] for intervals between two points in time.
 * @see [[Stopwatch]] for measuring elapsed time.
 * @see [[TimeFormat]] for converting to and from `String` representations.
 */
sealed class Time private[util] (protected val nanos: Long)
    extends TimeLike[Time]
    with Serializable {
  protected def ops: Time.type = Time
  import Time._

  def inNanoseconds: Long = nanos

  /**
   * Renders this time using the default format.
   */
  override def toString: String = defaultFormat.format(this)

  override def equals(other: Any): Boolean = {
    // in order to ensure that the sentinels are only equal
    // to themselves, we need to make sure we only compare nanos
    // when both instances are `Time`s and not a sentinel subclass.
    if (other != null && (other.getClass eq getClass)) {
      other.asInstanceOf[Time].nanos == nanos
    } else {
      false
    }
  }

  override def hashCode: Int =
    // inline java.lang.Long.hashCode to avoid the BoxesRunTime.boxToLong
    // and then Long.hashCode code.
    (nanos ^ (nanos >>> 32)).toInt

  /**
   * Formats this Time according to the given SimpleDateFormat pattern.
   */
  def format(pattern: String): String = new TimeFormat(pattern).format(this)

  /**
   * Formats this Time according to the given SimpleDateFormat pattern and locale.
   */
  def format(pattern: String, locale: Locale): String =
    new TimeFormat(pattern, Some(locale)).format(this)

  /**
   * Creates a duration between two times.
   */
  def -(that: Time): Duration = diff(that)

  def isFinite: Boolean = true

  def diff(that: Time): Duration = {
    // subtracts b from a while taking care of long overflow
    def subNanos(a: Long, b: Long): Duration = {
      val c = a - b
      if (((a ^ c) & (-b ^ c)) < 0)
        if (b < 0) Duration.Top else Duration.Bottom
      else Duration.fromNanoseconds(c)
    }

    that match {
      case Undefined => Duration.Undefined
      case Top => Duration.Bottom
      case Bottom => Duration.Top
      case _ => subNanos(this.inNanoseconds, that.inNanoseconds)
    }
  }

  /**
   * Duration that has passed between the given time and the current time.
   */
  def since(that: Time): Duration = this - that

  /**
   * Duration that has passed between the epoch and the current time.
   */
  def sinceEpoch: Duration = since(epoch)

  /**
   * Gets the current time as Duration since now
   */
  def sinceNow: Duration = since(now)

  /**
   * Duration between current time and the given time.
   */
  def until(that: Time): Duration = that - this

  /**
   * Gets the duration between this time and the epoch.
   */
  def untilEpoch: Duration = until(epoch)

  /**
   * Gets the duration between this time and now.
   */
  def untilNow: Duration = until(now)

  /**
   * Converts this Time object to a java.util.Date
   */
  def toDate: Date = new Date(inMillis)

  /**
   * Converts this Time object to a java.time.Instant
   */
  def toInstant: Instant = {
    val millis = inMilliseconds
    val nanos = inNanoseconds - (millis * Duration.NanosPerMillisecond)
    Instant.ofEpochMilli(millis).plusNanos(nanos)
  }

  /**
   * Converts this Time object to a java.time.ZonedDateTime with ZoneId of UTC
   */
  def toZonedDateTime: ZonedDateTime = ZonedDateTime.ofInstant(toInstant, ZoneOffset.UTC)

  /**
   * Converts this Time object to a java.time.OffsetDateTime with ZoneId of UTC
   */
  def toOffsetDateTime: OffsetDateTime = OffsetDateTime.ofInstant(toInstant, ZoneOffset.UTC)

  private def writeReplace(): Object = TimeBox.Finite(inNanoseconds)

  /**
   * Adds `delta` to this `Time`.
   */
  def plus(delta: Duration): Time = this + delta

  /**
   * Subtracts `delta` from this `Time`.
   */
  def minus(delta: Duration): Time = this - delta

  /**
   *  Finds a diff between this and ''that'' time.
   */
  def minus(that: Time): Duration = this - that

  // for Java-compatibility
  override def floor(increment: Duration): Time = super.floor(increment)
  override def ceil(increment: Duration): Time = super.ceil(increment)
}
