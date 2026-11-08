package com.ai.limbs.permission.server;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class AppLaunchResultTest {
    // Captured from the affected device: bringing the current task forward is a valid launch.
    private static final String HOT_LAUNCH =
            "Starting: Intent { cmp=io.github.samolego.canta/.MainActivity }\n"
            + "Warning: Activity not started, its current task has been brought to the front\n"
            + "Status: ok\nLaunchState: HOT\n"
            + "Activity: io.github.samolego.canta/.MainActivity\n"
            + "TotalTime: 152\nWaitTime: 155\nComplete\n";

    @Test public void acceptsCompletedLaunchOfRequestedPackage() {
        AppLaunchResult.requireComplete("io.github.samolego.canta", 0, HOT_LAUNCH);
    }

    @Test public void timeoutIsNotSuccessEvenWithZeroExitCode() {
        rejects(0, HOT_LAUNCH.replace("Status: ok", "Status: timeout"), "timeout");
    }

    @Test public void anotherPackageCannotCountAsTheRequestedApp() {
        rejects(0, HOT_LAUNCH.replace("Activity: io.github.samolego.canta/",
                "Activity: other.package/"), "other.package");
    }

    @Test public void preservesPlatformFailureInsteadOfReportingConnectionSent() {
        rejects(1, "Error: Activity not started, unable to resolve Intent", "unable to resolve");
    }

    private static void rejects(int exitCode, String output, String detail) {
        try {
            AppLaunchResult.requireComplete("io.github.samolego.canta", exitCode, output);
            fail("Incomplete launch must stop the connection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(detail));
        }
    }
}
