/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.arquillian.kubernetes.enricher;

import io.fabric8.arquillian.kubernetes.Session;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import static io.fabric8.arquillian.kubernetes.enricher.EnricherUtils.getLabels;
import static io.fabric8.arquillian.kubernetes.enricher.EnricherUtils.getPodName;
import static io.fabric8.utils.Lists.notNullList;

/**
 * A {@link org.jboss.arquillian.test.spi.enricher.resource.ResourceProvider} for {@link io.fabric8.kubernetes.api.model.PodList}.
 * It refers to replication controllers that have been created during the current session.
 */
public class PodResourceProvider implements ResourceProvider {

    @Inject
    private Instance<KubernetesClient> clientInstance;

    @Inject
    private Instance<Session> sessionInstance;

    @Override
    public boolean canProvide(Class<?> type) {
        return Pod.class.isAssignableFrom(type);
    }

    @Override
    public Object lookup(ArquillianResource resource, Annotation... qualifiers) {
        KubernetesClient client = this.clientInstance.get();
        Session session = sessionInstance.get();
        String name = getPodName(qualifiers);
        if (name != null) {
            return client.pods().inNamespace(session.getNamespace()).withName(name).get();
        }

        // Gets the first pod found that matches the labels.
        Map<String, String> labels = getLabels(qualifiers);
        PodList list = client.pods().inNamespace(session.getNamespace()).withLabels(labels).list();
        List<Pod> pods = notNullList(list.getItems());
        if( !pods.isEmpty() ) {
            return pods.get(0);
        }

        return null;
    }
}
