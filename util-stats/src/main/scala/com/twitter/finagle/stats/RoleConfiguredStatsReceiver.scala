package com.twitter.finagle.stats

/**
 * A StatsReceiver proxy that configures all counter, stat, and gauge
 * SourceRoles to the passed in "role".
 *
 * @param self The underlying StatsReceiver to which translated names are passed
 * @param role the role used for SourceRole Metadata
 */
case class RoleConfiguredStatsReceiver(
  protected val self: StatsReceiver,
  role: SourceRole)
    extends StatsReceiverProxy {

  override def counter(counterSchema: CounterSchema): Counter = {
    self.counter(CounterSchema(counterSchema.metricBuilder.withRole(role)))
  }

  override def stat(histogramSchema: HistogramSchema): Stat = {
    self.stat(HistogramSchema(histogramSchema.metricBuilder.withRole(role)))
  }

  override def addGauge(gaugeSchema: GaugeSchema)(f: => Float): Gauge = {
    self.addGauge(GaugeSchema(gaugeSchema.metricBuilder.withRole(role)))(f)
  }
}
