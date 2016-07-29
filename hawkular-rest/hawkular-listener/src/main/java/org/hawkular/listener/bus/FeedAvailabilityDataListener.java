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

import java.util.List;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.EJB;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.jms.MessageListener;

import org.hawkular.bus.common.consumer.BasicMessageListener;
import org.hawkular.listener.cache.BackfillCache;
import org.hawkular.listener.cache.BackfillCacheManager;
import org.hawkular.metrics.component.publish.AvailDataMessage;
import org.hawkular.metrics.component.publish.AvailDataMessage.AvailData;
import org.hawkular.metrics.component.publish.AvailDataMessage.SingleAvail;
import org.jboss.logging.Logger;

/**
 * <p>
 * A listener for Availability data published from Hawkular Metrics. Tracks availability "pings" from feeds via
 * a cluster-wide cache.  Looks for non-reporting feeds in order to perform backfill operations. To backfill a feed
 * is to set the feed to DOWN avail and its resources to UNKNOWN avail.
 * </p>
 * This is useful only when deploying into the Hawkular Bus with Hawkular Metrics. The expected message payload should
 * be JSON representation of {@link AvailDataMessage}.
 *
 * @author Jay Shaughnessy
 */
@MessageDriven(messageListenerInterface = MessageListener.class, activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = "HawkularAvailData") })
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class FeedAvailabilityDataListener extends BasicMessageListener<AvailDataMessage> {
    private final Logger log = Logger.getLogger(FeedAvailabilityDataListener.class);

    private static final String UP = "UP";

    @EJB
    BackfillCache backfillCacheManager;

    @Override
    protected void onBasicMessage(AvailDataMessage msg) {

        AvailData availData = msg.getAvailData();
        if (log.isTraceEnabled()) {
            log.trace("Message received with [" + availData.getData().size() + "] avails.");
        }

        List<SingleAvail> data = availData.getData();
        for (SingleAvail a : data) {
            String metricId = a.getId();
            // ignore non-ping or non-up avail
            if (metricId.startsWith(BackfillCacheManager.FEED_PREFIX) && UP.equals(a.getAvail())) {
                backfillCacheManager.updateFeedAvailability(a.getTenantId(), metricId);
            }
        }
    }

}
