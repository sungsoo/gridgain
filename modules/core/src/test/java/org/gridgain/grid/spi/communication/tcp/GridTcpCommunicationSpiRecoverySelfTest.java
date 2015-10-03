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

package org.gridgain.grid.spi.communication.tcp;

import org.eclipse.jetty.util.*;
import org.gridgain.grid.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.communication.*;
import org.gridgain.grid.util.direct.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.nio.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.*;
import org.gridgain.testframework.junits.spi.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 *
 */
@GridSpiTest(spi = GridTcpCommunicationSpi.class, group = "Communication SPI")
public class GridTcpCommunicationSpiRecoverySelfTest<T extends GridCommunicationSpi> extends GridSpiAbstractTest<T> {
    /** */
    private static final Collection<GridTestResources> spiRsrcs = new ArrayList<>();

    /** */
    protected static final List<GridTcpCommunicationSpi> spis = new ArrayList<>();

    /** */
    protected static final List<GridNode> nodes = new ArrayList<>();

    /** */
    private static final int SPI_CNT = 2;

    /** */
    private static final int ITERS = 10;

    /** */
    private static int port = 30_000;

    /**
     *
     */
    static {
        GridTcpCommunicationMessageFactory.registerCustom(new GridTcpCommunicationMessageProducer() {
            @Override public GridTcpCommunicationMessageAdapter create(byte type) {
                return new GridTestMessage();
            }
        }, GridTestMessage.DIRECT_TYPE);
    }

    /**
     * Disable SPI auto-start.
     */
    public GridTcpCommunicationSpiRecoverySelfTest() {
        super(false);
    }

    /** */
    @SuppressWarnings({"deprecation"})
    private class TestListener implements GridCommunicationListener<GridTcpCommunicationMessageAdapter> {
        /** */
        private boolean block;

        /** */
        private CountDownLatch blockLatch;

        /** */
        private ConcurrentHashSet<Long> msgIds = new ConcurrentHashSet<>();

        /** */
        private AtomicInteger rcvCnt = new AtomicInteger();

        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, GridTcpCommunicationMessageAdapter msg, GridRunnable msgC) {
            // info("Test listener received message: " + msg);

            assertTrue("Unexpected message: " + msg, msg instanceof GridTestMessage);

            GridTestMessage msg0 = (GridTestMessage)msg;

            assertTrue("Duplicated message received: " + msg0, msgIds.add(msg0.getMsgId()));

            rcvCnt.incrementAndGet();

            msgC.run();

            try {
                synchronized (this) {
                    while (block) {
                        info("Test listener blocks.");

                        assert blockLatch != null;

                        blockLatch.countDown();

                        wait();

                        if (block)
                            continue;

                        info("Test listener throws exception.");

                        throw new RuntimeException("Test exception.");
                    }
                }
            }
            catch (InterruptedException e) {
                fail("Unexpected error: " + e);
            }
        }

        /**
         *
         */
        void block() {
            synchronized (this) {
                block = true;

                blockLatch = new CountDownLatch(1);
            }
        }

        /**
         *
         */
        void unblock() {
            synchronized (this) {
                block = false;

                notifyAll();
            }
        }

        /** {@inheritDoc} */
        @Override public void onDisconnected(UUID nodeId) {
            // No-op.
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testBlockListener() throws Exception {
        // Test listener throws exception and stops selector thread, so must restart SPI.
        for (int i = 0; i < ITERS; i++) {
            log.info("Creating SPIs: " + i);

            createSpis();

            try {
                checkBlockListener();
            }
            finally {
                stopSpis();
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    @SuppressWarnings("BusyWait")
    private void checkBlockListener() throws Exception {
        GridTcpCommunicationSpi spi0 = spis.get(0);
        GridTcpCommunicationSpi spi1 = spis.get(1);

        final TestListener lsnr0 = (TestListener)spi0.getListener();
        final TestListener lsnr1 = (TestListener)spi1.getListener();

        GridNode node0 = nodes.get(0);
        GridNode node1 = nodes.get(1);

        lsnr1.block();

        int msgId = 0;

        for (int j = 0; j < 10; j++) {
            spi0.sendMessage(node1, new GridTestMessage(node0.id(), ++msgId, 0));

            spi1.sendMessage(node0, new GridTestMessage(node1.id(), ++msgId, 0));
        }

        lsnr1.blockLatch.await();

        lsnr1.unblock();

        Thread.sleep(500);

        int errCnt = 0;

        int msgs = 0;

        while (true) {
            try {
                int id = msgId + 1;

                spi0.sendMessage(node1, new GridTestMessage(node0.id(), id, 0));

                msgId++;

                msgs++;

                if (msgs == 10)
                    break;
            }
            catch (GridSpiException e) {
                errCnt++;

                if (errCnt > 10)
                    fail("Failed to send message: " + e);
            }
        }

        for (int j = 0; j < 10; j++)
            spi1.sendMessage(node0, new GridTestMessage(node1.id(), ++msgId, 0));

        final int expMsgs = 20;

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return lsnr0.rcvCnt.get() >= expMsgs && lsnr1.rcvCnt.get() >= expMsgs;
            }
        }, 5000);

        assertEquals(expMsgs, lsnr0.rcvCnt.get());
        assertEquals(expMsgs, lsnr1.rcvCnt.get());
    }

    /**
     * @throws Exception If failed.
     */
    public void testBlockRead1() throws Exception {
        createSpis();

        try {
            final GridTcpCommunicationSpi spi0 = spis.get(0);
            final GridTcpCommunicationSpi spi1 = spis.get(1);

            final TestListener lsnr1 = (TestListener)spi1.getListener();

            final GridNode node0 = nodes.get(0);
            final GridNode node1 = nodes.get(1);

            final AtomicInteger msgId = new AtomicInteger();

            // Send message to establish connection.
            spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

            final AtomicInteger sentCnt = new AtomicInteger(1);

            int errCnt = 0;

            for (int i = 0; i < ITERS; i++) {
                log.info("Iteration: " + i);

                try {
                    final GridNioSession ses0 = communicationSession(spi0);
                    final GridNioSession ses1 = communicationSession(spi1);

                    ses1.pauseReads().get();

                    GridFuture<?> sndFut = GridTestUtils.runAsync(new Callable<Void>() {
                        @Override public Void call() throws Exception {
                            for (int i = 0; i < 5000; i++) {
                                spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

                                sentCnt.incrementAndGet();
                            }

                            return null;
                        }
                    });

                    // Wait when session is closed because of write timeout.
                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return ses0.closeTime() != 0;
                        }
                    }, 5000);

                    assertTrue("Failed to wait for session close", ses0.closeTime() != 0);

                    ses1.resumeReads().get();

                    for (int j = 0; j < 100; j++) {
                        spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

                        sentCnt.incrementAndGet();
                    }

                    sndFut.get();

                    final int expMsgs = sentCnt.get();

                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return lsnr1.rcvCnt.get() >= expMsgs;
                        }
                    }, 60_000);

                    assertEquals(expMsgs, lsnr1.rcvCnt.get());
                }
                catch (GridException e) {
                    if (e.hasCause(BindException.class)) {
                        errCnt++;

                        if (errCnt > 3) {
                            log.warning("Got exception > 3 times, test fails.");

                            throw e;
                        }

                        if (i < ITERS - 1) {
                            info("Got exception caused by BindException, will retry after delay: " + e);

                            U.sleep(10_000);
                        }
                        else
                            info("Got exception caused by BindException, will ignore: " + e);
                    }
                    else {
                        log.warning("Unexpected exception: " + e, e);

                        throw e;
                    }
                }
            }
        }
        finally {
            stopSpis();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testBlockRead2() throws Exception {
        createSpis();

        try {
            final GridTcpCommunicationSpi spi0 = spis.get(0);
            final GridTcpCommunicationSpi spi1 = spis.get(1);

            final TestListener lsnr0 = (TestListener)spi0.getListener();
            final TestListener lsnr1 = (TestListener)spi1.getListener();

            final GridNode node0 = nodes.get(0);
            final GridNode node1 = nodes.get(1);

            final AtomicInteger msgId = new AtomicInteger();

            final AtomicInteger expCnt0 = new AtomicInteger();

            final AtomicInteger expCnt1 = new AtomicInteger();

            // Send message to establish connection.
            spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

            expCnt1.incrementAndGet();

            int errCnt = 0;

            for (int i = 0; i < ITERS; i++) {
                log.info("Iteration: " + i);

                try {
                    final GridNioSession ses0 = communicationSession(spi0);
                    final GridNioSession ses1 = communicationSession(spi1);

                    ses1.pauseReads().get();

                    GridFuture<?> sndFut = GridTestUtils.runAsync(new Callable<Void>() {
                        @Override public Void call() throws Exception {
                            for (int i = 0; i < 5000; i++) {
                                spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

                                expCnt1.incrementAndGet();
                            }

                            return null;
                        }
                    });

                    // Wait when session is closed because of write timeout.
                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return ses0.closeTime() != 0;
                        }
                    }, 5000);

                    assertTrue("Failed to wait for session close", ses0.closeTime() != 0);

                    ses1.resumeReads().get();

                    // Wait when session is closed, then try to open new connection from node1.
                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return ses1.closeTime() != 0;
                        }
                    }, 5000);

                    assertTrue("Failed to wait for session close", ses1.closeTime() != 0);

                    for (int j = 0; j < 100; j++) {
                        spi1.sendMessage(node0, new GridTestMessage(node1.id(), msgId.incrementAndGet(), 0));

                        expCnt0.incrementAndGet();
                    }

                    sndFut.get();

                    final int expMsgs0 = expCnt0.get();
                    final int expMsgs1 = expCnt1.get();

                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return lsnr0.rcvCnt.get() >= expMsgs0 && lsnr1.rcvCnt.get() >= expMsgs1;
                        }
                    }, 60_000);

                    assertEquals(expMsgs0, lsnr0.rcvCnt.get());
                    assertEquals(expMsgs1, lsnr1.rcvCnt.get());
                }
                catch (GridException e) {
                    if (e.hasCause(BindException.class)) {
                        errCnt++;

                        if (errCnt > 3) {
                            log.warning("Got exception > 3 times, test fails.");

                            throw e;
                        }

                        if (i < ITERS - 1) {
                            info("Got exception caused by BindException, will retry after delay: " + e);

                            U.sleep(10_000);
                        }
                        else
                            info("Got exception caused by BindException, will ignore: " + e);
                    }
                    else {
                        log.warning("Unexpected exception: " + e, e);

                        throw e;
                    }
                }
            }
        }
        finally {
            stopSpis();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testBlockRead3() throws Exception {
        createSpis();

        try {
            final GridTcpCommunicationSpi spi0 = spis.get(0);
            final GridTcpCommunicationSpi spi1 = spis.get(1);

            final TestListener lsnr1 = (TestListener)spi1.getListener();

            final GridNode node0 = nodes.get(0);
            final GridNode node1 = nodes.get(1);

            final AtomicInteger msgId = new AtomicInteger();

            // Send message to establish connection.
            spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

            final AtomicInteger sentCnt = new AtomicInteger(1);

            int errCnt = 0;

            for (int i = 0; i < ITERS; i++) {
                log.info("Iteration: " + i);

                try {
                    final GridNioSession ses0 = communicationSession(spi0);
                    final GridNioSession ses1 = communicationSession(spi1);

                    ses1.pauseReads().get();

                    GridFuture<?> sndFut = GridTestUtils.runAsync(new Callable<Void>() {
                        @Override public Void call() throws Exception {
                            for (int i = 0; i < 5000; i++) {
                                spi0.sendMessage(node1, new GridTestMessage(node0.id(), msgId.incrementAndGet(), 0));

                                sentCnt.incrementAndGet();
                            }

                            return null;
                        }
                    });

                    // Wait when session is closed because of write timeout.
                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return ses0.closeTime() != 0;
                        }
                    }, 5000);

                    assertTrue("Failed to wait for session close", ses0.closeTime() != 0);

                    ses1.resumeReads().get();

                    sndFut.get();

                    final int expMsgs = sentCnt.get();

                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return lsnr1.rcvCnt.get() >= expMsgs;
                        }
                    }, 60_000);

                    assertEquals(expMsgs, lsnr1.rcvCnt.get());
                }
                catch (GridException e) {
                    if (e.hasCause(BindException.class)) {
                        errCnt++;

                        if (errCnt > 3) {
                            log.warning("Got exception > 3 times, test fails.");

                            throw e;
                        }

                        if (i < ITERS - 1) {
                            info("Got exception caused by BindException, will retry after delay: " + e);

                            U.sleep(10_000);
                        }
                        else
                            info("Got exception caused by BindException, will ignore: " + e);
                    }
                    else {
                        log.warning("Unexpected exception: " + e, e);

                        throw e;
                    }
                }
            }
        }
        finally {
            stopSpis();
        }
    }

    /**
     * @param spi SPI.
     * @return Session.
     * @throws Exception If failed.
     */
    @SuppressWarnings("unchecked")
    private GridNioSession communicationSession(GridTcpCommunicationSpi spi) throws Exception {
        final GridNioServer srv = U.field(spi, "nioSrvr");

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                Collection<? extends GridNioSession> sessions = GridTestUtils.getFieldValue(srv, "sessions");

                return !sessions.isEmpty();
            }
        }, 5000);

        Collection<? extends GridNioSession> sessions = GridTestUtils.getFieldValue(srv, "sessions");

        assertEquals(1, sessions.size());

        return sessions.iterator().next();
    }

    /**
     * @param idx SPI index.
     * @return SPI instance.
     */
    protected GridTcpCommunicationSpi getSpi(int idx) {
        GridTcpCommunicationSpi spi = new GridTcpCommunicationSpi();

        spi.setSharedMemoryPort(-1);
        spi.setLocalPort(port++);
        spi.setIdleConnectionTimeout(10_000);
        spi.setConnectTimeout(10_000);
        spi.setAckSendThreshold(5);
        spi.setSocketWriteTimeout(1000);
        spi.setSocketSendBuffer(512);
        spi.setSocketReceiveBuffer(512);

        return spi;
    }

    /**
     * @throws Exception If failed.
     */
    private void startSpis() throws Exception {
        spis.clear();
        nodes.clear();
        spiRsrcs.clear();

        Map<GridNode, GridSpiTestContext> ctxs = new HashMap<>();

        for (int i = 0; i < SPI_CNT; i++) {
            GridTcpCommunicationSpi spi = getSpi(i);

            GridTestUtils.setFieldValue(spi, "gridName", "grid-" + i);

            GridTestResources rsrcs = new GridTestResources();

            GridTestNode node = new GridTestNode(rsrcs.getNodeId());

            node.order(i);

            GridSpiTestContext ctx = initSpiContext();

            ctx.setLocalNode(node);

            spiRsrcs.add(rsrcs);

            rsrcs.inject(spi);

            spi.setListener(new TestListener());

            node.setAttributes(spi.getNodeAttributes());

            nodes.add(node);

            spi.spiStart(getTestGridName() + (i + 1));

            spis.add(spi);

            spi.onContextInitialized(ctx);

            ctxs.put(node, ctx);
        }

        // For each context set remote nodes.
        for (Map.Entry<GridNode, GridSpiTestContext> e : ctxs.entrySet()) {
            for (GridNode n : nodes) {
                if (!n.equals(e.getKey()))
                    e.getValue().remoteNodes().add(n);
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void createSpis() throws Exception {
        for (int i = 0; i < 3; i++) {
            try {
                startSpis();

                break;
            }
            catch (GridException e) {
                if (e.hasCause(BindException.class)) {
                    if (i < 2) {
                        info("Failed to start SPIs because of BindException, will retry after delay.");

                        stopSpis();

                        U.sleep(10_000);
                    }
                    else
                        throw e;
                }
                else
                    throw e;
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void stopSpis() throws Exception {
        for (GridCommunicationSpi<GridTcpCommunicationMessageAdapter> spi : spis) {
            spi.onContextDestroyed();

            spi.setListener(null);

            spi.spiStop();
        }

        for (GridTestResources rsrcs : spiRsrcs) {
            rsrcs.stopThreads();
        }

        spis.clear();
        nodes.clear();
        spiRsrcs.clear();
    }
}
