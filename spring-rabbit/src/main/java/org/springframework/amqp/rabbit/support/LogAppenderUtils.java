/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.amqp.rabbit.support;

import java.util.Map;

import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;

/**
 * Utility methods for log appenders.
 *
 * @author Gary Russell
 * @since 1.5.6
 *
 */
public final class LogAppenderUtils {

	private LogAppenderUtils() {
		// empty
	}

	/**
	 * Parse the properties {@code key:value[,key:value]...} and add them to the
	 * connection factory client properties.
	 * @param connectionFactory the connection factory.
	 * @param clientConnectionProperties the properties.
	 */
	public static void updateClientConnectionProperties(AbstractConnectionFactory connectionFactory,
			String clientConnectionProperties) {
		if (clientConnectionProperties != null) {
			String[] props = clientConnectionProperties.split(",");
			if (props.length > 0) {
				Map<String, Object> clientProps = connectionFactory.getRabbitConnectionFactory()
						.getClientProperties();
				for (String prop : props) {
					String[] aProp = prop.split(":");
					if (aProp.length == 2) {
						clientProps.put(aProp[0].trim(), aProp[1].trim());
					}
				}
			}
		}
	}

}
