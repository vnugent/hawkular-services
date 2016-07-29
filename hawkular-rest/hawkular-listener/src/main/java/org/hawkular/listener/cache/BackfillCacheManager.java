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
package org.hawkular.listener.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Local;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import org.hawkular.inventory.api.Inventory;
import org.hawkular.inventory.api.Metrics;
import org.hawkular.inventory.api.filters.With;
import org.hawkular.inventory.api.model.Feed;
import org.hawkular.inventory.api.model.MetricDataType;
import org.hawkular.metrics.core.service.Functions;
import org.hawkular.metrics.core.service.MetricsService;
import org.hawkular.metrics.model.AvailabilityType;
import org.hawkular.metrics.model.DataPoint;
import org.hawkular.metrics.model.Metric;
import org.hawkular.metrics.model.MetricId;
import org.hawkular.metrics.model.MetricType;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Address;
import org.jboss.logging.Logger;

import rx.Observable;
import rx.Subscriber;

/**
 * <p>
 * Implementation of {@link Cache} services based on Infinispan cache. There is a cache entry to track each feed
 * "ping".  A feed "ping" is an UP avail sent regularly by a feed to indicate it is up and reporting. A single feed
 * may report more than one ping metric, typically one for each tenant for which it reports metrics.</p>
 * <p>
 * A feed is backfilled if it starts pinging the server and then stops pinging. The time between its first two pings
 * is assumed to be its ping period.  The ping period is multiplied by a provided factor to determine the max
 * quiet time between pings before a backfill is performed.</p>
 * <p>
 * The following system properties can be defined to configure the backfill mechanism:
 * <pre>
 * hawkular-services.backfill.job-period-secs
 *   The frequency of backfill checking. A dead feed should be detected no longer than this period past its max
 *   quiet time (based on the ping-period-factor).
 *   Default = 30s
 *
 * hawkular-services.backfill.job-threads
 *   The number of threads devoted to backfill checking jobs.  For large inventories this may need to be increased.
 *   Default = 10
 *
 * hawkular-services.backfill.ping-period-factor
 *   The multiplier applied to the ping period to determine the max quiet time before performing backfill. For
 *   example, if set to 2.5 and for a feed pinging every 60s, a backfill would be performed if no ping is received
 *   after more than 150s.
 *   Default = 2.5
 *
 * hawkular-services.backfill.ping-period-min-secs
 *   Feeds that ping too infrequently will not be checked. This value also protects against an interruption when
 *   establishing the ping period for a feed.  In other words, two pings must be received in less than this established
 *   min before a backfill job will be established for the feed.
 *   Default = 125s
 * </pre></p>
 * <p>
 * It needs the following cache defined in the Wildfly configuration files.
 * <pre>
 * {@code
 * standalone.xml:
 *       <cache-container name="hawkular-services" default-cache="backfill" statistics-enabled="true">
 *          <local-cache name="backfill"/>
 *       </cache-container>
 *
 * standalone-ha.xml:
 *       <cache-container name="hawkular-services" default-cache="backfill" statistics-enabled="true">
 *          <transport lock-timeout="60000"/>
 *          <replicated-cache name="backfill" mode="SYNC">
 *              <transaction mode="BATCH"/>
 *          </replicated-cache>
 *       </cache-container>
 * }
 * </pre></p>
 * <p>
 * Note that by default Singleton EJBs apply Lock(WRITE) to all business methods with a default
 * five-minute timeout.</p>
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@Local(BackfillCache.class)
@Startup
@Singleton
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class BackfillCacheManager implements BackfillCache {

    private static final String DEFAULT_JOB_PERIOD_SECS = "15";
    private static final String DEFAULT_JOB_THREADS = "10";
    private static final String DEFAULT_PING_PERIOD_FACTOR = "2.5";
    private static final String DEFAULT_PING_PERIOD_MIN_SECS = "125";

    private static final String PROP_JOB_PERIOD_SECS = "hawkular-services.backfill.job-period-secs";
    private static final String PROP_JOB_THREADS = "hawkular-services.backfill.job-threads";
    private static final String PROP_PING_PERIOD_FACTOR = "hawkular-services.backfill.ping-period-factor";
    private static final String PROP_PING_PERIOD_MIN_SECS = "hawkular-services.backfill.ping-period-min-secs";

    private static final int JOB_PERIOD_SECS;
    private static final int JOB_THREADS;
    private static final int PING_PERIOD_MIN_SECS;
    private static final double PING_PERIOD_FACTOR;

    public static final String FEED_PREFIX = "hawkular-feed-availability-";

    private static final String MONITORING_TYPE_KEY = "hawkular-services.monitoring-type";
    private static final String MONITORING_TYPE_VALUE_REMOTE = "remote";

    static {
        int jobPeriodSecs;
        int jobThreads;
        int pingPeriodMinSecs;
        double pingPeriodFactor;
        try {
            jobPeriodSecs = Integer
                    .valueOf(System.getProperty(PROP_JOB_PERIOD_SECS, DEFAULT_JOB_PERIOD_SECS))
                    .intValue();
        } catch (Exception e) {
            jobPeriodSecs = 30;
        }
        try {
            jobThreads = Integer
                    .valueOf(System.getProperty(PROP_JOB_THREADS, DEFAULT_JOB_THREADS))
                    .intValue();
        } catch (Exception e) {
            jobThreads = 10;
        }
        try {
            pingPeriodFactor = Double
                    .valueOf(System.getProperty(PROP_PING_PERIOD_FACTOR, DEFAULT_PING_PERIOD_FACTOR))
                    .doubleValue();
        } catch (Exception e) {
            pingPeriodFactor = 2.5;
        }
        try {
            pingPeriodMinSecs = Integer
                    .valueOf(System.getProperty(PROP_PING_PERIOD_MIN_SECS, DEFAULT_PING_PERIOD_MIN_SECS))
                    .intValue();
        } catch (Exception e) {
            pingPeriodMinSecs = 125;
        }
        JOB_PERIOD_SECS = jobPeriodSecs;
        JOB_THREADS = jobThreads;
        PING_PERIOD_FACTOR = pingPeriodFactor;
        PING_PERIOD_MIN_SECS = pingPeriodMinSecs;
    }

    private final Logger log = Logger.getLogger(BackfillCacheManager.class);

    /**
     * Indicate whether we are standalone or distributed.
     */
    private boolean standalone = true;

    /**
     * Number of cache members
     */
    private int numMembers = 1;

    /**
     * Each member assigned a unique number from 0..numMembers-1.
     */
    private int memberNumber = 0;

    private ScheduledExecutorService executorService;

    private Map<CacheKey, ScheduledFuture<?>> jobMap = new ConcurrentHashMap<>();

    /**
     * Access to the manager of the caches used for tracking avail.
     */
    @Resource(lookup = "java:jboss/infinispan/container/hawkular-services")
    private EmbeddedCacheManager cacheManager;

    /**
     * This cache keeps the avail data. Note that ISpan Cache implements ConcurrentMap
     */
    @Resource(lookup = "java:jboss/infinispan/cache/hawkular-services/backfill")
    private Cache<CacheKey, CacheValue> backfillCache;

    @Resource(lookup = "java:global/Hawkular/Inventory")
    Inventory inventory;

    @Resource(lookup = "java:global/Hawkular/Metrics")
    MetricsService metricsService;

    @EJB
    BackfillCache self;

    @PostConstruct
    public void init() {
        // Cache manager has an active transport (i.e. jgroups) when is configured on distributed mode
        standalone = (null == cacheManager.getTransport());
        if (standalone) {
            log.info("Initializing Standalone Availability Cache");
        } else {
            log.info("Initializing Distributed Availability Cache");
            processTopologyChange();
        }

        // This is basically a fixed size pool, the size may need to be increased if there are a lot of
        // active feeds.
        executorService = Executors.newScheduledThreadPool(JOB_THREADS);
    }

    @PreDestroy
    public void close() {
        executorService.shutdownNow();
    }

    @Override
    public boolean isStandalone() {
        return standalone;
    }

    /**
     * Auxiliary interface to add Infinispan listener to the caches
     */
    @Listener
    public class TopologyChangeListener {
        @ViewChanged
        public void onTopologyChange(ViewChangedEvent cacheEvent) {
            // When a node is joining/leaving the cluster partition needs to be re-calculated and updated
            self.processTopologyChange();
        }
    }

    /**
     * Reset the memberNumber for this cache member given the new cluster topology. Each member should execute
     * this on a topology change.  This method and {@link BackfillCacheManager#isResponsible(String)} work together.
     */
    @Override
    public void processTopologyChange() {
        List<Address> members = cacheManager.getMembers();
        Address member = cacheManager.getAddress();

        if (null == members || null == member || -1 == members.indexOf(member)) {
            log.error("Unexpected Cache Topology. Member: " + member + " not found in " + members);
            return;
        }

        Collections.sort(members);
        numMembers = members.size();
        memberNumber = members.indexOf(member);

        log.info("Topology Update. Member " + member + " assigned number " + memberNumber + " of " + numMembers);
    }

    @Override
    @Lock(LockType.READ)
    public boolean isResponsible(String metricId) {
        int hash = metricId.hashCode();
        int mod = hash % numMembers;
        boolean result = mod == memberNumber;
        log.trace("Member " + memberNumber + (result ? " is " : " is not ") + " responsible for " + metricId);
        return result;
    }

    @Override
    @Lock(LockType.READ)
    public void updateFeedAvailability(String tenantId, String feedAvailabilityMetricId) {
        if (!isResponsible(feedAvailabilityMetricId)) {
            return;
        }

        CacheKey key = new CacheKey(tenantId, feedAvailabilityMetricId);

        try {
            CacheValue value = backfillCache.get(key);
            if (null == value) {
                backfillCache.put(key, new CacheValue());

            } else {
                long now = System.currentTimeMillis();

                // On the second ping, if valid, start the backfill check job
                if (!value.hasBackfillJob()) {
                    long pingPeriodMs = now - value.getLastUpdateTime();

                    if (pingPeriodMs <= (PING_PERIOD_MIN_SECS * 1000)) {
                        log.debugf("Starting Backfill Job for %s", key);
                        long maxQuietPeriodMs = (long) (pingPeriodMs * PING_PERIOD_FACTOR);
                        value.setMaxQuietPeriodMs(maxQuietPeriodMs);
                        ScheduledFuture<?> sf = executorService.scheduleWithFixedDelay(
                                new BackfillCheckJob(key, maxQuietPeriodMs),
                                JOB_PERIOD_SECS, JOB_PERIOD_SECS, TimeUnit.SECONDS);
                        jobMap.put(key, sf);
                    } else {
                        log.debugf("Ignoring Backfill Job for %s, ping period %d > %d (the minimum)",
                                key, pingPeriodMs, PING_PERIOD_MIN_SECS);
                    }
                }

                // Update the cache with the latest ping
                value.setLastUpdateTime(now);
                backfillCache.put(key, value);
            }
        } catch (Exception e) {
            log.warn("Unable to update feed availability for " + key + ". Will try again on next update");
        }
    }

    @Override
    @Lock(LockType.READ)
    public void forceBackfill(String feedId) {
        String feedAvailabilityMetricId = FEED_PREFIX + feedId;

        if (!isResponsible(feedAvailabilityMetricId)) {
            return;
        }

        // Fetch all tenants for the feed
        Set<Feed> feeds = inventory.tenants().getAll().feeds().getAll(With.id(feedId)).entities();
        if (feeds.isEmpty()) {
            log.errorf("Expected at least one tenant for feedId [%s]", feedId);
            return;
        }

        for (Feed feed : feeds) {
            forceBackfill(feed.getPath().ids().getTenantId(), feedAvailabilityMetricId);
        }
    }

    private void forceBackfill(String tenantId, String feedAvailabilityMetricId) {
        CacheKey key = new CacheKey(tenantId, feedAvailabilityMetricId);
        CacheValue value = backfillCache.getOrDefault(key, new CacheValue());

        // backfill situation
        log.infof("Feed %s has been reported down and will be backfilled.", key);
        doBackfill(key, value);

    }

    private void doBackfill(CacheKey key, CacheValue value) {
        // only backfill once, so stop the backfill job
        cancelJob(key);

        // mark the cache entry as no longer having a backfill job running
        value.setMaxQuietPeriodMs(0L);
        backfillCache.put(key, value);

        // Fetch from hwkinventory all avail metrics for the feed on this tenant
        Set<org.hawkular.inventory.api.model.Metric> availMetricsForFeed = inventory
                .tenants()
                .get(key.getTenantId())
                .feeds()
                .get(key.getFeedId())
                .metricTypes()
                .getAll(With.propertyValue("__metric_data_type", MetricDataType.AVAILABILITY.getDisplayName()))
                .metrics()
                .getAll()
                .entities();

        long now = System.currentTimeMillis();

        List<DataPoint<AvailabilityType>> unknown = new ArrayList<>(1);
        unknown.add(new DataPoint<AvailabilityType>(now, AvailabilityType.UNKNOWN));

        List<DataPoint<AvailabilityType>> down = new ArrayList<>(1);
        down.add(new DataPoint<AvailabilityType>(now, AvailabilityType.DOWN));

        List<Metric<AvailabilityType>> availabilities = new ArrayList<>(availMetricsForFeed.size() + 1);

        // Set UNKNOWN for all remotely monitored avail metrics reported by this feed/tenant
        // Set DOWN for all locally monitored avail metrics, or by default, reported by this feed/tenant
        for (org.hawkular.inventory.api.model.Metric invMetric : availMetricsForFeed) {
            MetricId<AvailabilityType> metricId = new MetricId<>(key.getTenantId(), MetricType.AVAILABILITY,
                    invMetric.getId());
            String monitoringType = (String) invMetric.getProperties().get(MONITORING_TYPE_KEY);
            List<DataPoint<AvailabilityType>> availList = MONITORING_TYPE_VALUE_REMOTE
                    .equalsIgnoreCase(monitoringType) ? unknown : down;
            Metric<AvailabilityType> backfillAvail = new Metric<>(metricId, availList);
            availabilities.add(backfillAvail);
        }

        // Set DOWN avail for the feed/tenant itself
        MetricId<AvailabilityType> metricId = new MetricId<>(key.getTenantId(), MetricType.AVAILABILITY,
                key.getMetricId());
        Metric<AvailabilityType> backfillAvail = new Metric<>(metricId, down);
        availabilities.add(backfillAvail);

        // Push the avail to hwkmetrics
        Observable<Metric<AvailabilityType>> metrics = Functions.metricToObservable(key.getTenantId(),
                availabilities, MetricType.AVAILABILITY);
        Observable<Void> observable = metricsService.addDataPoints(MetricType.AVAILABILITY, metrics);
        observable.subscribe(new Subscriber<Void>() {

            @Override
            public void onCompleted() {
                if (log.isDebugEnabled()) {
                    log.debugf("Successful backfill of Feed %s with %s", key, availabilities);
                } else {
                    log.infof("Successful backfill of Feed %s", key);
                }
            }

            @Override
            public void onError(Throwable arg0) {
                log.warnf("Failed to backfill Feed %s with %s: %s", key, availabilities, arg0);
            }

            @Override
            public void onNext(Void arg0) {
            }
        });
    }

    private void cancelJob(CacheKey key) {
        ScheduledFuture<?> job = jobMap.get(key);
        if (null != job) {
            job.cancel(true);
        }
        try {
            jobMap.remove(key);
        } catch (Exception e) {
            log.errorf("Failed to cancel BackfillCheck job for %s", key);
        }
    }

    public class BackfillCheckJob implements Runnable {

        private CacheKey key;
        private long maxQuietPeriodMs;

        public BackfillCheckJob(CacheKey key, long maxQuietPeriodMs) {
            super();
            this.key = key;
            this.maxQuietPeriodMs = maxQuietPeriodMs;
        }

        @Resource(lookup = "java:global/Hawkular/Metrics")
        Metrics metrics;

        @Override
        public void run() {
            CacheValue value = backfillCache.get(key);
            if (null == value) {
                log.warnf("Did not find expected cache entry. Canceling backfill job for %s", key);
                cancelJob(key);
                return;
            }

            long quietPeriodMs = System.currentTimeMillis() - value.lastUpdateTime;
            if (quietPeriodMs <= maxQuietPeriodMs) {
                log.tracef("FEED IS REPORTING: %s", key);
                return;
            }

            // backfill situation
            log.infof("Feed %s has not reported for %d ms and will be backfilled.", key, quietPeriodMs);
            doBackfill(key, value);
        }

    }

    public static class CacheKey {
        private String tenantId;
        private String metricId;
        private String feedId;

        public CacheKey(String tenantId, String metricId) {
            super();
            this.tenantId = tenantId;
            this.metricId = metricId;
            this.feedId = metricId.substring(FEED_PREFIX.length());
        }

        public String getTenantId() {
            return tenantId;
        }

        public String getMetricId() {
            return metricId;
        }

        public String getFeedId() {
            return feedId;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((metricId == null) ? 0 : metricId.hashCode());
            result = prime * result + ((tenantId == null) ? 0 : tenantId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            CacheKey other = (CacheKey) obj;
            if (metricId == null) {
                if (other.metricId != null)
                    return false;
            } else if (!metricId.equals(other.metricId))
                return false;
            if (tenantId == null) {
                if (other.tenantId != null)
                    return false;
            } else if (!tenantId.equals(other.tenantId))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "CacheKey [tenantId=" + tenantId + ", metricId=" + metricId + "]";
        }
    }

    public static class CacheValue {
        private long lastUpdateTime;
        private long maxQuietPeriodMs; // <= 0 when there is no active timer

        public CacheValue() {
            super();
            this.lastUpdateTime = System.currentTimeMillis();
            this.maxQuietPeriodMs = 0;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }

        public void setLastUpdateTime(long lastUpdateTime) {
            this.lastUpdateTime = lastUpdateTime;
        }

        public boolean hasBackfillJob() {
            return maxQuietPeriodMs > 0;
        }

        public long getMaxQuietPeriodMs() {
            return maxQuietPeriodMs;
        }

        public void setMaxQuietPeriodMs(long maxQuietPeriodMs) {
            this.maxQuietPeriodMs = maxQuietPeriodMs;
        }

        @Override
        public String toString() {
            return "CacheValue [lastUpdateTime=" + lastUpdateTime + ", maxQuietPeriodMs=" + maxQuietPeriodMs + "]";
        }
    }
}
