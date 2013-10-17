/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.osgi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerFactory;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.JMXContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi deployable container
 *
 * @author thomas.diesler@jboss.com
 */
public abstract class AbstractEmbeddedDeployableContainer<T extends OSGiContainerConfiguration> implements DeployableContainer<T> {

    final Logger log = LoggerFactory.getLogger(AbstractEmbeddedDeployableContainer.class.getPackage().getName());

    private Framework framework;
    private BundleContext syscontext;
    private MBeanServerConnection mbeanServer;
    private OSGiContainerConfiguration configuration;

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("jmx-osgi");
    }

    @Override
    public void setup(T configuration) {
        this.configuration = configuration;
        this.framework = createFramework(configuration);
        this.mbeanServer = getMBeanServerConnection();
    }

    protected OSGiContainerConfiguration getContainerConfiguration() {
        return configuration;
    }

    protected Framework createFramework(T conf) {
        FrameworkFactory factory = conf.getFrameworkFactory();
        if (factory == null)
            throw new IllegalStateException("Cannot obtain " + FrameworkFactory.class.getName());
        Map<String, String> config = conf.getFrameworkConfiguration();
        return factory.newFramework(config);
    }

    protected Framework getFramework() {
        return framework;
    }

    protected BundleContext startFramework() throws BundleException {
        framework.start();
        return framework.getBundleContext();
    }

    protected void stopFramework() throws BundleException {
        framework.stop();
    }

    protected Bundle installBundle(String location, InputStream inputStream) throws BundleException {
        return syscontext.installBundle(location, inputStream);
    }

    protected void uninstallBundle(Bundle bundle) throws BundleException {
        bundle.uninstall();
    }

    @Override
    public final void start() throws LifecycleException {

        log.debug("Starting OSGi embedded container: " + getClass().getName());

        try {
            syscontext = startFramework();
        } catch (BundleException ex) {
            throw new LifecycleException("Cannot start embedded OSGi Framework", ex);
        }

        installArquillianBundle();

        // Wait for the arquillian-osgi-bundle to become ACTIVE
        final CountDownLatch latch = new CountDownLatch(1);
        BundleTracker<Bundle> tracker = new BundleTracker<Bundle>(syscontext, Bundle.ACTIVE, null) {
            @Override
            public Bundle addingBundle(Bundle bundle, BundleEvent event) {
                super.addingBundle(bundle, event);
                if ("arquillian-osgi-bundle".equals(bundle.getSymbolicName())) {
                    latch.countDown();
                }
                return bundle;
            }

        };
        tracker.open();

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new LifecycleException("Framework startup timeout");
            }
        } catch (InterruptedException ex) {
            throw new LifecycleException("Framework startup interupted", ex);
        }

        log.info("Started OSGi embedded container: " + getClass().getName());
    }

    protected void installArquillianBundle() throws LifecycleException {
        Bundle arqBundle = getInstalledBundle("arquillian-osgi-bundle");
        if (arqBundle == null) {
            try {
                // Note, the bundle does not have an ImplementationVersion, we use the one of the container.
                String arqVersion = AbstractEmbeddedDeployableContainer.class.getPackage().getImplementationVersion();
                if (arqVersion == null) {
                    arqVersion = System.getProperty("arquillian.osgi.version");
                }
                arqBundle = installBundle("org.jboss.arquillian.osgi", "arquillian-osgi-bundle", arqVersion, true);
            } catch (BundleException ex) {
                throw new LifecycleException("Cannot install arquillian-osgi-bundle", ex);
            }
        }
    }

    public BundleContext getSystemContext() {
        return syscontext;
    }

    @Override
    public final void stop() throws LifecycleException {
        try {
            stopFramework();
            framework.waitForStop(3000);
        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception ex) {
            throw new LifecycleException("Cannot stop embedded OSGi Framework", ex);
        } finally {
            syscontext = null;
        }
    }

    @Override
    public ProtocolMetaData deploy(final Archive<?> archive) throws DeploymentException {
        try {
            // Export the bundle bytes
            ZipExporter exporter = archive.as(ZipExporter.class);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            exporter.exportTo(baos);

            String location = archive.getName();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(baos.toByteArray());
            installBundle(location, inputStream);

        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception ex) {
            throw new DeploymentException("Cannot deploy: " + archive, ex);
        }

        return new ProtocolMetaData().addContext(new JMXContext(mbeanServer));
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        try {
            Bundle bundle = syscontext.getBundle(archive.getName());
            if (bundle != null && bundle.getState() != Bundle.UNINSTALLED) {
                uninstallBundle(bundle);
            }
        } catch (BundleException ex) {
            log.warn("Cannot undeploy: " + archive, ex);
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("OSGi does not support Descriptor deployment");
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("OSGi does not support Descriptor deployment");
    }

    private Bundle getInstalledBundle(String symbolicName) {
        for (Bundle aux : syscontext.getBundles()) {
            if (symbolicName.equals(aux.getSymbolicName()))
                return aux;
        }
        return null;
    }

    private Bundle installBundle(String groupId, String artifactId, String version, boolean startBundle) throws BundleException {
        String filespec = groupId + ":" + artifactId + ":jar:" + version;
        File[] resolved = Maven.resolver().resolve(filespec).withoutTransitivity().asFile();
        if (resolved == null || resolved.length == 0)
            throw new BundleException("Cannot obtain maven artifact: " + filespec);

        File bundleFile;
        if (resolved.length == 1) {
            bundleFile = resolved[0];
        } else if (version.endsWith("SNAPSHOT")) {
            // [TODO] process multiple snapshots
            throw new BundleException("Multiple maven artifacts for: " + filespec);
        } else {
            throw new BundleException("Multiple maven artifacts for: " + filespec);
        }

        try {
            Bundle bundle = syscontext.installBundle(bundleFile.toURI().toString());
            if (startBundle == true)
                bundle.start();

            return bundle;
        } catch (BundleException ex) {
            log.error("Cannot install/start bundle: " + bundleFile, ex);
        }
        return null;
    }

    private MBeanServerConnection getMBeanServerConnection() {
        MBeanServer mbeanServer = null;

        ArrayList<MBeanServer> serverArr = MBeanServerFactory.findMBeanServer(null);
        if (serverArr.size() > 1)
            log.warn("Multiple MBeanServer instances: " + serverArr);

        if (serverArr.size() > 0) {
            mbeanServer = serverArr.get(0);
            log.debug("Found MBeanServer: " + mbeanServer.getDefaultDomain());
        }

        if (mbeanServer == null) {
            log.debug("No MBeanServer, create one ...");
            mbeanServer = MBeanServerFactory.createMBeanServer();
        }

        return mbeanServer;
    }
}
