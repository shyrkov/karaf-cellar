/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.cellar.hazelcast;

import com.hazelcast.core.*;
import org.apache.karaf.cellar.core.*;
import org.apache.karaf.cellar.core.event.EventConsumer;
import org.apache.karaf.cellar.core.event.EventProducer;
import org.apache.karaf.cellar.core.event.EventTransportFactory;
import org.apache.karaf.cellar.core.utils.CombinedClassLoader;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;

/**
 * A group manager implementation powered by Hazelcast.
 * The role of this class is to provide means of creating groups, setting nodes to groups etc.
 * Keep in sync the distributed group configuration with the locally persisted.
 */
public class HazelcastGroupManager implements GroupManager, EntryListener<String,Object>, ConfigurationListener {

    private static final transient Logger LOGGER = org.slf4j.LoggerFactory.getLogger(HazelcastGroupManager.class);

    private static final String HAZELCAST_LOCK = "org.apache.karaf.cellar.groups.lock";
    private static final String HAZELCAST_GROUPS = "org.apache.karaf.cellar.groups";
    private static final String HAZELCAST_GROUPS_CONFIG = "org.apache.karaf.cellar.groups.config";

    private Map<String, ServiceRegistration> producerRegistrations = new HashMap<String, ServiceRegistration>();
    private Map<String, ServiceRegistration> consumerRegistrations = new HashMap<String, ServiceRegistration>();

    private Map<String, Object> localConfig = new HashMap<String, Object>();

    private Map<String, EventProducer> groupProducers = new HashMap<String, EventProducer>();
    private Map<String, EventConsumer> groupConsumer = new HashMap<String, EventConsumer>();

    private BundleContext bundleContext;

    private HazelcastInstance instance;
    private ConfigurationAdmin configurationAdmin;

    private EventTransportFactory eventTransportFactory;
    private CombinedClassLoader combinedClassLoader;

    public void init() {
        try {
            // create group stored in configuration admin
            Configuration groupsConfiguration = configurationAdmin.getConfiguration(Configurations.GROUP, null);
            Dictionary<String, Object> properties = groupsConfiguration.getProperties();
            if (properties == null) {
                properties = new Hashtable<String, Object>();
            }

            // create a listener for group configuration.
            IMap<String,Object> hazelcastGroupsConfig = instance.getMap(HAZELCAST_GROUPS_CONFIG);

            hazelcastGroupsConfig.addEntryListener(this, true);

            if (hazelcastGroupsConfig.isEmpty()) {
                // First one to be here - initialize hazelcast map with local configuration
                LOGGER.debug("CELLAR HAZELCAST: intialize cluster with local config");

                Map<String, Object> updates = getUpdatesForHazelcastMap(properties);
                hazelcastGroupsConfig.putAll(updates);
            } else {
                Enumeration<String> en = properties.keys();
                while (en.hasMoreElements()) {
                    String key = en.nextElement();
                    localConfig.put(key , properties.get(key));
                }
                boolean updated = false;
                for (String key : hazelcastGroupsConfig.keySet()) {
                    Object value = hazelcastGroupsConfig.get(key);
                    updated |= updatePropertiesFromHazelcastMap(properties, key, value);
                }
                if (updated) {
                    groupsConfiguration.update(properties);
                }
            }

            Node node = getNode();

            // add group membership from configuration
            Configuration nodeConfiguration = configurationAdmin.getConfiguration(Configurations.NODE, null);
            properties = nodeConfiguration.getProperties();
            if (properties == null) {
                properties = new Hashtable<String, Object>();
            }
            String groups = (String) properties.get(Configurations.GROUPS_KEY);
            Set<String> groupNames = convertStringToSet(groups);
            instance.getMap(HAZELCAST_GROUPS).put(node, groupNames);
        } catch (IOException e) {
            LOGGER.warn("CELLAR HAZELCAST: can't create cluster group from configuration admin", e);
        }
    }

    private boolean updatePropertiesFromHazelcastMap(Dictionary<String, Object> properties, String key, Object value) {
        boolean changed = false;
        if (value instanceof Map) {
            Map<String,Object> map = (Map<String, Object>) value;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getKey().equals(".change")) {
                    Set<String> groups = convertStringToSet((String) properties.get(Configurations.GROUPS_KEY));
                    if ((entry.getValue().equals("added") && !groups.contains(key)) || (entry.getValue().equals("removed") && groups.contains(key))) {
                        LOGGER.debug("CELLAR HAZELCAST: get group " + key + " configuration from cluster : " + key + " has been "+entry.getValue());
                        if (entry.getValue().equals("added")) {
                            groups.add(key);
                        } else {
                            groups.remove(key);
                        }
                        String newValue = convertSetToString(groups);
                        properties.put(Configurations.GROUPS_KEY, newValue);
                        localConfig.put(Configurations.GROUPS_KEY, newValue);
                        changed = true;
                    }
                } else if (properties.get(entry.getKey()) == null || !properties.get(entry.getKey()).equals(entry.getValue())) {
                    LOGGER.debug("CELLAR HAZELCAST: get group " + key + " configuration from cluster : " + entry.getKey() + " = " + entry.getValue());
                    properties.put(entry.getKey(), entry.getValue());
                    localConfig.put(entry.getKey(), entry.getValue());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private Map<String, Object> getUpdatesForHazelcastMap(Dictionary<String, Object> properties) {
        Map<String,Object> updates = new HashMap<String,Object>();
        Enumeration<String> en = properties.keys();
        while (en.hasMoreElements()) {
            String key = en.nextElement();
            Object value = properties.get(key);

            if (!localConfig.containsKey(key) || localConfig.get(key) == null || !localConfig.get(key).equals(value)) {
                if (!key.startsWith("felix.") && !key.startsWith("service.")) {
                    if (key.equals(Configurations.GROUPS_KEY)) {
                        Set<String> removedGroups = convertStringToSet((String) localConfig.get(key));
                        Set<String> addedGroups = convertStringToSet((String) value);
                        addedGroups.removeAll(removedGroups);
                        removedGroups.removeAll(convertStringToSet((String) value));
                        for (String addedGroup : addedGroups) {
                            if (!updates.containsKey(addedGroup)) {
                                updates.put(addedGroup, new HashMap<String, Object>());
                            }
                            ((Map<String, Object>) updates.get(addedGroup)).put(".change", "added");
                        }
                        for (String removedGroup : removedGroups) {
                            if (!updates.containsKey(removedGroup)) {
                                updates.put(removedGroup, new HashMap<String, Object>());
                            }
                            ((Map<String, Object>) updates.get(removedGroup)).put(".change", "removed");
                        }
                    } else {
                        String groupKey = key.substring(0, key.indexOf("."));
                        if (!updates.containsKey(groupKey)) {
                            updates.put(groupKey, new HashMap<String, Object>());
                        }
                        Map<String, Object> m = (Map<String, Object>) updates.get(groupKey);
                        m.put(key, value);
                    }
                }
            }

            localConfig.put(key , value);

        }
        return updates;
    }

    public void destroy() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            // update the group
            Node local = this.getNode();

            instance.getMap(HAZELCAST_GROUPS).remove(local);

            // shutdown the group consumer/producers
            for (Map.Entry<String, EventConsumer> consumerEntry : groupConsumer.entrySet()) {
                EventConsumer consumer = consumerEntry.getValue();
                consumer.stop();
            }
            groupConsumer.clear();
            groupProducers.clear();
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Node getNode() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            Node node = null;
            Cluster cluster = instance.getCluster();
            if (cluster != null) {
                Member member = cluster.getLocalMember();
                node = new HazelcastNode(member);
            }
            return node;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Group createGroup(String groupName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            Map<String, Group> listGroups = listGroups();
            Group group = listGroups.get(groupName);
            if (group == null) {
                try {
                    Configuration configuration = configurationAdmin.getConfiguration(Configurations.GROUP, null);
                    Dictionary properties = copyGroupConfiguration(Configurations.DEFAULT_GROUP_NAME, groupName, configuration);
                    Set<String> groups = convertStringToSet((String) properties.get(Configurations.GROUPS_KEY));
                    groups.add(groupName);
                    properties.put(Configurations.GROUPS_KEY, convertSetToString(groups));
                    configuration.update(properties);
                } catch (IOException e) {
                    LOGGER.error("CELLAR HAZELCAST: failed to update cluster group configuration", e);
                }
            }
            return group;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public void deleteGroup(String groupName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            if (!groupName.equals(Configurations.DEFAULT_GROUP_NAME)) {
                try {
                    // store the group list to configuration admin
                    Configuration configuration = configurationAdmin.getConfiguration(Configurations.GROUP, null);
                    Dictionary properties = configuration.getProperties();
                    Set<String> groups = convertStringToSet((String) properties.get(Configurations.GROUPS_KEY));
                    groups.remove(groupName);
                    properties.put(Configurations.GROUPS_KEY, convertSetToString(groups));
                    configuration.update(properties);
                } catch (IOException e) {
                    LOGGER.warn("CELLAR HAZELCAST: can't store cluster group list", e);
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Set<Group> listLocalGroups() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            return listGroups(getNode());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public boolean isLocalGroup(String groupName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            Set<Group> localGroups = this.listLocalGroups();
            for (Group localGroup : localGroups) {
                if (localGroup.getName().equals(groupName)) {
                    return true;
                }
            }
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Set<Group> listAllGroups() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            return new HashSet<Group>(listGroups().values());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Group findGroupByName(String groupName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            return listGroups().get(groupName);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Map<String, Group> listGroups() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Map<String, Group> res = new HashMap<String, Group>();
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            Map<Node, Set<String>> nodes = instance.getMap(HAZELCAST_GROUPS);

            Set<String> groups = convertStringToSet((String) localConfig.get(Configurations.GROUPS_KEY));
            groups.add("default");

            for (String groupName : groups) {
                Group group = new Group(groupName);
                res.put(groupName, group);
                for (Map.Entry<Node, Set<String>> entry : nodes.entrySet()) {
                    if (entry.getValue().contains(groupName)) {
                        group.getNodes().add(entry.getKey());
                    }
                }
            }

            return res;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Set<Group> listGroups(Node node) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            Set<Group> result = new HashSet<Group>();

            Map<Node, Set<String>> groupMap = instance.getMap(HAZELCAST_GROUPS);
            Set<String> groupNames = groupMap.get(node);

            if (groupNames != null) {
                Map<String, Group> g = listGroups();
                g.keySet().retainAll(groupNames);
                return new HashSet<Group>(g.values());
            }

            return new HashSet<Group>();
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Set<String> listGroupNames() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            return listGroupNames(getNode());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public Set<String> listGroupNames(Node node) {
        Set<String> names = new HashSet<String>();
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            Map<String, Group> groups = listGroups();

            if (groups != null && !groups.isEmpty()) {
                for (Group group : groups.values()) {
                    if (group.getNodes().contains(node)) {
                        names.add(group.getName());
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
        return names;
    }

    /**
     * Register a cluster {@link Group}.
     *
     * @param group the cluster group to register.
     */
    @Override
    public void registerGroup(Group group) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            String groupName = group.getName();

            LOGGER.debug("CELLAR HAZELCAST: registering cluster group {}.", groupName);
            Properties serviceProperties = new Properties();
            serviceProperties.put("type", "group");
            serviceProperties.put("name", groupName);

            if (!producerRegistrations.containsKey(groupName)) {
                EventProducer producer = groupProducers.get(groupName);
                if (producer == null) {
                    producer = eventTransportFactory.getEventProducer(groupName, Boolean.TRUE);
                    groupProducers.put(groupName, producer);
                }

                ServiceRegistration producerRegistration = bundleContext.registerService(EventProducer.class.getCanonicalName(), producer, (Dictionary) serviceProperties);
                producerRegistrations.put(groupName, producerRegistration);
            }

            if (!consumerRegistrations.containsKey(groupName)) {
                EventConsumer consumer = groupConsumer.get(groupName);
                if (consumer == null) {
                    consumer = eventTransportFactory.getEventConsumer(groupName, true);
                    groupConsumer.put(groupName, consumer);
                } else if (!consumer.isConsuming()) {
                    consumer.start();
                }
                ServiceRegistration consumerRegistration = bundleContext.registerService(EventConsumer.class.getCanonicalName(), consumer, (Dictionary) serviceProperties);
                consumerRegistrations.put(groupName, consumerRegistration);
            }

            Node node = getNode();
            group.getNodes().add(node);
            Map<Node, Set<String>> map = instance.getMap(HAZELCAST_GROUPS);
            Set<String> groupNames = (Set<String>) map.get(node);
            groupNames = new HashSet<String>(groupNames);
            groupNames.add(groupName);
            map.put(node, groupNames);

            // add group to configuration
            try {
                Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
                if (configuration != null) {
                    Dictionary<String, Object> properties = configuration.getProperties();
                    if (properties != null) {
                        String groups = (String) properties.get(Configurations.GROUPS_KEY);
                        if (groups == null || groups.isEmpty()) {
                            groups = groupName;
                        } else {
                            Set<String> groupNamesSet = convertStringToSet(groups);
                            groupNamesSet.add(groupName);
                            groups = convertSetToString(groupNamesSet);
                        }

                        if (groups == null || groups.isEmpty()) {
                            groups = groupName;
                        }
                        properties.put(Configurations.GROUPS_KEY, groups);
                        configuration.update(properties);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("CELLAR HAZELCAST: error reading cluster group configuration {}", group);
            }

            // launch the synchronization on the group
            try {
                ServiceReference[] serviceReferences = bundleContext.getAllServiceReferences("org.apache.karaf.cellar.core.Synchronizer", null);
                if (serviceReferences != null && serviceReferences.length > 0) {
                    for (ServiceReference ref : serviceReferences) {
                        Synchronizer synchronizer = (Synchronizer) bundleContext.getService(ref);
                        if (synchronizer != null) {
                            synchronizer.sync(group);
                        }
                        bundleContext.ungetService(ref);
                    }
                }
            } catch (InvalidSyntaxException e) {
                LOGGER.error("CELLAR HAZELCAST: failed to look for synchronizers", e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public void registerGroup(String groupName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            registerGroup(new Group(groupName));
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public void unRegisterGroup(String groupName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            unRegisterGroup(listGroups().get(groupName));
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public void unRegisterGroup(Group group) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(combinedClassLoader);
            String groupName = group.getName();
            // remove local node from cluster group
            group.getNodes().remove(getNode());
            listGroups().put(groupName, group);

            // un-register cluster group consumers
            if (consumerRegistrations != null && !consumerRegistrations.isEmpty()) {
                ServiceRegistration consumerRegistration = consumerRegistrations.get(groupName);
                if (consumerRegistration != null) {
                    consumerRegistration.unregister();
                    consumerRegistrations.remove(groupName);
                }
            }

            // un-register cluster group producers
            if (producerRegistrations != null && !producerRegistrations.isEmpty()) {
                ServiceRegistration producerRegistration = producerRegistrations.get(groupName);
                if (producerRegistration != null) {
                    producerRegistration.unregister();
                    producerRegistrations.remove(groupName);
                }
            }

            // remove consumers & producers
            groupProducers.remove(groupName);
            EventConsumer consumer = groupConsumer.remove(groupName);
            if (consumer != null) {
                consumer.stop();
            }

            Node node = getNode();
            group.getNodes().add(node);
            Map<Node, Set<String>> map = instance.getMap(HAZELCAST_GROUPS);
            Set<String> groupNames = (Set<String>) map.get(node);
            groupNames = new HashSet<String>(groupNames);
            groupNames.remove(groupName);
            map.put(node, groupNames);

            // remove cluster group from configuration
            try {
                Configuration configuration = configurationAdmin.getConfiguration(Configurations.NODE, null);
                Dictionary<String, Object> properties = configuration.getProperties();
                String groups = (String) properties.get(Configurations.GROUPS_KEY);
                if (groups == null || groups.isEmpty()) {
                    groups = "";
                } else if (groups.contains(groupName)) {
                    Set<String> groupNamesSet = convertStringToSet(groups);
                    groupNamesSet.remove(groupName);
                    groups = convertSetToString(groupNamesSet);
                }
                properties.put(Configurations.GROUPS_KEY, groups);
                configuration.update(properties);
            } catch (IOException e) {
                LOGGER.error("CELLAR HAZELCAST: failed to read cluster group configuration", e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * Copy the configuration of a cluster {@link Group}.
     *
     * <b>1.</b> Updates configuration admin from Hazelcast using source config.
     * <b>2.</b> Creates target configuration both on Hazelcast and configuration admin.
     *
     * @param sourceGroupName the source cluster group.
     * @param targetGroupName the target cluster group.
     * @param configuration
     */
    public Dictionary copyGroupConfiguration(String sourceGroupName, String targetGroupName, Configuration configuration) {
        if (configuration != null) {
            // get configuration from config admin
            Dictionary configAdminProperties = configuration.getProperties();
            if (configAdminProperties == null) {
                configAdminProperties = new Properties();
            }

            Dictionary updatedProperties = new Properties();
            Enumeration keyEnumeration = configAdminProperties.keys();
            while (keyEnumeration.hasMoreElements()) {
                String key = (String) keyEnumeration.nextElement();
                String value = configAdminProperties.get(key).toString();

                if (key.startsWith(sourceGroupName)) {
                    String newKey = key.replace(sourceGroupName, targetGroupName);
                    updatedProperties.put(newKey, value);
                }
                updatedProperties.put(key, value);
            }
            return updatedProperties;
        }

        return null;
    }

    /**
     * Util method which converts a Set to a String.
     *
     * @param set the Set to convert.
     * @return the String corresponding to the Set.
     */
    protected String convertSetToString(Set<String> set) {
        StringBuffer result = new StringBuffer();
        Iterator<String> groupIterator = set.iterator();
        while (groupIterator.hasNext()) {
            String name = groupIterator.next();
            result.append(name);
            if (groupIterator.hasNext()) {
                result.append(",");
            }
        }
        return result.toString();
    }

    /**
     * Util method which converts a String to a Set.
     *
     * @param string the String to convert.
     * @return the Set corresponding to the String.
     */
    protected Set<String> convertStringToSet(String string) {
        if (string == null)
            return Collections.EMPTY_SET;
        Set<String> result = new TreeSet<String>();
        String[] groupNames = string.split(",");

        if (groupNames != null && groupNames.length > 0) {
            for (String name : groupNames) {
                result.add(name);
            }
        } else {
            result.add(string);
        }
        return result;
    }

    /**
     * A local configuration listener to update the local Hazelcast instance when the configuration changes.
     *
     * @param configurationEvent the local configuration event.
     */
    @Override
    public void configurationEvent(ConfigurationEvent configurationEvent) {
        String pid = configurationEvent.getPid();
        if (pid.equals(Configurations.GROUP)) {
            Map<String,Object> hazelcastGroupConfig = instance.getMap(HAZELCAST_GROUPS_CONFIG);
            try {
                Configuration conf = configurationAdmin.getConfiguration(Configurations.GROUP, null);
                Dictionary<String, Object> properties = conf.getProperties();
                Map<String, Object> updates = getUpdatesForHazelcastMap(properties);
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (!hazelcastGroupConfig.containsKey(key) || hazelcastGroupConfig.get(key) == null || !hazelcastGroupConfig.get(key).equals(value)) {
                        LOGGER.debug("CELLAR HAZELCAST : sending updates to cluster : " + key + " = " + value);
                        if (hazelcastGroupConfig.containsKey(key) && value instanceof Map) {
                            Map<String,Object> newValue = new HashMap((Map) hazelcastGroupConfig.get(key));
                            newValue.putAll((Map<? extends String, ?>) value);
                            hazelcastGroupConfig.put(key, newValue);
                        } else {
                            hazelcastGroupConfig.put(key, value);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("CELLAR HAZELCAST: failed to update cluster group configuration", e);
            }
        }
    }

    /**
     * Invoked when an entry is added.
     *
     * @param entryEvent entry event
     */
    @Override
    public void entryAdded(EntryEvent<String,Object> entryEvent) {
        entryUpdated(entryEvent);
    }

    /**
     * Invoked when an entry is removed.
     *
     * @param entryEvent entry event
     */
    @Override
    public void entryRemoved(EntryEvent<String,Object> entryEvent) {
        entryUpdated(entryEvent);
    }

    /**
     * Invoked when an entry is updated.
     *
     * @param entryEvent entry event
     */
    @Override
    public void entryUpdated(EntryEvent<String,Object> entryEvent) {
        try {
            Configuration conf = configurationAdmin.getConfiguration(Configurations.GROUP, null);
            Dictionary properties = conf.getProperties();
            String key = entryEvent.getKey();
            Object value = entryEvent.getValue();

            if (updatePropertiesFromHazelcastMap(properties, key, value)) {
                LOGGER.debug("CELLAR HAZELCAST: cluster group configuration has been updated, updating local configuration : "+key + " = " +value);
                conf.update(properties);
            }
        } catch (Exception ex) {
            LOGGER.warn("CELLAR HAZELCAST: failed to update local configuration", ex);
        }
    }

    /**
     * Invoked when an entry is evicted.
     *
     * @param entryEvent entry event
     */
    @Override
    public void entryEvicted(EntryEvent<String,Object> entryEvent) {
        entryUpdated(entryEvent);
    }

    @Override
    public void mapCleared(MapEvent mapEvent) {
        // nothing to do
    }

    @Override
    public void mapEvicted(MapEvent mapEvent) {
        // nothing to do
    }

    public HazelcastInstance getInstance() {
        return instance;
    }

    public void setInstance(HazelcastInstance instance) {
        this.instance = instance;
    }

    public BundleContext getBundleContext() {
        return bundleContext;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin;
    }

    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    public EventTransportFactory getEventTransportFactory() {
        return eventTransportFactory;
    }

    public void setEventTransportFactory(EventTransportFactory eventTransportFactory) {
        this.eventTransportFactory = eventTransportFactory;
    }

    public CombinedClassLoader getCombinedClassLoader() {
        return combinedClassLoader;
    }

    public void setCombinedClassLoader(CombinedClassLoader combinedClassLoader) {
        this.combinedClassLoader = combinedClassLoader;
    }

}
