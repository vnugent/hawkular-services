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

import javax.ejb.ActivationConfigProperty;
import javax.ejb.EJB;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.jms.MessageListener;

import org.hawkular.bus.common.BasicMessage;
import org.hawkular.bus.common.consumer.BasicMessageListener;
import org.hawkular.cmdgw.api.FeedWebSocketClosedEvent;
import org.hawkular.listener.cache.BackfillCache;
import org.jboss.logging.Logger;

/**
 * A listener on HawkularTopic for various Hawkular Events. This is a catch-all listener for consuming relatively
 * infrequent events (high-volume events should have a dedicated Queue or Topic).  This is a Topic listener and
 * as such the messages will be consumed be every Hawkular server in the cluster and the handling must be
 * cluster-aware.
 *
 * @author Jay Shaughnessy
 */
@MessageDriven(messageListenerInterface = MessageListener.class, activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = "HawkularTopic") })
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class HawkularTopicListener extends BasicMessageListener<BasicMessage> {
    private final Logger log = Logger.getLogger(HawkularTopicListener.class);

    @EJB
    BackfillCache backfillCacheManager;

    @Override
    protected void onBasicMessage(BasicMessage msg) {

        // Feed/Agent WebSocket Closed Handling
        //
        if (msg instanceof FeedWebSocketClosedEvent) {
            FeedWebSocketClosedEvent fce = (FeedWebSocketClosedEvent) msg;
            log.debugf("Feed WebSocket Closed. feedId=%s reason=%s code=%s", fce.getFeedId(), fce.getReason(),
                    fce.getCode());
            backfillCacheManager.forceBackfill(fce.getFeedId());
        }
    }
}
