/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2020] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.microprofile.openapi.impl;

import static java.util.stream.Collectors.toSet;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.sun.enterprise.v3.server.ApplicationLifecycle;
import com.sun.enterprise.v3.services.impl.GrizzlyService;

import fish.payara.microprofile.openapi.impl.model.util.ModelUtils;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.classmodel.reflect.Parser;
import org.glassfish.hk2.classmodel.reflect.Type;
import org.glassfish.hk2.classmodel.reflect.Types;
import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.internal.deployment.analysis.StructuredDeploymentTracing;

import fish.payara.microprofile.openapi.impl.config.OpenApiConfiguration;
import fish.payara.microprofile.openapi.impl.model.OpenAPIImpl;
import fish.payara.microprofile.openapi.impl.processor.ApplicationProcessor;
import fish.payara.microprofile.openapi.impl.processor.BaseProcessor;
import fish.payara.microprofile.openapi.impl.processor.ConfigPropertyProcessor;
import fish.payara.microprofile.openapi.impl.processor.FileProcessor;
import fish.payara.microprofile.openapi.impl.processor.FilterProcessor;
import fish.payara.microprofile.openapi.impl.processor.ModelReaderProcessor;

public class OpenAPISupplier implements Supplier<OpenAPI> {

    private final OpenApiConfiguration config;
    private final String applicationId;
    private final String contextRoot;
    private final ReadableArchive archive;
    private final ClassLoader classLoader;

    private volatile OpenAPI document;

    private boolean enabled;

    public OpenAPISupplier(String applicationId, String contextRoot,
            ReadableArchive archive, ClassLoader classLoader) {
        this.config = new OpenApiConfiguration(classLoader);
        this.applicationId = applicationId;
        this.contextRoot = contextRoot;
        this.archive = archive;
        this.classLoader = classLoader;
        this.enabled = true;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized OpenAPI get() {
        if (this.document != null) {
            return this.document;
        }
        if (!enabled) {
            return null;
        }

        try {
            final Parser parser = Globals.get(ApplicationLifecycle.class).getDeployableParser(
                    archive,
                    true,
                    true,
                    StructuredDeploymentTracing.create(applicationId),
                    Logger.getLogger(OpenApiService.class.getName())
            );
            final Types types = parser.getContext().getTypes();

            OpenAPI doc = new OpenAPIImpl();
            try {
                final List<URL> baseURLs = getServerURL(contextRoot);
                doc = new ConfigPropertyProcessor().process(doc, config);
                doc = new ModelReaderProcessor().process(doc, config);
                doc = new FileProcessor(classLoader).process(doc, config);
                doc = new ApplicationProcessor(
                        types,
                        filterTypes(archive, config, types),
                        classLoader
                ).process(doc, config);
                if (doc.getPaths() != null && !doc.getPaths().getPathItems().isEmpty()) {
                    ((OpenAPIImpl) doc).setEndpoints(ModelUtils.buildEndpoints(null, contextRoot,
                            doc.getPaths().getPathItems().keySet()));
                }
                doc = new BaseProcessor(baseURLs).process(doc, config);
                doc = new FilterProcessor().process(doc, config);
            } finally {
                this.document = doc;
            }

            return this.document;
        } catch (Exception ex) {
            throw new RuntimeException("An error occurred while creating the OpenAPI document.", ex);
        }
    }
    

    /**
     * @return a list of all classes in the archive.
     */
    private Set<Type> filterTypes(ReadableArchive archive, OpenApiConfiguration config, Types hk2Types) {
        Set<Type> types = new HashSet<>(filterLibTypes(config, hk2Types, archive));
        types.addAll(
                Collections.list(archive.entries()).stream()
                        // Only use the classes
                        .filter(clazz -> clazz.endsWith(".class"))
                        // Remove the WEB-INF/classes and return the proper class name format
                        .map(clazz -> clazz.replaceAll("WEB-INF/classes/", "").replace("/", ".").replace(".class", ""))
                        // Fetch class type
                        .map(clazz -> hk2Types.getBy(clazz))
                        // Don't return null classes
                        .filter(Objects::nonNull)
                        .collect(toSet())
        );
        return config == null ? types : config.getValidClasses(types);
    }

    private Set<Type> filterLibTypes(
            OpenApiConfiguration config,
            Types hk2Types,
            ReadableArchive archive) {
        Set<Type> types = new HashSet<>();
        if (config != null && config.getScanLib()) {
            Enumeration<String> subArchiveItr = archive.entries();
            while (subArchiveItr.hasMoreElements()) {
                String subArchiveName = subArchiveItr.nextElement();
                if (subArchiveName.startsWith("WEB-INF/lib/") && subArchiveName.endsWith(".jar")) {
                    try {
                        ReadableArchive subArchive = archive.getSubArchive(subArchiveName);
                        types.addAll(
                                Collections.list(subArchive.entries())
                                        .stream()
                                        // Only use the classes
                                        .filter(clazz -> clazz.endsWith(".class"))
                                        // return the proper class name format
                                        .map(clazz -> clazz.replace("/", ".").replace(".class", ""))
                                        // Fetch class type
                                        .map(clazz -> hk2Types.getBy(clazz))
                                        // Don't return null classes
                                        .filter(Objects::nonNull)
                                        .collect(toSet())
                        );
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        }
        return types;
    }

    private List<URL> getServerURL(String contextRoot) {
        List<URL> result = new ArrayList<>();
        ServerContext context = Globals.get(ServerContext.class);

        String hostName;
        try {
            hostName = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException ex) {
            hostName = "localhost";
        }

        String instanceType = Globals.get(ServerEnvironment.class).getRuntimeType().toString();
        List<Integer> httpPorts = new ArrayList<>();
        List<Integer> httpsPorts = new ArrayList<>();
        List<NetworkListener> networkListeners = context.getConfigBean().getConfig().getNetworkConfig().getNetworkListeners().getNetworkListener();
        String adminListener = context.getConfigBean().getConfig().getAdminListener().getName();
        networkListeners
                .stream()
                .filter(networkListener -> Boolean.parseBoolean(networkListener.getEnabled()))
                .forEach(networkListener -> {

                    int port;
                    try {
                        // get the dynamic config port
                        port = Globals.get(GrizzlyService.class).getRealPort(networkListener);
                    } catch (MultiException ex) {
                        // get the port in the domain xml
                        port = Integer.parseInt(networkListener.getPort());
                    }

                    // Check if this listener is using HTTP or HTTPS
                    boolean securityEnabled = Boolean.parseBoolean(networkListener.findProtocol().getSecurityEnabled());
                    List<Integer> ports = securityEnabled ? httpsPorts : httpPorts;

                    // If this listener isn't the admin listener, it must be an HTTP/HTTPS listener
                    if (!networkListener.getName().equals(adminListener)) {
                        ports.add(port);
                    } else if (instanceType.equals("MICRO")) {
                        // micro instances can use the admin listener as both an admin and HTTP/HTTPS port
                        ports.add(port);
                    }
                });

        for (Integer httpPort : httpPorts) {
            try {
                result.add(new URL("http", hostName, httpPort, contextRoot));
            } catch (MalformedURLException ex) {
                // ignore
            }
        }
        for (Integer httpsPort : httpsPorts) {
            try {
                result.add(new URL("https", hostName, httpsPort, contextRoot));
            } catch (MalformedURLException ex) {
                // ignore
            }
        }
        return result;
    }

}
