package org.springframework.amqp.rabbit.listener;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.rabbit.test.BrokerRunning;
import org.springframework.amqp.rabbit.test.Log4jLevelAdjuster;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@RunWith(Parameterized.class)
public class SimpleMessageListenerContainerIntegrationTests {

	private static Log logger = LogFactory.getLog(SimpleMessageListenerContainerIntegrationTests.class);

	private Queue queue = new Queue("test.queue");

	private RabbitTemplate template = new RabbitTemplate();

	private final int concurrentConsumers;

	private final AcknowledgeMode acknowledgeMode;

	@Rule
	public Log4jLevelAdjuster logLevels = new Log4jLevelAdjuster(Level.ERROR, RabbitTemplate.class,
			SimpleMessageListenerContainer.class, BlockingQueueConsumer.class);

	@Rule
	public BrokerRunning brokerIsRunning = BrokerRunning.isRunningWithEmptyQueue(queue);

	private final int messageCount;

	private SimpleMessageListenerContainer container;

	private final int txSize;

	private final boolean externalTransaction;

	public SimpleMessageListenerContainerIntegrationTests(int messageCount, int concurrency,
			AcknowledgeMode acknowledgeMode, int txSize, boolean externalTransaction) {
		this.messageCount = messageCount;
		this.concurrentConsumers = concurrency;
		this.acknowledgeMode = acknowledgeMode;
		this.txSize = txSize;
		this.externalTransaction = externalTransaction;
	}

	@Parameters
	public static List<Object[]> getParameters() {
		return Arrays.asList( //
				params(0, 1, 1, AcknowledgeMode.AUTO), //
				params(1, 1, 1, AcknowledgeMode.NONE), //
				params(2, 4, 1, AcknowledgeMode.AUTO), //
				extern(3, 4, 1, AcknowledgeMode.AUTO), //
				params(4, 2, 2, AcknowledgeMode.AUTO), //
				params(5, 2, 2, AcknowledgeMode.NONE), //
				params(6, 20, 4, AcknowledgeMode.AUTO), //
				params(7, 20, 4, AcknowledgeMode.NONE), //
				params(8, 1000, 4, AcknowledgeMode.AUTO), //
				params(9, 1000, 4, AcknowledgeMode.NONE), //
				params(10, 1000, 4, AcknowledgeMode.AUTO, 10) //
				);
	}

	private static Object[] params(int i, int messageCount, int concurrency, AcknowledgeMode acknowledgeMode, int txSize) {
		// "i" is just a counter to make it easier to identify the test in the log
		return new Object[] { messageCount, concurrency, acknowledgeMode, txSize, false };
	}

	private static Object[] params(int i, int messageCount, int concurrency, AcknowledgeMode acknowledgeMode) {
		return params(i, messageCount, concurrency, acknowledgeMode, 1);
	}

	private static Object[] extern(int i, int messageCount, int concurrency, AcknowledgeMode acknowledgeMode) {
		return new Object[] { messageCount, concurrency, acknowledgeMode, 1, true };
	}

	@Before
	public void declareQueue() {
		CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
		connectionFactory.setChannelCacheSize(concurrentConsumers);
		// connectionFactory.setPort(5673);
		template.setConnectionFactory(connectionFactory);
	}

	@After
	public void clear() throws Exception {
		// Wait for broker communication to finish before trying to stop container
		Thread.sleep(300L);
		logger.debug("Shutting down at end of test");
		if (container != null) {
			container.shutdown();
		}
	}

	@Test
	public void testListenerSunnyDay() throws Exception {
		CountDownLatch latch = new CountDownLatch(messageCount);
		container = createContainer(new PojoListener(latch));
		for (int i = 0; i < messageCount; i++) {
			template.convertAndSend(queue.getName(), i + "foo");
		}
		boolean waited = latch.await(Math.max(1, messageCount / 50), TimeUnit.SECONDS);
		assertTrue("Timed out waiting for message", waited);
		assertNull(template.receiveAndConvert(queue.getName()));
	}

	@Test
	public void testListenerWithException() throws Exception {
		CountDownLatch latch = new CountDownLatch(messageCount);
		container = createContainer(new PojoListener(latch, true));
		if (acknowledgeMode.isTransactionAllowed()) {
			// Should only need one message if it is going to fail
			for (int i = 0; i < concurrentConsumers; i++) {
				template.convertAndSend(queue.getName(), i + "foo");
			}
		} else {
			for (int i = 0; i < messageCount; i++) {
				template.convertAndSend(queue.getName(), i + "foo");
			}
		}
		try {
			boolean waited = latch.await(5 + Math.max(1, messageCount / 20), TimeUnit.SECONDS);
			assertTrue("Timed out waiting for message", waited);
		} finally {
			// Wait for broker communication to finish before trying to stop
			// container
			Thread.sleep(300L);
			container.shutdown();
			Thread.sleep(300L);
		}
		if (acknowledgeMode.isTransactionAllowed()) {
			assertNotNull(template.receiveAndConvert(queue.getName()));
		} else {
			assertNull(template.receiveAndConvert(queue.getName()));
		}
	}

	private SimpleMessageListenerContainer createContainer(Object listener) {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(template.getConnectionFactory());
		container.setMessageListener(new MessageListenerAdapter(listener));
		container.setQueueName(queue.getName());
		container.setTxSize(txSize);
		container.setPrefetchCount(txSize);
		container.setConcurrentConsumers(concurrentConsumers);
		// For this test always us a transaction if it makes sense...
		container.setChannelTransacted(acknowledgeMode.isTransactionAllowed());
		container.setAcknowledgeMode(acknowledgeMode);
		if (externalTransaction) {
			container.setTransactionManager(new TestTransactionManager());
		}
		container.afterPropertiesSet();
		container.start();
		return container;
	}

	public static class PojoListener {
		private AtomicInteger count = new AtomicInteger();

		private final CountDownLatch latch;

		private final boolean fail;

		public PojoListener(CountDownLatch latch) {
			this(latch, false);
		}

		public PojoListener(CountDownLatch latch, boolean fail) {
			this.latch = latch;
			this.fail = fail;
		}

		public void handleMessage(String value) {
			try {
				int counter = count.getAndIncrement();
				if (logger.isDebugEnabled() && counter % 500 == 0) {
					logger.debug(value + counter);
				}
				if (fail) {
					throw new RuntimeException("Planned failure");
				}
			} finally {
				latch.countDown();
			}
		}
	}

	@SuppressWarnings("serial")
	private class TestTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
		}

		@Override
		protected Object doGetTransaction() throws TransactionException {
			return new Object();
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
		}

	}

}
