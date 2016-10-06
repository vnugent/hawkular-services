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
package org.hawkular.listener.bus;

import java.util.Collections;
import java.util.UUID;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.jms.MessageListener;
import javax.naming.InitialContext;

import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.bus.common.BasicMessage;
import org.hawkular.bus.common.consumer.BasicMessageListener;
import org.hawkular.cmdgw.api.EventDestination;
import org.hawkular.cmdgw.api.ResourcePathResponse;
import org.hawkular.inventory.paths.CanonicalPath;
import org.jboss.logging.Logger;

/**
 * Consume Command Gateway Events, convert to Hawkular Events and forward for persistence/evaluation.
 * <p>
 * This is useful only when deploying into the Hawkular Bus with Hawkular Command Gateway. The expected message
 * payload should be a command pojo.  We want to generate only one Hawkular Event per Command Gateway event, so this
 * is Queue based, limiting message consumption to one server.
 * </p>
 * @author Jay Shaughnessy
 */
@MessageDriven(messageListenerInterface = MessageListener.class, activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = "HawkularCommandEvent") })
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class CommandEventListener extends BasicMessageListener<BasicMessage> {
    private final Logger log = Logger.getLogger(CommandEventListener.class);

    private static final String ALERTS_SERVICE = "java:global/hawkular-metrics/hawkular-alerts/CassAlertsServiceImpl";

    private InitialContext ctx;
    private AlertsService alerts;

    @Override
    protected void onBasicMessage(BasicMessage msg) {

        String messageClass = msg.getClass().getSimpleName();
        log.infof("Received message [%s] with name [%s]", msg, messageClass); //TODO debug
        switch (messageClass) {
            case "AddDatasourceResponse":
                addEvent((ResourcePathResponse) msg, messageClass, "hawkular_datasource", "MiddlewareServer");
                break;
            case "DeployApplicationResponse":
                addEvent((ResourcePathResponse) msg, messageClass, "hawkular_deployment", "MiddlewareServer");
                break;
            case "RemoveDatasourceResponse":
                addEvent((ResourcePathResponse) msg, messageClass, "hawkular_datasource_remove", "MiddlewareServer");
                break;
            case "UndeployApplicationResponse": {
                addEvent((ResourcePathResponse) msg, messageClass, "hawkular_deployment_remove", "MiddlewareServer");
                break;
            }
            default: {
                // other EventDestination messages are expected but not currently interesting
                if (!(msg instanceof EventDestination)) {
                    log.warnf("Unexpected CommandEvent Message [%s]", msg.toJSON());
                }
            }
        }
    }

    private void addEvent(ResourcePathResponse response, String category, String miqEventType,
            String miqResourceType) {
        try {
            init();

            String canonicalPathString = response.getResourcePath();
            CanonicalPath canonicalPath = CanonicalPath.fromString(canonicalPathString);
            String tenantId = canonicalPath.ids().getTenantId();
            String eventId = UUID.randomUUID().toString();
            String status = response.getStatus().name().toLowerCase();
            Event event = new Event(tenantId, eventId, category, status);
            event.addContext("resource_path", canonicalPathString);
            event.addContext("message", response.getMessage());
            event.addTag("miq.event_type", miqEventType + ("error".equals(status) ? ".error" : ".ok"));
            event.addTag("miq.resource_type", miqResourceType);

            log.infof("Received message [%s] and forwarding it as [%s]", response, event); //TODO debug

            alerts.addEvents(Collections.singleton(event));

        } catch (Exception e) {
            log.errorf("Error processing event message [%s]: %s", response.toJSON(), e);
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
