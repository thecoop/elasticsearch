/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.test.rest.yaml.section;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

/**
 * Represents a length assert section:
 * <p>
 * - length:   { hits.hits: 1  }
 */
public class LengthAssertion extends Assertion {
    public static LengthAssertion parse(XContentParser parser) throws IOException {
        XContentLocation location = parser.getTokenLocation();
        Map.Entry<String, Object> stringObjectTuple = ParserUtils.parseTuple(parser);
        assert stringObjectTuple.getValue() != null;
        int value;
        if (stringObjectTuple.getValue() instanceof Number n) {
            value = n.intValue();
        } else {
            try {
                value = Integer.parseInt(stringObjectTuple.getValue().toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("length is not a valid number", e);
            }
        }
        return new LengthAssertion(location, stringObjectTuple.getKey(), value);
    }

    private static final Logger logger = LogManager.getLogger(LengthAssertion.class);

    public LengthAssertion(XContentLocation location, String field, Object expectedValue) {
        super(location, field, expectedValue);
    }

    @Override
    protected void doAssert(Object actualValue, Object expectedValue) {
        logger.trace("assert that [{}] has length [{}] (field: [{}])", actualValue, expectedValue, getField());
        assertThat(
            "expected value of [" + getField() + "] is not numeric (got [" + expectedValue.getClass() + "]",
            expectedValue,
            instanceOf(Number.class)
        );
        int length = ((Number) expectedValue).intValue();
        if (actualValue instanceof String s) {
            assertThat(errorMessage(), s, hasLength(length));
        } else if (actualValue instanceof List<?> l) {
            assertThat(errorMessage(), l, hasSize(length));
        } else if (actualValue instanceof Map<?, ?> m) {
            assertThat(errorMessage(), m, aMapWithSize(length));
        } else {
            throw new UnsupportedOperationException("value is of unsupported type [" + safeClass(actualValue) + "]");
        }
    }

    private String errorMessage() {
        return "field [" + getField() + "] doesn't have length [" + getExpectedValue() + "]";
    }
}
