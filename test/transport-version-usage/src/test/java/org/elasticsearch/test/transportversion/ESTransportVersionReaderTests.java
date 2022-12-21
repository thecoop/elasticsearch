/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test.transportversion;

import org.elasticsearch.test.ESTestCase;

import java.io.*;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class ESTransportVersionReaderTests extends ESTestCase {

    public void testVersionUsage() throws IOException {
        Class<?> testClass = TestUsageClass.class;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        try (
            PrintStream out = new PrintStream(outBytes);
            InputStream stream = testClass.getResourceAsStream(testClass.getSimpleName() + ".class")
        ) {
            ESTransportVersionReader.outputUsages(stream, out);
            out.flush();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(outBytes.toByteArray())))) {
            String usage = reader.readLine();
            assertThat(usage, equalTo(testClass.getName() + ": " + List.of("V_7_0_0", "V_7_5_0", "V_8_0_0")));
        }
    }
}
