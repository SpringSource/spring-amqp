/*
 * Copyright 2020 the original author or authors.
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

package org.springframework.amqp.rabbit.connection;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;

/**
 * A very simple connection factory that caches a channel per thread. Users are
 * responsible for releasing the thread's channel by calling
 * {@link #closeThreadChannel()}.
 *
 * @author Gary Russell
 *
 * @since 2.3
 *
 */
public class ThreadChannelConnectionFactory extends AbstractConnectionFactory {

	private volatile ConnectionWrapper connection;

	private boolean simplePublisherConfirms;

	/**
	 * Construct an instance.
	 * @param rabbitConnectionFactory the rabbitmq connection factory.
	 */
	public ThreadChannelConnectionFactory(ConnectionFactory rabbitConnectionFactory) {
		this(rabbitConnectionFactory, false);
	}

	/**
	 * Construct an instance.
	 * @param rabbitConnectionFactory the rabbitmq connection factory.
	 * @param isPublisher true if we are creating a publisher connection factory.
	 */
	private ThreadChannelConnectionFactory(ConnectionFactory rabbitConnectionFactory, boolean isPublisher) {
		super(rabbitConnectionFactory);
		if (!isPublisher) {
			setPublisherConnectionFactory(new ThreadChannelConnectionFactory(rabbitConnectionFactory, true));
		}
	}

	@Override
	public boolean isSimplePublisherConfirms() {
		return this.simplePublisherConfirms;
	}

	/**
	 * Enable simple publisher confirms.
	 * @param simplePublisherConfirms true to enable.
	 */
	public void setSimplePublisherConfirms(boolean simplePublisherConfirms) {
		this.simplePublisherConfirms = simplePublisherConfirms;
	}

	@Override
	public synchronized Connection createConnection() throws AmqpException {
		if (this.connection == null || !this.connection.isOpen()) {
			Connection bareConnection = createBareConnection(); // NOSONAR - see destroy()
			this.connection = new ConnectionWrapper(bareConnection.getDelegate(), getCloseTimeout());
		}
		return this.connection;
	}

	/**
	 * Close the channel associated with this thread, if any.
	 */
	public void closeThreadChannel() {
		ConnectionWrapper connection2 = this.connection;
		if (connection2 != null) {
			connection2.closeThreadChannel();
		}
	}

	@Override
	public synchronized void destroy() {
		super.destroy();
		if (this.connection != null) {
			this.connection.forceClose();
			this.connection = null;
		}
	}

	private final class ConnectionWrapper extends SimpleConnection {

		/*
		 * Intentionally not static.
		 */
		private final ThreadLocal<Channel> channels = new ThreadLocal<>();

		private final ThreadLocal<Channel> txChannels = new ThreadLocal<>();

		ConnectionWrapper(com.rabbitmq.client.Connection delegate, int closeTimeout) {
			super(delegate, closeTimeout);
		}

		@SuppressWarnings("resource")
		@Override
		public Channel createChannel(boolean transactional) {
			Channel channel = transactional ? this.txChannels.get() : this.channels.get();
			if (channel == null || !channel.isOpen()) {
				channel = createProxy(super.createChannel(transactional), transactional);
				if (transactional) {
					try {
						channel.txSelect();
					}
					catch (IOException e) {
						throw RabbitExceptionTranslator.convertRabbitAccessException(e);
					}
					this.txChannels.set(channel);
				}
				else {
					if (ThreadChannelConnectionFactory.this.simplePublisherConfirms) {
						try {
							channel.confirmSelect();
						}
						catch (IOException e) {
							throw RabbitExceptionTranslator.convertRabbitAccessException(e);
						}
					}
					this.channels.set(channel);
				}
			}
			return channel;
		}

		private Channel createProxy(Channel channel, boolean transactional) {
			ProxyFactory pf = new ProxyFactory(channel);
			AtomicBoolean confirmSelected = new AtomicBoolean();
			Advice advice =
					(MethodInterceptor) invocation -> {
						String method = invocation.getMethod().getName();
						switch (method) {
							case "close":
								return handleClose(channel, transactional, invocation);
							case "getTargetChannel":
								return channel;
							case "isTransactional":
								return transactional;
							case "confirmSelect":
								confirmSelected.set(true);
								return channel.confirmSelect();
							case "isConfirmSelected":
								return confirmSelected.get();
						}
						return null;
					};
			NameMatchMethodPointcutAdvisor advisor = new NameMatchMethodPointcutAdvisor(advice);
			advisor.addMethodName("close");
			advisor.addMethodName("getTargetChannel");
			advisor.addMethodName("isTransactional");
			advisor.addMethodName("confirmSelect");
			advisor.addMethodName("isConfirmSelected");
			pf.addAdvisor(advisor);
			pf.addInterface(ChannelProxy.class);
			return (Channel) pf.getProxy();
		}

		private Object handleClose(Channel channel, boolean transactional, MethodInvocation invocation)
				throws Throwable {

			if (ConnectionWrapper.this.channels.get() == null) {
				return invocation.proceed();
			}
			else {
				if (RabbitUtils.isPhysicalCloseRequired()) {
					physicalClose(channel);
					if (transactional) {
						this.txChannels.remove();
					}
					else {
						this.channels.remove();
					}
					RabbitUtils.clearPhysicalCloseRequired();
				}
				return null;
			}
		}

		@Override
		public void close() {
		}

		@Override
		public void closeThreadChannel() {
			doClose(this.channels);
			doClose(this.txChannels);
		}

		private void doClose(ThreadLocal<Channel> channelsTL) {
			Channel channel = channelsTL.get();
			if (channel != null) {
				channelsTL.remove();
				physicalClose(channel);
			}
		}

		private void physicalClose(Channel channel) {
			if (channel.isOpen()) {
				try {
					channel.close();
				}
				catch (IOException | TimeoutException e) {
					logger.debug("Error on close", e);
				}
			}
		}

		void forceClose() {
			super.close();
		}

	}

}
