/*
 * Copyright 2002-2010 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.amqp.rabbit.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.apache.log4j.Level;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.amqp.core.Queue; 
import org.springframework.amqp.rabbit.connection.SingleConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.test.BrokerPanic;
import org.springframework.amqp.rabbit.test.Log4jLevelAdjuster;

/**
 * @author Mark Pollack
 * @author Dave Syer
 * @author Helena Edelson
 */
public class RabbitBrokerAdminIntegrationTests {

	private static final int PORT = 15672;

	private static final String NODE_NAME = "spring@localhost";

	@Rule
	public Log4jLevelAdjuster logLevel = new Log4jLevelAdjuster(Level.INFO, RabbitBrokerAdmin.class);

	/*
	 * Ensure broker dies if a test fails (otherwise the erl process might have to be killed manually)
	 */
	@Rule
	public static BrokerPanic panic = new BrokerPanic();

	private static RabbitBrokerAdmin brokerAdmin;

	@BeforeClass
	public static void start() throws Exception {
		// Set up broker admin for non-root user
		brokerAdmin = new RabbitBrokerAdmin(NODE_NAME, PORT);
        brokerAdmin.setRabbitLogBaseDirectory("target/rabbitmq/log");
		brokerAdmin.setRabbitMnesiaBaseDirectory("target/rabbitmq/mnesia");
		brokerAdmin.setStartupTimeout(10000L);
		brokerAdmin.startNode();
		panic.setBrokerAdmin(brokerAdmin);
	}

	@AfterClass
	public static void stop() throws Exception {
		brokerAdmin.stopNode();
	}

    @Test
    public void testConversionStrategy() throws Exception {
        RabbitStatus status = brokerAdmin.getStatus();
        assertNotNull(status);

        List<String> users = brokerAdmin.listUsers();
        assertTrue((users.size() > 0) && (users.contains("guest")));

        SingleConnectionFactory cf = new SingleConnectionFactory();
        int queueCount = brokerAdmin.getQueues(cf.getVirtualHost()).size();
        new RabbitAdmin(cf).declareQueue();
        assertTrue(queueCount < brokerAdmin.getQueues().size());
    }

	@Test
	public void integrationTestsUserCrud() throws Exception {
		List<String> users = brokerAdmin.listUsers();
		if (users.contains("joe")) {
			brokerAdmin.deleteUser("joe");
		}
		Thread.sleep(200L);
		brokerAdmin.addUser("joe", "trader");
		Thread.sleep(200L);
		brokerAdmin.changeUserPassword("joe", "sales");
		Thread.sleep(200L);
		users = brokerAdmin.listUsers();
		if (users.contains("joe")) {
			Thread.sleep(200L);
			brokerAdmin.deleteUser("joe");
		}
	}

	@Test
	public void testGetQueues() throws Exception {
        SingleConnectionFactory connectionFactory = new SingleConnectionFactory();
        /* this was breaking ? */
		//connectionFactory.setPort(PORT);
        int queueCount = brokerAdmin.getQueues(connectionFactory.getVirtualHost()).size();
        Queue queue = new RabbitAdmin(connectionFactory).declareQueue();
		assertEquals("/", connectionFactory.getVirtualHost());
		List<QueueInfo> queues = brokerAdmin.getQueues();
		assertEquals(queue.getName(), queues.get(0).getName());
        assertEquals(brokerAdmin.getQueues().size(), queueCount + 1);      
	}

}
