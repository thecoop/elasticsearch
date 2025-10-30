/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.blockloader.docvalues;

import java.util.ArrayList;
import java.util.List;

// TODO move me once we have more of these loaders
class MockWarnings implements Warnings {
    record MockWarning(Class<? extends Exception> exceptionClass, String message) {}

    private final List<MockWarning> warnings = new ArrayList<>();

    @Override
    public void registerException(Class<? extends Exception> exceptionClass, String message) {
        warnings.add(new MockWarning(exceptionClass, message));
    }

    public List<MockWarning> warnings() {
        return warnings;
    }
}
