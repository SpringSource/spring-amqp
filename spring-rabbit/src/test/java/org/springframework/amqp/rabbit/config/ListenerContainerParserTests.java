/*
 * Copyright 2010-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.amqp.rabbit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.aopalliance.aop.Advice;
import org.junit.Before;
import org.junit.Test;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.ConsumerTagStrategy;
import org.springframework.amqp.utils.test.TestUtils;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.parsing.BeanDefinitionParsingException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author Mark Fisher
 * @author Gary Russell
 * @author Artem Bilan
 */
public class ListenerContainerParserTests {

	private DefaultListableBeanFactory beanFactory;

	@Before
	public void setUp() {
		beanFactory = new DefaultListableBeanFactory();
		XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(beanFactory);
		reader.loadBeanDefinitions(new ClassPathResource(getClass().getSimpleName() + "-context.xml", getClass()));
		beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver());
	}

	@Test
	public void testParseWithQueueNames() {
		SimpleMessageListenerContainer container =
				this.beanFactory.getBean("container1", SimpleMessageListenerContainer.class);
		assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
		assertThat(container.getConnectionFactory()).isEqualTo(beanFactory.getBean(ConnectionFactory.class));
		assertThat(container.getMessageListener().getClass()).isEqualTo(MessageListenerAdapter.class);
		DirectFieldAccessor listenerAccessor = new DirectFieldAccessor(container.getMessageListener());
		assertThat(listenerAccessor.getPropertyValue("delegate")).isEqualTo(beanFactory.getBean(TestBean.class));
		assertThat(listenerAccessor.getPropertyValue("defaultListenerMethod")).isEqualTo("handle");
		Queue queue = beanFactory.getBean("bar", Queue.class);
		assertThat(Arrays.asList(container.getQueueNames()).toString()).isEqualTo("[foo, " + queue.getName() + "]");
		assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(5);
		assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(6);
		assertThat(ReflectionTestUtils.getField(container, "startConsumerMinInterval")).isEqualTo(1234L);
		assertThat(ReflectionTestUtils.getField(container, "stopConsumerMinInterval")).isEqualTo(2345L);
		assertThat(ReflectionTestUtils.getField(container, "consecutiveActiveTrigger")).isEqualTo(12);
		assertThat(ReflectionTestUtils.getField(container, "consecutiveIdleTrigger")).isEqualTo(34);
		assertThat(ReflectionTestUtils.getField(container, "receiveTimeout")).isEqualTo(9876L);
		Map<?, ?> consumerArgs = TestUtils.getPropertyValue(container, "consumerArgs", Map.class);
		assertThat(consumerArgs.size()).isEqualTo(1);
		Object xPriority = consumerArgs.get("x-priority");
		assertThat(xPriority).isNotNull();
		assertThat(xPriority).isEqualTo(10);
		assertThat(TestUtils.getPropertyValue(container, "recoveryBackOff.interval", Long.class)).isEqualTo(Long.valueOf(5555));
		assertThat(TestUtils.getPropertyValue(container, "exclusive", Boolean.class)).isFalse();
		assertThat(TestUtils.getPropertyValue(container, "missingQueuesFatal", Boolean.class)).isFalse();
		assertThat(TestUtils.getPropertyValue(container, "possibleAuthenticationFailureFatal", Boolean.class)).isFalse();
		assertThat(TestUtils.getPropertyValue(container, "autoDeclare", Boolean.class)).isTrue();
		assertThat(TestUtils.getPropertyValue(container, "declarationRetries")).isEqualTo(5);
		assertThat(TestUtils.getPropertyValue(container, "failedDeclarationRetryInterval")).isEqualTo(1000L);
		assertThat(TestUtils.getPropertyValue(container, "retryDeclarationInterval")).isEqualTo(30000L);
		assertThat(TestUtils.getPropertyValue(container, "consumerTagStrategy")).isEqualTo(beanFactory.getBean("tagger"));
		@SuppressWarnings("unchecked")
		Collection<Object> group = beanFactory.getBean("containerGroup", Collection.class);
		assertThat(group.size()).isEqualTo(4);
		assertThat(group).containsExactly(beanFactory.getBean("container1"), beanFactory.getBean("testListener1"),
				beanFactory.getBean("testListener2"), beanFactory.getBean("direct1"));
		assertThat(ReflectionTestUtils.getField(container, "idleEventInterval")).isEqualTo(1235L);
		assertThat(container.getListenerId()).isEqualTo("container1");
		assertThat(TestUtils.getPropertyValue(container, "mismatchedQueuesFatal", Boolean.class)).isTrue();
	}

	@Test
	public void testParseWithDirect() {
		DirectMessageListenerContainer container = beanFactory.getBean("direct1", DirectMessageListenerContainer.class);
		assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
		assertThat(container.getConnectionFactory()).isEqualTo(beanFactory.getBean(ConnectionFactory.class));
		assertThat(container.getMessageListener().getClass()).isEqualTo(MessageListenerAdapter.class);
		DirectFieldAccessor listenerAccessor = new DirectFieldAccessor(container.getMessageListener());
		assertThat(listenerAccessor.getPropertyValue("delegate")).isEqualTo(beanFactory.getBean(TestBean.class));
		assertThat(listenerAccessor.getPropertyValue("defaultListenerMethod")).isEqualTo("handle");
		Queue queue = beanFactory.getBean("bar", Queue.class);
		assertThat(Arrays.asList(container.getQueueNames()).toString()).isEqualTo("[foo, " + queue.getName() + "]");
		assertThat(ReflectionTestUtils.getField(container, "consumersPerQueue")).isEqualTo(5);
		assertThat(ReflectionTestUtils.getField(container, "monitorInterval")).isEqualTo(5000L);
		assertThat(ReflectionTestUtils.getField(container, "taskScheduler")).isSameAs(this.beanFactory.getBean("sched"));
		assertThat(ReflectionTestUtils.getField(container, "taskExecutor")).isSameAs(this.beanFactory.getBean("exec"));
		Map<?, ?> consumerArgs = TestUtils.getPropertyValue(container, "consumerArgs", Map.class);
		assertThat(consumerArgs.size()).isEqualTo(1);
		Object xPriority = consumerArgs.get("x-priority");
		assertThat(xPriority).isNotNull();
		assertThat(xPriority).isEqualTo(10);
		assertThat(TestUtils.getPropertyValue(container, "recoveryBackOff.interval", Long.class)).isEqualTo(Long.valueOf(5555));
		assertThat(TestUtils.getPropertyValue(container, "exclusive", Boolean.class)).isFalse();
		assertThat(TestUtils.getPropertyValue(container, "missingQueuesFatal", Boolean.class)).isFalse();
		assertThat(TestUtils.getPropertyValue(container, "autoDeclare", Boolean.class)).isTrue();
		assertThat(TestUtils.getPropertyValue(container, "failedDeclarationRetryInterval")).isEqualTo(1000L);
		assertThat(TestUtils.getPropertyValue(container, "consumerTagStrategy")).isEqualTo(beanFactory.getBean("tagger"));
		@SuppressWarnings("unchecked")
		Collection<Object> group = beanFactory.getBean("containerGroup", Collection.class);
		assertThat(group.size()).isEqualTo(4);
		assertThat(group).containsExactly(beanFactory.getBean("container1"), beanFactory.getBean("testListener1"),
				beanFactory.getBean("testListener2"), beanFactory.getBean("direct1"));
		assertThat(ReflectionTestUtils.getField(container, "idleEventInterval")).isEqualTo(1235L);
		assertThat(container.getListenerId()).isEqualTo("direct1");
		assertThat(TestUtils.getPropertyValue(container, "mismatchedQueuesFatal", Boolean.class)).isTrue();
	}

	@Test
	public void testParseWithQueues() {
		SimpleMessageListenerContainer container = beanFactory.getBean("container2", SimpleMessageListenerContainer.class);
		Queue queue = beanFactory.getBean("bar", Queue.class);
		assertThat(Arrays.asList(container.getQueueNames()).toString()).isEqualTo("[foo, " + queue.getName() + "]");
		assertThat(TestUtils.getPropertyValue(container, "missingQueuesFatal", Boolean.class)).isTrue();
		assertThat(TestUtils.getPropertyValue(container, "autoDeclare", Boolean.class)).isFalse();
	}

	@Test
	public void testParseWithAdviceChain() {
		SimpleMessageListenerContainer container = beanFactory.getBean("container3", SimpleMessageListenerContainer.class);
		Object adviceChain = ReflectionTestUtils.getField(container, "adviceChain");
		assertThat(adviceChain).isNotNull();
		assertThat(((Advice[]) adviceChain).length).isEqualTo(3);
		assertThat(TestUtils.getPropertyValue(container, "exclusive", Boolean.class)).isTrue();
	}

	@Test
	public void testParseWithDefaults() {
		SimpleMessageListenerContainer container = beanFactory.getBean("container4", SimpleMessageListenerContainer.class);
		assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(1);
		assertThat(ReflectionTestUtils.getField(container, "defaultRequeueRejected")).isEqualTo(true);
	}

	@Test
	public void testParseWithDefaultQueueRejectedFalse() {
		SimpleMessageListenerContainer container = beanFactory.getBean("container5", SimpleMessageListenerContainer.class);
		assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(1);
		assertThat(ReflectionTestUtils.getField(container, "defaultRequeueRejected")).isEqualTo(false);
		assertThat(container.isChannelTransacted()).isFalse();
	}

	@Test
	public void testParseWithTx() {
		SimpleMessageListenerContainer container = beanFactory.getBean("container6", SimpleMessageListenerContainer.class);
		assertThat(container.isChannelTransacted()).isTrue();
		assertThat(ReflectionTestUtils.getField(container, "txSize")).isEqualTo(5);
	}

	@Test
	public void testNamedListeners() {
		beanFactory.getBean("testListener1", SimpleMessageListenerContainer.class);
		beanFactory.getBean("testListener2", SimpleMessageListenerContainer.class);
	}

	@Test
	public void testAnonListeners() {
		beanFactory.getBean("org.springframework.amqp.rabbit.config.ListenerContainerFactoryBean#0",
				SimpleMessageListenerContainer.class);
		beanFactory.getBean("org.springframework.amqp.rabbit.config.ListenerContainerFactoryBean#1",
				SimpleMessageListenerContainer.class);
		beanFactory.getBean("namedListener", SimpleMessageListenerContainer.class);
		beanFactory.getBean("org.springframework.amqp.rabbit.config.ListenerContainerFactoryBean#2",
				SimpleMessageListenerContainer.class);
	}

	@Test
	public void testAnonEverything() {
		SimpleMessageListenerContainer container = beanFactory.getBean(
				"org.springframework.amqp.rabbit.config.ListenerContainerFactoryBean#3",
				SimpleMessageListenerContainer.class);
		assertThat(ReflectionTestUtils.getField(ReflectionTestUtils.getField(container, "messageListener"),
				"responseExchange")).isEqualTo("ex1");
		container = beanFactory.getBean(
				"org.springframework.amqp.rabbit.config.ListenerContainerFactoryBean#4",
				SimpleMessageListenerContainer.class);
		assertThat(ReflectionTestUtils.getField(ReflectionTestUtils.getField(container, "messageListener"),
				"responseExchange")).isEqualTo("ex2");
	}

	@Test
	public void testAnonParent() {
		beanFactory.getBean("anonParentL1", SimpleMessageListenerContainer.class);
		beanFactory.getBean("anonParentL2", SimpleMessageListenerContainer.class);
	}

	@Test
	public void testIncompatibleTxAtts() {
		try {
			new ClassPathXmlApplicationContext(getClass().getSimpleName() + "-fail-context.xml", getClass()).close();
			fail("Parse exception expected");
		}
		catch (BeanDefinitionParsingException e) {
			assertThat(e.getMessage().startsWith(
					"Configuration problem: Listener Container - cannot set channel-transacted with acknowledge='NONE'")).isTrue();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testParseMessagePostProcessor() {
		SimpleMessageListenerContainer listenerContainer =
				this.beanFactory.getBean("testMessagePostProcessor", SimpleMessageListenerContainer.class);

		Collection<Object> messagePostProcessors =
				TestUtils.getPropertyValue(listenerContainer, "afterReceivePostProcessors", Collection.class);

		assertThat(messagePostProcessors.isEmpty()).isFalse();
		assertThat(messagePostProcessors).containsExactly(this.beanFactory.getBean("unzipPostProcessor"),
				this.beanFactory.getBean("gUnzipPostProcessor"));
	}

	static class TestBean {

		public void handle(String s) {
		}

	}

	static class TestAdvice implements MethodBeforeAdvice {

		@Override
		public void before(Method method, Object[] args, Object target) {
		}

	}

	public static class TestConsumerTagStrategy implements ConsumerTagStrategy {

		@Override
		public String createConsumerTag(String queue) {
			return "foo";
		}

	}

}
