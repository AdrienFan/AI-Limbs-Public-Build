package com.ai.limbs.permission.server;

/** Validates the platform launcher receipt before a client Binder can be delivered. */
final class AppLaunchResult {
    private AppLaunchResult() {}

    static void requireComplete(String packageName, int exitCode, String output) {
        boolean ok = false;
        boolean target = false;
        for (String line : output.split("\\r?\\n")) {
            String value = line.trim();
            if (value.equals("Status: ok")) ok = true;
            if (value.startsWith("Activity: " + packageName + "/")) target = true;
        }
        if (exitCode != 0 || !ok || !target) {
            String detail = output.trim();
            throw new IllegalStateException("启动目标应用未完成（exit=" + exitCode + "）："
                    + detail.substring(0, Math.min(detail.length(), 400)));
        }
    }
}
