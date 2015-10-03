/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.query.continuous;

import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.cache.query.GridCacheContinuousQueryEntry;
import org.gridgain.grid.lang.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static java.util.concurrent.TimeUnit.*;
import static org.gridgain.grid.cache.GridCacheMode.*;

/**
 * Continuous queries tests for replicated cache.
 */
public class GridCacheContinuousQueryReplicatedSelfTest extends GridCacheContinuousQueryAbstractSelfTest {
    /** {@inheritDoc} */
    @Override protected GridCacheMode cacheMode() {
        return REPLICATED;
    }

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return 3;
    }

    /**
     * @throws Exception If failed.
     */
    @SuppressWarnings("unchecked")
    public void testRemoteNodeCallback() throws Exception {
        GridCache<Integer, Integer> cache1 = grid(0).cache(null);

        GridCache<Integer, Integer> cache2 = grid(1).cache(null);

        GridCacheContinuousQuery<Integer, Integer> qry = cache2.queries().createContinuousQuery();

        final AtomicReference<Integer> val = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        qry.localCallback(new GridBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Integer, Integer>>>() {
            @Override public boolean apply(UUID uuid,
                Collection<GridCacheContinuousQueryEntry<Integer, Integer>> entries) {
                assertEquals(1, entries.size());

                Map.Entry<Integer, Integer> e = entries.iterator().next();

                log.info("Entry: " + e);

                val.set(e.getValue());

                latch.countDown();

                return false;
            }
        });

        qry.execute();

        cache1.put(1, 10);

        latch.await(LATCH_TIMEOUT, MILLISECONDS);

        assertEquals(10, val.get().intValue());
    }

    /**
     * Ensure that every node see every update.
     *
     * @throws Exception If failed.
     */
    @SuppressWarnings("unchecked")
    public void testCrossCallback() throws Exception {
        // Prepare.
        GridCache<Integer, Integer> cache1 = grid(0).cache(null);
        GridCache<Integer, Integer> cache2 = grid(1).cache(null);

        final int key1 = primaryKey(cache1);
        final int key2 = primaryKey(cache2);

        final CountDownLatch latch1 = new CountDownLatch(2);
        final CountDownLatch latch2 = new CountDownLatch(2);

        // Start query on the first node.
        GridCacheContinuousQuery<Integer, Integer> qry1 = cache1.queries().createContinuousQuery();

        qry1.localCallback(new GridBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Integer, Integer>>>() {
            @Override public boolean apply(UUID nodeID,
                Collection<GridCacheContinuousQueryEntry<Integer, Integer>> entries) {
                for (GridCacheContinuousQueryEntry entry : entries) {
                    log.info("Update in cache 1: " + entry);

                    if (entry.getKey() == key1 || entry.getKey() == key2)
                        latch1.countDown();
                }

                return latch1.getCount() != 0;
            }
        });

        qry1.execute();

        // Start query on the second node.
        GridCacheContinuousQuery<Integer, Integer> qry2 = cache2.queries().createContinuousQuery();

        qry2.localCallback(new GridBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Integer, Integer>>>() {
            @Override public boolean apply(UUID nodeID,
                Collection<GridCacheContinuousQueryEntry<Integer, Integer>> entries) {
                for (GridCacheContinuousQueryEntry entry : entries) {
                    log.info("Update in cache 2: " + entry);

                    if (entry.getKey() == key1 || entry.getKey() == key2)
                        latch2.countDown();
                }

                return latch2.getCount() != 0;
            }
        });

        qry2.execute();

        cache1.put(key1, key1);
        cache1.put(key2, key2);

        assert latch1.await(LATCH_TIMEOUT, MILLISECONDS);
        assert latch2.await(LATCH_TIMEOUT, MILLISECONDS);
    }
}
