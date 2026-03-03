package com.libreshockwave.player.wasm;

/**
 * Basic smoke test for QueuedNetProvider and the rewritten WASM module.
 * Verifies core polling-based network provider works on JVM.
 *
 * Run: ./gradlew :player-wasm:runQueuedNetProviderTest
 */
public class QueuedNetProviderTest {

    public static void main(String[] args) {
        int passed = 0, failed = 0;

        // Test 1: preloadNetThing queues a request
        {
            var net = new QueuedNetProvider("http://example.com/game/movie.dcr");
            int taskId = net.preloadNetThing("cast.cct");
            if (taskId > 0 && net.getPendingRequests().size() == 1) {
                System.out.println("PASS: preloadNetThing queues request (taskId=" + taskId + ")");
                passed++;
            } else {
                System.err.println("FAIL: preloadNetThing did not queue request");
                failed++;
            }
        }

        // Test 2: pending request has resolved URL and fallbacks
        {
            var net = new QueuedNetProvider("http://example.com/game/movie.dcr");
            net.preloadNetThing("cast.cct");
            var req = net.getPendingRequests().get(0);
            boolean urlOk = req.url().contains("example.com") && req.url().contains("cast.cct");
            boolean hasFallbacks = req.fallbacks() != null && req.fallbacks().length > 0;
            if (urlOk && hasFallbacks) {
                System.out.println("PASS: pending request has resolved URL and fallbacks");
                passed++;
            } else {
                System.err.println("FAIL: URL=" + req.url() + " fallbacks=" + (req.fallbacks() != null ? req.fallbacks().length : "null"));
                failed++;
            }
        }

        // Test 3: drainPendingRequests clears the list
        {
            var net = new QueuedNetProvider("http://example.com/");
            net.preloadNetThing("file.txt");
            net.drainPendingRequests();
            if (net.getPendingRequests().isEmpty()) {
                System.out.println("PASS: drainPendingRequests clears list");
                passed++;
            } else {
                System.err.println("FAIL: drain did not clear list");
                failed++;
            }
        }

        // Test 4: onFetchComplete marks task done and provides data
        {
            var net = new QueuedNetProvider("http://example.com/");
            int taskId = net.preloadNetThing("data.txt");
            if (!net.netDone(taskId)) {
                net.onFetchComplete(taskId, "hello world".getBytes());
                if (net.netDone(taskId) && "hello world".equals(net.netTextResult(taskId)) && net.netError(taskId) == 0) {
                    System.out.println("PASS: onFetchComplete delivers data correctly");
                    passed++;
                } else {
                    System.err.println("FAIL: data not delivered correctly");
                    failed++;
                }
            } else {
                System.err.println("FAIL: task should not be done before delivery");
                failed++;
            }
        }

        // Test 5: onFetchError marks task done with error
        {
            var net = new QueuedNetProvider("http://example.com/");
            int taskId = net.preloadNetThing("missing.txt");
            net.onFetchError(taskId, 404);
            if (net.netDone(taskId) && net.netError(taskId) == 404 && "Error".equals(net.getStreamStatus(taskId))) {
                System.out.println("PASS: onFetchError marks task with error code");
                passed++;
            } else {
                System.err.println("FAIL: error not recorded correctly");
                failed++;
            }
        }

        // Test 6: getStreamStatus transitions
        {
            var net = new QueuedNetProvider("http://example.com/");
            int taskId = net.preloadNetThing("file.txt");
            String s1 = net.getStreamStatus(taskId);
            net.onFetchComplete(taskId, new byte[0]);
            String s2 = net.getStreamStatus(taskId);
            if ("Loading".equals(s1) && "Complete".equals(s2)) {
                System.out.println("PASS: getStreamStatus transitions Loading -> Complete");
                passed++;
            } else {
                System.err.println("FAIL: status was " + s1 + " -> " + s2);
                failed++;
            }
        }

        // Test 7: serializePendingRequests produces valid JSON
        {
            var net = new QueuedNetProvider("http://example.com/");
            net.preloadNetThing("cast.cct");
            net.postNetText("http://example.com/api", "key=value");
            String json = net.serializePendingRequests();
            if (json.startsWith("[") && json.endsWith("]") && json.contains("\"taskId\"") && json.contains("\"POST\"")) {
                System.out.println("PASS: serializePendingRequests produces valid JSON");
                passed++;
            } else {
                System.err.println("FAIL: JSON=" + json);
                failed++;
            }
        }

        // Test 8: WasmPlayer loadMovie with invalid data returns false
        {
            var wp = new WasmPlayer();
            boolean result = wp.loadMovie(new byte[]{0, 1, 2, 3}, "");
            if (!result) {
                System.out.println("PASS: WasmPlayer.loadMovie rejects invalid data");
                passed++;
            } else {
                System.err.println("FAIL: loadMovie should have returned false for garbage data");
                failed++;
            }
        }

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
