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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.jms.MessageListener;

import org.hawkular.inventory.api.Action.Enumerated;
import org.hawkular.inventory.api.EntityNotFoundException;
import org.hawkular.inventory.api.Inventory;
import org.hawkular.inventory.api.RelationAlreadyExistsException;
import org.hawkular.inventory.api.Relationships;
import org.hawkular.inventory.api.Resources;
import org.hawkular.inventory.api.filters.With;
import org.hawkular.inventory.api.model.DataEntity;
import org.hawkular.inventory.api.model.Feed;
import org.hawkular.inventory.api.model.Resource;
import org.hawkular.inventory.api.model.ResourceType;
import org.hawkular.inventory.api.model.StructuredData;
import org.hawkular.inventory.bus.api.InventoryEvent;
import org.hawkular.inventory.bus.api.InventoryEventMessageListener;
import org.hawkular.inventory.bus.api.ResourceEvent;
import org.hawkular.inventory.bus.api.ResourceTypeEvent;
import org.hawkular.inventory.paths.CanonicalPath;
import org.hawkular.inventory.paths.DataRole;
import org.hawkular.inventory.paths.RelativePath;
import org.jboss.logging.Logger;

/**
 * <p>
 * Listen for Hawkular Inventory events posted to the bus and take necessary actions. Current configured Actions:</p>
 * <p>
 * <b>Server Create/Remove:</b> Look for resource Creation or Removal events for the various flavors of WildFly.
 * </p>
 * <p>
 * <b>Cluster Discovery:</b> Look for "JGroups Channel" Resource Creations or Config changes. If we detect cluster
 * membership then ensure the cluster relationships exists between the servers.
 * </p>
 * @author Jay Shaughnessy
 */
@MessageDriven(messageListenerInterface = MessageListener.class, activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = "HawkularInventoryChanges") })
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class InventoryEventListener extends InventoryEventMessageListener {
    private final Logger log = Logger.getLogger(InventoryEventListener.class);

    @javax.annotation.Resource(lookup = "java:global/Hawkular/Inventory")
    Inventory inventory;

    private final ListenerUtils utils = new ListenerUtils();

    // For Server Create/Remove
    private static final Set<String> SERVER_TYPES = new HashSet<>(Arrays.asList(
            "Domain Host",
            "Domain WildFly Server",
            "Domain WildFly Server Controller",
            "Host Controller",
            "WildFly Server"));

    // For Cluster Discovery
    private static final String ATTR_GROUP_MEMBERSHIP_VIEW = "Group Membership View";
    private static final String ATTR_NODE_NAME = "Node Name";
    private static final String ATTR_IP_ADDRESS = "IP Address";
    private static final String TYPE_JGROUPS_CHANNEL = "JGroups Channel";
    private static final String TYPE_WILDFLY_SERVER = "WildFly Server";

    private static final String RELATIONSHIP = "isClusteredWith";

    @Override
    protected void onBasicMessage(InventoryEvent<?> event) {
        switch (event.getAction()) {
            case CREATED:
            case UPDATED:
            case DELETED: {
                if (event instanceof ResourceEvent) {
                    handleResourceEvent((ResourceEvent) event);

                } else if (event instanceof ResourceTypeEvent) {
                    // handleResourceTypeEvent((ResourceTypeEvent) event);
                }
                break;
            }
            default:
                break; // not interesting
        }
    }

    private void handleResourceEvent(ResourceEvent event) {
        String tenantId = null;
        Resource r = null;
        String type = null;
        try {
            tenantId = event.getTenant().getId();
            r = event.getObject();
            type = r.getType().getId();
            boolean handled = false;

            handled |= checkServerEvent(event.getAction(), tenantId, r, type);

            handled |= checkClusterEvent(event.getAction(), tenantId, r, type);

            if (!handled) {
                log.debugf("Skipping Type [%s] ", type);
            }
        } catch (EntityNotFoundException e) {
            log.errorf("Expected configuration for resourcetype [%s]", type); //TODO debug
        } catch (Exception e) {
            log.errorf("Error processing inventory bus event %s : %s", event, e);
        }
    }

    private boolean checkServerEvent(Enumerated action, String tenantId, Resource r, String type) {
        switch (action) {
            case CREATED:
            case DELETED: {
                if (SERVER_TYPES.contains(type)) {
                    String message = ((action == Enumerated.CREATED) ? "Added: " : "Removed: ") + type;

                    utils.addEvent(r.getPath(), "Inventory Change", message, "hawkular_event",
                            "MiddlewareServer", message);
                    return true;
                }
            }
            default:
                return false; // not interesting
        }
    }

    private boolean checkClusterEvent(Enumerated action, String tenantId, Resource r, String type) {
        switch (action) {
            case CREATED:
            case UPDATED: {
                if (TYPE_JGROUPS_CHANNEL.equals(type)) {
                    log.debugf("Clusterizing on %s of %s", action, r.getName());
                    clusterize(tenantId, r);
                    return true;
                }
            }
            default:
                return false; // not interesting
        }
    }

    private void clusterize(String tenantId, Resource r) {
        Map<String, Set<String>> mappings = getClusterMappings(r);
        if (mappings.isEmpty()) {
            log.warnf("No cluster mappings found for [%s]", r.getName());
            return;
        }

        // OK, this is a bit convoluted at the moment because:
        //   1) An inventory bug prevents us from using identical()
        //   2) Inventory does not yet support easy filtering on config values.
        // So, for now we:
        //   1) Fetch all feeds for the tenant and for each feed:
        //      1) Fetch all of the Wildfly Server 'Node Name' Configurations (so, one per server
        //         in inventory for this tenant).
        //      2) Create a map of Node Names to CanonicalPaths of the actual WildFlyServer resource
        //      3) Use the ones we need to create the cluster relationships
        Map<String, CanonicalPath> nodeResourceMap = getNodeResourceMap(tenantId);

        String thisServer = mappings.keySet().iterator().next();
        if (!nodeResourceMap.containsKey(thisServer)) {
            log.warnf("No Server found for node name [%s]", thisServer);
            return;
        }

        Set<String> otherServers = mappings.get(thisServer);
        for (String otherServer : otherServers) {
            CanonicalPath cpThis = nodeResourceMap.get(thisServer);
            CanonicalPath cpOther = nodeResourceMap.get(otherServer);

            // ensure cluster relationship exists between the two members, in both directions
            log.debugf("Creating cluster mapping %s %s %s", thisServer, RELATIONSHIP, otherServer);
            try {
                inventory.inspect(cpThis, Resources.Single.class)
                        .relationships(Relationships.Direction.both)
                        .linkWith(RELATIONSHIP, cpOther, null);

                // TODO: maybe turn this down to debug
                log.infof("Created cluster mapping %s %s %s", thisServer, RELATIONSHIP, otherServer);

            } catch (RelationAlreadyExistsException e) {
                log.debugf("Cluster mapping already exists %s", e);
            } catch (Exception e) {
                log.errorf("Failed to establish cluster mapping %s %s %s: %s", cpThis, RELATIONSHIP, cpOther, e);
            }
        }
    }

    // TODO: When identical() works we should be able to remove this per-feed loop logic
    private Map<String, CanonicalPath> getNodeResourceMap(String tenantId) {
        Map<String, CanonicalPath> nodeResourceMap = new HashMap<>();

        Set<Feed> feeds = inventory
                .tenants().get(tenantId)
                .feeds().getAll()
                .entities();

        feeds.stream().forEach(f -> {
            Set<DataEntity> nodeNameConfigs = inventory
                    .tenants().get(tenantId)
                    .feeds().get(f.getId())
                    .resourceTypes().get(TYPE_WILDFLY_SERVER)
                    //.identical().getAll()
                    .resources().getAll()
                    .data()
                    .getAll(With.id("configuration"),
                            With.dataAt(RelativePath.to().structuredData().key(ATTR_NODE_NAME).get()))
                    .entities();

            for (DataEntity de : nodeNameConfigs) {
                nodeResourceMap.put(de.getValue().map().get(ATTR_NODE_NAME).string(), de.getPath().up());
            }
        });

        return nodeResourceMap;
    }

    private Map<String, Set<String>> getClusterMappings(Resource r) {
        DataEntity data = inventory.inspect(r).data().get(DataRole.Resource.configuration).entity();
        StructuredData config = (null != data) ? data.getValue() : null;

        if (!config.map().containsKey(ATTR_IP_ADDRESS)) {
            log.warnf("[%s] missing config property: [%s]", TYPE_JGROUPS_CHANNEL, ATTR_IP_ADDRESS);
            return Collections.emptyMap();
        }
        if (!config.map().containsKey(ATTR_GROUP_MEMBERSHIP_VIEW)) {
            log.warnf("[%s] missing config property: [%s]", TYPE_JGROUPS_CHANNEL, ATTR_GROUP_MEMBERSHIP_VIEW);
            return Collections.emptyMap();
        }

        String thisMember = config.map().get(ATTR_IP_ADDRESS).string();
        String membersView = config.map().get(ATTR_GROUP_MEMBERSHIP_VIEW).string();
        log.debugf("Cluster view for %s: %s", r, membersView);

        Map<String, Set<String>> mappings = new HashMap<>(1);
        Pattern p = Pattern.compile(".*\\[(.*)\\]");
        Matcher m = p.matcher(membersView);
        boolean membersFound = (m.matches() && !isEmpty(m.group(1)));
        if (membersFound) {
            Set<String> otherMembers = new HashSet<>();
            mappings.put(thisMember, otherMembers);
            for (String member : m.group(1).split(",")) {
                member = member.trim();
                if (!thisMember.equals(member)) {
                    otherMembers.add(member);
                }
            }
        }

        return mappings;
    }

    /**
     * NOT CURRENTLY USED, JUST LEFT AS A FUTURE HOOK.
     *
     * When creating a relevant type generate necessary group trigger. The triggers define out-of-box
     * events that subsequently get pulled into MIQ.
     *
     * @param event Create/Delete event
     */
    @SuppressWarnings("unused")
    private void handleResourceTypeEvent(ResourceTypeEvent event) {
        try {
            ResourceType rt = event.getObject();
            String type = rt.getId();
            switch (type) {
                default:
                    log.debugf("Unhandled Type [%s] ", type);
                    return;
            }

        } catch (Exception e) {
            log.errorf("Error processing inventory bus event %s : %s", event, e);
        }
    }

    private boolean isEmpty(String s) {
        return null == s || s.trim().isEmpty();
    }
}
