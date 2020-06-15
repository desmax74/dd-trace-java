package datadog.trace.common.writer;

import static datadog.trace.api.Config.DEFAULT_AGENT_HOST;
import static datadog.trace.api.Config.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.Config.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.Config.DEFAULT_TRACE_AGENT_PORT;

import com.lmax.disruptor.EventFactory;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.common.writer.ddagent.DispatchingDisruptor;
import datadog.trace.common.writer.ddagent.Monitor;
import datadog.trace.common.writer.ddagent.MsgPackStatefulSerializer;
import datadog.trace.common.writer.ddagent.StatefulSerializer;
import datadog.trace.common.writer.ddagent.TraceBuffer;
import datadog.trace.common.writer.ddagent.TraceProcessingDisruptor;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * This writer buffers traces and sends them to the provided DDApi instance. Buffering is done with
 * a distruptor to limit blocking the application threads. Internally, the trace is serialized and
 * put onto a separate disruptor that does block to decouple the CPU intensive from the IO bound
 * threads.
 *
 * <p>[Application] -> [trace processing buffer] -> [serialized trace batching buffer] -> [dd-agent]
 *
 * <p>Note: the first buffer is non-blocking and will discard if full, the second is blocking and
 * will cause back pressure on the trace processing (serializing) thread.
 *
 * <p>If the buffer is filled traces are discarded before serializing. Once serialized every effort
 * is made to keep, to avoid wasting the serialization effort.
 */
@Slf4j
public class DDAgentWriter implements Writer {

  private static final int DISRUPTOR_BUFFER_SIZE = 1024;
  private static final int OUTSTANDING_REQUESTS = 4;

  private final DDAgentApi api;
  private final TraceProcessingDisruptor traceProcessingDisruptor;
  private final DispatchingDisruptor dispatchingDisruptor;

  private final AtomicInteger traceCount = new AtomicInteger(0);
  private volatile boolean closed;

  public final Monitor monitor;

  // Apply defaults to the class generated by lombok.
  public static class DDAgentWriterBuilder {
    String agentHost = DEFAULT_AGENT_HOST;
    int traceAgentPort = DEFAULT_TRACE_AGENT_PORT;
    String unixDomainSocket = DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
    long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_AGENT_TIMEOUT);
    int traceBufferSize = DISRUPTOR_BUFFER_SIZE;
    Monitor monitor = new Monitor.Noop();
    int flushFrequencySeconds = 1;
  }

  @Deprecated
  public DDAgentWriter() {
    this(
        new DDAgentApi(
            DEFAULT_AGENT_HOST,
            DEFAULT_TRACE_AGENT_PORT,
            DEFAULT_AGENT_UNIX_DOMAIN_SOCKET,
            TimeUnit.SECONDS.toMillis(DEFAULT_AGENT_TIMEOUT)),
        new Monitor.Noop());
  }

  @Deprecated
  public DDAgentWriter(final DDAgentApi api, final Monitor monitor) {
    StatefulSerializer serializer = new MsgPackStatefulSerializer();
    this.api = api;
    this.monitor = monitor;
    dispatchingDisruptor =
        new DispatchingDisruptor(
            OUTSTANDING_REQUESTS, toEventFactory(serializer), api, monitor, this);
    traceProcessingDisruptor =
        new TraceProcessingDisruptor(
            DISRUPTOR_BUFFER_SIZE,
            dispatchingDisruptor,
            monitor,
            this,
            serializer,
            1,
            TimeUnit.SECONDS,
            false);
  }

  @lombok.Builder
  // These field names must be stable to ensure the builder api is stable.
  private DDAgentWriter(
      final DDAgentApi agentApi,
      final String agentHost,
      final int traceAgentPort,
      final String unixDomainSocket,
      final long timeoutMillis,
      final int traceBufferSize,
      final Monitor monitor,
      final int flushFrequencySeconds,
      final StatefulSerializer serializer) {
    if (agentApi != null) {
      api = agentApi;
    } else {
      api = new DDAgentApi(agentHost, traceAgentPort, unixDomainSocket, timeoutMillis);
    }
    final StatefulSerializer s = null == serializer ? new MsgPackStatefulSerializer() : serializer;
    this.monitor = monitor;
    this.dispatchingDisruptor =
        new DispatchingDisruptor(OUTSTANDING_REQUESTS, toEventFactory(s), api, monitor, this);
    this.traceProcessingDisruptor =
        new TraceProcessingDisruptor(
            traceBufferSize,
            dispatchingDisruptor,
            monitor,
            this,
            s,
            flushFrequencySeconds,
            TimeUnit.SECONDS,
            flushFrequencySeconds > 0);
  }

  public void addResponseListener(final DDAgentResponseListener listener) {
    api.addResponseListener(listener);
  }

  // Exposing some statistics for consumption by monitors
  public final long getDisruptorCapacity() {
    return traceProcessingDisruptor.getDisruptorCapacity();
  }

  public final long getDisruptorUtilizedCapacity() {
    return getDisruptorCapacity() - getDisruptorRemainingCapacity();
  }

  public final long getDisruptorRemainingCapacity() {
    return traceProcessingDisruptor.getDisruptorRemainingCapacity();
  }

  @Override
  public void write(final List<DDSpan> trace) {
    // We can't add events after shutdown otherwise it will never complete shutting down.
    if (!closed) {
      final int representativeCount;
      if (trace.isEmpty() || !(trace.get(0).isRootSpan())) {
        // We don't want to reset the count if we can't correctly report the value.
        representativeCount = 1;
      } else {
        representativeCount = traceCount.getAndSet(0) + 1;
      }
      final boolean published = traceProcessingDisruptor.publish(trace, representativeCount);
      if (published) {
        monitor.onPublish(DDAgentWriter.this, trace);
      } else {
        // We're discarding the trace, but we still want to count it.
        traceCount.addAndGet(representativeCount);
        log.debug("Trace written to overfilled buffer. Counted but dropping trace: {}", trace);
        monitor.onFailedPublish(this, trace);
      }
    } else {
      log.debug("Trace written after shutdown. Ignoring trace: {}", trace);
      monitor.onFailedPublish(this, trace);
    }
  }

  public boolean flush() {
    if (!closed) { // give up after a second
      if (traceProcessingDisruptor.flush(1, TimeUnit.SECONDS)) {
        monitor.onFlush(this, false);
        return true;
      }
    }
    return false;
  }

  @Override
  public void incrementTraceCount() {
    traceCount.incrementAndGet();
  }

  public DDAgentApi getApi() {
    return api;
  }

  @Override
  public void start() {
    if (!closed) {
      dispatchingDisruptor.start();
      traceProcessingDisruptor.start();
      monitor.onStart(this);
    }
  }

  @Override
  public void close() {
    boolean flushed = flush();
    closed = true;
    try {
      traceProcessingDisruptor.close();
    } finally { // in case first close fails.
      dispatchingDisruptor.close();
    }
    monitor.onShutdown(this, flushed);
  }

  @Override
  public String toString() {
    // DQH - I don't particularly like the instanceof check,
    // but I decided it was preferable to adding an isNoop method onto
    // Monitor or checking the result of Monitor#toString() to determine
    // if something is *probably* the NoopMonitor.

    String str = "DDAgentWriter { api=" + api;
    if (!(monitor instanceof Monitor.Noop)) {
      str += ", monitor=" + monitor;
    }
    str += " }";

    return str;
  }

  private static EventFactory<TraceBuffer> toEventFactory(final StatefulSerializer serializer) {
    return new SerializerBackedEventFactory(serializer);
  }

  private static final class SerializerBackedEventFactory implements EventFactory<TraceBuffer> {
    private final StatefulSerializer serializer;

    private SerializerBackedEventFactory(StatefulSerializer serializer) {
      this.serializer = serializer;
    }

    @Override
    public TraceBuffer newInstance() {
      return serializer.newBuffer();
    }
  }
}
