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

final class H3SpecResult {
    private final String name;
    private final String rfcSection;
    private final boolean failure;

    H3SpecResult(final String name, final String rfcSection, boolean failure) {
        this.name = name;
        this.rfcSection = rfcSection;
        this.failure = failure;
    }

    String rfcSection() {
        return rfcSection;
    }

    String name() {
        return name;
    }

    boolean isFailure() {
        return failure;
    }
}
