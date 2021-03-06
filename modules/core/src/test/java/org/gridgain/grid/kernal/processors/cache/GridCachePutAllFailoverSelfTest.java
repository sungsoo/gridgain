package org.gridgain.grid.kernal.processors.cache;

import com.google.common.collect.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.discovery.tcp.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.spi.failover.*;
import org.gridgain.grid.spi.failover.always.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * Tests putAll() method along with failover and different configurations.
 */
public class GridCachePutAllFailoverSelfTest extends GridCommonAbstractTest {
    /** IP finder. */
    private static GridTcpDiscoveryIpFinder ipFinder = new GridTcpDiscoveryVmIpFinder(true);

    /** Size of the test map. */
    private static final int TEST_MAP_SIZE = 100000;

    /** Cache name. */
    private static final String CACHE_NAME = "partitioned";

    /** Size of data chunk, sent to a remote node. */
    private static final int DATA_CHUNK_SIZE = 1000;

    /** Number of chunk on which to fail worker node. */
    public static final int FAIL_ON_CHUNK_NO = (TEST_MAP_SIZE / DATA_CHUNK_SIZE) / 3;

    /** Await timeout in seconds. */
    public static final int AWAIT_TIMEOUT_SEC = 65;

    /** */
    private static final int FAILOVER_PUSH_GAP = 30;

    /** Master node name. */
    private static final String MASTER = "master";

    /** Near enabled flag. */
    private boolean nearEnabled;

    /** Backups count. */
    private int backups;

    /** Filter to include only worker nodes. */
    private static final GridPredicate<GridNode> workerNodesFilter = new PN() {
        @SuppressWarnings("unchecked")
        @Override public boolean apply(GridNode n) {
             return "worker".equals(n.attribute("segment"));
        }
    };

    /**
     * Result future queue (restrict the queue size
     * to 50 in order to prevent in-memory data grid from over loading).
     */
    private final BlockingQueue<GridComputeTaskFuture<?>> resQueue = new LinkedBlockingQueue<>(50);

    /** Test failover SPI. */
    private MasterFailoverSpi failoverSpi = new MasterFailoverSpi((GridPredicate)workerNodesFilter);

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverColocatedNearEnabledThreeBackups() throws Exception {
        checkPutAllFailoverColocated(true, 7, 3);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverColocatedNearDisabledThreeBackups() throws Exception {
        checkPutAllFailoverColocated(false, 7, 3);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverNearEnabledOneBackup() throws Exception {
        checkPutAllFailover(true, 3, 1);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverNearDisabledOneBackup() throws Exception {
        checkPutAllFailover(false, 3, 1);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverNearEnabledTwoBackups() throws Exception {
        checkPutAllFailover(true, 5, 2);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverNearDisabledTwoBackups() throws Exception {
        checkPutAllFailover(false, 5, 2);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverNearEnabledThreeBackups() throws Exception {
        checkPutAllFailover(true, 7, 3);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverNearDisabledThreeBackups() throws Exception {
        checkPutAllFailover(false, 7, 3);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverColocatedNearEnabledOneBackup() throws Exception {
        checkPutAllFailoverColocated(true, 3, 1);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverColocatedNearDisabledOneBackup() throws Exception {
        checkPutAllFailoverColocated(false, 3, 1);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverColocatedNearEnabledTwoBackups() throws Exception {
        checkPutAllFailoverColocated(true, 5, 2);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAllFailoverColocatedNearDisabledTwoBackups() throws Exception {
        checkPutAllFailoverColocated(false, 5, 2);
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return super.getTestTimeout() * 5;
    }

    /**
     * Tests putAll() method along with failover and cache backup.
     *
     * Checks that the resulting primary cache size is the same as
     * expected.
     *
     * @param near Near enabled.
     * @param workerCnt Worker count.
     * @param shutdownCnt Shutdown count.
     * @throws Exception If failed.
     */
    public void checkPutAllFailover(boolean near, int workerCnt, int shutdownCnt) throws Exception {
        nearEnabled = near;
        backups = shutdownCnt;

        Collection<Integer> testKeys = generateTestKeys();

        Grid master = startGrid(MASTER);

        List<Grid> workers = new ArrayList<>(workerCnt);

        for (int i = 1; i <= workerCnt; i++)
            workers.add(startGrid("worker" + i));

        info("Master: " + master.localNode().id());

        List<Grid> runningWorkers = new ArrayList<>(workerCnt);

        for (int i = 1; i <= workerCnt; i++) {
            UUID id = workers.get(i - 1).localNode().id();

            info(String.format("Worker%d - %s", i, id));

            runningWorkers.add(workers.get(i - 1));
        }

        try {
            // Dummy call to fetch affinity function from remote node
            master.mapKeyToNode(CACHE_NAME, "Dummy");

            Random rnd = new Random();

            Collection<Integer> dataChunk = new ArrayList<>(DATA_CHUNK_SIZE);
            int entryCntr = 0;
            int chunkCntr = 0;
            final AtomicBoolean jobFailed = new AtomicBoolean(false);

            int failoverPushGap = 0;

            final CountDownLatch emptyLatch = new CountDownLatch(1);

            final AtomicBoolean inputExhausted = new AtomicBoolean();

            for (Integer key : testKeys) {
                dataChunk.add(key);
                entryCntr++;

                if (entryCntr == DATA_CHUNK_SIZE) { // time to send data
                    chunkCntr++;

                    assert dataChunk.size() == DATA_CHUNK_SIZE;

                    log.info("Pushing data chunk [chunkNo=" + chunkCntr + "]");

                    GridComputeTaskFuture<Void> fut = master.forPredicate(workerNodesFilter).compute().execute(
                        new GridCachePutAllTask(
                            runningWorkers.get(rnd.nextInt(runningWorkers.size())).localNode().id(), CACHE_NAME),
                        dataChunk);

                    resQueue.put(fut); // Blocks if queue is full.

                    fut.listenAsync(new CI1<GridFuture<Void>>() {
                        @Override public void apply(GridFuture<Void> f) {
                            GridComputeTaskFuture<?> taskFut = (GridComputeTaskFuture<?>)f;

                            try {
                                taskFut.get(); //if something went wrong - we'll get exception here
                            }
                            catch (GridException e) {
                                log.error("Job failed", e);

                                jobFailed.set(true);
                            }

                            // Remove complete future from queue to allow other jobs to proceed.
                            resQueue.remove(taskFut);

                            if (inputExhausted.get() && resQueue.isEmpty())
                                emptyLatch.countDown();
                        }
                    });

                    entryCntr = 0;
                    dataChunk = new ArrayList<>(DATA_CHUNK_SIZE);

                    if (chunkCntr >= FAIL_ON_CHUNK_NO) {
                        if (workerCnt - runningWorkers.size() < shutdownCnt) {
                            if (failoverPushGap > 0)
                                failoverPushGap--;
                            else {
                                Grid victim = runningWorkers.remove(0);

                                info("Shutting down node: " + victim.localNode().id());

                                stopGrid(victim.name());

                                // Fail next node after some jobs have been pushed.
                                failoverPushGap = FAILOVER_PUSH_GAP;
                            }
                        }
                    }
                }
            }

            inputExhausted.set(true);

            if (resQueue.isEmpty())
                emptyLatch.countDown();

            assert chunkCntr == TEST_MAP_SIZE / DATA_CHUNK_SIZE;

            // Wait for queue to empty.
            log.info("Waiting for empty queue...");

            boolean failedWait = false;

            if (!emptyLatch.await(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                info(">>> Failed to wait for queue to empty.");

                failedWait = true;
            }

            if (!failedWait)
                assertFalse("One or more jobs have failed.", jobFailed.get());

            Collection<Integer> absentKeys = findAbsentKeys(runningWorkers.get(0), testKeys);

            if (!failedWait && !absentKeys.isEmpty()) {
                // Give some time to preloader.
                U.sleep(15000);

                absentKeys = findAbsentKeys(runningWorkers.get(0), testKeys);
            }

            info(">>> Absent keys: " + absentKeys);

            assertTrue(absentKeys.isEmpty());

            // Actual primary cache size.
            int primaryCacheSize = 0;

            for (Grid g : runningWorkers) {
                info(">>>>> " + g.cache(CACHE_NAME).size());

                primaryCacheSize += g.cache(CACHE_NAME).primarySize();
            }

            assertEquals(TEST_MAP_SIZE, primaryCacheSize);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * Tests putAll() method along with failover and cache backup.
     *
     * Checks that the resulting primary cache size is the same as
     * expected.
     *
     * @param near Near enabled.
     * @param workerCnt Worker count.
     * @param shutdownCnt Shutdown count.
     * @throws Exception If failed.
     */
    public void checkPutAllFailoverColocated(boolean near, int workerCnt, int shutdownCnt) throws Exception {
        nearEnabled = near;
        backups = shutdownCnt;

        Collection<Integer> testKeys = generateTestKeys();

        Grid master = startGrid(MASTER);

        List<Grid> workers = new ArrayList<>(workerCnt);

        for (int i = 1; i <= workerCnt; i++)
            workers.add(startGrid("worker" + i));

        info("Master: " + master.localNode().id());

        List<Grid> runningWorkers = new ArrayList<>(workerCnt);

        for (int i = 1; i <= workerCnt; i++) {
            UUID id = workers.get(i - 1).localNode().id();

            info(String.format("Worker%d: %s", i, id));

            runningWorkers.add(workers.get(i - 1));
        }

        try {
            // Dummy call to fetch affinity function from remote node
            master.mapKeyToNode(CACHE_NAME, "Dummy");

            Map<UUID, Collection<Integer>> dataChunks = new HashMap<>();

            int chunkCntr = 0;
            final AtomicBoolean jobFailed = new AtomicBoolean(false);

            int failoverPushGap = 0;

            final CountDownLatch emptyLatch = new CountDownLatch(1);

            final AtomicBoolean inputExhausted = new AtomicBoolean();

            for (Integer key : testKeys) {
                GridNode mappedNode = master.mapKeyToNode(CACHE_NAME, key);

                UUID nodeId = mappedNode.id();

                Collection<Integer> data = dataChunks.get(nodeId);

                if (data == null) {
                    data = new ArrayList<>(DATA_CHUNK_SIZE);

                    dataChunks.put(nodeId, data);
                }

                data.add(key);

                if (data.size() == DATA_CHUNK_SIZE) { // time to send data
                    chunkCntr++;

                    log.info("Pushing data chunk [chunkNo=" + chunkCntr + "]");

                    GridComputeTaskFuture<Void> fut = master.forPredicate(workerNodesFilter).compute().execute(
                        new GridCachePutAllTask(nodeId, CACHE_NAME),
                        data);

                    resQueue.put(fut); // Blocks if queue is full.

                    fut.listenAsync(new CI1<GridFuture<Void>>() {
                        @Override public void apply(GridFuture<Void> f) {
                            GridComputeTaskFuture<?> taskFut = (GridComputeTaskFuture<?>)f;

                            try {
                                taskFut.get(); //if something went wrong - we'll get exception here
                            }
                            catch (GridException e) {
                                log.error("Job failed", e);

                                jobFailed.set(true);
                            }

                            // Remove complete future from queue to allow other jobs to proceed.
                            resQueue.remove(taskFut);

                            if (inputExhausted.get() && resQueue.isEmpty())
                                emptyLatch.countDown();
                        }
                    });

                    data = new ArrayList<>(DATA_CHUNK_SIZE);

                    dataChunks.put(nodeId, data);

                    if (chunkCntr >= FAIL_ON_CHUNK_NO) {
                        if (workerCnt - runningWorkers.size() < shutdownCnt) {
                            if (failoverPushGap > 0)
                                failoverPushGap--;
                            else {
                                Grid victim = runningWorkers.remove(0);

                                info("Shutting down node: " + victim.localNode().id());

                                stopGrid(victim.name());

                                // Fail next node after some jobs have been pushed.
                                failoverPushGap = FAILOVER_PUSH_GAP;
                            }
                        }
                    }
                }
            }

            for (Map.Entry<UUID, Collection<Integer>> entry : dataChunks.entrySet()) {
                GridComputeTaskFuture<Void> fut = master.forPredicate(workerNodesFilter).compute().execute(
                    new GridCachePutAllTask(entry.getKey(), CACHE_NAME),
                    entry.getValue());

                resQueue.put(fut); // Blocks if queue is full.

                fut.listenAsync(new CI1<GridFuture<Void>>() {
                    @Override public void apply(GridFuture<Void> f) {
                        GridComputeTaskFuture<?> taskFut = (GridComputeTaskFuture<?>)f;

                        try {
                            taskFut.get(); //if something went wrong - we'll get exception here
                        }
                        catch (GridException e) {
                            log.error("Job failed", e);

                            jobFailed.set(true);
                        }

                        // Remove complete future from queue to allow other jobs to proceed.
                        resQueue.remove(taskFut);

                        if (inputExhausted.get() && resQueue.isEmpty())
                            emptyLatch.countDown();
                    }
                });
            }

            inputExhausted.set(true);

            if (resQueue.isEmpty())
                emptyLatch.countDown();

            // Wait for queue to empty.
            log.info("Waiting for empty queue...");

            boolean failedWait = false;

            if (!emptyLatch.await(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                info(">>> Failed to wait for queue to empty.");

                failedWait = true;
            }

            if (!failedWait)
                assertFalse("One or more jobs have failed.", jobFailed.get());

            Collection<Integer> absentKeys = findAbsentKeys(runningWorkers.get(0), testKeys);

            if (!failedWait && !absentKeys.isEmpty()) {
                // Give some time to preloader.
                U.sleep(15000);

                absentKeys = findAbsentKeys(runningWorkers.get(0), testKeys);
            }

            info(">>> Absent keys: " + absentKeys);

            assertTrue(absentKeys.isEmpty());

            // Actual primary cache size.
            int primaryCacheSize = 0;

            for (Grid g : runningWorkers) {
                info(">>>>> " + g.cache(CACHE_NAME).size());

                primaryCacheSize += g.cache(CACHE_NAME).primarySize();
            }

            assertEquals(TEST_MAP_SIZE, primaryCacheSize);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * Tries to find keys, that are absent in cache.
     *
     * @param workerNode Worker node.
     * @param keys Keys that are suspected to be absent
     * @return List of absent keys. If no keys are absent, the list is empty.
     * @throws GridException If error occurs.
     */
    private Collection<Integer> findAbsentKeys(Grid workerNode,
        Collection<Integer> keys) throws GridException {

        Collection<Integer> ret = new ArrayList<>(keys.size());

        GridCache<Object, Object> cache = workerNode.cache(CACHE_NAME);

        for (Integer key : keys) {
            if (cache.get(key) == null) // Key is absent.
                ret.add(key);
        }

        return ret;
    }

    /**
     * Generates a test keys collection.
     *
     * @return A test keys collection.
     */
    private Collection<Integer> generateTestKeys() {
        Collection<Integer> ret = new ArrayList<>(TEST_MAP_SIZE);

        for (int i = 0; i < TEST_MAP_SIZE; i++)
            ret.add(i);

        return ret;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected GridConfiguration getConfiguration(String gridName) throws Exception {
        GridConfiguration cfg = super.getConfiguration(gridName);

        cfg.setPeerClassLoadingEnabled(false);

        cfg.setDeploymentMode(GridDeploymentMode.CONTINUOUS);

        GridTcpDiscoverySpi discoverySpi = new GridTcpDiscoverySpi();

        discoverySpi.setAckTimeout(60000);
        discoverySpi.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(discoverySpi);

        if (gridName.startsWith("master")) {
            cfg.setUserAttributes(ImmutableMap.of("segment", "master"));

            // For sure.
            failoverSpi.setMaximumFailoverAttempts(50);

            cfg.setFailoverSpi(failoverSpi);
        }
        else if (gridName.startsWith("worker")) {
            cfg.setUserAttributes(ImmutableMap.of("segment", "worker"));

            GridCacheConfiguration cacheCfg = defaultCacheConfiguration();
            cacheCfg.setName("partitioned");
            cacheCfg.setCacheMode(GridCacheMode.PARTITIONED);
            cacheCfg.setStartSize(4500000);

            cacheCfg.setBackups(backups);

            cacheCfg.setDgcSuspectLockTimeout(600000);
            cacheCfg.setDgcFrequency(0);
            cacheCfg.setStoreValueBytes(true);
            cacheCfg.setDistributionMode(nearEnabled ? NEAR_PARTITIONED : PARTITIONED_ONLY);
            cacheCfg.setQueryIndexEnabled(false);

            cacheCfg.setWriteSynchronizationMode(FULL_SYNC);

            cfg.setCacheConfiguration(cacheCfg);
        }
        else
            throw new IllegalStateException("Unexpected grid name: " + gridName);

        return cfg;
    }

    /**
     * Test failover SPI for master node.
     */
    @GridSpiConsistencyChecked(optional = true)
    private static class MasterFailoverSpi extends GridAlwaysFailoverSpi {
        /** */
        private static final String FAILOVER_NUMBER_ATTR = "failover:number:attr";

        /** */
        private Set<GridComputeJobContext> failedOverJobs = new HashSet<>();

        /** Node filter. */
        private GridPredicate<? super GridNode>[] filter;

        /** */
        @GridLoggerResource
        private GridLogger log;

        /**
         * @param filter Filter.
         */
        MasterFailoverSpi(GridPredicate<? super GridNode>... filter) {
            this.filter = filter;
        }

        /** {@inheritDoc} */
        @Override public GridNode failover(GridFailoverContext ctx, List<GridNode> top) {
            failedOverJobs.add(ctx.getJobResult().getJobContext());

            // Clear failed nodes list - allow to failover on the same node.
            ctx.getJobResult().getJobContext().setAttribute(FAILED_NODE_LIST_ATTR, null);

            // Account for maximum number of failover attempts since we clear failed node list.
            Integer failoverCnt = ctx.getJobResult().getJobContext().getAttribute(FAILOVER_NUMBER_ATTR);

            if (failoverCnt == null)
                ctx.getJobResult().getJobContext().setAttribute(FAILOVER_NUMBER_ATTR, 1);
            else {
                if (failoverCnt >= getMaximumFailoverAttempts()) {
                    U.warn(log, "Job failover failed because number of maximum failover attempts is exceeded " +
                        "[failedJob=" + ctx.getJobResult().getJob() + ", maxFailoverAttempts=" +
                        getMaximumFailoverAttempts() + ']');

                    return null;
                }

                ctx.getJobResult().getJobContext().setAttribute(FAILOVER_NUMBER_ATTR, failoverCnt + 1);
            }

            List<GridNode> cp = new ArrayList<>(top);

            // Keep collection type.
            F.retain(cp, false, new GridPredicate<GridNode>() {
                @Override public boolean apply(GridNode node) {
                    return F.isAll(node, filter);
                }
            });

            return super.failover(ctx, cp); //use cp to ensure we don't failover on failed node
        }

        /**
         * @return Job contexts for failed over jobs.
         */
        public Set<GridComputeJobContext> getFailedOverJobs() {
            return failedOverJobs;
        }
    }
}
