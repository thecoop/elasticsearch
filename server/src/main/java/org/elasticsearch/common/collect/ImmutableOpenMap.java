/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.collect;

import org.elasticsearch.common.util.Maps;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * An immutable map implementation based on open hash map.
 * <p>
 * Can be constructed using a {@link #builder()}, or using {@link #builder(Map)} (which is an optimized
 * option to copy over existing content and modify it).
 */
public final class ImmutableOpenMap<KType, VType> extends AbstractMap<KType, VType> {

    @SuppressWarnings("rawtypes")
    private static final Set<Class<? extends Map>> IMMUTABLE_MAPS = Set.of(
        Collections.emptyMap().getClass(),
        Map.of(new Object(), new Object()).getClass(),
        Map.of(new Object(), new Object(), new Object(), new Object()).getClass()
    );

    private final Map<KType, VType> map;

    private ImmutableOpenMap(Map<KType, VType> map) {
        // the map is already expected to not be modified by other code, this is just checking
        // if the map provides unmodifiable semantics we can forward to
        this.map = IMMUTABLE_MAPS.contains(map.getClass()) ? map : Collections.unmodifiableMap(map);
    }

    /**
     * @return Returns the value associated with the given key or the default value
     * for the key type, if the key is not associated with any value.
     * <p>
     * <b>Important note:</b> For primitive type values, the value returned for a non-existing
     * key may not be the default value of the primitive type (it may be any value previously
     * assigned to that slot).
     */
    @Override
    public VType get(Object key) {
        return map.get(key);
    }

    /**
     * @return Returns the value associated with the given key or the provided default value if the
     * key is not associated with any value.
     */
    @Override
    public VType getOrDefault(Object key, VType defaultValue) {
        return map.getOrDefault(key, defaultValue);
    }

    /**
     * Returns <code>true</code> if this container has an association to a value for
     * the given key.
     */
    @Override
    public boolean containsKey(Object key) {
        return map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    @Override
    public VType put(KType key, VType value) {
        throw new UnsupportedOperationException("modification is not supported");
    }

    @Override
    public VType remove(Object key) {
        throw new UnsupportedOperationException("modification is not supported");
    }

    @Override
    public void putAll(Map<? extends KType, ? extends VType> m) {
        throw new UnsupportedOperationException("modification is not supported");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("modification is not supported");
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public Set<Map.Entry<KType, VType>> entrySet() {
        return map.entrySet();
    }

    @Override
    public Set<KType> keySet() {
        return map.keySet();
    }

    @Override
    public Collection<VType> values() {
        return map.values();
    }

    @Override
    public void forEach(BiConsumer<? super KType, ? super VType> action) {
        map.forEach(action);
    }

    /* Other map methods modify the map via these calls, so don't need to implement explicitly here */

    @Override
    public String toString() {
        return map.toString();
    }

    @Override
    public boolean equals(Object o) {
        return map.equals(o);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static final ImmutableOpenMap EMPTY = new ImmutableOpenMap(Map.of());

    @SuppressWarnings("unchecked")
    public static <KType, VType> ImmutableOpenMap<KType, VType> of() {
        return EMPTY;
    }

    public static <KType, VType> Builder<KType, VType> builder() {
        return new Builder<>();
    }

    public static <KType, VType> Builder<KType, VType> builder(int size) {
        return new Builder<>(size);
    }

    public static <KType, VType> Builder<KType, VType> builder(Map<KType, VType> map) {
        if (map instanceof ImmutableOpenMap<KType, VType> iom) {
            return new Builder<>(iom.map);
        }
        if (IMMUTABLE_MAPS.contains(map.getClass())) {
            return new Builder<>(map);
        }
        Builder<KType, VType> builder = new Builder<>(map.size());
        builder.putAllFromMap(map);
        return builder;
    }

    public static class Builder<KType, VType> {

        // if the Builder was constructed with a reference to an existing immutable map, then this will be non-null
        // (at least until the point where the builder has been used to actually make some changes to the map that is
        // being built -- see maybeCloneMap)
        private Map<KType, VType> reference;

        // if the Builder was constructed with a size (only), then this will be non-null (and reference will be null)
        private Map<KType, VType> mutableMap;

        // n.b. a constructor can either set reference or it can set mutableMap, but it must not set both.

        /**
         * This method must be called before reading or writing via the {@code mutableMap} -- so every method
         * of builder should call this as the first thing it does. If {@code reference} is not null, it will be used to
         * populate {@code mutableMap} as a clone of the {@code reference} ImmutableOpenMap. It will then null out
         * {@code reference}.
         *
         * If {@code reference} is already null (and by extension, {@code mutableMap} is already *not* null),
         * then this method is a no-op.
         */
        private void maybeCloneMap() {
            if (reference != null) {
                this.mutableMap = new HashMap<>(reference);
                this.reference = null; // and throw away the reference, we now rely on mutableMap
            }
        }

        private Builder() {
            this(Map.of());
        }

        private Builder(int size) {
            this.mutableMap = Maps.newHashMapWithExpectedSize(size);
        }

        private Builder(Map<KType, VType> immutableMap) {
            // already checked to be an immutable map
            this.reference = immutableMap;
        }

        /**
         * Builds a new Map from this builder.
         */
        public ImmutableOpenMap<KType, VType> build() {
            if (reference != null) {
                Map<KType, VType> reference = this.reference;
                this.reference = null; // null out the reference so that you can't reuse this builder
                return new ImmutableOpenMap<>(reference);
            } else {
                Map<KType, VType> mutableMap = this.mutableMap;
                this.mutableMap = null; // null out the map so that you can't reuse this builder
                return mutableMap.isEmpty() ? of() : new ImmutableOpenMap<>(mutableMap);
            }
        }

        /**
         * Puts all the entries in the map to the builder.
         */
        public Builder<KType, VType> putAllFromMap(Map<KType, VType> map) {
            maybeCloneMap();
            this.mutableMap.putAll(map);
            return this;
        }

        /**
         * A put operation that can be used in the fluent pattern.
         */
        public Builder<KType, VType> fPut(KType key, VType value) {
            maybeCloneMap();
            mutableMap.put(key, value);
            return this;
        }

        public VType put(KType key, VType value) {
            maybeCloneMap();
            return mutableMap.put(key, value);
        }

        public VType get(KType key) {
            return reference != null ? reference.get(key) : mutableMap.get(key);
        }

        public VType getOrDefault(KType kType, VType vType) {
            return reference != null ? reference.getOrDefault(kType, vType) : mutableMap.getOrDefault(kType, vType);
        }

        public VType remove(KType key) {
            maybeCloneMap();
            return mutableMap.remove(key);
        }

        public boolean containsKey(KType key) {
            return reference != null ? reference.containsKey(key) : mutableMap.containsKey(key);
        }

        public int size() {
            return reference != null ? reference.size() : mutableMap.size();
        }

        public void clear() {
            maybeCloneMap();
            mutableMap.clear();
        }

        public Set<KType> keys() {
            // caller might hold a reference & expect it to be updated
            maybeCloneMap();
            return Collections.unmodifiableSet(mutableMap.keySet());
        }

        public boolean removeAll(BiPredicate<? super KType, ? super VType> predicate) {
            maybeCloneMap();
            return mutableMap.entrySet().removeIf(e -> predicate.test(e.getKey(), e.getValue()));
        }
    }
}
