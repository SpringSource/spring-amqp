/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.amqp.rabbit.test;

import org.springframework.amqp.rabbit.annotation.RabbitListenerAnnotationBeanPostProcessor;
import org.springframework.amqp.rabbit.config.RabbitListenerConfigUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Overrides the default BPP with a {@link RabbitListenerAnnotationSpyBeanPostProcessor}.
 *
 * @author Gary Russell
 * @since 1.6
 *
 */
@Configuration
public class RabbitListenerSpyTestBootstrap {

	@Bean(name = RabbitListenerConfigUtils.RABBIT_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME)
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public static RabbitListenerAnnotationBeanPostProcessor rabbitListenerAnnotationProcessor() {
		return new RabbitListenerAnnotationSpyBeanPostProcessor();
	}

}
