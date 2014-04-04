/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.distributed.near;

import org.gridgain.grid.cache.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;

/**
 * Checks that readers are properly handled for atomic cache.
 */
public class GridCacheAtomicNearReadersSelfTest extends GridCacheNearReadersSelfTest {
    /** {@inheritDoc} */
    @Override protected GridCacheAtomicityMode atomicityMode() {
        return ATOMIC;
    }
}