import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import okhttp3.OkHttpClient
import okhttp3.Request
import play.api.test.TestServer
import play.test.Helpers
import spock.lang.Shared

class PlayTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.java_concurrent.enabled", "true")
    System.setProperty("dd.integration.play.enabled", "true")
  }

  @Shared
  TestServer testServer

  def setupSpec() {
    testServer = Helpers.testServer(9080, PlayTestUtils.buildTestApp())
    testServer.start()
  }

  def cleanupSpec() {
    testServer.stop()
  }

  @Override
  void afterTest() {
    // Ignore failures to instrument sun proxy classes
  }

  def "request traces" () {
    setup:
    OkHttpClient client = new OkHttpClient.Builder().build()
    def request = new Request.Builder()
      .url("http://localhost:9080/helloplay/spock")
      .header("x-datadog-trace-id", "123")
      .header("x-datadog-parent-id", "456")
      .get()
      .build()
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan[] playTrace = TEST_WRITER.get(0)
    DDSpan root = playTrace[0]

    expect:
    testServer != null
    response.code() == 200
    response.body().string() == "hello spock"

    // async work is linked to play trace
    playTrace.size() == 2
    playTrace[1].operationName == 'TracedWork$.doWork'

    root.traceId == 123
    root.parentId == 456
    root.serviceName == "unnamed-java-app"
    root.operationName == "/helloplay/:from"
    root.resourceName == "GET /helloplay/:from"
    !root.context().getErrorFlag()
    root.context().tags["http.status_code"] == 200
    root.context().tags["http.url"] == "/helloplay/:from"
    root.context().tags["http.method"] == "GET"
    root.context().tags["span.kind"] == "server"
    root.context().tags["component"] == "play-action"
  }

  def "5xx errors trace" () {
    setup:
    OkHttpClient client = new OkHttpClient.Builder().build()
    def request = new Request.Builder()
      .url("http://localhost:9080/make-error")
      .get()
      .build()
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan[] playTrace = TEST_WRITER.get(0)
    DDSpan root = playTrace[0]

    expect:
    testServer != null
    response.code() == 500

    root.serviceName == "unnamed-java-app"
    root.operationName == "/make-error"
    root.resourceName == "GET /make-error"
    root.context().getErrorFlag()
    root.context().tags["http.status_code"] == 500
    root.context().tags["http.url"] == "/make-error"
    root.context().tags["http.method"] == "GET"
    root.context().tags["span.kind"] == "server"
    root.context().tags["component"] == "play-action"
  }

  def "error thrown in request" () {
    setup:
    OkHttpClient client = new OkHttpClient.Builder().build()
    def request = new Request.Builder()
      .url("http://localhost:9080/exception")
      .get()
      .build()
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan[] playTrace = TEST_WRITER.get(0)
    DDSpan root = playTrace[0]

    expect:
    testServer != null
    response.code() == 500

    root.context().getErrorFlag()
    root.context().tags["error.msg"] == "oh no"
    root.context().tags["error.type"] == RuntimeException.getName()

    root.serviceName == "unnamed-java-app"
    root.operationName == "/exception"
    root.resourceName == "GET /exception"
    root.context().tags["http.status_code"] == 500
    root.context().tags["http.url"] == "/exception"
    root.context().tags["http.method"] == "GET"
    root.context().tags["span.kind"] == "server"
    root.context().tags["component"] == "play-action"
  }

  def "4xx errors trace" () {
    setup:
    OkHttpClient client = new OkHttpClient.Builder().build()
    def request = new Request.Builder()
      .url("http://localhost:9080/nowhere")
      .get()
      .build()
    def response = client.newCall(request).execute()
    TEST_WRITER.waitForTraces(1)
    DDSpan[] playTrace = TEST_WRITER.get(0)
    DDSpan root = playTrace[0]

    expect:
    testServer != null
    response.code() == 404

    root.serviceName == "unnamed-java-app"
    root.operationName == "play.request"
    root.resourceName == "404"
    !root.context().getErrorFlag()
    root.context().tags["http.status_code"] == 404
    root.context().tags["http.url"] == null
    root.context().tags["http.method"] == "GET"
    root.context().tags["span.kind"] == "server"
    root.context().tags["component"] == "play-action"
  }
}
