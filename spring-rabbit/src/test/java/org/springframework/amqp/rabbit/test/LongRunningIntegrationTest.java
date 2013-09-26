/*
 * Copyright 2013 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Assume;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * Rule to prevent long running tests from running on every build; set environment
 * variable RUN_LONG_INTEGRATION_TESTS on a CI nightly build to ensure coverage.
 *
 * @author Gary Russell
 * @since 3.0
 *
 */
public class LongRunningIntegrationTest extends TestWatchman {

	private final static Log logger = LogFactory.getLog(LongRunningIntegrationTest.class);

	@Override
	public Statement apply(Statement base, FrameworkMethod method, Object target) {
		boolean shouldRun = "true".equalsIgnoreCase(System.getenv("RUN_LONG_INTEGRATION_TESTS"));
		if (!shouldRun) {
			logger.info("Skipping long running test " + this.getClass().getSimpleName() + "." + method.getName());
		}
		Assume.assumeTrue(shouldRun);
		return super.apply(base, method, target);
	}

}
