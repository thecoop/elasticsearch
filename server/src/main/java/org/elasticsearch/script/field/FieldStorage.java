/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public class FieldStorage {

    private record Key(String field, int prefix) implements Comparable<Key> {

        private static Key min(String field) {
            return new Key(field, 0);
        }

        private static Key max(String field) {
            return new Key(field, Integer.MAX_VALUE);
        }

        private Key(String field) {
            this(field, 0);
        }

        @Override
        public int compareTo(Key o) {
            int c = field.compareTo(o.field);
            if (c == 0) {
                c = Integer.compare(prefix, o.prefix);
            }
            return c;
        }
    }

    private static class Node {
        private final NavigableMap<Key, Object> nested = new TreeMap<>();
    }

    private final Node root = new Node();

    public Optional<?> getCtx(String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        Object curr = root;
        do {
            curr = ((Node)curr).nested.get(fields.next());
        } while (fields.hasNext() && curr != null);
        return Optional.ofNullable(curr);
    }

    public Stream<?> getField(String... field) {
        assert field.length > 0;
        // ignore prefix here
        Stream<Object> currentValues = Stream.of(root);
        for (String f : field) {
            if (f.contains(".")) throw new IllegalArgumentException();

            Key min = Key.min(f);
            Key max = Key.max(f);
            currentValues = currentValues
                .flatMap(n -> ((Node)n).nested.subMap(min, true, max, true).values().stream());
        }
        return currentValues;
    }

    public Stream<?> findAllWithPrefix(String prefix) {
        String[] path = prefix.split("\\.");
        Node anchorNode = root;
        if (path.length > 1) {
            var fields = accessKeys(Arrays.stream(path, 0, path.length-1)).iterator();

            do {
                anchorNode = (Node)anchorNode.nested.get(fields.next());
            } while (fields.hasNext() && anchorNode != null);
        }
        if (anchorNode == null) return Stream.empty();

        String purePrefix = path[path.length-1];
        // and recursively iterate through all nested child maps too...
        return anchorNode.nested.subMap(Key.min(purePrefix), Key.max(purePrefix + Character.MAX_VALUE)).values().stream();
    }

    public void put(Object value, String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        for (Node curr = root;;) {
            Key next = fields.next();
            if (fields.hasNext() == false) {
                // this is the final key - put the data here
                curr.nested.put(next, value);
                return;
            }
            else {
                curr = (Node)curr.nested.computeIfAbsent(next, k -> new Node());
            }
        }
    }

    private static Stream<Key> accessKeys(Stream<String> path) {
        return path
            .flatMap(s -> {
                String[] sf = s.split("\\.");
                int prefixLength = sf.length-1;
                // don't need to specify prefix on intermediate nodes, as they're only visible if there's child data to see
                return prefixLength == 0
                    ? Stream.of(new Key(s))
                    : Stream.concat(
                            Arrays.stream(sf, 0, prefixLength).map(Key::new),
                            Stream.of(sf[prefixLength]).map(pf -> new Key(pf, prefixLength)));
            });
    }

    private static class NestedCtxMap extends AbstractMap<String, Object> {
        private final Map<Key, Object> nodeData;

        private NestedCtxMap(Map<Key, Object> nodeData) {
            this.nodeData = nodeData;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            // don't include keys in nodeData with prefix > 0
            // need to also include any prefixed keys further down the collections
            // recursively scan all child objects for any with a prefix < the distance from that object to here

            // this means iteration & size() will be O(total number of recursive child objects)
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return null;
                }

                @Override
                public int size() {
                    return 0;
                }
            };
        }
    }
}
