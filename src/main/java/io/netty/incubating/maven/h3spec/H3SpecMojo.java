/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubating.maven.h3spec;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;


@Mojo(name = "h3spec", defaultPhase = LifecyclePhase.INTEGRATION_TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
public class H3SpecMojo extends AbstractMojo {

    /**
     * The port on which the Server will listen.
     */
    @Parameter(defaultValue = "-1", property = "port", required = true)
    private int port;

    /**
     * A list of cases to exclude during the test. Default is to exclude none.
     */
    @Parameter(property = "excludeSpecs")
    private List<String> excludeSpecs;

    /**
     * The class which is used to startup the Server. It will pass the port in as argument to the main(...) method.
     */
    @Parameter(property = "mainClass", required = true)
    private String mainClass;

    /**
     * Allow to skip execution of plugin
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    /**
     * Allow to skip execution of plugin
     */
    @Parameter(property = "delay", defaultValue = "1000", required = true)
    private long delay;

    @Component
    private MavenProject project;

    @SuppressWarnings("unchecked")
    private ClassLoader getClassLoader() throws MojoExecutionException {
        try {
            List<String> classpathElements = project.getTestClasspathElements();
            classpathElements.add(project.getBuild().getOutputDirectory());
            classpathElements.add(project.getBuild().getTestOutputDirectory());
            URL[] urls = new URL[classpathElements.size()];

            for (int i = 0; i < classpathElements.size(); i++) {
                urls[i] = new File(classpathElements.get(i)).toURI().toURL();
            }
            return new URLClassLoader(urls, getClass().getClassLoader());
        } catch (Exception e) {
            throw new MojoExecutionException("Couldn't create a classloader", e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skip execution of h3spec-maven-plugin");
            return;
        }

        final AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = null;
        try {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                getLog().debug("Unable to detect localhost address, using 127.0.0.1 as fallback");
                host = "127.0.0.1";
            }
            if (port == -1) {
                // Get some random free port
                port = findRandomOpenPortOnAllLocalInterfaces();
            }
            CountDownLatch latch = new CountDownLatch(1);
            runner = new Thread(() -> {
                try {
                    Thread.currentThread().setContextClassLoader(getClassLoader());
                    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(mainClass);
                    Method main = clazz.getMethod("main", String[].class);
                    latch.countDown();
                    main.invoke(null, (Object) new String[] { String.valueOf(port) });
                } catch (Throwable e) {
                    error.set(e);
                    latch.countDown();
                }
            });
            runner.setDaemon(true);
            runner.start();

            try {
                latch.await();

                Throwable cause = error.get();
                if (cause != null) {
                    throw cause;
                }
                try {
                    // wait for a few milliseconds to give the server some time to startup
                    Thread.sleep(delay);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }

                if (excludeSpecs == null) {
                    excludeSpecs = Collections.emptyList();
                }

                File outputDirectory = new File(project.getBuild().getDirectory());
                List<H3SpecResult> results = H3Spec.execute(outputDirectory,
                        port, new HashSet<>(excludeSpecs));

                File reportsDirectory = new File(outputDirectory, "h3spec-reports");
                if (!reportsDirectory.exists()) {
                    getLog().debug("Reports directory " + reportsDirectory.getAbsolutePath() +
                            " does not exist, try creating it...");
                    if (reportsDirectory.mkdirs()) {
                        getLog().debug("Reports directory " + reportsDirectory.getAbsolutePath() +
                                " created.");
                    } else {
                        getLog().debug("Failed to create report directory");
                    }
                }

                File junitFile = new File(reportsDirectory, "TEST-h3spec.xml");
                if (writeJUnitXmlReport(results, junitFile)) {
                    StringBuilder sb = new StringBuilder("\nFailed test cases:\n");
                    for (H3SpecResult result: results) {
                        if (result.isFailure()) {
                            sb.append("\t");
                            sb.append(result.name()).append(" ").append(result.rfcSection());
                            sb.append("\n\n");
                        }
                    }
                    throw new MojoFailureException(sb.toString());
                } else {
                    getLog().info("All test cases passed.");
                }
            } catch (Throwable e) {
                throw new MojoExecutionException("Failure during execution", e);
            }
        } finally {
            if (runner != null) {
                runner.interrupt();
            }
        }
    }

    private Integer findRandomOpenPortOnAllLocalInterfaces() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket(0);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Can't find an open socket", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Can't close server socket.");
                }
            }
        }
    }

    private boolean writeJUnitXmlReport(List<H3SpecResult> results, File junitFile) throws Exception {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory .newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        String className = getClass().getName();
        int failures = 0;
        Document doc = docBuilder.newDocument();
        Element rootElement = doc.createElement("testsuite");
        rootElement.setAttribute("name", className);
        rootElement.setAttribute("tests", Integer.toString(results.size()));
        rootElement.setAttribute("errors", Integer.toString(0));
        rootElement.setAttribute("skipped", Integer.toString(0));

        for (H3SpecResult r: results) {
            Element testcase = doc.createElement("testcase");
            testcase.setAttribute("classname", "H3Spec");
            testcase.setAttribute("name", r.name() + " " + r.rfcSection());

            if (r.isFailure()) {
                Element failure = doc.createElement("failure");
                failure.setAttribute("type", "behaviorMissmatch");
                testcase.appendChild(failure);
                failures++;
            }

            rootElement.appendChild(testcase);
        }
        rootElement.setAttribute("failures", Integer.toString(failures));
        rootElement.setAttribute("time", "0");
        doc.appendChild(rootElement);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(junitFile);
        transformer.transform(source, result);
        return failures > 0;
    }
}
