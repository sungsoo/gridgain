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
import java.util.concurrent.atomic.*;

/**
 *
 */
@GridSpiTest(spi = GridTcpCommunicationSpi.class, group = "Communication SPI")
public class GridTcpCommunicationSpiRecoveryAckSelfTest<T extends GridCommunicationSpi> extends GridSpiAbstractTest<T> {
    /** */
    private static final Collection<GridTestResources> spiRsrcs = new ArrayList<>();

    /** */
    protected static final List<GridTcpCommunicationSpi> spis = new ArrayList<>();

    /** */
    protected static final List<GridNode> nodes = new ArrayList<>();

    /** */
    private static final int SPI_CNT = 2;

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
    public GridTcpCommunicationSpiRecoveryAckSelfTest() {
        super(false);
    }

    /** */
    @SuppressWarnings({"deprecation"})
    private class TestListener implements GridCommunicationListener<GridTcpCommunicationMessageAdapter> {
        /** */
        private ConcurrentHashSet<Long> msgIds = new ConcurrentHashSet<>();

        /** */
        private AtomicInteger rcvCnt = new AtomicInteger();

        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, GridTcpCommunicationMessageAdapter msg, GridRunnable msgC) {
            info("Test listener received message: " + msg);

            assertTrue("Unexpected message: " + msg, msg instanceof GridTestMessage);

            GridTestMessage msg0 = (GridTestMessage)msg;

            assertTrue("Duplicated message received: " + msg0, msgIds.add(msg0.getMsgId()));

            rcvCnt.incrementAndGet();

            msgC.run();
        }

        /** {@inheritDoc} */
        @Override public void onDisconnected(UUID nodeId) {
            // No-op.
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testAckOnIdle() throws Exception {
        checkAck(10, 2000, 9);
    }

    /**
     * @throws Exception If failed.
     */
    public void testAckOnCount() throws Exception {
        checkAck(10, 60_000, 10);
    }

    /**
     * @param ackCnt Recovery acknowledgement count.
     * @param idleTimeout Idle connection timeout.
     * @param msgPerIter Messages per iteration.
     * @throws Exception If failed.
     */
    private void checkAck(int ackCnt, int idleTimeout, int msgPerIter) throws Exception {
        createSpis(ackCnt, idleTimeout, GridTcpCommunicationSpi.DFLT_MSG_QUEUE_LIMIT);

        try {
            GridTcpCommunicationSpi spi0 = spis.get(0);
            GridTcpCommunicationSpi spi1 = spis.get(1);

            GridNode node0 = nodes.get(0);
            GridNode node1 = nodes.get(1);

            int msgId = 0;

            int expMsgs = 0;

            for (int i = 0; i < 5; i++) {
                info("Iteration: " + i);

                for (int j = 0; j < msgPerIter; j++) {
                    spi0.sendMessage(node1, new GridTestMessage(node0.id(), ++msgId, 0));

                    spi1.sendMessage(node0, new GridTestMessage(node1.id(), ++msgId, 0));
                }

                expMsgs += msgPerIter;

                for (GridTcpCommunicationSpi spi : spis) {
                    GridNioServer srv = U.field(spi, "nioSrvr");

                    Collection<? extends GridNioSession> sessions = GridTestUtils.getFieldValue(srv, "sessions");

                    assertFalse(sessions.isEmpty());

                    boolean found = false;

                    for (GridNioSession ses : sessions) {
                        final GridNioRecoveryDescriptor recoveryDesc = ses.recoveryDescriptor();

                        if (recoveryDesc != null) {
                            found = true;

                            GridTestUtils.waitForCondition(new GridAbsPredicate() {
                                @Override public boolean apply() {
                                    return recoveryDesc.messagesFutures().isEmpty();
                                }
                            }, 10_000);

                            assertEquals("Unexpected messages: " + recoveryDesc.messagesFutures(), 0,
                                recoveryDesc.messagesFutures().size());

                            break;
                        }
                    }

                    assertTrue(found);
                }

                final int expMsgs0 = expMsgs;

                for (GridTcpCommunicationSpi spi : spis) {
                    final TestListener lsnr = (TestListener)spi.getListener();

                    GridTestUtils.waitForCondition(new GridAbsPredicate() {
                        @Override public boolean apply() {
                            return lsnr.rcvCnt.get() >= expMsgs0;
                        }
                    }, 5000);

                    assertEquals(expMsgs, lsnr.rcvCnt.get());
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
    public void testQueueOverflow() throws Exception {
        for (int i = 0; i < 3; i++) {
            try {
                startSpis(5, 60_000, 10);

                checkOverflow();

                break;
            }
            catch (GridException e) {
                if (e.hasCause(BindException.class)) {
                    if (i < 2) {
                        info("Got exception caused by BindException, will retry after delay: " + e);

                        stopSpis();

                        U.sleep(10_000);
                    }
                    else
                        throw e;
                }
                else
                    throw e;
            }
            finally {
                stopSpis();
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void checkOverflow() throws Exception {
        GridTcpCommunicationSpi spi0 = spis.get(0);
        GridTcpCommunicationSpi spi1 = spis.get(1);

        GridNode node0 = nodes.get(0);
        GridNode node1 = nodes.get(1);

        final GridNioServer srv1 = U.field(spi1, "nioSrvr");

        int msgId = 0;

        // Send message to establish connection.
        spi0.sendMessage(node1, new GridTestMessage(node0.id(), ++msgId, 0));

        // Prevent node1 from send
        GridTestUtils.setFieldValue(srv1, "skipWrite", true);

        final GridNioSession ses0 = communicationSession(spi0);

        for (int i = 0; i < 150; i++)
            spi0.sendMessage(node1, new GridTestMessage(node0.id(), ++msgId, 0));

        // Wait when session is closed because of queue overflow.
        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return ses0.closeTime() != 0;
            }
        }, 5000);

        assertTrue("Failed to wait for session close", ses0.closeTime() != 0);

        GridTestUtils.setFieldValue(srv1, "skipWrite", false);

        for (int i = 0; i < 100; i++)
            spi0.sendMessage(node1, new GridTestMessage(node0.id(), ++msgId, 0));

        final int expMsgs = 251;

        final TestListener lsnr = (TestListener)spi1.getListener();

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return lsnr.rcvCnt.get() >= expMsgs;
            }
        }, 5000);

        assertEquals(expMsgs, lsnr.rcvCnt.get());
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
     * @param ackCnt Recovery acknowledgement count.
     * @param idleTimeout Idle connection timeout.
     * @param queueLimit Message queue limit.
     * @return SPI instance.
     */
    protected GridTcpCommunicationSpi getSpi(int ackCnt, int idleTimeout, int queueLimit) {
        GridTcpCommunicationSpi spi = new GridTcpCommunicationSpi();

        spi.setSharedMemoryPort(-1);
        spi.setLocalPort(GridTestUtils.getNextCommPort(getClass()));
        spi.setIdleConnectionTimeout(idleTimeout);
        spi.setTcpNoDelay(true);
        spi.setAckSendThreshold(ackCnt);
        spi.setMessageQueueLimit(queueLimit);

        return spi;
    }

    /**
     * @param ackCnt Recovery acknowledgement count.
     * @param idleTimeout Idle connection timeout.
     * @param queueLimit Message queue limit.
     * @throws Exception If failed.
     */
    private void startSpis(int ackCnt, int idleTimeout, int queueLimit) throws Exception {
        spis.clear();
        nodes.clear();
        spiRsrcs.clear();

        Map<GridNode, GridSpiTestContext> ctxs = new HashMap<>();

        for (int i = 0; i < SPI_CNT; i++) {
            GridTcpCommunicationSpi spi = getSpi(ackCnt, idleTimeout, queueLimit);

            GridTestUtils.setFieldValue(spi, "gridName", "grid-" + i);

            GridTestResources rsrcs = new GridTestResources();

            GridTestNode node = new GridTestNode(rsrcs.getNodeId());

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
     * @param ackCnt Recovery acknowledgement count.
     * @param idleTimeout Idle connection timeout.
     * @param queueLimit Message queue limit.
     * @throws Exception If failed.
     */
    private void createSpis(int ackCnt, int idleTimeout, int queueLimit) throws Exception {
        for (int i = 0; i < 3; i++) {
            try {
                startSpis(ackCnt, idleTimeout, queueLimit);

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

        for (GridTestResources rsrcs : spiRsrcs)
            rsrcs.stopThreads();

        spis.clear();
        nodes.clear();
        spiRsrcs.clear();
    }
}
