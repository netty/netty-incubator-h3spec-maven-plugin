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

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.codehaus.plexus.util.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class H3Spec {

    private static final Pattern PATTERN = Pattern.compile(" {2}(MUST.+) (\\[.+])( FAILED.*)+");

    private H3Spec() { }

    static H3SpecResult execute(File targetDirectory, Config config)
            throws IOException {
        File h3spec = extractH3Spec(targetDirectory);
        Executor exec = new DefaultExecutor();

        // Streams used to redirect output too.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        PumpStreamHandler psh = new PumpStreamHandler(out, err, System.in);
        exec.setStreamHandler(psh);
        exec.setExitValues(new int[] { 0, 1 });

        psh.start();
        int ret = exec.execute(buildCommandLine(h3spec, config));
        if (ret == 0 || ret == 1) {
            StringBuilder sb = new StringBuilder();
            List<H3SpecCaseResult> results = new ArrayList<>();
            // We check STDIN and STDERR as maven surefire may redirect one to the other.
            parseH3SpecResult(err, results, sb);
            parseH3SpecResult(out, results, sb);

            return new H3SpecResult(results, sb.toString());
        }
        psh.stop();

        return new H3SpecResult(Collections.emptyList(), null);
    }

    private static void parseH3SpecResult(ByteArrayOutputStream out, List<H3SpecCaseResult> results,
                                          StringBuilder failureDetails) {
        String[] outLines = out.toString().split("\n");
        boolean failure = false;
        for (String outLine: outLines) {
            if (failure) {
                failureDetails.append(outLine).append("\n");
            } else {
                Matcher matcher = PATTERN.matcher(outLine);
                if (matcher.matches()) {
                    results.add(new H3SpecCaseResult(matcher.group(1), matcher.group(2), matcher.group(3) != null));
                } else if (outLine.startsWith("Failures:")) {
                    failure = true;
                    failureDetails.append(outLine).append("\n");
                }
            }
        }
    }

    private static CommandLine buildCommandLine(File h3spec, Config config) {
        CommandLine cmd = new CommandLine(h3spec);
        cmd.addArgument(config.host).addArgument(String.valueOf(config.port));

        // Exclude some tests if needed.
        for (String exclude: config.excludeSpecs) {
            cmd.addArguments("--skip='" + exclude + "'");
        }
        if (config.debug) {
            cmd.addArguments("--debug");
        }
        if (config.timeoutMillis >= 0) {
            cmd.addArguments("--timeout='" + config.timeoutMillis + "'");
        }
        return cmd;
    }

    private static File extractH3Spec(final File targetDirectory) throws IOException {
        URL h3SpecInJar = H3Spec.class.getResource(getH3SpecPath());
        assert h3SpecInJar != null;
        File h3Spec = new File(targetDirectory, new File(h3SpecInJar.getPath()).getName());
        FileUtils.copyURLToFile(h3SpecInJar, h3Spec);

        if (!h3Spec.setExecutable(true)) {
            throw new RuntimeException("Can't set h3spec as executable");
        }
        return h3Spec;
    }

    private static String getH3SpecPath() {
        String os = System.getProperty("os.name").toLowerCase();
        StringBuilder fileNameBuilder = new StringBuilder();
        fileNameBuilder.append("/h3spec/");
        // See https://github.com/kazu-yamamoto/h3spec/releases
        if (os.contains("linux")) {
            fileNameBuilder.append("h3spec-linux-x86_64");
        } else if (os.contains("mac")) {
            fileNameBuilder.append("h3spec-mac-x86_64");
        } else {
            throw new IllegalStateException("This OS is not supported.");
        }
        return fileNameBuilder.toString();
    }

    static final class Config {
        private final String host;
        private final int port;
        private final Iterable<String> excludeSpecs;
        private final long timeoutMillis;
        private final boolean debug;

        Config(String host, int port, Iterable<String> excludeSpecs, long timeoutMillis, boolean debug) {
            this.host = host;
            this.port = port;
            this.excludeSpecs = excludeSpecs;
            this.timeoutMillis = timeoutMillis;
            this.debug = debug;
        }
    }
}
