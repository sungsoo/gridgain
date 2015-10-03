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

package org.gridgain.grid.util.nio;

import org.gridgain.grid.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

/**
 * Recovery information for single node.
 */
public class GridNioRecoveryDescriptor {
    /** Number of acknowledged messages. */
    private long acked;

    /** Unacknowledged message futures. */
    private final ArrayDeque<GridNioFuture<?>> msgFuts;

    /** Number of messages to resend. */
    private int resendCnt;

    /** Number of received messages. */
    private long rcvCnt;

    /** Reserved flag. */
    private boolean reserved;

    /** Last acknowledged message. */
    private long lastAck;

    /** Node left flag. */
    private boolean nodeLeft;

    /** Target node. */
    private final GridNode node;

    /** Logger. */
    private final GridLogger log;

    /** Incoming connection request from remote node. */
    private GridBiTuple<Long, GridInClosure<Boolean>> handshakeReq;

    /** Connected flag. */
    private boolean connected;

    /** Number of outgoing connect attempts. */
    private long connectCnt;

    /** Maximum size of unacknowledged messages queue. */
    private final int queueLimit;

    /**
     * @param queueLimit Maximum size of unacknowledged messages queue.
     * @param node Node.
     * @param log Logger.
     */
    public GridNioRecoveryDescriptor(int queueLimit, GridNode node, GridLogger log) {
        assert !node.isLocal() : node;
        assert queueLimit > 0;

        msgFuts = new ArrayDeque<>(queueLimit);

        this.queueLimit = queueLimit;
        this.node = node;
        this.log = log;
    }

    /**
     * @return Connect count.
     */
    public long incrementConnectCount() {
        return connectCnt++;
    }

    /**
     * @return Node.
     */
    public GridNode node() {
        return node;
    }

    /**
     * Increments received messages counter.
     *
     * @return Number of received messages.
     */
    public long onReceived() {
        rcvCnt++;

        return rcvCnt;
    }

    /**
     * @return Number of received messages.
     */
    public long received() {
        return rcvCnt;
    }

    /**
     * @param lastAck Last acknowledged message.
     */
    public void lastAcknowledged(long lastAck) {
        this.lastAck = lastAck;
    }

    /**
     * @return Last acknowledged message.
     */
    public long lastAcknowledged() {
        return lastAck;
    }

    /**
     * @return Received messages count.
     */
    public long receivedCount() {
        return rcvCnt;
    }

    /**
     * @return Maximum size of unacknowledged messages queue.
     */
    public int queueLimit() {
        return queueLimit;
    }

    /**
     * @param fut NIO future.
     * @return {@code False} if queue limit is exceeded.
     */
    public boolean add(GridNioFuture<?> fut) {
        assert fut != null;

        if (!fut.skipRecovery()) {
            if (resendCnt == 0) {
                msgFuts.addLast(fut);

                return msgFuts.size() < queueLimit;
            }
            else
                resendCnt--;
        }

        return true;
    }

    /**
     * @param rcvCnt Number of messages received by remote node.
     */
    public void ackReceived(long rcvCnt) {
        if (log.isDebugEnabled())
            log.debug("Handle acknowledgment [acked=" + acked + ", rcvCnt=" + rcvCnt +
                ", msgFuts=" + msgFuts.size() + ']');

        while (acked < rcvCnt) {
            GridNioFuture<?> fut = msgFuts.pollFirst();

            assert fut != null;

            assert fut.isDone();

            acked++;
        }
    }

    /**
     * Node left callback.
     */
    public void onNodeLeft() {
        GridNioFuture<?>[] futs = null;

        synchronized (this) {
            nodeLeft = true;

            if (!reserved && !msgFuts.isEmpty()) {
                futs = msgFuts.toArray(new GridNioFuture<?>[msgFuts.size()]);

                msgFuts.clear();
            }
        }

        if (futs != null)
            completeOnNodeLeft(futs);
    }

    /**
     * @return Message futures for unacknowledged messages.
     */
    public Deque<GridNioFuture<?>> messagesFutures() {
        return msgFuts;
    }

    /**
     * @param node Node.
     * @return {@code True} if node is not null and has the same order as initial remtoe node.
     */
    public boolean nodeAlive(@Nullable GridNode node) {
        return node != null && node.order() == this.node.order();
    }

    /**
     * @throws InterruptedException If interrupted.
     * @return {@code True} if reserved.
     */
    public boolean reserve() throws InterruptedException {
        synchronized (this) {
            while (!connected && reserved)
                wait();

            if (!connected)
                reserved = true;

            return !connected;
        }
    }

    /**
     * @param rcvCnt Number of messages received by remote node.
     */
    public void onHandshake(long rcvCnt) {
        ackReceived(rcvCnt);

        resendCnt = msgFuts.size();
    }

    /**
     *
     */
    public void connected() {
        synchronized (this) {
            assert reserved;
            assert !connected;

            connected = true;

            if (handshakeReq != null) {
                GridInClosure<Boolean> c = handshakeReq.get2();

                assert c != null;

                c.apply(false);

                handshakeReq = null;
            }

            notifyAll();
        }
    }

    /**
     *
     */
    public void release() {
        GridNioFuture<?>[] futs = null;

        synchronized (this) {
            connected = false;

            if (handshakeReq != null) {
                GridInClosure<Boolean> c = handshakeReq.get2();

                assert c != null;

                handshakeReq = null;

                c.apply(true);
            }
            else {
                reserved = false;

                notifyAll();
            }

            if (nodeLeft && !msgFuts.isEmpty()) {
                futs = msgFuts.toArray(new GridNioFuture<?>[msgFuts.size()]);

                msgFuts.clear();
            }
        }

        if (futs != null)
            completeOnNodeLeft(futs);
    }

    /**
     * @param id Handshake ID.
     * @param c Closure to run on reserve.
     * @return {@code True} if reserved.
     */
    public boolean tryReserve(long id, GridInClosure<Boolean> c) {
        synchronized (this) {
            if (connected) {
                c.apply(false);

                return false;
            }

            if (reserved) {
                if (handshakeReq != null) {
                    assert handshakeReq.get1() != null;

                    long id0 = handshakeReq.get1();

                    assert id0 != id : id0;

                    if (id > id0) {
                        GridInClosure<Boolean> c0 = handshakeReq.get2();

                        assert c0 != null;

                        c0.apply(false);

                        handshakeReq = new GridBiTuple<>(id, c);
                    }
                    else
                        c.apply(false);
                }
                else
                    handshakeReq = new GridBiTuple<>(id, c);

                return false;
            }
            else {
                reserved = true;

                return true;
            }
        }
    }

    /**
     * @param futs Futures to complete.
     */
    private void completeOnNodeLeft(GridNioFuture<?>[] futs) {
        for (GridNioFuture<?> msg : futs)
            ((GridNioFutureImpl)msg).onDone(new IOException("Failed to send message, node has left: " + node.id()));
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridNioRecoveryDescriptor.class, this);
    }
}
