/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import org.elasticsearch.test.ESTestCase;
import org.junit.Ignore;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.theInstance;

public class FieldStorageTest extends ESTestCase {

    public void testBasicPutGet() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b", "c");

        assertThat(s.getField("a", "b", "c").toList(), contains("foo"));
        assertThat(s.getCtx("a", "b", "c"), equalTo(Optional.of("foo")));
    }

    public void testPrefixFieldAccess() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b.c");

        assertThat(s.getField("a", "b", "c").toList(), contains("foo"));
        assertThat(s.getCtx("a", "b.c"), equalTo(Optional.of("foo")));
        assertThat(s.getCtx("a", "b", "c"), is(Optional.empty()));
    }

    public void testPrefixCombination() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b.c");
        s.put("bar", "a.b", "c");

        assertThat(s.getField("a", "b", "c").toList(), contains("foo", "bar"));
        assertThat(s.getCtx("a", "b", "c"), is(Optional.empty()));
        assertThat(s.getCtx("a", "b.c"), equalTo(Optional.of("foo")));
        assertThat(s.getCtx("a.b", "c"), equalTo(Optional.of("bar")));
    }

    public void testMultiHomePutGet() {
        FieldStorage s = new FieldStorage();
        Map<String, String> data = Map.of("foo", "foo", "bar", "bar");
        s.put(data, "a", "b");
        s.put(data, "a", "c");

        assertThat(s.getCtx("a", "b").get(), theInstance(data));
        assertThat(s.getCtx("a", "c").get(), theInstance(data));
    }

    @Ignore
    public void testValueAndNestedField() {
        FieldStorage s = new FieldStorage();
        s.put(10, "value");
        s.put(15, "value", "max");
        s.put(5, "value", "min");

        // what does this return?
        s.getField("value");
    }
}
