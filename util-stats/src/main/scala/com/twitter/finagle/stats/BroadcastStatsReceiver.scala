package com.twitter.finagle.stats

import com.twitter.finagle.stats.exp.ExpressionSchema

/**
 * BroadcastStatsReceiver is a helper object that create a StatsReceiver wrapper around multiple
 * StatsReceivers (n).
 */
object BroadcastStatsReceiver {
  def apply(receivers: Seq[StatsReceiver]): StatsReceiver = receivers.filterNot(_.isNull) match {
    case Seq() => NullStatsReceiver
    case Seq(fst) => fst
    case Seq(first, second) => new Two(first, second)
    case more => new N(more)
  }

  private class Two(first: StatsReceiver, second: StatsReceiver)
      extends StatsReceiver
      with DelegatingStatsReceiver {
    val repr: AnyRef = this

    def counter(schema: CounterSchema) = new BroadcastCounter.Two(
      first.counter(schema),
      second.counter(schema)
    )

    def stat(schema: HistogramSchema) = new BroadcastStat.Two(
      first.stat(schema),
      second.stat(schema)
    )

    def addGauge(schema: GaugeSchema)(f: => Float) = new Gauge {
      val firstGauge = first.addGauge(schema)(f)
      val secondGauge = second.addGauge(schema)(f)
      def remove(): Unit = {
        firstGauge.remove()
        secondGauge.remove()
      }
    }

    override protected[finagle] def registerExpression(expressionSchema: ExpressionSchema): Unit = {
      first.registerExpression(expressionSchema)
      second.registerExpression(expressionSchema)
    }

    def underlying: Seq[StatsReceiver] = Seq(first, second)

    override def toString: String =
      s"Broadcast($first, $second)"
  }

  private class N(srs: Seq[StatsReceiver]) extends StatsReceiver with DelegatingStatsReceiver {
    val repr: AnyRef = this

    def counter(schema: CounterSchema) =
      BroadcastCounter(srs.map { _.counter(schema) })

    def stat(schema: HistogramSchema) =
      BroadcastStat(srs.map { _.stat(schema) })

    def addGauge(schema: GaugeSchema)(f: => Float) = new Gauge {
      val gauges = srs.map { _.addGauge(schema)(f) }
      def remove(): Unit = gauges.foreach { _.remove() }
    }

    override protected[finagle] def registerExpression(expressionSchema: ExpressionSchema): Unit =
      srs.map(_.registerExpression(expressionSchema))

    def underlying: Seq[StatsReceiver] = srs

    override def toString: String =
      s"Broadcast(${underlying.mkString(", ")})"
  }
}

/**
 * BroadcastCounter is a helper object that create a Counter wrapper around multiple
 * Counters (n).
 * For performance reason, we have specialized cases if n == (0, 1, 2, 3 or 4)
 */
object BroadcastCounter {
  def apply(counters: Seq[Counter]): Counter = counters match {
    case Seq() => NullCounter
    case Seq(counter) => counter
    case Seq(a, b) => new Two(a, b)
    case Seq(a, b, c) => new Three(a, b, c)
    case Seq(a, b, c, d) => new Four(a, b, c, d)
    case more => new N(more)
  }

  private object NullCounter extends Counter {
    def incr(delta: Long): Unit = ()
  }

  private[stats] class Two(a: Counter, b: Counter) extends Counter {
    def incr(delta: Long): Unit = {
      a.incr(delta)
      b.incr(delta)
    }
  }

  private class Three(a: Counter, b: Counter, c: Counter) extends Counter {
    def incr(delta: Long): Unit = {
      a.incr(delta)
      b.incr(delta)
      c.incr(delta)
    }
  }

  private class Four(a: Counter, b: Counter, c: Counter, d: Counter) extends Counter {
    def incr(delta: Long): Unit = {
      a.incr(delta)
      b.incr(delta)
      c.incr(delta)
      d.incr(delta)
    }
  }

  private class N(counters: Seq[Counter]) extends Counter {
    def incr(delta: Long): Unit = { counters.foreach(_.incr(delta)) }
  }
}

/**
 * BroadcastStat is a helper object that create a Counter wrapper around multiple
 * Stats (n).
 * For performance reason, we have specialized cases if n == (0, 1, 2, 3 or 4)
 */
object BroadcastStat {
  def apply(stats: Seq[Stat]): Stat = stats match {
    case Seq() => NullStat
    case Seq(counter) => counter
    case Seq(a, b) => new Two(a, b)
    case Seq(a, b, c) => new Three(a, b, c)
    case Seq(a, b, c, d) => new Four(a, b, c, d)
    case more => new N(more)
  }

  private object NullStat extends Stat {
    def add(value: Float): Unit = ()
  }

  private[stats] class Two(a: Stat, b: Stat) extends Stat {
    def add(value: Float): Unit = {
      a.add(value)
      b.add(value)
    }
  }

  private class Three(a: Stat, b: Stat, c: Stat) extends Stat {
    def add(value: Float): Unit = {
      a.add(value)
      b.add(value)
      c.add(value)
    }
  }

  private class Four(a: Stat, b: Stat, c: Stat, d: Stat) extends Stat {
    def add(value: Float): Unit = {
      a.add(value)
      b.add(value)
      c.add(value)
      d.add(value)
    }
  }

  private class N(stats: Seq[Stat]) extends Stat {
    def add(value: Float): Unit = { stats.foreach(_.add(value)) }
  }
}
