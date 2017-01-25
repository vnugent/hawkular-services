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

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.jms.MessageListener;

import org.hawkular.bus.common.BasicMessage;
import org.hawkular.bus.common.consumer.BasicMessageListener;
import org.hawkular.cmdgw.api.EventDestination;
import org.hawkular.cmdgw.api.ResourcePathResponse;
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
    private final ListenerUtils utils = new ListenerUtils();

    @Override
    protected void onBasicMessage(BasicMessage msg) {

        String messageClass = msg.getClass().getSimpleName();
        log.debugf("Received message [%s] with name [%s]", msg, messageClass); //TODO debug

        String miqEventType = null;
        String miqResourceType = null;
        switch (messageClass) {
            case "AddDatasourceResponse":
                miqEventType = "hawkular_datasource";
                miqResourceType = "MiddlewareServer";
                break;
            case "DeployApplicationResponse":
                miqEventType = "hawkular_deployment";
                miqResourceType = "MiddlewareServer";
                break;
            case "RemoveDatasourceResponse":
                miqEventType = "hawkular_datasource_remove";
                miqResourceType = "MiddlewareServer";
                break;
            case "UndeployApplicationResponse": {
                miqEventType = "hawkular_deployment_remove";
                miqResourceType = "MiddlewareServer";
                break;
            }
            default: {
                // other EventDestination messages are expected but not currently interesting
                if (!(msg instanceof EventDestination)) {
                    log.warnf("Unexpected CommandEvent Message [%s]", msg.toJSON());
                }
            }
        }
        if (null != miqEventType) {
            ResourcePathResponse response = (ResourcePathResponse) msg;
            String text = response.getStatus().name().toLowerCase();
            boolean isError = "error".equals(text);
            utils.addEvent(response.getResourcePath(), messageClass, text,
                    (miqEventType + (isError ? ".error" : ".ok")), miqResourceType, response.getMessage());
        }

    }
}
