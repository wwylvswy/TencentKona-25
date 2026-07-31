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
 * @summary Test parsing and lookup of CompileCommand requirefullprofile
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:.
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:CompileCommand=requirefullprofile,compiler.oracle.TestRequireFullProfileCommand::target
 *                   compiler.oracle.TestRequireFullProfileCommand lookup
 * @run main/othervm -Xbootclasspath/a:.
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:CompileCommand=option,compiler.oracle.TestRequireFullProfileCommand::target,requirefullprofile
 *                   compiler.oracle.TestRequireFullProfileCommand lookup
 * @run driver compiler.oracle.TestRequireFullProfileCommand help
 */

package compiler.oracle;

import java.lang.reflect.Method;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestRequireFullProfileCommand {
    private static final String OPTION_NAME = "requirefullprofile";

    public static void main(String[] args) throws Exception {
        if (args[0].equals("help")) {
            checkHelp();
        } else {
            checkLookup();
        }
    }

    private static void checkLookup() throws Exception {
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        Method target = method("target");
        Method unmatched = method("unmatched");

        Boolean value = whiteBox.getMethodBooleanOption(target, OPTION_NAME);
        if (!Boolean.TRUE.equals(value)) {
            throw new RuntimeException("Expected requirefullprofile=true for "
                    + target + ", actual value: " + value);
        }
        if (!Boolean.TRUE.equals(whiteBox.getMethodOption(target, OPTION_NAME))) {
            throw new RuntimeException("Universal option lookup failed for " + target);
        }
        if (whiteBox.getMethodBooleanOption(unmatched, OPTION_NAME) != null
                || whiteBox.getMethodOption(unmatched, OPTION_NAME) != null) {
            throw new RuntimeException("Unmatched method has requirefullprofile");
        }
    }

    private static void checkHelp() throws Exception {
        OutputAnalyzer output = ProcessTools.executeTestJava(
                "-XX:CompileCommand=help", "-version");
        output.shouldHaveExitValue(0)
              .shouldContain("All available options:")
              .shouldContain("requirefullprofile (bool)");
    }

    private static Method method(String name) throws NoSuchMethodException {
        return TestRequireFullProfileCommand.class.getDeclaredMethod(name);
    }

    private static void target() {
    }

    private static void unmatched() {
    }
}
