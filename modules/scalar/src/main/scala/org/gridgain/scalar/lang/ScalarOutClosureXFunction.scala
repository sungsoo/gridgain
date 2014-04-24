/* @scala.file.header */

/*
 * ________               ______                    ______   _______
 * __  ___/_____________ ____  /______ _________    __/__ \  __  __ \
 * _____ \ _  ___/_  __ `/__  / _  __ `/__  ___/    ____/ /  _  / / /
 * ____/ / / /__  / /_/ / _  /  / /_/ / _  /        _  __/___/ /_/ /
 * /____/  \___/  \__,_/  /_/   \__,_/  /_/         /____/_(_)____/
 *
 */

package org.gridgain.scalar.lang

import org.gridgain.grid.util.lang.{GridOutClosureX}

/**
 * Wrapping Scala function for `GridOutClosureX`.
 */
class ScalarOutClosureXFunction[R](val inner: GridOutClosureX[R]) extends (() => R) {
    assert(inner != null)

    /**
     * Delegates to passed in grid closure.
     */
    def apply(): R = {
        inner.applyx()
    }
}