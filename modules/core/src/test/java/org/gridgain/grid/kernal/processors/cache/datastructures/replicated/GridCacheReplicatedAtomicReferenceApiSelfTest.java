/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.datastructures.replicated;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.datastructures.*;

import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * AtomicReference tests with replicated cache.
 */
public class GridCacheReplicatedAtomicReferenceApiSelfTest extends GridCacheAtomicReferenceApiSelfAbstractTest {

    /** {@inheritDoc} */
    @Override protected GridConfiguration getConfiguration(String gridName) throws Exception {
        GridConfiguration cfg = super.getConfiguration(gridName);

        // Default cache configuration.
        GridCacheConfiguration cacheCfg = getCacheConfiguration();

        cacheCfg.setCacheMode(REPLICATED);
        cacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        cfg.setCacheConfiguration(cacheCfg);

        return cfg;
    }
}