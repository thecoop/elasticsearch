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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.elasticsearch.script.field.FieldStorage.match;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.theInstance;

public class FieldStorageTest extends ESTestCase {

    public void testBasicPutGet() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b", "c");

        assertThat(s.getField("a", "b", "c"), contains("foo"));
        assertThat(s.getCtx("a", "b", "c"), equalTo(Optional.of("foo")));
    }

    public void testPrefixFieldAccess() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b.c");

        assertThat(s.getField("a", "b", "c"), contains("foo"));
        assertThat(s.getCtx("a", "b.c"), equalTo(Optional.of("foo")));
        assertThat(s.getCtx("a", "b", "c"), is(Optional.empty()));
    }

    public void testPrefixCombination() {
        FieldStorage s = new FieldStorage();
        s.put("bar", "a.b", "c");
        s.put("foo", "a", "b.c");
        s.put("baz", "a.b.c");

        assertThat(s.getField("a", "b", "c"), containsInAnyOrder("foo", "bar", "baz"));
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

    public void testCtxMultiHomePutGet() {
        FieldStorage s = new FieldStorage();
        Map<String, String> data = Map.of("foo", "foo", "bar", "bar");
        s.put(data, "a.b");
        s.put(data, "a.c");

        assertThat(s.getCtx("a.b").get(), theInstance(data));
        assertThat(s.getCtx("a.c").get(), theInstance(data));
    }

    public void testNestedCtxMultiHomePutGet() {
        FieldStorage s = new FieldStorage();
        Map<String, String> data = Map.of("foo", "foo", "bar", "bar");
        s.put(data, "a", "b.c");
        s.put(data, "a.b", "c");

        assertThat(s.getCtx("a", "b.c").get(), theInstance(data));
        assertThat(s.getCtx("a.b", "c").get(), theInstance(data));
    }

    public void testValueAndNestedField() {
        FieldStorage s = new FieldStorage();
        s.put(10, "value");
        s.put(15, "value.max");
        s.put(5, "value.min");

        assertThat(s.getCtx("value").get(), equalTo(10));
        assertThat(s.getCtx("value.min").get(), equalTo(5));
        assertThat(s.getCtx("value.max").get(), equalTo(15));
    }

    public void testNestedCtxMap() {
        FieldStorage s = new FieldStorage();
        s.put("bar", "a.b", "c");
        s.put("foo", "a", "b.c");
        s.put("baz", "a.b.c");
        s.put("qux", "a", "b", "c");

        Object a = s.getCtxMap("a");
        assertThat(a, instanceOf(Map.class));
        Map<?, ?> aMap = (Map<?, ?>) a;
        assertThat(aMap.keySet(), containsInAnyOrder("b", "b.c"));
        assertThat(aMap.get("b.c"), is("foo"));
        assertThat(((Map<?, ?>) aMap.get("b")).get("c"), is("qux"));

        Object ab = s.getCtxMap("a.b");
        assertThat(ab, instanceOf(Map.class));
        Map<?, ?> abMap = (Map<?, ?>) ab;
        assertThat(abMap.keySet(), contains("c"));
        assertThat(abMap.get("c"), is("bar"));
    }

    public void testRemove() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b", "c");
        s.put("bar", "a.b", "c");

        assertThat(s.remove("a", "b", "c"), is("foo"));
        assertThat(s.getField("a", "b", "c"), contains("bar"));
        assertThat(s.remove("a.b", "c"), is("bar"));
        assertThat(s.getField("a", "b", "c"), empty());
    }

    public void testNestedRemove() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b.c", "d");
        s.put("bar", "a", "b.c", "e");

        s.remove("a", "b.c", "d");
        assertThat(((Map<?, ?>)s.getCtxMap("a")).keySet(), contains("b.c"));
        s.remove("a", "b.c", "e");
        assertThat(s.getCtxMap("a"), nullValue());
    }

    public void testUnmanagedMapSearch() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b.c", "d");
        Map<String, Object> unmanaged = new HashMap<>();
        Object aObj = s.getCtxMap("a");
        assertThat(aObj, instanceOf(Map.class));
        Map<String, Object> aMap = (Map<String, Object>) aObj;
        aMap.put("b", unmanaged);
        unmanaged.put("c.d", "bar");
        Map<String, Object> cMap = new HashMap<>();
        unmanaged.put("c", cMap);
        cMap.put("d", "baz"); // this is missing

        assertThat(s.getField("a", "b", "c", "d"), containsInAnyOrder("foo", "bar", "baz"));
    }

    public void testUnmanagedMapWithManagedChildSearch() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b.c", "d");
        Map<String, Object> unmanaged = new HashMap<>();
        Object aObj = s.getCtxMap("a");
        assertThat(aObj, instanceOf(Map.class));
        Map<String, Object> aMap = (Map<String, Object>) aObj;
        aMap.put("b", unmanaged);
        unmanaged.put("c", ((Map<?, ?>) s.getCtxMap("a")).get("b.c"));

        assertThat(s.getField("a", "b", "c", "d"), containsInAnyOrder("foo", "foo"));
    }

    public void testMatch() {
        String candidate = "abcd.efg";
        String[] path = new String[]{"abcd", "efg"};
        assertEquals(0, match(0, path, candidate));
        assertEquals(-1, match(0, path, candidate + ".")); // final return
        assertEquals(2, match(1, new String[]{"abc", "defh", "ijkl", "lmn"}, "defh.ijkl"));
    }

    public void testRehoming() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b", "c");

        Map<String, Object> m = (Map<String, Object>)s.getCtxMap("a");
        s.put(m, "z");
        m = (Map<String, Object>)s.getCtxMap("z");
        m.put("q", "r");
        assertThat(s.getField("a", "q"), contains("r"));
    }

    public void testIndirectRehoming() {
        FieldStorage s = new FieldStorage();
        s.put("foo", "a", "b", "c");

        Map<?, ?> m = (Map<?, ?>)s.getCtxMap("a");
        s.put(new HashMap<>(Map.of("i", m)), "z");
        assertThat(s.getField("z", "i", "b", "c"), contains("foo"));
    }

    @Ignore
    public void testCtxDeletion() {
        // add ctx at a, b.c.d
        // through fields API delete a.b.c.d through a map access from a.b.c
        // that needs to delete the fields reference on b pointing to b.c.d, but we don't necessarily have the parent map

        // add map at a.b.c.d
        // move a.b to a.z
    }
}
