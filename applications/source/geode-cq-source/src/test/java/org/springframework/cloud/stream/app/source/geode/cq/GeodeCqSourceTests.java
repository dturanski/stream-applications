/*
 * Copyright 2020-2020 the original author or authors.
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

package org.springframework.cloud.stream.app.source.geode.cq;

import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.geode.cache.Region;
import org.apache.geode.pdx.PdxInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.fn.common.geode.JsonPdxFunctions;
import org.springframework.cloud.fn.supplier.geode.cq.GeodeCqSupplierConfiguration;
import org.springframework.cloud.fn.test.support.geode.GeodeContainer;
import org.springframework.cloud.fn.test.support.geode.GeodeContainerIntializer;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GeodeCqSourceTests {

	private ApplicationContextRunner applicationContextRunner;

	private GeodeContainer geode;

	private ObjectMapper objectMapper = new ObjectMapper();

	@BeforeAll
	void setup() {
		GeodeContainerIntializer initializer = new GeodeContainerIntializer(
				geodeContainer -> {
					geodeContainer.connectAndExecGfsh("create region --name=Stocks --type=REPLICATE");
				});

		applicationContextRunner = new ApplicationContextRunner()
				.withUserConfiguration(
						TestChannelBinderConfiguration.getCompleteConfiguration(GeodeCqSourceTestApplication.class));

		geode = initializer.geodeContainer();
	}

	@Test
	void pdxReadSerialized() {
		applicationContextRunner
				.withPropertyValues(
						"spring.cloud.stream.function.definition=geodeCqSupplier",
						"geode.region.regionName=Stocks",
						"geode.client.pdx-read-serialized=true",
						"geode.cq.supplier.query= Select * from /Stocks where symbol='XXX' and price > 140",
						"geode.pool.hostAddresses=" + "localhost:" + geode.getLocatorPort())
				.run(context -> {
					OutputDestination outputDestination = context.getBean(OutputDestination.class);
					// Using local region here
					Region<String, PdxInstance> region = context.getBean(Region.class);
					putStockEvent(region, new Stock("XXX", 140.00));
					putStockEvent(region, new Stock("XXX", 140.20));
					putStockEvent(region, new Stock("YYY", 110.00));
					putStockEvent(region, new Stock("YYY", 110.01));
					putStockEvent(region, new Stock("XXX", 139.80));

					Message<byte[]> message = outputDestination.receive(Duration.ofSeconds(3).toMillis());
					assertThat(message).isNotNull();
					Stock result = objectMapper.readValue(message.getPayload(), Stock.class);
					assertThat(result).isEqualTo(new Stock("XXX", 140.20));
				});
	}

	private void putStockEvent(Region<String, PdxInstance> region, Stock stock) throws JsonProcessingException {
		String json = objectMapper.writeValueAsString(stock);
		region.put(stock.getSymbol(), JsonPdxFunctions.jsonToPdx().apply(json));
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class Stock {
		private String symbol;

		private double price;
	}

	@SpringBootApplication
	@Import(GeodeCqSupplierConfiguration.class)
	static class GeodeCqSourceTestApplication {

	}
}
