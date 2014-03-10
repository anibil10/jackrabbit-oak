/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.oak.plugins.index.property.strategy;

import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.jackrabbit.oak.plugins.index.property.OrderedIndex;
import org.apache.jackrabbit.oak.plugins.index.property.OrderedIndex.OrderDirection;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.AbstractChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

/**
 * Same as for {@link ContentMirrorStoreStrategy} but the order of the keys is kept by using the
 * following structure
 * 
 * <code>
 *  :index : {
 *      :start : { :next = n1 },
 *      n0 : { /content/foo/bar(match=true), :next=n3 },
 *      n1 : { /content/foo1/bar(match=true), :next=n0 },
 *      n2 : { /content/foo2/bar(match=true), :next= }, //this is the end of the list
 *      n3 : { /content/foo3/bar(match=true), :next=n2 }
 *  }
 * </code>
 */
public class OrderedContentMirrorStoreStrategy extends ContentMirrorStoreStrategy {

    /**
     * the property linking to the next node
     */
    public static final String NEXT = ":next";

    /**
     * node that works as root of the index (start point or 0 element)
     */
    public static final String START = ":start";

    /**
     * a NodeState used for easy creating of an empty :start
     */
    public static final NodeState EMPTY_START_NODE = EmptyNodeState.EMPTY_NODE.builder()
                                                                              .setProperty(NEXT, "")
                                                                              .getNodeState();

    private static final Logger LOG = LoggerFactory.getLogger(OrderedContentMirrorStoreStrategy.class);

    /**
     * the direction of the index.
     */
    private OrderDirection direction = OrderedIndex.DEFAULT_DIRECTION;

    public OrderedContentMirrorStoreStrategy() {
        super();
    }
    
    public OrderedContentMirrorStoreStrategy(OrderDirection direction) {
        this();
        this.direction = direction;
    }
    
    @Override
    NodeBuilder fetchKeyNode(@Nonnull NodeBuilder index, @Nonnull String key) {
        LOG.debug("fetchKeyNode() - index: {} - key: {}", index, key);
        NodeBuilder localkey = null;
        NodeBuilder start = index.child(START);

        // identifying the right place for insert
        String n = start.getString(NEXT);
        if (Strings.isNullOrEmpty(n)) {
            // new/empty index
            localkey = index.child(key);
            localkey.setProperty(NEXT, "");
            start.setProperty(NEXT, key);
        } else {
            // specific use-case where the item has to be added as first of the list
            String nextKey = n;
            Iterable<? extends ChildNodeEntry> children = getChildNodeEntries(index.getNodeState(),
                                                                              true);
            for (ChildNodeEntry child : children) {
                nextKey = child.getNodeState().getString(NEXT);
                if (Strings.isNullOrEmpty(nextKey)) {
                    // we're at the last element, therefore our 'key' has to be appended
                    index.getChildNode(child.getName()).setProperty(NEXT, key);
                    localkey = index.child(key);
                    localkey.setProperty(NEXT, "");
                } else {
                    if (isInsertHere(key, nextKey)) {
                        index.getChildNode(child.getName()).setProperty(NEXT, key);
                        localkey = index.child(key);
                        localkey.setProperty(NEXT, nextKey);
                        break;
                    }
                }
            }
        }

        return localkey;
    }

    /**
     * tells whether or not the is right to insert here a new item.
     * 
     * @param newItemKey the new item key to be added
     * @param existingItemKey the 'here' of the existing index
     * @return true for green light on insert false otherwise.
     */
    private boolean isInsertHere(@Nonnull String newItemKey, @Nonnull String existingItemKey) {
        if (OrderDirection.ASC.equals(direction)) {
            return newItemKey.compareTo(existingItemKey) < 0;
        } else {
            return newItemKey.compareTo(existingItemKey) > 0;
        }
    }
                                        
    @Override
    void prune(final NodeBuilder index, final Deque<NodeBuilder> builders) {
        for (NodeBuilder node : builders) {
            if (node.hasProperty("match") || node.getChildNodeCount(1) > 0) {
                return;
            } else if (node.exists()) {
                if (node.hasProperty(NEXT)) {
                    // it's an index key and we have to relink the list
                    // (1) find the previous element
                    ChildNodeEntry previous = findPrevious(
                            index.getNodeState(), node.getNodeState());
                    LOG.debug("previous: {}", previous);
                    // (2) find the next element
                    String next = node.getString(NEXT); 
                    if (next == null) {
                        next = "";
                    }
                    // (3) re-link the previous to the next
                    index.getChildNode(previous.getName()).setProperty(NEXT, next); 
                } 
                node.remove();
            }
        }
    }

    /**
     * find the previous item (ChildNodeEntry) in the index given the provided NodeState for
     * comparison
     * 
     * in an index sorted in ascending manner where we have @{code [1, 2, 3, 4, 5]} if we ask for 
     * a previous given 4 it will be 3. previous(4)=3.
     * 
     * in an index sorted in descending manner where we have @{code [5, 4, 3, 2, 1]} if we as for
     * a previous given 4 it will be 5. previous(4)=5.
     * 
     * @param index the index we want to look into ({@code :index})
     * @param node the node we want to compare
     * @return the previous item or null if not found.
     */
    @Nullable
    ChildNodeEntry findPrevious(@Nonnull final NodeState index, @Nonnull final NodeState node) {
        ChildNodeEntry previous = null;
        ChildNodeEntry current = null;
        boolean found = false;
        Iterator<? extends ChildNodeEntry> it = getChildNodeEntries(index, true).iterator();

        while (!found && it.hasNext()) {
            current = it.next();
            if (previous == null) {
                // first iteration
                previous = current;
            } else {
                found = node.equals(current.getNodeState());
                if (!found) {
                    previous = current;
                }
            }
        }

        return found ? previous : null;
    }

    @Override
    public void update(NodeBuilder index, String path, Set<String> beforeKeys,
                       Set<String> afterKeys) {
        LOG.debug("update() - index     : {}", index);
        LOG.debug("update() - path      : {}", path);
        LOG.debug("update() - beforeKeys: {}", beforeKeys);
        LOG.debug("update() - afterKeys : {}", afterKeys);
        super.update(index, path, beforeKeys, afterKeys);
    }

    /**
     * retrieve an Iterable for going through the index in the right order without the :start node
     * 
     * @param index the root of the index (:index)
     * @return
     */
    @Override
    @Nonnull
    Iterable<? extends ChildNodeEntry> getChildNodeEntries(@Nonnull final NodeState index) {
        return getChildNodeEntries(index, false);
    }

    /**
     * Retrieve an Iterable for going through the index in the right order with potentially the
     * :start node
     * 
     * @param index the root of the index (:index)
     * @param includeStart true if :start should be included as first element
     * @return
     */
    @Nonnull
    Iterable<? extends ChildNodeEntry> getChildNodeEntries(@Nonnull final NodeState index,
                                                           final boolean includeStart) {
        Iterable<? extends ChildNodeEntry> cne = null;
        final NodeState start = index.getChildNode(START);

        if ((!start.exists() || Strings.isNullOrEmpty(start.getString(NEXT))) && !includeStart) {
            // if the property is not there or is empty it means we're empty
            cne = Collections.emptyList();
        } else {
            cne = new Iterable<ChildNodeEntry>() {
                private NodeState localIndex = index;
                private NodeState localStart = includeStart && !start.exists() ? EMPTY_START_NODE
                                                                             : start;
                private NodeState current = localStart;
                private boolean localIncludeStart = includeStart;

                @Override
                public Iterator<ChildNodeEntry> iterator() {
                    return new Iterator<ChildNodeEntry>() {

                        @Override
                        public boolean hasNext() {
                            return (localIncludeStart && localStart.equals(current)) || (!localIncludeStart && !Strings.isNullOrEmpty(current.getString(NEXT)));
                        }

                        @Override
                        public ChildNodeEntry next() {
                            ChildNodeEntry localCNE = null;
                            if (localIncludeStart && localStart.equals(current)) {
                                localCNE = new OrderedChildNodeEntry(START, current);
                                // let's set it to false. We just included it.
                                localIncludeStart = false; 
                            } else {
                                if (hasNext()) {
                                    final String name = current.getString(NEXT);
                                    current = localIndex.getChildNode(name);
                                    localCNE = new OrderedChildNodeEntry(name, current);
                                } else {
                                    throw new NoSuchElementException();
                                }
                            }
                            return localCNE;
                        }

                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException();
                        }
                    };
                }
            };
        }
        return cne;
    }

    private static final class OrderedChildNodeEntry extends AbstractChildNodeEntry {
        private final String name;
        private final NodeState state;

        public OrderedChildNodeEntry(@Nonnull
        final String name, @Nonnull
        final NodeState state) {
            this.name = name;
            this.state = state;
        }

        @Override
        @Nonnull
        public String getName() {
            return name;
        }

        @Override
        @Nonnull
        public NodeState getNodeState() {
            return state;
        }
    }
}