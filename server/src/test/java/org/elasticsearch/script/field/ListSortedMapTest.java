/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import org.elasticsearch.test.ESTestCase;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class ListSortedMapTest extends ESTestCase {
    // just some basic tests, ideally need guava testlib for an exhaustive test
    public void testEmptyOperations() {
        ListSortedMap<Integer, Integer> m = new ListSortedMap<>();
        assertThat(m.get(0), nullValue());
        assertThat(m.keySet(), empty());
        assertThat(m.subMap(1, true, 1, true), anEmptyMap());
    }

    public void testGetPut() {
        ListSortedMap<Integer, Integer> m = new ListSortedMap<>();
        m.put(6, 6);
        m.put(4, 4);

        assertThat(m.entrySet(), contains(Map.entry(4, 4), Map.entry(6, 6)));
        assertThat(m.get(4), is(4));
        assertThat(m.get(5), nullValue());
    }

    public void testRemove() {
        ListSortedMap<Integer, Integer> m = new ListSortedMap<>(IntStream.range(0, 4).boxed().collect(Collectors.toMap(i -> i*2, i -> i*2)));

        m.remove(2);
        assertThat(m.entrySet(), contains(Map.entry(0, 0), Map.entry(4, 4), Map.entry(6, 6)));
    }

    public void testSubMap() {
        ListSortedMap<Integer, Integer> m = new ListSortedMap<>(IntStream.range(0, 10).boxed().collect(Collectors.toMap(i -> i*2, i -> i*2)));

        assertThat(m.subMap(3, false, 7, false), equalTo(Map.of(4, 4, 6, 6)));
        assertThat(m.subMap(2, false, 6, true), equalTo(Map.of(4, 4, 6, 6)));
        assertThat(m.subMap(2, true, 6, false), equalTo(Map.of(2, 2, 4, 4)));
    }
}
