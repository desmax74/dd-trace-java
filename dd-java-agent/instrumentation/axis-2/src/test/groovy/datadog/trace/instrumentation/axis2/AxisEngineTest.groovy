package datadog.trace.instrumentation.axis2

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.axis2.AxisFault
import org.apache.axis2.addressing.EndpointReference
import org.apache.axis2.context.MessageContext
import org.apache.axis2.context.ServiceContext
import org.apache.axis2.context.ServiceGroupContext
import org.apache.axis2.description.AxisService
import org.apache.axis2.description.InOnlyAxisOperation
import org.apache.axis2.description.OutOnlyAxisOperation
import org.apache.axis2.description.TransportInDescription
import org.apache.axis2.description.TransportOutDescription
import org.apache.axis2.engine.AxisEngine
import org.apache.axis2.engine.Handler.InvocationResponse
import org.apache.axis2.handlers.AbstractHandler
import org.apache.axis2.transport.local.LocalTransportSender
import spock.lang.Shared

import javax.xml.namespace.QName

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static org.apache.axiom.om.OMAbstractFactory.getSOAP11Factory
import static org.apache.axis2.context.ConfigurationContextFactory.createEmptyConfigurationContext
import static org.apache.axis2.context.MessageContext.IN_FLOW
import static org.apache.axis2.context.MessageContext.OUT_FLOW
import static org.apache.axis2.context.MessageContext.TRANSPORT_IN
import static org.apache.axis2.context.MessageContext.TRANSPORT_OUT
import static org.apache.axis2.engine.Handler.InvocationResponse.CONTINUE
import static org.apache.axis2.engine.Handler.InvocationResponse.SUSPEND
import static org.apache.axis2.util.MessageContextBuilder.createFaultMessageContext

class AxisEngineTest extends AgentTestRunner {

  @Shared
  def testDestination = new EndpointReference('testDestination')

  def "test no traces without surrounding operation"() {
    when:
    def message1 = outMessage()
    message1.setSoapAction('testAction')
    AxisEngine.send(message1)

    def message2 = outMessage()
    // no action, expect span to use testDestination
    AxisEngine.send(message2)

    def message3 = inMessage()
    message3.setSoapAction('testAction')
    AxisEngine.receive(message3)

    def message4 = inMessage()
    // no action, expect span to use testDestination
    AxisEngine.receive(message4)

    then:
    assertTraces(0) {}
  }

  def "test send"() {
    when:
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      def message1 = outMessage()
      message1.setSoapAction('testAction')
      AxisEngine.send(message1)

      def message2 = outMessage()
      // no action, expect span to use testDestination
      AxisEngine.send(message2)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(3) {
        testSpan(it, 0)
        axisSpan(it, 1, 'testDestination', span(0))
        axisSpan(it, 2, 'testAction', span(0))
      }
    }
  }

  def "test pause+resume send"() {
    when:
    def message = outMessage()
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      message.getConfigurationContext().getAxisConfiguration().getOutFlowPhases().add(new AbstractHandler() {
        @Override
        InvocationResponse invoke(MessageContext messageContext) {
          return SUSPEND
        }
      })
      AxisEngine.send(message)
    }
    span0.finish()

    then:
    assertTraces(0) {}

    when:
    activateSpan(span0).withCloseable {
      message.getExecutionChain().clear()
      AxisEngine.resume(message)
    }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        testSpan(it, 0)
        axisSpan(it, 1, 'testDestination', span(0))
        axisSpan(it, 2, 'testDestination', span(1))
      }
    }
  }

  def "test exception during send"() {
    when:
    def message = outMessage()
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      message.getConfigurationContext().getAxisConfiguration().getOutFlowPhases().add(new AbstractHandler() {
        @Override
        InvocationResponse invoke(MessageContext messageContext) {
          throw new RuntimeException('Internal Error')
        }
      })
      try {
        AxisEngine.send(message)
      } catch (RuntimeException ignore) {
        // expected
      }
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(2) {
        testSpan(it, 0)
        axisSpanWithException(it, 1, 'testDestination', span(0), new RuntimeException('Internal Error'))
      }
    }
  }

  def "test sendFault"() {
    when:
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      AxisEngine.sendFault(createFaultMessageContext(outMessage(), new Exception('internal error')))
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(2) {
        testSpan(it, 0)
        axisSpanWithError(it, 1, 'http://www.w3.org/2005/08/addressing/soap/fault', span(0))
      }
    }
  }

  def "test receive"() {
    when:
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      def message1 = inMessage()
      message1.setSoapAction('testAction')
      AxisEngine.receive(message1)

      def message2 = inMessage()
      // no action, expect span to use testDestination
      AxisEngine.receive(message2)
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(3) {
        testSpan(it, 0)
        axisSpan(it, 1, 'testDestination', span(0))
        axisSpan(it, 2, 'testAction', span(0))
      }
    }
  }

  def "test pause+resume receive"() {
    when:
    def message = inMessage()
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      message.getConfigurationContext().getAxisConfiguration().getInFlowPhases().add(new AbstractHandler() {
        @Override
        InvocationResponse invoke(MessageContext messageContext) {
          return SUSPEND
        }
      })
      AxisEngine.receive(message)
    }
    span0.finish()

    then:
    assertTraces(0) {}

    when:
    activateSpan(span0).withCloseable {
      message.getExecutionChain().clear()
      AxisEngine.resume(message)
    }

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        testSpan(it, 0)
        axisSpan(it, 1, 'testDestination', span(0))
        axisSpan(it, 2, 'testDestination', span(1))
      }
    }
  }

  def "test exception during receive"() {
    when:
    def message = inMessage()
    AgentSpan span0 = startSpan('test')
    span0.setServiceName('testSpan')
    activateSpan(span0).withCloseable {
      message.getConfigurationContext().getAxisConfiguration().getInFlowPhases().add(new AbstractHandler() {
        @Override
        InvocationResponse invoke(MessageContext messageContext) {
          throw new RuntimeException('Internal Error')
        }
      })
      try {
        AxisEngine.receive(message)
      } catch (RuntimeException ignore) {
        // expected
      }
    }
    span0.finish()

    then:
    assertTraces(1) {
      trace(2) {
        testSpan(it, 0)
        axisSpanWithException(it, 1, 'testDestination', span(0), new RuntimeException('Internal Error'))
      }
    }
  }

  def outMessage() {
    def message = new MessageContext()
    message.setFLOW(OUT_FLOW)
    def config = createEmptyConfigurationContext()
    message.setConfigurationContext(config)
    def service = new AxisService('TestService')
    def operation = new OutOnlyAxisOperation(new QName('TestOut'))
    service.addOperation(operation)
    config.getAxisConfiguration().addService(service)
    def group = new ServiceGroupContext(config, null)
    message.setOperationContext(new ServiceContext(service, group).createOperationContext(operation))
    def soapFactory = getSOAP11Factory()
    def envelope = soapFactory.createSOAPEnvelope()
    def body = soapFactory.createSOAPBody(envelope)
    body.addChild(soapFactory.createOMElement('value', null))
    message.setEnvelope(envelope)
    message.setTransportOut(new TransportOutDescription('http'))
    message.getTransportOut().setSender(new LocalTransportSender() {
      @Override
      InvocationResponse invoke(MessageContext msgContext) throws AxisFault {
        return CONTINUE
      }
    })
    message.setProperty(TRANSPORT_OUT, new ByteArrayOutputStream())
    message.setTo(testDestination)
    return message
  }

  def inMessage() {
    def message = new MessageContext()
    message.setFLOW(IN_FLOW)
    def config = createEmptyConfigurationContext()
    message.setConfigurationContext(config)
    def service = new AxisService('TestService')
    def operation = new InOnlyAxisOperation(new QName('TestIn'))
    service.addOperation(operation)
    config.getAxisConfiguration().addService(service)
    def group = new ServiceGroupContext(config, null)
    message.setOperationContext(new ServiceContext(service, group).createOperationContext(operation))
    def soapFactory = getSOAP11Factory()
    def envelope = soapFactory.createSOAPEnvelope()
    def body = soapFactory.createSOAPBody(envelope)
    body.addChild(soapFactory.createOMElement('value', null))
    message.setEnvelope(envelope)
    message.setTransportIn(new TransportInDescription('http'))
    message.setProperty(TRANSPORT_IN, new ByteArrayInputStream())
    message.setTo(testDestination)
    return message
  }

  def testSpan(TraceAssert trace, int index, Object parentSpan = null) {
    trace.span {
      serviceName "testSpan"
      operationName "test"
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        defaultTags()
      }
    }
  }

  def axisSpan(TraceAssert trace, int index, String soapAction, Object parentSpan, Object error = null) {
    trace.span {
      serviceName "testSpan"
      operationName "axis2.message"
      resourceName soapAction
      spanType DDSpanTypes.SOAP
      errored error != null
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel parentSpan == null
      tags {
        if (error instanceof Exception) {
          "error.msg" error.message
          "error.type" { it == error.class.name }
          "error.stack" String
        }
        "$Tags.COMPONENT" "axis2"
        defaultTags()
      }
    }
  }

  def axisSpanWithError(TraceAssert trace, int index, String soapAction, Object parentSpan) {
    axisSpan(trace, index, soapAction, parentSpan, true)
  }

  def axisSpanWithException(TraceAssert trace, int index, String soapAction, Object parentSpan, Exception error) {
    axisSpan(trace, index, soapAction, parentSpan, error)
  }
}
