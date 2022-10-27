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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
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
        s.put("bar", "a.b", "c");
        s.put("foo", "a", "b.c");
        s.put("baz", "a.b.c");

        assertThat(s.getField("a", "b", "c").toList(), containsInAnyOrder("foo", "bar", "baz"));
        assertThat(s.getCtx("a", "b", "c"), is(Optional.empty()));
        assertThat(s.getCtx("a", "b.c"), equalTo(Optional.of("foo")));
        assertThat(s.getCtx("a.b", "c"), equalTo(Optional.of("bar")));
        assertThat(s.getCtx("a.b.c"), equalTo(Optional.of("baz")));
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

    public void testNestedCtxMap() {
        FieldStorage s = new FieldStorage();
        s.put("bar", "a.b", "c");
        s.put("foo", "a", "b.c");
        s.put("baz", "a.b.c");
        s.put("qux", "a", "b", "c");

        Object a = s.getCtxMap("a");
        assertThat(a, instanceOf(Map.class));
        Map<String, Object> aMap = (Map<String, Object>) a;
        assertThat(aMap.get("b.c"), is("foo"));
        assertThat(((Map<String, Object>) aMap.get("b")).get("c"), is("qux"));
    }
}
