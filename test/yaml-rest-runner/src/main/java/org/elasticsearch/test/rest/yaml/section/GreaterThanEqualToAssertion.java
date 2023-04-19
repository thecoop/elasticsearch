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
import org.hamcrest.Matcher;

import java.io.IOException;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Represents a gte assert section:
 *
 *   - gte:     { fields._ttl: 0 }
 */
public class GreaterThanEqualToAssertion extends ComparableAssertion {
    public static GreaterThanEqualToAssertion parse(XContentParser parser) throws IOException {
        XContentLocation location = parser.getTokenLocation();
        Map.Entry<String, Object> stringObjectTuple = ParserUtils.parseTuple(parser);
        if ((stringObjectTuple.getValue() instanceof Comparable) == false) {
            throw new IllegalArgumentException(
                "gte section can only be used with objects that support natural ordering, found "
                    + stringObjectTuple.getValue().getClass().getSimpleName()
            );
        }
        return new GreaterThanEqualToAssertion(location, stringObjectTuple.getKey(), stringObjectTuple.getValue());
    }

    private static final Logger logger = LogManager.getLogger(GreaterThanEqualToAssertion.class);

    public GreaterThanEqualToAssertion(XContentLocation location, String field, Object expectedValue) {
        super(location, field, expectedValue);
    }

    @Override
    protected Logger logger() {
        return logger;
    }

    @Override
    protected String comparisonName() {
        return "greater than or equal to";
    }

    @Override
    protected <T extends Comparable<T>> Matcher<T> matcher(T c) {
        return greaterThanOrEqualTo(c);
    }
}
