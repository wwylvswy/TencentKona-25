/*
 * Copyright (c) 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Test requirefullprofile tiered transitions and queue adjustments
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 *          java.management
 *
 * @requires vm.flavor == "server" & vm.compiler1.enabled & vm.compiler2.enabled
 * @requires vm.compMode != "Xcomp"
 * @requires (vm.opt.TieredStopAtLevel == null | vm.opt.TieredStopAtLevel == 4)
 * @requires (vm.opt.CompilationMode == null | vm.opt.CompilationMode == "default" | vm.opt.CompilationMode == "normal")
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=240 -Xmixed -Xbootclasspath/a:.
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=4
 *                   -XX:CompilationMode=default
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:+BackgroundCompilation
 *                   -XX:CompileCommand=dontinline,compiler.tiered.RequireFullProfileTest::*Target
 *                   -XX:CompileCommand=dontinline,compiler.tiered.RequireFullProfileTest$TrivialHolder::getValue
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest::queueTarget
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest::tier2RequestTarget
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest::directTarget
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest::osrQueueTarget
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest::osrTier2RequestTarget
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileTest$TrivialHolder::getValue
 *                   compiler.tiered.RequireFullProfileTest
 */

package compiler.tiered;

import java.lang.reflect.Method;

import jdk.test.whitebox.WhiteBox;

public class RequireFullProfileTest {
    private static final WhiteBox WHITE_BOX = WhiteBox.getWhiteBox();

    private static final int COMP_LEVEL_NONE = 0;
    private static final int COMP_LEVEL_SIMPLE = 1;
    private static final int COMP_LEVEL_LIMITED_PROFILE = 2;
    private static final int COMP_LEVEL_FULL_PROFILE = 3;
    private static final int COMP_LEVEL_FULL_OPTIMIZATION = 4;
    private static final int INVOCATION_ENTRY_BCI = -1;
    private static final long COMPILATION_TIMEOUT_MS = 30_000;

    private static final TrivialHolder TRIVIAL_HOLDER = new TrivialHolder();
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        Method queue = method("queueTarget", int.class);
        Method control = method("controlTarget", int.class);
        Method tier2Request = method("tier2RequestTarget", int.class);
        Method direct = method("directTarget", int.class);
        Method osrQueue = method("osrQueueTarget", int.class);
        Method osrTier2Request = method("osrTier2RequestTarget", int.class);

        checkLevel3QueueAdjustment(queue, COMP_LEVEL_FULL_PROFILE);
        checkLevel3QueueAdjustment(control, COMP_LEVEL_LIMITED_PROFILE);
        checkTier2Request(tier2Request, INVOCATION_ENTRY_BCI, false);
        checkTier2Request(osrTier2Request, 0, true);
        checkLevel3IsNotDowngradedForOsr(osrQueue);
        checkDirectTransitionAndTier4(direct);
        checkTrivialMethodRemainsAtLevel1();
    }

    private static void checkLevel3QueueAdjustment(Method method,
                                                    int expectedLevel) throws Exception {
        reset(method, false);
        WHITE_BOX.markMethodProfiled(method);
        enqueue(method, COMP_LEVEL_FULL_PROFILE, INVOCATION_ENTRY_BCI);
        waitForCompilation(method);
        checkLevel(method, false, expectedLevel);
    }

    private static void checkTier2Request(Method method, int bci,
                                          boolean isOsr) throws Exception {
        reset(method, isOsr);
        enqueue(method, COMP_LEVEL_LIMITED_PROFILE, bci);
        waitForCompilation(method);
        checkLevel(method, isOsr, COMP_LEVEL_FULL_PROFILE);
    }

    private static void checkLevel3IsNotDowngradedForOsr(Method method)
            throws Exception {
        reset(method, true);
        WHITE_BOX.markMethodProfiled(method);
        enqueue(method, COMP_LEVEL_FULL_PROFILE, 0);
        waitForCompilation(method);
        checkLevel(method, true, COMP_LEVEL_FULL_PROFILE);
        checkNotLevel2(method);
    }

    private static void checkDirectTransitionAndTier4(Method method)
            throws Exception {
        reset(method, false);
        WHITE_BOX.markMethodProfiled(method);

        int firstLevel = COMP_LEVEL_NONE;
        for (int i = 0; i < 1_000_000 && firstLevel == COMP_LEVEL_NONE; i++) {
            sink = directTarget(i);
            waitIfQueued(method);
            checkNotLevel2(method);
            firstLevel = WHITE_BOX.getMethodCompilationLevel(method, false);
        }
        if (firstLevel != COMP_LEVEL_FULL_PROFILE) {
            throw new RuntimeException("Expected first regular compilation of "
                    + method + " at level 3, actual level: " + firstLevel);
        }

        for (int i = 0; i < 1_000_000; i++) {
            sink = directTarget(i);
            waitIfQueued(method);
            checkNotLevel2(method);
            if (WHITE_BOX.getMethodCompilationLevel(method, false)
                    == COMP_LEVEL_FULL_OPTIMIZATION) {
                return;
            }
        }
        throw new RuntimeException(method + " did not reach level 4");
    }

    private static void checkTrivialMethodRemainsAtLevel1() throws Exception {
        Method method = TrivialHolder.class.getDeclaredMethod("getValue");
        reset(method, false);

        for (int i = 0; i < 1_000_000; i++) {
            sink = TRIVIAL_HOLDER.getValue();
            waitIfQueued(method);
            int level = WHITE_BOX.getMethodCompilationLevel(method, false);
            if (level != COMP_LEVEL_NONE) {
                checkLevel(method, false, COMP_LEVEL_SIMPLE);
                return;
            }
        }
        throw new RuntimeException("Trivial accessor was not compiled");
    }

    private static void reset(Method method, boolean isOsr) {
        WHITE_BOX.deoptimizeMethod(method, isOsr);
        if (isOsr) {
            WHITE_BOX.deoptimizeMethod(method, false);
        }
        WHITE_BOX.clearMethodState(method);
        WHITE_BOX.testSetDontInlineMethod(method, true);
    }

    private static void enqueue(Method method, int level, int bci) {
        if (!WHITE_BOX.enqueueMethodForCompilation(method, level, bci)) {
            throw new RuntimeException("Failed to enqueue " + method
                    + " at compilation level " + level + ", bci " + bci);
        }
    }

    private static void waitIfQueued(Method method) throws Exception {
        if (WHITE_BOX.isMethodQueuedForCompilation(method)) {
            waitForCompilation(method);
        }
    }

    private static void waitForCompilation(Method method) throws Exception {
        long deadline = System.currentTimeMillis() + COMPILATION_TIMEOUT_MS;
        while (WHITE_BOX.isMethodQueuedForCompilation(method)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (WHITE_BOX.isMethodQueuedForCompilation(method)) {
            throw new RuntimeException("Timed out waiting for compilation of " + method);
        }
    }

    private static void checkNotLevel2(Method method) {
        int regularLevel = WHITE_BOX.getMethodCompilationLevel(method, false);
        int osrLevel = WHITE_BOX.getMethodCompilationLevel(method, true);
        if (regularLevel == COMP_LEVEL_LIMITED_PROFILE
                || osrLevel == COMP_LEVEL_LIMITED_PROFILE) {
            throw new RuntimeException(method + " reached level 2: regular="
                    + regularLevel + ", OSR=" + osrLevel);
        }
    }

    private static void checkLevel(Method method, boolean isOsr, int expected) {
        int actual = WHITE_BOX.getMethodCompilationLevel(method, isOsr);
        if (actual != expected) {
            throw new RuntimeException("Expected " + method
                    + (isOsr ? " OSR" : "") + " at level " + expected
                    + ", actual level: " + actual);
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return RequireFullProfileTest.class.getDeclaredMethod(name, parameterTypes);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int queueTarget(int value) {
        return nonTrivialWork(value);
    }

    private static int controlTarget(int value) {
        return nonTrivialWork(value);
    }

    private static int tier2RequestTarget(int value) {
        return nonTrivialWork(value);
    }

    private static int directTarget(int value) {
        return nonTrivialWork(value);
    }

    private static int osrQueueTarget(int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            result += i;
        }
        return result;
    }

    private static int osrTier2RequestTarget(int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            result ^= i;
        }
        return result;
    }

    private static int nonTrivialWork(int value) {
        int result = value;
        for (int i = 0; i < 4; i++) {
            result = result * 31 + i;
        }
        return result;
    }

    private static class TrivialHolder {
        private int value = 42;

        int getValue() {
            return value;
        }
    }
}
