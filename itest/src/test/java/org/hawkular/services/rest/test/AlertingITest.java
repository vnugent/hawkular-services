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

import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.services.rest.test.TestClient.Retry;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.logging.Logger;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Alerting integration tests.
 *
 * @author <a href="https://github.com/lucasponce">Lucas Ponce</a>
 */
public class AlertingITest extends AbstractTestBase {
    private static final Logger log = Logger.getLogger(AlertingITest.class);
    public static final String GROUP = "AlertingITest";
    private static final String alertingPath = "/hawkular/alerts";

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

    @Test(dependsOnMethods = { "alertingUp" }, groups = { GROUP })
    @RunAsClient
    public void postGetDelete() throws Throwable {
        final String triggersPath = alertingPath + "/triggers";
        final String testTriggerId = "demo-itest-trigger";
        final String triggerPath = triggersPath + "/" + testTriggerId;

        /* ensure our test trigger is not created */
        testClient.newRequest().path(triggerPath).get().assertCode(404);

        /* create our test trigger */
        Trigger testTrigger = new Trigger(testTriggerId, "No-Metric");
        testClient.newRequest().path(triggersPath).postObject(testTrigger).assertCode(200);

        /* check that the trigger is created */
        testClient.newRequest()
                .path(triggerPath).get()
                .assertCode(200)
                .assertJson(json -> Assert.assertEquals(json.get("id").asText(), testTriggerId,
                        String.format("GET [%s] returned an unexpected object", triggerPath)));

        /* cleanup */
        testClient.newRequest().path(triggerPath).delete().assertCode(200);
    }
}
