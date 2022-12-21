/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test.transportversion;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.util.Arrays;

class TestUsageClass {

    TestUsageClass(StreamInput input) {
        // reference some things
        Arrays.asList(Version.V_7_0_0, Version.V_7_5_0);
    }

    public void writeTo(StreamOutput output) {
        // reference some things
        Arrays.asList(Version.V_7_0_0, Version.V_8_0_0);
    }
}
