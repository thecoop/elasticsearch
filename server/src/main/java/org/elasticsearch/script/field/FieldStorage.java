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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
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

    /**
     * A reference to a field value in the field value tree.
     * The value is obtained by getting the {@param fieldKey} from the {@param fieldNode}.
     * If the value {@param isMap} then caller should inspect the {@link Node#ctxValues} of the value Node to
     * construct the map.
     */
    private record FieldValue(Key fieldKey, Node fieldNode, boolean isMap) {
        public FieldValue {
            Objects.requireNonNull(fieldKey);
            Objects.requireNonNull(fieldNode);
        }

        private FieldValue(Key key, Node parent) {
            this(key, parent, false);
        }

        Object ctxGet() {
            Object value = fieldNode.nested.get(fieldKey);
            if (isMap) {
                if (value instanceof Node node) {
                    if (node.ctxValues == null) {
                        return Collections.emptyMap();
                    }
                    return new NestedCtxMap(node);
                } else {
                    throw new IllegalStateException("expected map [" + this + "] to have a node, not [" + value + "]");
                }
            }
            return value;
        }

        Object ctxSet(Object value) {
            return fieldNode.nested.put(fieldKey, value);
        }

        // This is for debugging via expressions
        public Object get() {
            Object value = fieldNode.nested.get(fieldKey);
            if (isMap) {
                if (value instanceof Node node) {
                    return node.ctxValues.keySet();
                } else {
                    throw new IllegalStateException("expected map [" + this + "] to have a node, not [" + value + "]");
                }
            }
            return value;
        }
    }

    private static class Node {
        private Map<String, FieldValue> ctxValues;
        private final NavigableMap<Key, Object> nested = new TreeMap<>();

        // for debugging
        @Override
        public String toString() {
            return (ctxValues.isEmpty() ? "<internal>" : "{" + ctxValues.keySet() + "}") + " [" + nested.keySet() + "]";
        }

        void putCtxValue(String ctxKey, Key fieldKey, Node fieldNode, boolean isMap) {
            if (ctxValues == null) {
                ctxValues = new HashMap<>();
            }
            ctxValues.putIfAbsent(ctxKey, new FieldValue(fieldKey, fieldNode, isMap));
        }
    }

    private final Node root = new Node();

    public Optional<?> getCtx(String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        Object curr = root;
        do {
            curr = ((Node)curr).nested.get(fields.next().fieldKey);
        } while (fields.hasNext() && curr != null);
        return Optional.ofNullable(curr);
    }

    public Object getCtxMap(String key) {
        FieldValue fv =  root.ctxValues.get(key);
        return fv != null ? fv.ctxGet() : null;
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

        Node container = root;
        for (Node curr = root;;) {
            CtxKeyTailKey next = fields.next();
            if (fields.hasNext() == false) {
                // this is the final key - put the data here
                curr.nested.put(next.fieldKey, value);
                if (next.isLastSegment() == false) {
                    throw new IllegalStateException("no ctx for key [" + next + "] at terminal");
                }
                container.putCtxValue(next.ctxKey, next.fieldKey, curr, false);
                return;
            }
            else {
                if (next.isLastSegment()) {
                    // We know how to access the value now, so add to container at the beginning of this split field.
                    // Also know this is not the final key, so this value is a map.
                    container.putCtxValue(next.ctxKey, next.fieldKey, curr, true);
                    curr = (Node)curr.nested.computeIfAbsent(next.fieldKey, k -> new Node());
                    // The top of the next run
                    container = curr;
                } else {
                    curr = (Node)curr.nested.computeIfAbsent(next.fieldKey, k -> new Node());
                }
            }
        }
    }

    public Object remove(String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        record Breadcrumb(CtxKeyTailKey key, Node node, Node ctxNode) {}

        List<Breadcrumb> trail = new ArrayList<>();
        Node container = root;
        trail.add(new Breadcrumb(null, root, null));
        boolean removed;
        Object value;
        for (Node curr = root;;) {
            CtxKeyTailKey next = fields.next();
            if (fields.hasNext() == false) {
                // this is the final key - data to remove is here
                removed = curr.nested.containsKey(next.fieldKey);
                value = curr.nested.remove(next.fieldKey);
                container.ctxValues.remove(next.ctxKey);
                break;
            }
            else {
                if (next.isLastSegment()) {
                    curr = (Node)curr.nested.get(next.fieldKey);
                    if (curr == null)
                        return null;
                    // The top of the next run
                    trail.add(new Breadcrumb(next, curr, container));
                    container = curr;
                } else {
                    curr = (Node)curr.nested.get(next.fieldKey);
                    if (curr == null)
                        return null;
                    trail.add(new Breadcrumb(next, curr, null));
                }
            }
        }

        if (removed) {
            // go back up the tree & remove empty containers as we go
            for (int i=trail.size()-1; i>=1; i--) {
                var b = trail.get(i);
                if (b.node.nested.isEmpty()) {
                    trail.get(i-1).node.nested.remove(b.key.fieldKey);
                    if (b.ctxNode != null) {
                        b.ctxNode.ctxValues.remove(b.key.ctxKey);
                    }
                }
            }
        }

        return value;
    }

    private static Stream<CtxKeyTailKey> accessKeys(Stream<String> path) {
        return path
            .flatMap(s -> {
                String[] sf = s.split("\\.");
                int prefixLength = sf.length-1;
                // don't need to specify prefix on intermediate nodes, as they're only visible if there's child data to see
                return prefixLength == 0
                    ? Stream.of(new CtxKeyTailKey(s, s))
                    : Stream.concat(
                            Arrays.stream(sf, 0, prefixLength).map(CtxKeyTailKey::new),
                            Stream.of(sf[prefixLength]).map(pf -> new CtxKeyTailKey(s, pf, prefixLength)));
            });
    }

    /**
     * The context key and the key for the last segment of the context key.
     * Note: we could compute this from the ctxKey directly
     */
    private record CtxKeyTailKey(String ctxKey, Key fieldKey) {
        /**
         * Constructor for an intermediate prefix key
         */
        private CtxKeyTailKey(String field)  {
            this(null, new Key(field));
        }

        /**
         * Constructor for a normal key
         */
        private CtxKeyTailKey(String ctxKey, String field) {
            this(ctxKey, new Key(field));
        }

        /**
         * Constructor for a final prefix key, specifying the prefix length
         */
        private CtxKeyTailKey(String ctxKey, String field, int prefix) {
            this(ctxKey, new Key(field, prefix));
        }

        boolean isLastSegment() {
            return ctxKey != null;
        }
    }

    private static class NestedCtxMap extends AbstractMap<String, Object> {
        private final Node node;

        public NestedCtxMap(Node node) {
            this.node = node;
        }

        @Override
        public int size() {
            return node.ctxValues.size();
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                final Set<Entry<String, FieldValue>> values = node.ctxValues.entrySet();

                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return new Iterator<>() {

                        Iterator<Entry<String, FieldValue>> it = values.iterator();

                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Entry<String, Object> next() {
                            Entry<String, FieldValue> entry = it.next();
                            return new Entry<String, Object>() {
                                @Override
                                public String getKey() {
                                    return entry.getKey();
                                }

                                @Override
                                public Object getValue() {
                                    return entry.getValue().ctxGet();
                                }

                                @Override
                                public Object setValue(Object value) {
                                    // TODO(stu): handle NestedCtxMap
                                    return entry.getValue().ctxSet(value);
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            // TODO(stu): implement
                            // need to have access to full trail back to root, so can delete nested containers as we go
                        }
                    };
                }

                @Override
                public int size() {
                    return NestedCtxMap.this.size();
                }
            };
        }
    }
}
