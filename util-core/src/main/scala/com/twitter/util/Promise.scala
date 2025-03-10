package com.twitter.util

import com.twitter.concurrent.Scheduler
import scala.annotation.tailrec
import scala.runtime.NonLocalReturnControl
import scala.util.control.NonFatal

/**
 * @define PromiseInterruptsSingleArgLink
 * [[com.twitter.util.Promise.interrupts[A](f:com\.twitter\.util\.Future[_]):com\.twitter\.util\.Promise[A]* interrupts(Future)]]
 *
 * @define PromiseInterruptsTwoArgsLink
 * [[com.twitter.util.Promise.interrupts[A](a:com\.twitter\.util\.Future[_],b:com\.twitter\.util\.Future[_]):com\.twitter\.util\.Promise[A]* interrupts(Future,Future)]]
 *
 * @define PromiseInterruptsVarargsLink
 * [[com.twitter.util.Promise.interrupts[A](fs:com\.twitter\.util\.Future[_]*):com\.twitter\.util\.Promise[A]* interrupts(Future*)]]
 */
object Promise {

  /**
   * Embeds an "interrupt handler" into a [[Promise]].
   *
   * This is a total handler such that it's defined on any `Throwable`. Use
   * [[Promise.setInterruptHandler]] if you need to leave an interrupt handler
   * undefined for certain types of exceptions.
   *
   * Example: (`p` and `q` are equivalent, but `p` allocates less):
   *
   * {{{
   *   import com.twitter.util.Promise
   *
   *   val p = new Promise[A] with Promise.InterruptHandler {
   *     def onInterrupt(t: Throwable): Unit = setException(t)
   *   }
   *
   *   val q = new Promise[A]
   *   q.setInterruptHandler { case t: Throwable => q.setException(t) }
   * }}}
   *
   * @note Later calls to `setInterruptHandler` on a promise mixing in this
   *       trait will replace the embedded handler.
   */
  trait InterruptHandler extends PartialFunction[Throwable, Unit] { self: Promise[_] =>

    // An interrupt handler is defined on each throwable. It's a total function.
    final def isDefinedAt(x: Throwable): Boolean = true
    final def apply(t: Throwable): Unit = onInterrupt(t)

    /**
     * Triggered on any interrupt (even [[scala.util.control.NonFatal a fatal one]]).
     */
    protected def onInterrupt(t: Throwable): Unit

    // Register ourselves as the interrupt handler.
    self.setInterruptHandler(this)
  }

  private class ReleaseOnApplyCDL[A]
      extends java.util.concurrent.CountDownLatch(1)
      with (Try[A] => Unit) {
    def apply(ta: Try[A]): Unit = countDown()
  }

  /**
   * A persistent queue of continuations (i.e., `K`).
   */
  private[util] sealed abstract class WaitQueue[-A] {
    def first: K[A]
    def rest: WaitQueue[A]

    final def size: Int = {
      @tailrec
      def loop(wq: WaitQueue[_], result: Int): Int =
        if (wq eq WaitQueue.Empty) result
        else loop(wq.rest, result + 1)

      loop(this, 0)
    }

    @tailrec
    final def contains(k: K[_]): Boolean =
      if (this eq WaitQueue.Empty) false
      else (first eq k) || rest.contains(k)

    final def remove(k: K[_]): WaitQueue[A] = {
      @tailrec
      def loop(from: WaitQueue[A], to: WaitQueue[A]): WaitQueue[A] =
        if (from eq WaitQueue.Empty) to
        else if (from.first eq k) loop(from.rest, to)
        else loop(from.rest, WaitQueue(from.first, to))

      loop(this, WaitQueue.empty)
    }

    final def runInScheduler(t: Try[A]): Unit = {
      if (this ne WaitQueue.Empty) {
        Scheduler.submit(() => WaitQueue.this.run(t))
      }
    }

    @tailrec
    private def run(t: Try[A]): Unit =
      if (this ne WaitQueue.Empty) {
        first(t)
        rest.run(t)
      }

    final override def toString: String = s"WaitQueue(size=$size)"
  }

  private[util] object WaitQueue {

    // Works the best when length(from) < length(to)
    @tailrec
    final def merge[A](from: WaitQueue[A], to: WaitQueue[A]): WaitQueue[A] =
      if (from eq Empty) to
      else merge(from.rest, WaitQueue(from.first, to))

    object Empty extends WaitQueue[Nothing] {
      final def first: K[Nothing] =
        throw new IllegalStateException("WaitQueue.Empty")

      final def rest: WaitQueue[Nothing] =
        throw new IllegalStateException("WaitQueue.Empty")
    }

    def empty[A]: WaitQueue[A] = Empty.asInstanceOf[WaitQueue[A]]

    def apply[A](f: K[A], r: WaitQueue[A]): WaitQueue[A] =
      if (r eq Empty) f
      else
        new WaitQueue[A] {
          final def first: K[A] = f
          final def rest: WaitQueue[A] = r
        }
  }

  /**
   * A continuation stored from a promise. Also represents a `WaitQueue` with
   * one element.
   *
   * @note At this point, it's **not possible** to have `Promise` extending `K` given
   *       it will make "Linked" and "Waiting" state cases ambiguous. This, however,
   *       may change following the further performance improvements.
   */
  private[util] abstract class K[-A] extends WaitQueue[A] {
    final def first: K[A] = this
    final def rest: WaitQueue[A] = WaitQueue.empty
    def apply(r: Try[A]): Unit
  }

  /**
   * A template trait for [[com.twitter.util.Promise Promises]] that are derived
   * and capable of being detached from other Promises.
   */
  trait Detachable { self: Promise[_] =>

    /**
     * Returns true if successfully detached, will return true at most once.
     *
     * The contract is that non-idempotent side effects should only be done after the
     * successful detach.
     */
    def detach(): Boolean
  }

  /**
   * A detachable [[Promise]] created from a [[Promise]].
   */
  private class DetachablePromise[A](underlying: Promise[_ <: A])
      extends Promise[A]
      with Detachable { self =>

    // It's not possible (yet) to embed K[A] into Promise because
    // Promise[A] (Linked) and WaitQueue (Waiting) states become ambiguous.
    private[this] val k = new K[A] {
      // This is only called after the underlying has been successfully satisfied
      def apply(result: Try[A]): Unit = self.update(result)
    }

    def detach(): Boolean = underlying.detach(k)

    // Register continuation.
    underlying.continue(k)
  }

  /**
   * A detachable [[Promise]] created from a [[Future]].
   */
  private class DetachableFuture[A](underlying: Future[A])
      extends Promise[A]
      with Detachable
      with (Try[A] => Unit) {

    // 0 represents not yet detached, 1 represents detached.
    @volatile
    private[this] var alreadyDetached: Int = 0

    // We add this method so that the Scala 3 compiler doesn't optimize
    // away the `alreadyDetached` field which isn't explictly used within
    // the scope of this class
    private[this] def detached: Boolean = alreadyDetached == 1

    def detach(): Boolean =
      unsafe.compareAndSwapInt(this, detachedFutureOffset, 0, 1)

    def apply(result: Try[A]): Unit = if (detach()) update(result)

    // Register handler.
    underlying.respond(this)
  }

  /**
   * A monitored continuation.
   *
   * @param saved The saved local context of the invocation site
   * @param k the closure to invoke in the saved context, with the
   * provided result
   */
  private final class Monitored[A](saved: Local.Context, k: Try[A] => Unit) extends K[A] {

    def apply(result: Try[A]): Unit = {
      val current = Local.save()
      if (current ne saved)
        Local.restore(saved)
      try k(result)
      catch Monitor.catcher
      finally Local.restore(current)
    }
  }

  private abstract class Transformer[A, B](saved: Local.Context) extends K[A] {

    protected[this] def k(r: Try[A]): Unit

    final def apply(result: Try[A]): Unit = {
      val current = Local.save()
      if (current ne saved)
        Local.restore(saved)
      try k(result)
      catch {
        case t: Throwable =>
          Monitor.handle(t)
          throw t
      } finally Local.restore(current)
    }
  }

  /**
   * A transforming continuation.
   *
   * @param saved The saved local context of the invocation site
   * @param f The closure to invoke to produce the Future of the transformed value.
   * @param promise The Promise for the transformed value
   */
  private final class FutureTransformer[A, B](
    saved: Local.Context,
    f: Try[A] => Future[B],
    promise: Promise[B])
      extends Transformer[A, B](saved) {

    protected[this] def k(r: Try[A]): Unit =
      // The promise can be fulfilled only by the transformer, so it's safe to use `become` here
      promise.become(
        try f(r)
        catch {
          case e: NonLocalReturnControl[_] => Future.exception(new FutureNonLocalReturnControl(e))
          case NonFatal(e) => Future.exception(e)
        }
      )
  }

  private final class TryTransformer[A, B](
    saved: Local.Context,
    f: Try[A] => Try[B],
    promise: Promise[B])
      extends Transformer[A, B](saved) {

    protected[this] def k(r: Try[A]): Unit = {
      // The promise can be fulfilled only by the transformer, so it's safe to use `update` here
      promise.update(
        try f(r)
        catch {
          case e: NonLocalReturnControl[_] => Throw(new FutureNonLocalReturnControl(e))
          case NonFatal(e) => Throw(e)
        }
      )
    }
  }

  /**
   * An unsatisfied [[Promise]] which has an interrupt handler attached to it.
   * `waitq` represents the continuations that should be run once it
   * is satisfied.
   */
  private class Interruptible[A](
    val waitq: WaitQueue[A],
    val handler: PartialFunction[Throwable, Unit],
    val saved: Local.Context)

  /**
   * An unsatisfied [[Promise]] which forwards interrupts to `other`.
   * `waitq` represents the continuations that should be run once it
   * is satisfied.
   */
  private class Transforming[A](val waitq: WaitQueue[A], val other: Future[_])

  /**
   * An unsatisfied [[Promise]] that has been interrupted by `signal`.
   * `waitq` represents the continuations that should be run once it
   * is satisfied.
   */
  private class Interrupted[A](val waitq: WaitQueue[A], val signal: Throwable)

  private val unsafe: sun.misc.Unsafe = Unsafe()
  private val stateOff: Long =
    unsafe.objectFieldOffset(classOf[Promise[_]].getDeclaredField("state"))

  private val detachedFutureOffset: Long =
    unsafe.objectFieldOffset(classOf[DetachableFuture[_]].getDeclaredField("alreadyDetached"))

  private val AlwaysUnit: Any => Unit = _ => ()

  // PUBLIC API

  /**
   * Indicates that an attempt to satisfy a [[com.twitter.util.Promise]] was made
   * after that promise had already been satisfied.
   */
  case class ImmutableResult(message: String) extends Exception(message)

  /** Create a new, empty, promise of type {{A}}. */
  def apply[A](): Promise[A] = new Promise[A]

  /**
   * Single-arg version to avoid object creation and take advantage of `forwardInterruptsTo`.
   *
   * @see $PromiseInterruptsTwoArgsLink
   * @see $PromiseInterruptsVarargsLink
   */
  def interrupts[A](f: Future[_]): Promise[A] = new Promise[A](f)

  /**
   * Create a promise that interrupts `a` and `b` futures. In particular:
   * the returned promise handles an interrupt when either `a` or `b` does.
   *
   * @see $PromiseInterruptsSingleArgLink
   * @see $PromiseInterruptsVarargsLink
   */
  def interrupts[A](a: Future[_], b: Future[_]): Promise[A] =
    new Promise[A] with InterruptHandler {
      protected def onInterrupt(t: Throwable): Unit = {
        a.raise(t)
        b.raise(t)
      }
    }

  /**
   * Create a promise that interrupts all of `fs`. In particular:
   * the returned promise handles an interrupt when any of `fs` do.
   *
   * @see $PromiseInterruptsSingleArgLink
   * @see $PromiseInterruptsTwoArgsLink
   */
  def interrupts[A](fs: Future[_]*): Promise[A] =
    new Promise[A] with InterruptHandler {
      protected def onInterrupt(t: Throwable): Unit = {
        val it = fs.iterator
        while (it.hasNext) {
          it.next().raise(t)
        }
      }
    }

  /**
   * Create a derivative promise that will be satisfied with the result of the
   * parent.
   *
   * If the derivative promise is detached before the parent is satisfied, then
   * it becomes disconnected from the parent and can be used as a normal,
   * unlinked Promise.
   *
   * By the contract of `Detachable`, satisfaction of the Promise must occur
   * ''after'' detachment. Promises should only ever be satisfied after they are
   * successfully detached (thus satisfaction is the responsibility of the
   * detacher).
   *
   * Ex:
   *
   * {{{
   * val f: Future[Unit]
   * val p: Promise[Unit] with Detachable = Promise.attached(f)
   * ...
   * if (p.detach()) p.setValue(())
   * }}}
   */
  def attached[A](parent: Future[A]): Promise[A] with Detachable = parent match {
    case p: Promise[A] =>
      new DetachablePromise(p)
    case _ =>
      new DetachableFuture(parent)
  }
}

/**
 * A writeable [[com.twitter.util.Future]] that supports merging.
 * Callbacks (responders) of Promises are scheduled with
 * [[com.twitter.concurrent.Scheduler]].
 *
 * =Implementation details=
 *
 * A Promise is in one of six states: `Waiting`, `Interruptible`,
 * `Interrupted`, `Transforming`, `Done` and `Linked` where `Interruptible`,
 * `Interrupted`, and `Transforming` are variants of `Waiting` to deal with future
 * interrupts. Promises are concurrency-safe, using lock-free operations
 * throughout. Callback dispatch is scheduled with [[com.twitter.concurrent.Scheduler]].
 *
 * Waiters (i.e., continuations) are stored in a `Promise.WaitQueue` and
 * executed in the LIFO order.
 *
 * `Promise.become` merges two promises: they are declared equivalent.
 * `become` merges the states of the two promises, and links one to the
 * other. Thus promises support the analog to tail-call elimination: no
 * space leak is incurred from `flatMap` in the tail position since
 * intermediate promises are merged into the root promise.
 */
class Promise[A] extends Future[A] with Updatable[Try[A]] {
  import Promise._
  import ResourceTracker.wrapAndMeasureUsage

  /**
   * Note: exceptions in responds are monitored.  That is, if the
   * computation `k` throws a raw (ie.  not encoded in a Future)
   * exception, it is handled by the current monitor, see
   * [[Monitor]] for details.
   */
  final def respond(k: Try[A] => Unit): Future[A] = {
    val saved = Local.save()
    val tracker = saved.resourceTracker
    if (tracker eq None) continue(new Monitored(saved, k))
    else continue(new Monitored(saved, wrapAndMeasureUsage(k, tracker.get)))
    this
  }

  final def transform[B](f: Try[A] => Future[B]): Future[B] = {
    val promise = interrupts[B](this)
    val saved = Local.save()
    val tracker = saved.resourceTracker
    if (tracker eq None) continue(new FutureTransformer(saved, f, promise))
    else continue(new FutureTransformer(saved, wrapAndMeasureUsage(f, tracker.get), promise))
    promise
  }

  final protected def transformTry[B](f: Try[A] => Try[B]): Future[B] = {
    val promise = interrupts[B](this)
    val saved = Local.save()
    val tracker = saved.resourceTracker
    if (tracker eq None) continue(new TryTransformer(saved, f, promise))
    else continue(new TryTransformer(saved, wrapAndMeasureUsage(f, tracker.get), promise))
    promise
  }

  // Promise state encoding notes:
  //
  // There are six (6) possible promise states that we encode as raw objects (no state class
  // wrappers) for the sake of reducing allocations. This is why, for example, state "Linked"
  // is encoded as directly 'Promise[_]' and not as 'case class Linked(to: Promise[_])'.
  //
  // When we transition between states, we take into consideration the order in which we match
  // against the possible state cases. To determine the most optimal match oder, we  instrumented
  // the Promise code to keep track of the histogram of the most frequent states.
  //
  // Here is the data we've collected from one of our production services:
  //  - Transforming: 73%
  //  - Waiting: 13%
  //  - Done: 8%
  //  - Interruptible: 4%
  //  - Linked: 1%
  //  - Interrupted: <1%
  //
  // We use this exact match order (from most frequent to less frequent) in the Promise code.
  @volatile private[this] var state: Any = WaitQueue.empty[A]
  private def theState(): Any = state

  private[util] def this(forwardInterrupts: Future[_]) = {
    this()
    this.state = new Transforming[A](WaitQueue.empty, forwardInterrupts)
  }

  def this(handleInterrupt: PartialFunction[Throwable, Unit]) = {
    this()
    this.state = new Interruptible[A](WaitQueue.empty, handleInterrupt, Local.save())
  }

  def this(result: Try[A]) = {
    this()
    this.state = result
  }

  override def toString: String = {
    val theState = state match {
      case s: Transforming[A] => s"Transforming(${s.waitq},${s.other})"
      case waitq: Promise.WaitQueue[A] => s"Waiting($waitq)"
      case res: Try[A] => s"Done($res)"
      case s: Interruptible[A] => s"Interruptible(${s.waitq},${s.handler})"
      case p: Promise[A] => s"Linked(${p.toString})"
      case s: Interrupted[A] => s"Interrupted(${s.waitq},${s.signal})"
    }
    s"Promise@$hashCode(state=$theState)"
  }

  @inline private[this] def cas(oldState: Any, newState: Any): Boolean =
    unsafe.compareAndSwapObject(this, stateOff, oldState, newState)

  /**
   * (Re)sets the interrupt handler. There is only
   * one active interrupt handler.
   *
   * @param f the new interrupt handler
   */
  final def setInterruptHandler(f: PartialFunction[Throwable, Unit]): Unit =
    setInterruptHandler0(f, WaitQueue.empty)

  @tailrec
  private final def setInterruptHandler0(
    f: PartialFunction[Throwable, Unit],
    wq: WaitQueue[A]
  ): Unit =
    state match {
      case s: Transforming[A] =>
        if (!cas(s, new Interruptible(WaitQueue.merge(wq, s.waitq), f, Local.save())))
          setInterruptHandler0(f, wq)

      case waitq: WaitQueue[A] =>
        if (!cas(waitq, new Interruptible(WaitQueue.merge(wq, waitq), f, Local.save())))
          setInterruptHandler0(f, wq)

      case t: Try[A] /* Done */ => wq.runInScheduler(t)

      case s: Interruptible[A] =>
        if (!cas(s, new Interruptible(WaitQueue.merge(wq, s.waitq), f, Local.save())))
          setInterruptHandler0(f, wq)

      case p: Promise[A] /* Linked */ => p.setInterruptHandler0(f, wq)

      case s: Interrupted[A] =>
        if ((wq ne WaitQueue.Empty) &&
          !cas(s, new Interrupted(WaitQueue.merge(wq, s.waitq), s.signal)))
          setInterruptHandler0(f, wq)
        else f.applyOrElse(s.signal, Promise.AlwaysUnit)
    }

  // Useful for debugging waitq.
  private[util] def waitqLength: Int = state match {
    case s: Transforming[A] => s.waitq.size
    case waitq: WaitQueue[A] => waitq.size
    case _: Try[A] /* Done */ => 0
    case s: Interruptible[A] => s.waitq.size
    case _: Promise[A] /* Linked */ => 0
    case s: Interrupted[A] => s.waitq.size
  }

  /**
   * Forward interrupts to another future.
   * If the other future is fulfilled, this is a no-op.
   * Calling this multiple times is not recommended as
   * the resulting state may not be as expected.
   *
   * @param other the Future to which interrupts are forwarded.
   */
  final def forwardInterruptsTo(other: Future[_]): Unit =
    forwardInterruptsTo0(other, WaitQueue.empty)

  @tailrec private final def forwardInterruptsTo0(other: Future[_], wq: WaitQueue[A]): Unit = {
    // This reduces allocations in the common case.
    if (other.isDefined) continue(wq)
    else
      state match {
        case s: Transforming[A] =>
          if (!cas(s, new Transforming(WaitQueue.merge(wq, s.waitq), other)))
            forwardInterruptsTo0(other, wq)

        case waitq: WaitQueue[A] =>
          if (!cas(waitq, new Transforming(WaitQueue.merge(wq, waitq), other)))
            forwardInterruptsTo0(other, wq)

        case t: Try[A] /* Done */ => wq.runInScheduler(t)

        case s: Interruptible[A] =>
          if (!cas(s, new Transforming(WaitQueue.merge(wq, s.waitq), other)))
            forwardInterruptsTo0(other, wq)

        case p: Promise[A] /* Linked */ => p.forwardInterruptsTo0(other, wq)

        case s: Interrupted[_] =>
          if ((wq ne WaitQueue.Empty) &&
            !cas(s, new Interrupted(WaitQueue.merge(wq, s.waitq), s.signal)))
            forwardInterruptsTo0(other, wq)
          else other.raise(s.signal)
      }
  }

  final def raise(intr: Throwable): Unit = raise0(intr, WaitQueue.empty)

  // This is implemented as a private method on Promise so that the Scala 3
  // compiler recognizes this as a tail-recursive implementation. `raise` is
  // a definition on `Future` instead of `Promise`, which the Scala 3
  // compiler errors on with @tailrec annocation on the `Transforming` case,
  // where `s.other.raise` is invoked on a Future and not Promise. See CSL-11192.
  @tailrec private final def raise0(intr: Throwable, wq: WaitQueue[A]): Unit = state match {
    case s: Transforming[A] =>
      if (!cas(s, new Interrupted(WaitQueue.merge(wq, s.waitq), intr))) raise0(intr, wq)
      else s.other.raise(intr)

    case waitq: WaitQueue[A] =>
      if (!cas(waitq, new Interrupted(WaitQueue.merge(wq, waitq), intr)))
        raise0(intr, wq)

    case t: Try[A] /* Done */ => wq.runInScheduler(t)

    case s: Interruptible[A] =>
      if (!cas(s, new Interrupted(WaitQueue.merge(wq, s.waitq), intr))) raise0(intr, wq)
      else {
        val current = Local.save()
        if (current ne s.saved)
          Local.restore(s.saved)
        try s.handler.applyOrElse(intr, Promise.AlwaysUnit)
        finally Local.restore(current)
      }

    case p: Promise[A] /* Linked */ => p.raise0(intr, wq)

    case s: Interrupted[A] =>
      if (!cas(s, new Interrupted(WaitQueue.merge(wq, s.waitq), intr)))
        raise0(intr, wq)
  }

  @tailrec protected[Promise] final def detach(k: K[A]): Boolean = state match {
    case s: Transforming[A] =>
      if (!cas(s, new Transforming(s.waitq.remove(k), s.other)))
        detach(k)
      else
        s.waitq.contains(k)

    case waitq: WaitQueue[A] =>
      if (!cas(waitq, waitq.remove(k)))
        detach(k)
      else
        waitq.contains(k)

    case _: Try[A] /* Done */ => false

    case s: Interruptible[A] =>
      if (!cas(s, new Interruptible(s.waitq.remove(k), s.handler, s.saved)))
        detach(k)
      else
        s.waitq.contains(k)

    case p: Promise[A] /* Linked */ => p.detach(k)

    case s: Interrupted[A] =>
      if (!cas(s, new Interrupted(s.waitq.remove(k), s.signal)))
        detach(k)
      else
        s.waitq.contains(k)
  }

  // Awaitable
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  def ready(timeout: Duration)(implicit permit: Awaitable.CanAwait): this.type = state match {
    case _: Transforming[A] | _: WaitQueue[A] | _: Interruptible[A] | _: Interrupted[A] =>
      val condition = new ReleaseOnApplyCDL[A]
      respond(condition)

      // we need to `flush` pending tasks to give ourselves a chance
      // to complete. As a succinct example, this hangs without the `flush`:
      //
      //   Future.Done.map { _ =>
      //     Await.result(Future.Done.map(Predef.identity))
      //   }
      //
      Scheduler.flush()

      if (condition.await(timeout.inNanoseconds, java.util.concurrent.TimeUnit.NANOSECONDS)) this
      else throw new TimeoutException(timeout.toString)

    case _: Try[A] /* Done */ => this

    case p: Promise[A] /* Linked */ => p.ready(timeout); this
  }

  @throws(classOf[Exception])
  def result(timeout: Duration)(implicit permit: Awaitable.CanAwait): A = {
    val theTry = ready(timeout).compress().theState().asInstanceOf[Try[A]]
    theTry()
  }

  def isReady(implicit permit: Awaitable.CanAwait): Boolean =
    isDefined

  /**
   * Returns this promise's interrupt if it is interrupted.
   */
  def isInterrupted: Option[Throwable] = state match {
    case _: Transforming[A] | _: WaitQueue[A] | _: Interruptible[A] => None
    case _: Try[A] /* Done */ => None
    case p: Promise[A] /* Linked */ => p.isInterrupted
    case s: Interrupted[A] => Some(s.signal)
  }

  /**
   * Become the other promise. `become` declares an equivalence
   * relation: `this` and `other` are the ''same''.
   *
   * By becoming `other`, its waitlists are now merged into `this`'s,
   * and `this` becomes canonical. The same is true of interrupt
   * handlers: `other`'s interrupt handler is overwritten with the
   * handlers installed for `this`.
   *
   * Note: Using `become` and `setInterruptHandler` on the same
   * promise is not recommended. Consider the following, which
   * demonstrates unexpected behavior related to this usage.
   *
   * {{{
   * val a, b = new Promise[Unit]
   * a.setInterruptHandler { case _ => println("A") }
   * b.become(a)
   * b.setInterruptHandler { case _ => println("B") }
   * a.raise(new Exception)
   * }}}
   *
   * This prints "B", the action in the interrupt handler for `b`,
   * which is unexpected because we raised on `a`. In this case and
   * others, using [[com.twitter.util.Future.proxyTo]] may be more
   * appropriate.
   *
   * Note that `this` must be unsatisfied at the time of the call,
   * and not race with any other setters. `become` is a form of
   * satisfying the promise.
   *
   * This has the combined effect of compressing the `other` into
   * `this`, effectively providing a form of tail-call elimination
   * when used in recursion constructs. `transform` (and thus any
   * other combinator) use this to compress Futures, freeing them
   * from space leaks when used with recursive constructions.
   *
   * '''Note:''' do not use become with cyclic graphs of futures: the
   * behavior of racing `a.become(b)` with `b.become(a)` is undefined
   * (where `a` and `b` may resolve as such transitively).
   *
   * @see [[com.twitter.util.Future.proxyTo]]
   */
  def become(other: Future[A]): Unit = {
    if (other.isInstanceOf[Promise[_]]) {
      if (isDefined) {
        val current = Await.result(liftToTry)
        throw new IllegalStateException(
          s"cannot become() on an already satisfied promise: $current")
      }
      val that = other.asInstanceOf[Promise[A]]
      that.link(compress())
    } else {
      // avoid an extra call to `isDefined` as `proxyTo` checks
      other.proxyTo(this)
      forwardInterruptsTo(other)
    }
  }

  /**
   * Populate the Promise with the given result.
   *
   * @throws Promise.ImmutableResult if the Promise is already populated
   */
  def setValue(result: A): Unit = update(Return(result))

  /**
   * Populate the Promise with the given exception.
   *
   * @throws Promise.ImmutableResult if the Promise is already populated
   */
  def setException(throwable: Throwable): Unit = update(Throw(throwable))

  /**
   * Sets a Unit-typed future. By convention, futures of type
   * Future[Unit] are used for signalling.
   */
  def setDone()(implicit ev: this.type <:< Promise[Unit]): Boolean =
    ev(this).updateIfEmpty(Return.Unit)

  /**
   * Populate the Promise with the given Try. The try can either be a
   * value or an exception. setValue and setException are generally
   * more readable methods to use.
   *
   * @throws Promise.ImmutableResult if the Promise is already populated
   */
  final def update(result: Try[A]): Unit = {
    updateIfEmpty(result) || {
      val current = Await.result(liftToTry)
      throw ImmutableResult(s"Result set multiple times. Value='$current', New='$result'")
    }
  }

  /**
   * Populate the Promise with the given Try. The Try can either be a
   * value or an exception. `setValue` and `setException` are generally
   * more readable methods to use.
   *
   * @note Invoking `updateIfEmpty` without checking the boolean result is almost
   * never the right approach. Doing so is generally unsafe unless race
   * conditions are acceptable.
   * @return true only if the result is updated, false if it was already set.
   */
  @tailrec
  final def updateIfEmpty(result: Try[A]): Boolean = state match {
    case s: Transforming[A] =>
      if (!cas(s, result)) updateIfEmpty(result)
      else {
        s.waitq.runInScheduler(result)
        true
      }

    case waitq: WaitQueue[A] =>
      if (!cas(waitq, result)) updateIfEmpty(result)
      else {
        waitq.runInScheduler(result)
        true
      }

    case _: Try[A] /* Done */ => false

    case s: Interruptible[A] =>
      if (!cas(s, result)) updateIfEmpty(result)
      else {
        s.waitq.runInScheduler(result)
        true
      }

    case p: Promise[A] /* Linked */ => p.updateIfEmpty(result)

    case s: Interrupted[A] =>
      if (!cas(s, result)) updateIfEmpty(result)
      else {
        s.waitq.runInScheduler(result)
        true
      }
  }

  @tailrec
  protected final def continue(wq: WaitQueue[A]): Unit =
    if (wq ne WaitQueue.Empty)
      state match {
        case s: Transforming[A] =>
          if (!cas(s, new Transforming(WaitQueue.merge(wq, s.waitq), s.other)))
            continue(wq)

        case waitq: WaitQueue[A] =>
          if (!cas(waitq, WaitQueue.merge(wq, waitq)))
            continue(wq)

        case v: Try[A] /* Done */ => wq.runInScheduler(v)

        case s: Interruptible[A] =>
          if (!cas(s, new Interruptible(WaitQueue.merge(wq, s.waitq), s.handler, s.saved)))
            continue(wq)

        case p: Promise[A] /* Linked */ => p.continue(wq)

        case s: Interrupted[A] =>
          if (!cas(s, new Interrupted(WaitQueue.merge(wq, s.waitq), s.signal)))
            continue(wq)
      }

  /**
   * Should only be called when this Promise has already been fulfilled
   * or it is becoming another Future via `become`.
   */
  protected final def compress(): Promise[A] = state match {
    case p: Promise[A] /* Linked */ =>
      val target = p.compress()
      // due to the assumptions stated above regarding when this can be called,
      // there should never be a `cas` fail.
      state = target
      target

    case _ => this
  }

  @tailrec
  protected final def link(target: Promise[A]): Unit = {
    if (this eq target) return

    state match {
      case s: Transforming[A] =>
        if (!cas(s, target)) link(target)
        else target.forwardInterruptsTo0(s.other, s.waitq)

      case waitq: WaitQueue[A] =>
        if (!cas(waitq, target)) link(target)
        else target.continue(waitq)

      case value: Try[A] /* Done */ =>
        if (!target.updateIfEmpty(value) && value != Await.result(target)) {
          throw new IllegalArgumentException("Cannot link two Done Promises with differing values")
        }

      case s: Interruptible[A] =>
        if (!cas(s, target)) link(target)
        else target.setInterruptHandler0(s.handler, s.waitq)

      case p: Promise[A] /* Linked */ =>
        if (cas(p, target)) p.link(target)
        else link(target)

      case s: Interrupted[A] =>
        if (!cas(s, target)) link(target)
        else target.raise0(s.signal, s.waitq)
    }
  }

  def poll: Option[Try[A]] = state match {
    case res: Try[A] /* Done */ => Some(res)
    case p: Promise[A] /* Linked */ => p.poll
    case _ /* WaitQueue, Interruptible, Interrupted, or Transforming */ => None
  }

  override def isDefined: Boolean = state match {
    // Note: the basic implementation is the same as `poll()`, but we want to avoid doing
    // object allocations for `Some`s when the caller does not need the result.
    case _: Try[A] /* Done */ => true
    case p: Promise[A] /* Linked */ => p.isDefined
    case _ /* WaitQueue, Interruptible, Interrupted, or Transforming */ => false
  }
}
