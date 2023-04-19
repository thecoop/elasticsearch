/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test.rest.yaml.section;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.xcontent.XContentLocation;
import org.hamcrest.Matcher;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

public abstract class ComparableAssertion extends Assertion {

    public ComparableAssertion(XContentLocation location, String field, Object expectedValue) {
        super(location, field, expectedValue);
    }

    protected abstract Logger logger();

    protected abstract String comparisonName();

    protected abstract <T extends Comparable<T>> Matcher<T> matcher(T c);

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected void doAssert(Object actualValue, Object expectedValue) {
        logger().trace("assert that [{}] is {} [{}] (field: [{}])", actualValue, comparisonName(), expectedValue, getField());
        assertThat(
            "value of [" + getField() + "] is not comparable (got [" + safeClass(actualValue) + "])",
            actualValue,
            instanceOf(Comparable.class)
        );
        assertThat(
            "expected value of [" + getField() + "] is not comparable (got [" + expectedValue.getClass() + "])",
            expectedValue,
            instanceOf(Comparable.class)
        );
        if (actualValue instanceof Long && expectedValue instanceof Integer i) {
            expectedValue = i.longValue();
        }
        try {
            assertThat(errorMessage(), (Comparable) actualValue, matcher((Comparable) expectedValue));
        } catch (ClassCastException e) {
            throw new AssertionError("cast error while checking (" + errorMessage() + "): " + e, e);
        }
    }

    private String errorMessage() {
        return "field [" + getField() + "] is not " + comparisonName() + " [" + getExpectedValue() + "]";
    }
}
