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
import java.util.stream.Collectors;

import static org.elasticsearch.test.MapMatcher.matchesMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class ContainsAssertion extends Assertion {
    public static ContainsAssertion parse(XContentParser parser) throws IOException {
        XContentLocation location = parser.getTokenLocation();
        Map.Entry<String, Object> stringObjectTuple = ParserUtils.parseTuple(parser);
        return new ContainsAssertion(location, stringObjectTuple.getKey(), stringObjectTuple.getValue());
    }

    private static final Logger logger = LogManager.getLogger(ContainsAssertion.class);

    public ContainsAssertion(XContentLocation location, String field, Object expectedValue) {
        super(location, field, expectedValue);
    }

    @Override
    protected void doAssert(Object actualValue, Object expectedValue) {
        // add support for matching objects ({a:b}) against list of objects ([ {a:b, c:d} ])
        if (expectedValue instanceof Map<?, ?> expectedMap && actualValue instanceof List<?> actualList) {
            logger.trace("assert that [{}] contains [{}]", actualValue, expectedValue);
            @SuppressWarnings("SuspiciousMethodCalls")
            List<Map<?, ?>> actualValues = actualList.stream()
                .filter(each -> each instanceof Map)
                .map((each -> (Map<?, ?>) each))
                .filter(each -> each.keySet().containsAll(expectedMap.keySet()))
                .collect(Collectors.toList());
            assertThat(
                getField()
                    + " expected to be a list with at least one object that has keys: "
                    + expectedMap.keySet()
                    + " but it was "
                    + actualList,
                actualValues,
                is(not(empty()))
            );
            assertThat(actualValues, hasItem(matchesMap(expectedMap).extraOk()));
        } else if (expectedValue instanceof String ex && actualValue instanceof String ac) {
            assertThat(ac, containsString(ex));
        } else {
            fail("'contains' only supports checking an object against a list of objects or a string against a string");
        }
    }
}
