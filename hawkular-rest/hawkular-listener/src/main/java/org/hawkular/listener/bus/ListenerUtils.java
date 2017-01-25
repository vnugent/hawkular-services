/*
 * Copyright 2016-2017 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.listener.bus;

import java.util.Collections;
import java.util.UUID;

import javax.naming.InitialContext;

import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.inventory.paths.CanonicalPath;
import org.jboss.logging.Logger;

public class ListenerUtils {
    private static final String ALERTS_SERVICE = "java:global/hawkular-metrics/hawkular-alerts/CassAlertsServiceImpl";

    private final Logger log = Logger.getLogger(ListenerUtils.class);

    public InitialContext ctx;
    public AlertsService alerts;

    public ListenerUtils() {
    }

    /**
     * @param resourcePathStr resource canonical path string
     * @param category the event category
     * @param text the event text
     * @param miqEventType the MIQ event type
     * @param miqResourceType the MIQ event resource type
     * @param miqMessage optional message for the MIQ event
     */
    public void addEvent(String resourcePathStr, String category, String text, String miqEventType,
            String miqResourceType, String miqMessage) {
        addEvent(CanonicalPath.fromString(resourcePathStr), category, text, miqEventType, miqResourceType,
                miqMessage);
    }

    /**
     * @param resourcePathStr resource canonical path
     * @param category the event category
     * @param text the event text
     * @param miqEventType the MIQ event type
     * @param miqResourceType the MIQ event resource type
     * @param miqMessage optional message for the MIQ event
     */
    public void addEvent(CanonicalPath resourcePath, String category, String text, String miqEventType,
            String miqResourceType, String miqMessage) {
        try {
            init();

            String tenantId = resourcePath.ids().getTenantId();
            String eventId = UUID.randomUUID().toString();
            Event event = new Event(tenantId, eventId, category, text);
            event.addContext("resource_path", resourcePath.toString());
            event.addContext("message", miqMessage);
            event.addTag("miq.event_type", miqEventType);
            event.addTag("miq.resource_type", miqResourceType);

            log.debugf("Received message [%s] and forwarding it as [%s]", miqMessage, event);

            alerts.addEvents(Collections.singleton(event));

        } catch (Exception e) {
            log.errorf("Error processing event for message [%s]: %s", miqMessage, e);
        }
    }

    private synchronized void init() throws Exception {
        if (ctx == null) {
            ctx = new InitialContext();
        }
        if (alerts == null) {
            alerts = (AlertsService) ctx.lookup(ALERTS_SERVICE);
        }
    }

}