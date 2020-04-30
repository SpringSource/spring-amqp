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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.util.Assert;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;

/**
 * A very simple connection factory that caches channels using Apache Pool2
 * {@link GenericObjectPool}s (one for transactional and one for non-transactional
 * channels). The pools have default configuration but you can further configure
 * them using callbacks.
 *
 * @author Gary Russell
 * @since 2.3
 *
 */
public class PooledChannelConnectionFactory extends AbstractConnectionFactory {

	private volatile ConnectionWrapper connection;

	private boolean simplePublisherConfirms;

	private BiConsumer<GenericObjectPool<Channel>, Boolean> poolConfigurer = (pool, tx) -> { };

	/**
	 * Construct an instance.
	 *
	 * @param rabbitConnectionFactory the rabbitmq connection factory.
	 */
	public PooledChannelConnectionFactory(ConnectionFactory rabbitConnectionFactory) {
		this(rabbitConnectionFactory, false);
	}

	/**
	 * Construct an instance.
	 *
	 * @param rabbitConnectionFactory the rabbitmq connection factory.
	 * @param isPublisher true if we are creating a publisher connection factory.
	 */
	private PooledChannelConnectionFactory(ConnectionFactory rabbitConnectionFactory, boolean isPublisher) {
		super(rabbitConnectionFactory);
		if (!isPublisher) {
			setPublisherConnectionFactory(new PooledChannelConnectionFactory(rabbitConnectionFactory, true));
		}
	}

	/**
	 * Add a consumer to configure the object pool. The second argument is true when
	 * called with the transactional pool.
	 * @param poolConfigurer the configurer.
	 */
	public void setPoolConfigurer(BiConsumer<GenericObjectPool<Channel>, Boolean> poolConfigurer) {
		Assert.notNull(poolConfigurer, "'poolConfigurer' cannot be null");
		this.poolConfigurer = poolConfigurer;
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
			Connection bareConnection = createBareConnection();
			this.connection = new ConnectionWrapper(bareConnection.getDelegate(), getCloseTimeout(),
					this.simplePublisherConfirms, this.logger, this.poolConfigurer);
		}
		return this.connection;
	}

	@Override
	public synchronized void destroy() {
		super.destroy();
		if (this.connection != null) {
			this.connection.forceClose();
			this.connection = null;
		}
	}

	private final static class ConnectionWrapper extends SimpleConnection {

		private final Log logger;

		private final ObjectPool<Channel> channels;

		private final ObjectPool<Channel> txChannels;

		private final boolean simplePublisherConfirms;

		ConnectionWrapper(com.rabbitmq.client.Connection delegate, int closeTimeout, boolean simplePublisherConfirms,
				Log logger, BiConsumer<GenericObjectPool<Channel>, Boolean> configurer) {

			super(delegate, closeTimeout);
			GenericObjectPool<Channel> pool = new GenericObjectPool<>(new ChannelFactory());
			configurer.accept(pool, false);
			this.channels = pool;
			pool = new GenericObjectPool<>(new TxChannelFactory());
			configurer.accept(pool, true);
			this.txChannels = pool;
			this.simplePublisherConfirms = simplePublisherConfirms;
			this.logger = logger;
		}

		@Override
		public Channel createChannel(boolean transactional) {
			try {
				return transactional ? this.txChannels.borrowObject() : this.channels.borrowObject();
			}
			catch (Exception e) {
				throw RabbitExceptionTranslator.convertRabbitAccessException(e);
			}
		}

		private Channel createProxy(Channel channel, boolean transacted) {
			ProxyFactory pf = new ProxyFactory(channel);
			AtomicReference<Channel> proxy = new AtomicReference<>();
			Advice advice = new MethodInterceptor() {

				@Override
				public Object invoke(MethodInvocation invocation) throws Throwable {
					if (transacted) {
						ConnectionWrapper.this.txChannels.returnObject(proxy.get());
					}
					else {
						ConnectionWrapper.this.channels.returnObject(proxy.get());
					}
					return null;
				}

			};
			NameMatchMethodPointcutAdvisor advisor = new NameMatchMethodPointcutAdvisor(advice);
			advisor.addMethodName("close");
			pf.addAdvisor(advisor);
			proxy.set((Channel) pf.getProxy());
			return proxy.get();
		}

		@Override
		public void close() {
		}

		void forceClose() {
			super.close();
			this.channels.close();
			this.txChannels.close();
		}

		private class ChannelFactory implements PooledObjectFactory<Channel> {

			@Override
			public PooledObject<Channel> makeObject() throws Exception {
				Channel channel = ConnectionWrapper.super.createChannel(false);
				if (ConnectionWrapper.this.simplePublisherConfirms) {
					try {
						channel.confirmSelect();
					}
					catch (IOException e) {
						throw RabbitExceptionTranslator.convertRabbitAccessException(e);
					}
				}
				return new DefaultPooledObject<>(createProxy(channel, false));
			}

			@Override
			public void destroyObject(PooledObject<Channel> p) throws Exception {
				p.getObject().close();
			}

			@Override
			public boolean validateObject(PooledObject<Channel> p) {
				return p.getObject().isOpen();
			}

			@Override
			public void activateObject(PooledObject<Channel> p) {
			}

			@Override
			public void passivateObject(PooledObject<Channel> p) {
			}

		}

		private final class TxChannelFactory extends ChannelFactory {

			@Override
			public PooledObject<Channel> makeObject() throws Exception {
				Channel channel = ConnectionWrapper.super.createChannel(true);
				try {
					channel.txSelect();
				}
				catch (IOException e) {
					throw RabbitExceptionTranslator.convertRabbitAccessException(e);
				}
				return new DefaultPooledObject<>(createProxy(channel, true));
			}

		}

	}

}
