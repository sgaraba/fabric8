/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
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
package org.fusesource.fabric.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.karaf.admin.management.AdminServiceMBean;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.ContainerProvider;
import org.fusesource.fabric.api.CreateContainerChildMetadata;
import org.fusesource.fabric.api.CreateContainerChildOptions;
import org.fusesource.fabric.internal.FabricConstants;
import org.fusesource.fabric.utils.PortUtils;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.fabric.zookeeper.ZkDefs;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils;


import static org.fusesource.fabric.utils.PortUtils.mapPortToRange;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_ADDRESS;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_IP;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_RESOLVER;


public class ChildContainerProvider implements ContainerProvider<CreateContainerChildOptions, CreateContainerChildMetadata> {

    final FabricServiceImpl service;
    Set<Integer> usedPorts = new LinkedHashSet<Integer>();

    public ChildContainerProvider(FabricServiceImpl service) {
        this.service = service;
    }

    @Override
    public Set<CreateContainerChildMetadata> create(final CreateContainerChildOptions options) throws Exception {
        final Set<CreateContainerChildMetadata> result = new LinkedHashSet<CreateContainerChildMetadata>();
        final String parentName = options.getParent();
        final Container parent = service.getContainer(parentName);
        ContainerTemplate containerTemplate = service.getContainerTemplate(parent, options.getJmxUser(), options.getJmxPassword());

        //Retrieve the credentials from the URI if available.
        if (options.getProviderURI() != null && options.getProviderURI().getUserInfo() != null) {
            String ui = options.getProviderURI().getUserInfo();
            String[] uip = ui != null ? ui.split(":") : null;
            if (uip != null) {
                containerTemplate.setLogin(uip[0]);
                containerTemplate.setPassword(uip[1]);
            }
        }

        containerTemplate.execute(new ContainerTemplate.AdminServiceCallback<Object>() {
            public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                StringBuilder jvmOptsBuilder = new StringBuilder();

                jvmOptsBuilder.append("-server -Dcom.sun.management.jmxremote")
                        .append(options.getZookeeperUrl() != null ? " -Dzookeeper.url=\"" + options.getZookeeperUrl() + "\"" : "")
                        .append(options.getZookeeperPassword() != null ? " -Dzookeeper.password=\"" + options.getZookeeperPassword() + "\"" : "");

                if (options.getJvmOpts() == null || !options.getJvmOpts().contains("-Xmx")) {
                    jvmOptsBuilder.append(" -Xmx512m");
                }
                if (options.isEnsembleServer()) {
                    jvmOptsBuilder.append(" ").append(ENSEMBLE_SERVER_CONTAINER);
                }

                if (options.getJvmOpts() != null && !options.getJvmOpts().isEmpty()) {
                    jvmOptsBuilder.append(" ").append(options.getJvmOpts());
                }

                String features = "fabric-agent";
                String featuresUrls = "mvn:org.fusesource.fabric/fuse-fabric/" + FabricConstants.FABRIC_VERSION + "/xml/features";
                String originalName = new String(options.getName());
                usedPorts.addAll(getContainerUsedPorts(parent));

                for (int i = 1; i <= options.getNumber(); i++) {
                    String containerName;
                    if (options.getNumber() > 1) {
                        containerName = originalName + i;
                    } else {
                        containerName = originalName;
                    }
                    CreateContainerChildMetadata metadata = new CreateContainerChildMetadata();

                    metadata.setCreateOptions(options);
                    metadata.setContainerName(containerName);
                    int minimumPort = parent.getMinimumPort();
                    int maximumPort = parent.getMaximumPort();

                    ZooKeeperUtils.set(service.getZooKeeper(), ZkPath.CONTAINER_PORT_MIN.getPath(containerName), String.valueOf(minimumPort));
                    ZooKeeperUtils.set(service.getZooKeeper(), ZkPath.CONTAINER_PORT_MAX.getPath(containerName), String.valueOf(maximumPort));

                    inheritAddresses(service.getZooKeeper(), parentName, containerName, options);

                    //This is not enough as it will not work if children has been created and then deleted.
                    //The admin service should be responsible for allocating ports
                    int sshPort = mapPortToRange(8101 + i, minimumPort, maximumPort);
                    while (usedPorts.contains(sshPort)) {
                        sshPort++;
                    }
                    usedPorts.add(sshPort);


                    int rmiServerPort = mapPortToRange(44444 + i, minimumPort, maximumPort);
                    while (usedPorts.contains(rmiServerPort)) {
                        rmiServerPort++;
                    }
                    usedPorts.add(rmiServerPort);
                    int rmiRegistryPort = mapPortToRange(1099 + i, minimumPort, maximumPort);
                    while (usedPorts.contains(rmiRegistryPort)) {
                        rmiRegistryPort++;
                    }
                    usedPorts.add(rmiRegistryPort);

                    try {
                        adminService.createInstance(containerName,
                                sshPort,
                                rmiServerPort,
                                rmiRegistryPort, null, jvmOptsBuilder.toString(), features, featuresUrls);
                        adminService.startInstance(containerName, null);
                    } catch (Throwable t) {
                        metadata.setFailure(t);
                    }
                    result.add(metadata);
                }
                return null;
            }
        });
        return result;
    }

    @Override
    public void start(final Container container) {
        getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
            public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                adminService.startInstance(container.getId(), null);
                return null;
            }
        });
    }

    @Override
    public void stop(final Container container) {
        getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
            public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                adminService.stopInstance(container.getId());
                return null;
            }
        });
    }

    @Override
    public void destroy(final Container container) {
        getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
            public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                try {
                    adminService.stopInstance(container.getId());
                } catch (Exception e) {
                    // Ignore if the container is stopped
                    if (container.isAlive()) {
                        throw e;
                    }
                }
                adminService.destroyInstance(container.getId());
                return null;
            }
        });
    }

    /**
     * Returns the {@link ContainerTemplate} of the parent of the specified child {@link Container}.
     *
     * @param container
     * @return
     */
    protected ContainerTemplate getContainerTemplateForChild(Container container) {
        CreateContainerChildOptions options = (CreateContainerChildOptions) container.getMetadata().getCreateOptions();
        return new ContainerTemplate(container.getParent(), options.getJmxUser(), options.getJmxPassword(), false);
    }

    /**
     * Extracts the used ports of the {@link Container} and its children.
     *
     * @param container
     * @return
     */
    private Set<Integer> getContainerUsedPorts(Container container) {
        Set<Integer> usedPorts = new LinkedHashSet<Integer>();
        usedPorts.add(getSshPort(container));
        usedPorts.addAll(getRmiPorts(container));
        if (container.getChildren() != null) {
            for (Container child : container.getChildren()) {
                usedPorts.addAll(getContainerUsedPorts(child));
            }
        }
        return usedPorts;
    }

    /**
     * Extracts the ssh Port of the {@link Container}.
     *
     * @param container
     * @return
     */
    private int getSshPort(Container container) {
        String sshUrl = container.getSshUrl();
        int sshPort = 0;
        if (sshUrl != null) {
            sshPort = PortUtils.extractPort(sshUrl);
        }
        return sshPort;
    }

    /**
     * Extracts the rmi ports of the {@link Container}.
     *
     * @param container
     * @return
     */
    private Set<Integer> getRmiPorts(Container container) {
        Set<Integer> rmiPorts = new LinkedHashSet<Integer>();
        String jmxUrl = container.getJmxUrl();
        String address = container.getIp();
        // maybe container didn't register yet, but ports should be already added, so it's OK
        if (jmxUrl != null && address != null) {
            Pattern pattern = Pattern.compile(address + ":\\d{1,5}");
            Matcher mather = pattern.matcher(jmxUrl);
            while (mather.find()) {
                String socketAddress = mather.group();
                rmiPorts.add(PortUtils.extractPort(socketAddress));
            }
        }
        return rmiPorts;
    }

    /**
     * Links child container resolver and addresses to its parents resolver and addresses.
     *
     * @param zooKeeper
     * @param parent
     * @param name
     * @param options
     * @throws KeeperException
     * @throws InterruptedException
     */
    private void inheritAddresses(IZKClient zooKeeper, String parent, String name, CreateContainerChildOptions options) throws KeeperException, InterruptedException {
        //Link to the addresses from the parent container.
        for (String resolver : ZkDefs.VALID_RESOLVERS) {
            zooKeeper.createOrSetWithParents(CONTAINER_ADDRESS.getPath(name, resolver), "${zk:" + parent + "/" + resolver + "}", CreateMode.PERSISTENT);
        }

        if (options.getResolver() != null) {
            zooKeeper.createOrSetWithParents(CONTAINER_RESOLVER.getPath(name), options.getResolver(), CreateMode.PERSISTENT);
        } else {
            zooKeeper.createOrSetWithParents(CONTAINER_RESOLVER.getPath(name), "${zk:" + parent + "/resolver}", CreateMode.PERSISTENT);
        }

        zooKeeper.createOrSetWithParents(CONTAINER_RESOLVER.getPath(name), "${zk:" + parent + "/resolver}", CreateMode.PERSISTENT);
        zooKeeper.createOrSetWithParents(CONTAINER_IP.getPath(name), "${zk:" + name + "/resolver}", CreateMode.PERSISTENT);
    }
}
