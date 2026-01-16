package com.libreshockwave.player;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.xtras.XtraManager;

/**
 * Test for NetLingo Xtra functions.
 * Run with: gradlew :player:runNetXtraTest
 */
public class NetXtraTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== NetLingo Xtra Test ===\n");

        // Create a minimal LingoVM (no movie file needed for this test)
        LingoVM vm = new LingoVM(null);
        vm.setDebugMode(true);
        vm.setDebugOutputCallback(System.out::println);

        // Set up NetManager
        NetManager netManager = new NetManager();
        vm.setNetManager(netManager);

        // Register Xtras
        XtraManager xtraManager = XtraManager.createWithStandardXtras();
        xtraManager.registerAll(vm);
        System.out.println("Xtras registered: " + xtraManager.getXtras().size());

        // Test URL - using httpbin for testing
        String testUrl = "https://httpbin.org/get";
        System.out.println("\nTesting preloadNetThing with: " + testUrl);

        // Call preloadNetThing
        Datum taskIdDatum = vm.call("preloadNetThing", Datum.of(testUrl));
        int taskId = taskIdDatum.intValue();
        System.out.println("preloadNetThing returned taskId: " + taskId);

        // Poll netDone until complete
        System.out.println("\nPolling netDone...");
        int maxWait = 10000; // 10 seconds
        int waited = 0;
        while (waited < maxWait) {
            Datum done = vm.call("netDone", Datum.of(taskId));
            if (done.intValue() != 0) {
                System.out.println("netDone returned: " + done.intValue() + " (complete)");
                break;
            }
            Thread.sleep(100);
            waited += 100;
            if (waited % 1000 == 0) {
                System.out.println("  Waiting... " + waited + "ms");
            }
        }

        // Check for errors
        Datum error = vm.call("netError", Datum.of(taskId));
        System.out.println("netError returned: " + error);

        // Get the text result
        Datum text = vm.call("getNetText", Datum.of(taskId));
        String resultText = text.stringValue();
        System.out.println("\ngetNetText returned (" + resultText.length() + " chars):");
        if (resultText.length() > 500) {
            System.out.println(resultText.substring(0, 500) + "...");
        } else {
            System.out.println(resultText);
        }

        // Verify it worked
        System.out.println("\n=== Test Result ===");
        if (resultText.contains("httpbin.org") || resultText.contains("Host")) {
            System.out.println("SUCCESS: preloadNetThing works!");
        } else if (resultText.isEmpty()) {
            System.out.println("FAILED: No data received");
        } else {
            System.out.println("PARTIAL: Got data but unexpected content");
        }
    }
}
