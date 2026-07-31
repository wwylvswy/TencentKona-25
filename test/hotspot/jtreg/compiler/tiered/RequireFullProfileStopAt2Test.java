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
 * @summary Test requirefullprofile with TieredStopAtLevel=2
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 *          java.management
 *
 * @requires vm.flavor == "server" & vm.compiler1.enabled
 * @requires vm.compMode != "Xcomp"
 * @requires (vm.opt.TieredStopAtLevel == null | vm.opt.TieredStopAtLevel == 2)
 * @requires (vm.opt.CompilationMode == null | vm.opt.CompilationMode == "default" | vm.opt.CompilationMode == "normal")
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/timeout=240 -Xmixed -Xbootclasspath/a:.
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:+TieredCompilation -XX:TieredStopAtLevel=2
 *                   -XX:CompilationMode=default
 *                   -XX:CompileThresholdScaling=0.01
 *                   -XX:-BackgroundCompilation
 *                   -XX:CompileCommand=dontinline,compiler.tiered.RequireFullProfileStopAt2Test::*Target
 *                   -XX:CompileCommand=requirefullprofile,compiler.tiered.RequireFullProfileStopAt2Test::requiredTarget
 *                   compiler.tiered.RequireFullProfileStopAt2Test
 */

package compiler.tiered;

import java.lang.reflect.Method;

import jdk.test.whitebox.WhiteBox;

public class RequireFullProfileStopAt2Test {
    private static final WhiteBox WHITE_BOX = WhiteBox.getWhiteBox();

    private static final int COMP_LEVEL_NONE = 0;
    private static final int COMP_LEVEL_LIMITED_PROFILE = 2;

    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        Method required = method("requiredTarget");
        Method control = method("controlTarget");

        reset(required);
        reset(control);

        for (int i = 0; i < 1_000_000; i++) {
            sink = requiredTarget(i);
            sink = controlTarget(i);
            checkRequiredMethod(required);

            if (WHITE_BOX.getMethodCompilationLevel(control, false)
                    == COMP_LEVEL_LIMITED_PROFILE) {
                checkRequiredMethod(required);
                return;
            }
        }
        throw new RuntimeException("Unmatched control method did not reach level 2");
    }

    private static void checkRequiredMethod(Method method) {
        int regularLevel = WHITE_BOX.getMethodCompilationLevel(method, false);
        int osrLevel = WHITE_BOX.getMethodCompilationLevel(method, true);
        if (regularLevel != COMP_LEVEL_NONE || osrLevel != COMP_LEVEL_NONE) {
            throw new RuntimeException(method
                    + " must remain interpreted when Tier 3 is unavailable: "
                    + "regular=" + regularLevel + ", OSR=" + osrLevel);
        }
    }

    private static void reset(Method method) {
        WHITE_BOX.deoptimizeMethod(method, false);
        WHITE_BOX.deoptimizeMethod(method, true);
        WHITE_BOX.clearMethodState(method);
        WHITE_BOX.testSetDontInlineMethod(method, true);
    }

    private static Method method(String name) {
        try {
            return RequireFullProfileStopAt2Test.class
                    .getDeclaredMethod(name, int.class);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int requiredTarget(int value) {
        return nonTrivialWork(value);
    }

    private static int controlTarget(int value) {
        return nonTrivialWork(value);
    }

    private static int nonTrivialWork(int value) {
        int result = value;
        for (int i = 0; i < 4; i++) {
            result = result * 31 + i;
        }
        return result;
    }
}
