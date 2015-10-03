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

package org.gridgain.grid.util.ipc;

import org.gridgain.grid.*;
import org.gridgain.grid.kernal.processors.ggfs.*;
import org.gridgain.grid.util.ipc.loopback.*;
import org.gridgain.grid.util.ipc.shmem.*;
import org.gridgain.testframework.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Tests for {@code GridIpcServerEndpointDeserializer}.
 */
public class GridIpcServerEndpointDeserializerSelfTest extends GridGgfsCommonAbstractTest {
    /** */
    private Map<String,String> shmemSrvEndpoint;

    /** */
    private Map<String,String> tcpSrvEndpoint;

    /**
     * Initialize test stuff.
     */
    @Override protected void beforeTest() throws Exception {
        shmemSrvEndpoint = new HashMap<>();
        shmemSrvEndpoint.put("port", "888");
        shmemSrvEndpoint.put("size", "111");
        shmemSrvEndpoint.put("tokenDirectoryPath", "test-my-path-baby");

        tcpSrvEndpoint = new HashMap<>();
        tcpSrvEndpoint.put("port", "999");
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfJsonIsNull() throws Exception {
        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @SuppressWarnings("NullableProblems")
            @Override public Object call() throws Exception {
                return GridIpcServerEndpointDeserializer.deserialize(null);
            }
        }, NullPointerException.class, "Ouch! Argument cannot be null: endpointCfg");
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfShmemAndNoTypeInfoInJson() throws Exception {
        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                return GridIpcServerEndpointDeserializer.deserialize(shmemSrvEndpoint);
            }
        }, GridException.class, "Failed to create server endpoint (type is not specified)");
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfShmemAndNoUnknownTypeInfoInJson() throws Exception {
        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                Map<String, String> endPnt = new HashMap<>();

                endPnt.putAll(shmemSrvEndpoint);
                endPnt.put("type", "unknownEndpointType");

                return GridIpcServerEndpointDeserializer.deserialize(endPnt);
            }
        }, GridException.class, "Failed to create server endpoint (type is unknown): unknownEndpointType");
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfLoopbackAndJsonIsLightlyBroken() throws Exception {
        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                return GridIpcServerEndpointDeserializer.deserialize(tcpSrvEndpoint);
            }
        }, GridException.class, null);
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfShmemAndJsonIsOk() throws Exception {
        Map<String, String> endPnt = new HashMap<>();

        endPnt.putAll(shmemSrvEndpoint);
        endPnt.put("type", "shmem");

        GridIpcServerEndpoint deserialized = GridIpcServerEndpointDeserializer.deserialize(endPnt);

        assertTrue(deserialized instanceof GridIpcSharedMemoryServerEndpoint);

        GridIpcSharedMemoryServerEndpoint deserializedShmemEndpoint = (GridIpcSharedMemoryServerEndpoint)deserialized;

        assertEquals(shmemSrvEndpoint.get("port"), String.valueOf(deserializedShmemEndpoint.getPort()));
        assertEquals(shmemSrvEndpoint.get("size"), String.valueOf(deserializedShmemEndpoint.getSize()));
        assertEquals(shmemSrvEndpoint.get("tokenDirectoryPath"), deserializedShmemEndpoint.getTokenDirectoryPath());
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfShmemAndJsonIsOkAndDefaultValuesAreSetToFields() throws Exception {
        GridIpcSharedMemoryServerEndpoint defShmemSrvEndpoint = new GridIpcSharedMemoryServerEndpoint();
        defShmemSrvEndpoint.setPort(8);

        Map<String, String> endPnt = new HashMap<>();

        endPnt.put("type", "shmem");
        endPnt.put("port", String.valueOf(defShmemSrvEndpoint.getPort()));

        GridIpcServerEndpoint deserialized = GridIpcServerEndpointDeserializer.deserialize(endPnt);

        assertTrue(deserialized instanceof GridIpcSharedMemoryServerEndpoint);

        GridIpcSharedMemoryServerEndpoint deserializedShmemEndpoint = (GridIpcSharedMemoryServerEndpoint)deserialized;

        assertEquals(defShmemSrvEndpoint.getPort(), deserializedShmemEndpoint.getPort());
        assertEquals(defShmemSrvEndpoint.getSize(), deserializedShmemEndpoint.getSize());
        assertEquals(defShmemSrvEndpoint.getTokenDirectoryPath(), deserializedShmemEndpoint.getTokenDirectoryPath());
    }

    /**
     * @throws Exception In case of any exception.
     */
    public void testDeserializeIfLoopbackAndJsonIsOk() throws Exception {
        Map<String, String> endPnt = new HashMap<>();

        endPnt.putAll(tcpSrvEndpoint);
        endPnt.put("type", "tcp");

        GridIpcServerEndpoint deserialized = GridIpcServerEndpointDeserializer.deserialize(endPnt);

        assertTrue(deserialized instanceof GridIpcServerTcpEndpoint);

        assertEquals(tcpSrvEndpoint.get("port"), String.valueOf(deserialized.getPort()));
    }
}
