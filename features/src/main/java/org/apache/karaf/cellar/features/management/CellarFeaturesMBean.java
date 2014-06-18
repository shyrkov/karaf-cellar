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
package org.apache.karaf.cellar.features.management;

import javax.management.openmbean.TabularData;
import java.util.List;

/**
 * Describe the operations and attributes on the Cellar Features MBean.
 */
public interface CellarFeaturesMBean {

    /**
     * Add a features repository in a cluster group.
     *
     * @param group the cluster group name.
     * @param url the features repository URL.
     * @throws Exception in case of add failure.
     */
    void addRepository(String group, String url) throws Exception;

    /**
     * Add a features repository in a cluster group.
     *
     * @param group the cluster group name.
     * @param url the features repository URL.
     * @param install true to install all features contained in the repository, false else.
     * @throws Exception in case of add failure.
     */
    void addRepository(String group, String url, boolean install) throws Exception;

    /**
     * Remove a features repository from a cluster group.
     *
     * @param group the cluster group name.
     * @param url the features repository URL.
     * @throws Exception in case of remove failure.
     */
    void removeRepository(String group, String url) throws Exception;

    /**
     * Remove a features repository from a cluster group, eventually uninstalling all features described in the repository.
     *
     * @param group the cluster group name.
     * @param url the features repository URL.
     * @param uninstall true to uninstall all features described in the repository URL.
     * @throws Exception
     */
    void removeRepository(String group, String url, boolean uninstall) throws Exception;

    /**
     * Install a feature in a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @throws Exception in case of install failure.
     */
    void installFeature(String group, String name) throws Exception;

    /**
     * Install a feature in a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param noClean true to not uninstall the bundles if the installation of the feature failed, false else.
     * @param noRefresh true to not automatically refresh the bundles, false else.
     * @throws Exception in case of install failure.
     */
    void installFeature(String group, String name, boolean noClean, boolean noRefresh) throws Exception;

    /**
     * Install a feature in a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param noClean true to not uninstall the bundles if the installation of the feature failed, false else.
     * @param noRefresh true to not automatically refresh the bundles, false else.
     * @param noStart true to not automatically start the bundles, false else.
     * @throws Exception in case of install failure.
     */
    void installFeature(String group, String name, boolean noClean, boolean noRefresh, boolean noStart) throws Exception;

    /**
     * Install a feature in a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param version the feature version.
     * @throws Exception in case of install failure.
     */
    void installFeature(String group, String name, String version) throws Exception;

    /**
     * Install a feature in a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param version the feature version.
     * @param noClean true to not uninstall the bundles if the installation of the feature failed, false else.
     * @param noRefresh true to not automatically refresh the bundles, false else.
     * @throws Exception in case of install failure.
     */
    void installFeature(String group, String name, String version, boolean noClean, boolean noRefresh) throws Exception;

    /**
     * Install a feature in a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param version the feature version.
     * @param noClean true to not uninstall the bundles if the installation of the feature failued, false else.
     * @param noRefresh true to not automatically refresh the bundles, false else.
     * @param noStart true to not automatically start the bundles, false else.
     * @throws Exception in case of install failure.
     */
    void installFeature(String group, String name, String version, boolean noClean, boolean noRefresh, boolean noStart) throws Exception;

    /**
     * Uninstall a feature from a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @throws Exception in case of uninstall failure.
     */
    void uninstallFeature(String group, String name) throws Exception;

    /**
     * Uninstall a feature from a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param noRefresh true to not automatically refresh the bundles, false else.
     * @throws Exception in case of uninstall failure.
     */
    void uninstallFeature(String group, String name, boolean noRefresh) throws Exception;

    /**
     * Uninstall a feature from a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param version the feature version.
     * @throws Exception in case of uninstall failure.
     */
    void uninstallFeature(String group, String name, String version) throws Exception;

    /**
     * Uninstall a feature from a cluster group.
     *
     * @param group the cluster group name.
     * @param name the feature name.
     * @param version the feature version.
     * @param noRefresh true to not automatically refresh the bundles, false else.
     * @throws Exception in case of uninstall failure.
     */
    void uninstallFeature(String group, String name, String version, boolean noRefresh) throws Exception;

    /**
     * Get the list of features repository URLs in a cluster group.
     *
     * @param group the cluster group name.
     * @return the list of features repository URLs.
     * @throws Exception in case of retrieval failure.
     */
    List<String> getRepositories(String group) throws Exception;

    /**
     * Get the list of features in a cluster group.
     *
     * @param group the cluster group name.
     * @return the list of features.
     * @throws Exception in case of retrieval failure.
     */
    TabularData getFeatures(String group) throws Exception;

}
