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

import java.util.Arrays;
import java.util.Collection;

import org.hawkular.alerts.api.model.condition.Condition;
import org.hawkular.alerts.api.model.condition.ThresholdCondition;
import org.hawkular.alerts.api.model.event.EventType;
import org.hawkular.alerts.api.model.trigger.Mode;
import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.services.rest.test.TestClient.Retry;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.logging.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Metrics & Alerting publishing flow integration tests.
 *
 * @author <a href="https://github.com/lucasponce">Lucas Ponce</a>
 */
public class MetricsAlertingITest extends AbstractTestBase {
    private static final Logger log = Logger.getLogger(MetricsAlertingITest.class);
    public static final String GROUP = "MetricsAlertingITest";
    public static final String metricsPath = "/hawkular/metrics";
    private static final String alertingPath = "/hawkular/alerts";

    @Test(groups = { GROUP })
    @RunAsClient
    public void metricsUp() throws Throwable {

        final String path = metricsPath + "/status";
        final String expectedState = "STARTED";
        testClient.newRequest()
                .path(path)
                .get()
                .assertWithRetries(testResponse -> {
                    testResponse
                            .assertCode(200)
                            .assertJson(metricsStatus -> {
                                log.tracef("Got Metrics status [%s]", metricsStatus);
                                String foundState = metricsStatus.get("MetricsService").asText();
                                Assert.assertEquals(foundState, expectedState);
                            });
                }, Retry.times(50).delay(500));
    }

    @Test(groups = { GROUP })
    @RunAsClient
    public void alertingUp() throws Throwable {

        final String path = alertingPath + "/status";
        final String expectedState = "STARTED";
        testClient.newRequest()
                .path(path)
                .get()
                .assertWithRetries(testResponse -> {
                    testResponse
                            .assertCode(200)
                            .assertJson(alertingStatus -> {
                                    log.tracef("Got Alerting status [%s]", alertingStatus);
                                    String foundState = alertingStatus.get("status").asText();
                                    Assert.assertEquals(foundState, expectedState);
                            });
                }, Retry.times(20).delay(250));
    }

    @Test(dependsOnMethods = { "metricsUp", "alertingUp" }, groups = { GROUP })
    @RunAsClient
    public void createTriggerSendMetricsReceiveEvents() throws Throwable {
        final String GAUGE = "hm_g_";
        final String metricId = "heapused";
        final String triggersPath = alertingPath + "/triggers";
        final String triggerId = "trigger-heapused";
        final String triggerPath = triggersPath + "/" + triggerId;
        final String firingConditionsPath = triggerPath + "/conditions/firing";
        final String eventsPath = alertingPath + "/events?triggerIds=" + triggerId;
        final String deleteEventsPath = alertingPath + "/events/delete?triggerIds=" + triggerId;
        final String eventsTriggerPath = eventsPath;
        final String gaugePath = metricsPath + "/gauges/" + metricId + "/data";

        /* ensure our test trigger is not created */
        testClient.newRequest().path(triggerPath).get().assertCode(404);

        /* create our test trigger */
        Trigger testTrigger = new Trigger(triggerId, "Heap Used");
        testTrigger.setEventType(EventType.EVENT);
        testTrigger.setEnabled(true);
        testClient.newRequest().path(triggersPath).postObject(testTrigger).assertCode(200);

        /*
            create conditions
            dataId = GAUGE + metricId
            GAUGE prexix is needed for publishing filtering
         */
        ThresholdCondition gtThreshold = new ThresholdCondition(triggerId, Mode.FIRING,
                GAUGE + metricId, ThresholdCondition.Operator.GT, 100.0);
        Collection<Condition> conditions = Arrays.asList(gtThreshold);
        testClient.newRequest().path(firingConditionsPath).putObject(conditions).assertCode(200);

        /* Check there are not events */
        testClient.newRequest()
                .path(eventsTriggerPath)
                .get()
                .assertCode(200)
                .assertJson(events -> {
                    /* Expect empty events for trigger created */
                    Assert.assertEquals(events.size(), 0);
                });

        /* ensure no data points there already */
        testClient.newRequest().path(gaugePath).get().assertCode(204);

        final String json = "[{timestamp: " + System.currentTimeMillis() + ", value: 101.0}]";
        testClient.newRequest().path(gaugePath).postJson(json).assertCode(200);

        /* Check if we have generated an event */
        testClient.newRequest()
                .path(gaugePath)
                .get()
                .assertWithRetries(testResponse -> {
                    testResponse
                            .assertCode(200)
                            .assertJson(events -> {
                                /* Expect one event */
                                Assert.assertEquals(events.size(), 1);
                            });
                }, Retry.times(20).delay(250));

        /* cleanup */
        testClient.newRequest().path(triggerPath).delete().assertCode(200);
        testClient.newRequest().path(deleteEventsPath).putObject(conditions).assertCode(200);
    }

}
