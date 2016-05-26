/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.services.rest.test;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.logging.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Objects;

/**
 * Metrics integration tests.
 *
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
public class MetricsITest extends AbstractTestBase {
    private static final Logger log = Logger.getLogger(MetricsITest.class);
    private static final String metricsPath = "/hawkular/metrics";
    private static final String gaugeId = MetricsITest.class.getSimpleName() + ".metric";
    private static final long gaugeTime = System.currentTimeMillis();
    private static final int gaugeValue = 42;

    @Test
    @RunAsClient
    public void metricsUp() throws Throwable {

        final int attemptsCount = 50;
        final int retryAfterMs = 500;
        final String path = metricsPath + "/status";
        final String expectedState = "STARTED";
        String foundState = null;
        for (int i = 0; i < attemptsCount; i++) {
            JsonNode metricsStatus = testClient.newRequest()
                    .path(path).get(200, attemptsCount, retryAfterMs)
                    .asJson();
            log.tracef("Got metrics status [%s] on attempt[%d]", metricsStatus, i);
            foundState = metricsStatus.get("MetricsService").asText();
            if (Objects.equal(expectedState, foundState)) {
                /* expected */
                return;
            }
            /* otherwise retry */
            log.tracef("Retrying to get [%s] in [%d] ms", path, retryAfterMs);
            Thread.sleep(retryAfterMs);
        }
        Assert.fail(
                "Metrics service still not in state [" + expectedState + "] but rather in state [" + foundState + "]");
    }

    @Test(dependsOnMethods = { "metricsUp" })
    @RunAsClient
    public void postGet() throws Exception {

        final String path = metricsPath + "/gauges/" + gaugeId + "/data";

        /* ensure no data points there already */
        testClient.newRequest().path(path).get().assertCode(204);

        final String json = "[{timestamp: " + gaugeTime + ", value: " + gaugeValue + "}]";
        testClient.newRequest().path(path).postJson(json).assertCode(200);

        testClient.newRequest()
                .path(path).get()
                .assertCode(200).assertJson(dataPoints -> {
                    Assert.assertTrue(dataPoints.isArray(), "GET " + path + " should return an array");
                    Assert.assertEquals(dataPoints.size(), 1, "GET " + path + " returned an array of unexpected size");
                    JsonNode dp0 = dataPoints.get(0);
                    Assert.assertEquals(dp0.size(), 2, "first data point has an unexpected number of fields");
                    Assert.assertEquals(dp0.get("timestamp").asLong(), gaugeTime,
                            "first data point has an unexpected timestamp");
                    Assert.assertEquals(dp0.get("value").asInt(), gaugeValue,
                            "first data point has an unexpected value");
                });
    }

}
