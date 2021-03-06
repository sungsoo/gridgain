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

package org.gridgain.grid.kernal.visor.cmd.tasks;

import org.gridgain.grid.kernal.processors.task.*;
import org.gridgain.grid.kernal.visor.cmd.*;
import org.gridgain.grid.kernal.visor.cmd.dto.*;
import org.gridgain.grid.util.typedef.internal.*;

/**
 * Grid configuration data collect task.
 */
@GridInternal
public class VisorConfigCollectorTask extends VisorOneNodeTask<Void, VisorGridConfig> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override protected VisorConfigCollectorJob job(Void arg) {
        return new VisorConfigCollectorJob();
    }

    /**
     * Grid configuration data collect job.
     */
    private static class VisorConfigCollectorJob extends VisorJob<Void, VisorGridConfig> {
        /** */
        private static final long serialVersionUID = 0L;

        private VisorConfigCollectorJob() {
            super(null);
        }

        /** {@inheritDoc} */
        @Override protected VisorGridConfig run(Void arg) {
            return VisorGridConfig.from(g);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(VisorConfigCollectorJob.class, this);
        }
    }
}
