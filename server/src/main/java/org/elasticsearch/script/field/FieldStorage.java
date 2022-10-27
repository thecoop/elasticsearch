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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public class FieldStorage {

    /**
     * A key into the field graph, {@param field} is a segment, it must not contain the segment separator, a dot.
     * {@param prefix} is the number of segment separators in the original key's path, or equivalently, one less than the number
     * of segments.  The prefix is only necessary for the last segment.  Intermediate segments have prefix of zero.
     */
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
     * If the value {@param isMap} then caller should inspect the {@link Node#nestedCtxValues} of the value Node to
     * construct the map.
     */
    private record FieldValueReference(Key fieldKey, Node fieldNode, boolean isMap) {
        public FieldValueReference {
            Objects.requireNonNull(fieldKey);
            Objects.requireNonNull(fieldNode);
        }

        /**
         * Dereference this object for use in ctx.  If {@link #isMap} then return a {@link NestedCtxMap} for the node,
         * otherwise return {@link Node#value} for the referenced node.
         */
        Object ctxGet() {
            Node node = fieldNode.nested().get(fieldKey);
            if (isMap) {
                if (node.nestedCtxValues == null) {
                    return Collections.emptyMap();
                }
                return new NestedCtxMap(node);
            }
            return node.value;
        }

        // TODO(stu): untested
        Object ctxSet(Object value) {
            return fieldNode.getContainer(fieldKey).setValue(value);
        }

        // This is for debugging via expressions
        public Object get() {
            Object value = fieldNode.nested.get(fieldKey);
            if (isMap) {
                if (value instanceof Node node) {
                    return node.nestedCtxValues.keySet();
                } else {
                    throw new IllegalStateException("expected map [" + this + "] to have a node, not [" + value + "]");
                }
            }
            return value;
        }
    }

    /**
     * A node in the {@link FieldStorage} tree.  Nodes can have values and children.
     * If accessed in field-centric manner, which uses the {@link #nested} Map.
     * If accessed in a ctx-centric manner, the {@link #nestedCtxValues} Map provides references to {@link Node}s further
     * down the tree corresponding to the keys that would be in the ctx view of this container.
     */
    private static class Node {
        // value at the node, a node may have both a value and children in the case of subobject=false, PR #86166
        private Object value;
        private Map<String, FieldValueReference> nestedCtxValues;
        private NavigableMap<Key, Node> nested;

        Map<String, FieldValueReference> nestedCtxValues() {
            return Objects.requireNonNullElse(nestedCtxValues, Collections.emptyMap());
        }

        NavigableMap<Key, Node> nested() {
            return Objects.requireNonNullElse(nested, Collections.emptyNavigableMap());
        }

        Object setValue(Object value) {
            Object existing = this.value;
            this.value = value;
            return existing;
        }

        Node getContainer(Key key) {
            if (nested == null) {
                nested = new TreeMap<>();
            }
            return nested.computeIfAbsent(key, k -> new Node());
        }

        /**
         * Map the {@link Key} to an existing {@link Node}.  This only occurs during rehoming via ctx access.
         */
        void setContainer(Key key, Node value) {
            if (nested == null) {
                nested = new TreeMap<>();
            }
            nested.put(key, value);
        }

        void putCtxValue(String ctxKey, Key fieldKey, Node fieldNode, boolean isMap) {
            if (nestedCtxValues == null) {
                nestedCtxValues = new HashMap<>();
            }
            nestedCtxValues.putIfAbsent(ctxKey, new FieldValueReference(fieldKey, fieldNode, isMap));
        }

        // for debugging
        @Override
        public String toString() {
            return (nestedCtxValues().isEmpty() ? "<internal>" : "{" + nestedCtxValues().keySet() + "}")
                + " <"
                + this.value
                + ">"
                + " ["
                + nested().keySet()
                + "]";
        }
    }

    private final Node root = new Node();

    public Optional<?> getCtx(String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        Node curr = root;
        do {
            curr = curr.nested.get(fields.next().fieldKey);
        } while (fields.hasNext() && curr != null);
        return Optional.ofNullable(curr).map(n -> n.value);
    }

    public Object getCtxMap(String key) {
        FieldValueReference fv = root.nestedCtxValues().get(key);
        return fv != null ? fv.ctxGet() : null;
    }

    public List<?> getField(String... field) {
        List<Object> result = new ArrayList<>();
        return getField(result, root, 0, field);
    }

    static List<?> getField(List<Object> result, Node root, int start, String[] field) {
        assert field.length > 0;
        // ignore prefix here
        List<Node> currentValues = List.of(root);
        for (int i = start; i < field.length; i++) {
            List<Node> nextLevel = new ArrayList<>();
            String f = field[i];
            if (f.contains(".")) throw new IllegalArgumentException();

            Key min = Key.min(f);
            Key max = Key.max(f);
            for (Node node : currentValues) {
                // Unmanaged map
                if (node.value instanceof Map<?, ?> map) {
                    search(result, i, field, (Map<String, Object>) map);
                }
                if (node.nested != null) {
                    nextLevel.addAll(node.nested.subMap(min, true, max, true).values());
                }
            }
            currentValues = nextLevel;
        }
        for (Node node : currentValues) {
            if (node.value != null) {
                result.add(node.value);
            }
        }
        return result;
    }

    /**
     * Search for keys matching the substring path[start:] in root, recursively.  Accumulate results
     * in {@param result}.
     */
    private static void search(List<Object> result, int start, String[] path, Map<String, Object> root) {
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            int m = match(start, path, entry.getKey());
            if (m == 0) {
                result.add(entry.getValue());
            } else if (m > 0) {
                if (entry.getValue()instanceof Map<?, ?> map) {
                    if (entry.getValue()instanceof NestedCtxMap nested) {
                        getField(result, nested.node, m + 1, path);
                    } else {
                        // TODO(stu): handle entries that are NestedCtxMap
                        search(result, m + 1, path, (Map<String, Object>) map);
                    }
                }
            }
        }
    }

    /**
     * Match segments from source against candidate.
     * Return:
     *   -1 if source does not match candidate
     *   0 if source exactly matches candidate
     *   > 0 if candidate fully matches some number of segments.  Returned value is the index of last matched segment
     */
    public static int match(int pathStart, String[] path, String candidate) {
        int candidateStart = 0;
        for (int i = pathStart; i < path.length; i++) {
            String pathSegment = path[i];
            int maxMatchLength = candidate.length() - candidateStart;
            if (pathSegment.length() > maxMatchLength) {
                // candidate too short, assumes no dots in path
                return -1;
            }
            // pathSegment is <= candidate length
            for (int j = 0; j < pathSegment.length(); j++) {
                if (pathSegment.charAt(j) != candidate.charAt(candidateStart + j)) {
                    // different character
                    return -1;
                }
            }
            candidateStart += pathSegment.length();
            if (candidateStart == candidate.length()) {
                if (i == path.length - 1) {
                    // full match
                    return 0;
                }
                // this is the last segment that can match
                return i;
            }
            // candidate has more values and we are at a segment boundary, so next char must be a dot.
            if (candidate.charAt(candidateStart++) != '.') {
                return -1;
            }
        }
        // matched all path segments but candidate had extra values (starting with dot due to last conditional)
        return -1;
    }

    public Stream<?> findAllWithPrefix(String prefix) {
        String[] path = prefix.split("\\.");
        Node anchorNode = root;
        if (path.length > 1) {
            var fields = accessKeys(Arrays.stream(path, 0, path.length - 1)).iterator();

            do {
                anchorNode = anchorNode.nested.get(fields.next().fieldKey);
            } while (fields.hasNext() && anchorNode != null);
        }
        if (anchorNode == null) return Stream.empty();

        String purePrefix = path[path.length - 1];
        // and recursively iterate through all nested child maps too...
        return anchorNode.nested.subMap(Key.min(purePrefix), Key.max(purePrefix + Character.MAX_VALUE))
            .values()
            .stream()
            .map(n -> n.value)
            .filter(Objects::nonNull);
    }

    public void put(Object value, String... field) {
        put(root, value, field);
    }

    private static void put(Node container, Object value, String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        for (Node curr = container;;) {
            CtxKeyTailKey next = fields.next();
            if (fields.hasNext() == false) {
                boolean isMap = false;
                if (value instanceof NestedCtxMap nested) {
                    // Rehoming a container
                    curr.setContainer(next.fieldKey, nested.node);
                    isMap = true;
                } else {
                    // this is the final key - put the data here
                    curr.getContainer(next.fieldKey).setValue(value);
                }
                // TODO(stu): do we need this in case of NestedCtxMap?
                container.putCtxValue(next.ctxKey, next.fieldKey, curr, isMap);
                if (next.isLastSegment() == false) {
                    throw new IllegalStateException("no ctx for key [" + next + "] at terminal");
                }
                return;
            } else {
                if (next.isLastSegment()) {
                    // We know how to access the value now, so add to container at the beginning of this split field.
                    // Also know this is not the final key, so this value is a map.
                    container.putCtxValue(next.ctxKey, next.fieldKey, curr, true);
                    curr = curr.getContainer(next.fieldKey);
                    // The top of the next run
                    container = curr;
                } else {
                    curr = curr.getContainer(next.fieldKey);
                }
            }
        }
    }

    public Object remove(String... field) {
        var fields = accessKeys(Arrays.stream(field)).iterator();

        record Breadcrumb(CtxKeyTailKey key, Node node, Node ctxNode) {}

        List<Breadcrumb> trail = new ArrayList<>();
        Node foundNode = root;
        Node container = root;
        trail.add(new Breadcrumb(null, root, null));
        while (fields.hasNext()) {
            CtxKeyTailKey next = fields.next();
            if (next.isLastSegment()) {
                foundNode = foundNode.nested.get(next.fieldKey);
                if (foundNode == null) return null;
                // The top of the next run
                trail.add(new Breadcrumb(next, foundNode, container));
                container = foundNode;
            } else {
                foundNode = foundNode.nested.get(next.fieldKey);
                if (foundNode == null) return null;
                trail.add(new Breadcrumb(next, foundNode, null));
            }
        }

        Object value = foundNode.setValue(null);
        if (value != null) {
            // go back up the tree & remove empty containers as we go
            for (int i = trail.size() - 1; i >= 1; i--) {
                var b = trail.get(i);
                if (b.node.nested().isEmpty() && b.node.value == null) {
                    trail.get(i - 1).node.nested.remove(b.key.fieldKey);
                    if (b.ctxNode != null) {
                        b.ctxNode.nestedCtxValues.remove(b.key.ctxKey);
                    }
                } else {
                    break;
                }
            }
        }

        return value;
    }

    private static Stream<CtxKeyTailKey> accessKeys(Stream<String> path) {
        return path.flatMap(s -> {
            String[] sf = s.split("\\.");
            int prefixLength = sf.length - 1;
            // don't need to specify prefix on intermediate nodes, as they're only visible if there's child data to see
            return prefixLength == 0
                ? Stream.of(new CtxKeyTailKey(s, s))
                : Stream.concat(
                    Arrays.stream(sf, 0, prefixLength).map(CtxKeyTailKey::new),
                    Stream.of(sf[prefixLength]).map(pf -> new CtxKeyTailKey(s, pf, prefixLength))
                );
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
        private CtxKeyTailKey(String field) {
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

        private NestedCtxMap(Node node) {
            this.node = node;
        }

        @Override
        public int size() {
            return node.nestedCtxValues.size();
        }

        @Override
        public Object put(String key, Object value) {
            FieldStorage.put(node, value, key);
            return null; // TODO(stu): implement
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                final Set<Entry<String, FieldValueReference>> values = node.nestedCtxValues.entrySet();

                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return new Iterator<>() {

                        Iterator<Entry<String, FieldValueReference>> it = values.iterator();

                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Entry<String, Object> next() {
                            Entry<String, FieldValueReference> entry = it.next();
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
                                    // TODO(stu): untested
                                    return entry.getValue().ctxSet(value);
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            // TODO(stu): implement
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
