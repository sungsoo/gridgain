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

package org.gridgain.grid.kernal.processors.cache;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.testframework.junits.common.*;

import static org.gridgain.grid.cache.GridCacheTxConcurrency.*;
import static org.gridgain.grid.cache.GridCacheTxIsolation.*;

/**
 * Check for specific support issue.
 */
public class GridCacheOffHeapUpdateSelfTest extends GridCommonAbstractTest {
    /** {@inheritDoc} */
    @Override protected GridConfiguration getConfiguration(String gridName) throws Exception {
        GridConfiguration cfg = super.getConfiguration(gridName);

        cfg.setPeerClassLoadingEnabled(false);

        GridCacheConfiguration ccfg = new GridCacheConfiguration();

        ccfg.setCacheMode(GridCacheMode.PARTITIONED);
        ccfg.setDistributionMode(GridCacheDistributionMode.PARTITIONED_ONLY);
        ccfg.setAtomicityMode(GridCacheAtomicityMode.TRANSACTIONAL);
        ccfg.setOffHeapMaxMemory(0);
        ccfg.setMemoryMode(GridCacheMemoryMode.OFFHEAP_TIERED);

        cfg.setCacheConfiguration(ccfg);

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testUpdateInPessimisticTxOnRemoteNode() throws Exception {
        try {
            Grid grid = startGrids(2);

            GridCache<Object, Object> rmtCache = grid.cache(null);

            int key = 0;

            while (!rmtCache.affinity().isPrimary(grid(1).localNode(), key))
                key++;

            GridCache<Object, Object> locCache = grid(1).cache(null);

            try (GridCacheTx tx = locCache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                locCache.putxIfAbsent(key, 0);

                tx.commit();
            }

            try (GridCacheTx tx = rmtCache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                assertEquals(0, rmtCache.get(key));

                rmtCache.putx(key, 1);

                tx.commit();
            }

            try (GridCacheTx tx = rmtCache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                assertEquals(1, rmtCache.get(key));

                rmtCache.putx(key, 2);

                tx.commit();
            }
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testReadEvictedPartition() throws Exception {
        try {
            Grid grid = startGrid(0);

            GridCache<Object, Object> cache = grid.cache(null);

            for (int i = 0; i < 30; i++)
                cache.put(i, 0);

            startGrid(1);

            awaitPartitionMapExchange();

            for (int i = 0; i < 30; i++)
                grid(1).cache(null).put(i, 10);

            // Find a key that does not belong to started node anymore.
            int key = 0;

            GridNode locNode = grid.localNode();

            for (;key < 30; key++) {
                if (!cache.affinity().isPrimary(locNode, key) && !cache.affinity().isBackup(locNode, key))
                    break;
            }

            assertEquals(10, cache.get(key));

            try (GridCacheTx ignored = cache.txStart(OPTIMISTIC, REPEATABLE_READ)) {
                assertEquals(10, cache.get(key));
            }

            try (GridCacheTx ignored = cache.txStart(PESSIMISTIC, READ_COMMITTED)) {
                assertEquals(10, cache.get(key));
            }
        }
        finally {
            stopAllGrids();
        }
    }
}
