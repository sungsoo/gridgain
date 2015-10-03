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

package org.gridgain.grid.kernal.processors.cache.distributed.dht;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;

import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCachePreloadMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * Metrics test for partitioned cache with disabled near cache.
 */
public class GridCachePartitionedNearDisabledMetricsSelfTest extends GridCacheAbstractSelfTest {
    /** */
    private static final int GRID_CNT = 2;

    /** {@inheritDoc} */
    @Override protected GridCacheConfiguration cacheConfiguration(String gridName) throws Exception {
        GridCacheConfiguration cfg = super.cacheConfiguration(gridName);

        cfg.setCacheMode(PARTITIONED);
        cfg.setBackups(gridCount() - 1);
        cfg.setPreloadMode(SYNC);
        cfg.setTxSerializableEnabled(true);
        cfg.setWriteSynchronizationMode(FULL_SYNC);
        cfg.setDistributionMode(PARTITIONED_ONLY);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return GRID_CNT;
    }

    // TODO: extend from GridCacheTransactionalAbstractMetricsSelfTest and uncomment:

//    /** {@inheritDoc} */
//    @Override protected int expectedReadsPerPut(boolean isPrimary) {
//        return 1;
//    }
//
//    /** {@inheritDoc} */
//    @Override protected int expectedMissesPerPut(boolean isPrimary) {
//        return 1;
//    }

    /**
     * @throws Exception If failed.
     */
    public void _testGettingRemovedKey() throws Exception {
        GridCache<Integer, Integer> cache = grid(0).cache(null);

        cache.put(0, 0);

        for (int i = 0; i < gridCount(); i++) {
            Grid g = grid(i);

            // TODO: getting of removed key will produce 3 inner read operations.
            g.cache(null).removeAll();

            // TODO: getting of removed key will produce inner write and 4 inner read operations.
            //g.cache(null).remove(0);

            assert g.cache(null).isEmpty();

            g.cache(null).resetMetrics();
        }

        assertNull("Value is not null for key: " + 0, cache.get(0));

        // Check metrics for the whole cache.
        long writes = 0;
        long reads = 0;
        long hits = 0;
        long misses = 0;

        for (int i = 0; i < gridCount(); i++) {
            GridCacheMetrics m = grid(i).cache(null).metrics();

            writes += m.writes();
            reads += m.reads();
            hits += m.hits();
            misses += m.misses();
        }

        assertEquals(0, writes);
        assertEquals(1, reads);
        assertEquals(0, hits);
        assertEquals(1, misses);
    }
}
