package com.libreshockwave.player.simulator;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.NetBuiltins;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;

/**
 * Integration test that simulates the WASM JS↔Java bridge flow on the JVM.
 * All data is loaded via HTTP from localhost — exactly like the real browser embed.
 *
 * Validates that releaseRawData() doesn't break rendering:
 * 1. Fetches habbo.dcr via HTTP (same as browser fetch → ArrayBuffer → loadMovie)
 * 2. Calls releaseRawData() on the main file immediately after parse
 * 3. Uses a polling NetProvider (like QueuedNetProvider) — bytes delivered via HTTP callbacks
 * 4. Delivers external cast bytes via HTTP and calls releaseRawData() on each after parse
 * 5. Ticks until sprites appear and verifies rendering works
 */
public class ReleaseRawDataTest {

    private static final String MOVIE_URL = "http://localhost/dcr/14.1_b8/habbo.dcr";
    private static final String BASE_PATH = "http://localhost/dcr/14.1_b8/";
    private static final int MAX_TICKS = 500;

    public static void main(String[] args) throws Exception {
        String movieUrl = args.length > 0 ? args[0] : MOVIE_URL;
        String basePath = args.length > 1 ? args[1] : BASE_PATH;

        System.out.println("=== ReleaseRawDataTest ===");
        System.out.println("Movie URL: " + movieUrl);
        System.out.println("Base path: " + basePath);

        // 1. Fetch movie via HTTP (simulates browser fetch → ArrayBuffer → WASM loadMovie)
        byte[] movieData = httpGet(movieUrl);
        if (movieData == null) {
            System.out.println("FAIL: Could not fetch movie from " + movieUrl);
            System.out.println("Make sure Apache/XAMPP is running on localhost.");
            System.exit(1);
        }
        System.out.println("Movie fetched: " + movieData.length + " bytes");

        DirectorFile file = DirectorFile.load(movieData);
        file.setBasePath(basePath);

        // 2. Release raw data immediately after parse — this is the behavior under test
        file.releaseRawData();
        file.releaseNonEssentialChunks();
        System.out.println("PASS: releaseRawData() on main movie");

        // 3. Create polling NetProvider (simulates QueuedNetProvider in WASM)
        PollingNetProvider netProvider = new PollingNetProvider(basePath);
        Player player = new Player(file, netProvider);

        // Set external params — exactly as the browser embed PARAM tags
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=http://localhost/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                       "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));

        // 4. Preload external casts (queues fetch requests)
        int castCount = player.preloadAllCasts();
        System.out.println("Queued " + castCount + " external casts for fetch");

        // 5. Pump network: fetch via HTTP, deliver to player, releaseRawData on each cast
        int castsDelivered = pumpNetwork(netProvider, player);
        System.out.println("Delivered " + castsDelivered + " cast files via HTTP");

        // 6. Start playback
        player.play();

        // 7. Tick loop — simulate JS animation loop with network pump each tick
        int maxSprites = 0;
        int lastFrame = 0;
        int ticksWithSprites = 0;
        int totalCastsDelivered = castsDelivered;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            boolean alive;
            try {
                alive = player.tick();
            } catch (Throwable e) {
                System.out.println("Tick " + tick + " error: " + e.getMessage());
                alive = true; // keep going like WASM does
            }
            if (!alive) {
                System.out.println("Player stopped at tick " + tick);
                break;
            }

            // Pump network each tick (Lingo may request external_variables.txt, more casts, etc.)
            totalCastsDelivered += pumpNetwork(netProvider, player);

            // Check sprites
            try {
                int spriteCount = player.getStageRenderer()
                        .getSpritesForFrame(player.getCurrentFrame()).size();
                if (spriteCount > maxSprites) {
                    maxSprites = spriteCount;
                    System.out.println("Tick " + tick + ": " + spriteCount +
                            " sprites (frame " + player.getCurrentFrame() + ")");
                }
                if (spriteCount > 0) ticksWithSprites++;
            } catch (Throwable e) {
                // ignore sprite count errors
            }
            lastFrame = player.getCurrentFrame();
        }

        // 8. Verify rendering works after releaseRawData
        System.out.println("\n--- Results ---");
        System.out.println("Total casts delivered: " + totalCastsDelivered);
        System.out.println("Max sprites: " + maxSprites);
        System.out.println("Last frame: " + lastFrame);
        System.out.println("Ticks with sprites: " + ticksWithSprites);

        boolean renderOk = false;
        try {
            FrameSnapshot snap = player.getFrameSnapshot();
            int renderedSprites = snap.sprites().size();
            System.out.println("FrameSnapshot sprites: " + renderedSprites);
            renderOk = true;
        } catch (Exception e) {
            System.out.println("FAIL: FrameSnapshot threw: " + e.getMessage());
        }

        player.shutdown();

        // 9. Pass/fail verdict
        boolean pass = maxSprites >= 10 && lastFrame > 0 && renderOk;
        if (pass) {
            System.out.println("\nPASS: releaseRawData() does not break rendering " +
                    "(maxSprites=" + maxSprites + ", frame=" + lastFrame + ")");
        } else {
            System.out.println("\nFAIL: maxSprites=" + maxSprites +
                    ", frame=" + lastFrame + ", renderOk=" + renderOk);
            System.exit(1);
        }
    }

    /**
     * Deliver all pending network requests via HTTP.
     * Mirrors the WASM JS bridge: poll pending requests → fetch → deliverFetchResult.
     */
    private static int pumpNetwork(PollingNetProvider net, Player player) {
        int delivered = 0;
        // Keep pumping until no new requests appear (a single delivery can trigger more)
        while (!net.getPendingRequests().isEmpty()) {
            List<PollingNetProvider.PendingRequest> requests = new ArrayList<>(net.getPendingRequests());
            net.drainPendingRequests();

            for (var req : requests) {
                byte[] data = fetchWithFallbacks(req.url, req.fallbacks);
                if (data != null) {
                    net.onFetchComplete(req.taskId, data);

                    // Try to load as external cast — mirrors WasmEntry.tryLoadExternalCast
                    player.getCastLibManager().cacheFileData(req.url, data);
                    boolean loaded = player.getCastLibManager().setExternalCastDataByUrl(req.url, data);
                    if (loaded) {
                        // Release raw data on each external cast's DirectorFile
                        for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                            var sourceFile = castLib.getSourceFile();
                            if (sourceFile != null) {
                                sourceFile.releaseRawData();
                                sourceFile.releaseNonEssentialChunks();
                            }
                        }
                        player.getBitmapCache().clear();
                        delivered++;
                    }
                } else {
                    net.onFetchError(req.taskId, 404);
                    System.out.println("  HTTP 404: " + req.url);
                }
            }
        }
        return delivered;
    }

    /**
     * Fetch a URL via HTTP, trying fallback URLs (e.g. .cct before .cst).
     */
    private static byte[] fetchWithFallbacks(String url, String[] fallbacks) {
        String[] urls = fallbacks != null ? fallbacks : new String[]{url};
        for (String u : urls) {
            byte[] data = httpGet(u);
            if (data != null) return data;
        }
        return null;
    }

    /**
     * HTTP GET — returns response bytes, or null on any error.
     */
    private static byte[] httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int status = conn.getResponseCode();
            if (status != 200) {
                conn.disconnect();
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Polling-based NetProvider that mirrors QueuedNetProvider behavior.
     * Requests are queued; the test loop fetches them via HTTP and delivers results.
     */
    static class PollingNetProvider implements NetBuiltins.NetProvider {
        private final String basePath;
        private final Map<Integer, NetTask> tasks = new HashMap<>();
        private final List<PendingRequest> pendingRequests = new ArrayList<>();
        private int nextTaskId = 1;
        private int lastTaskId = 0;

        PollingNetProvider(String basePath) {
            this.basePath = basePath;
        }

        @Override
        public int preloadNetThing(String url) {
            int taskId = nextTaskId++;
            lastTaskId = taskId;
            String resolved = resolveUrl(url);
            String[] fallbacks = FileUtil.getUrlsWithFallbacks(resolved);
            tasks.put(taskId, new NetTask(taskId, resolved));
            pendingRequests.add(new PendingRequest(taskId, fallbacks[0], "GET", null, fallbacks));
            return taskId;
        }

        @Override
        public int postNetText(String url, String postData) {
            int taskId = nextTaskId++;
            lastTaskId = taskId;
            String resolved = resolveUrl(url);
            tasks.put(taskId, new NetTask(taskId, resolved));
            pendingRequests.add(new PendingRequest(taskId, resolved, "POST", postData, null));
            return taskId;
        }

        @Override
        public boolean netDone(Integer taskId) {
            NetTask task = getTask(taskId);
            return task != null && task.done;
        }

        @Override
        public String netTextResult(Integer taskId) {
            NetTask task = getTask(taskId);
            return (task != null && task.done && task.data != null) ? new String(task.data) : "";
        }

        @Override
        public int netError(Integer taskId) {
            NetTask task = getTask(taskId);
            return task != null ? task.errorCode : 0;
        }

        @Override
        public String getStreamStatus(Integer taskId) {
            NetTask task = getTask(taskId);
            if (task == null) return "Error";
            if (task.done) return task.errorCode == 0 ? "Complete" : "Error";
            return "Loading";
        }

        @Override
        public Datum getStreamStatusDatum(Integer taskId) {
            NetTask task = getTask(taskId);
            var props = new LinkedHashMap<String, Datum>();
            if (task == null) {
                props.put("URL", Datum.EMPTY_STRING);
                props.put("state", Datum.of("Error"));
                props.put("bytesSoFar", Datum.ZERO);
                props.put("bytesTotal", Datum.ZERO);
                props.put("error", Datum.of("OK"));
                return Datum.propList(props);
            }
            int byteCount = task.done && task.data != null ? task.data.length : 0;
            String state = task.done ? (task.errorCode == 0 ? "Complete" : "Error") : "Loading";
            props.put("URL", Datum.EMPTY_STRING);
            props.put("state", Datum.of(state));
            props.put("bytesSoFar", Datum.of(byteCount));
            props.put("bytesTotal", Datum.of(byteCount));
            props.put("error", task.errorCode == 0 ? Datum.of("OK") : Datum.of(String.valueOf(task.errorCode)));
            return Datum.propList(props);
        }

        List<PendingRequest> getPendingRequests() { return pendingRequests; }
        void drainPendingRequests() { pendingRequests.clear(); }

        void onFetchComplete(int taskId, byte[] data) {
            NetTask task = tasks.get(taskId);
            if (task != null) { task.data = data; task.done = true; }
        }

        void onFetchError(int taskId, int status) {
            NetTask task = tasks.get(taskId);
            if (task != null) { task.errorCode = status != 0 ? status : -1; task.done = true; }
        }

        private NetTask getTask(Integer taskId) {
            if (taskId == null || taskId == 0) return lastTaskId > 0 ? tasks.get(lastTaskId) : null;
            return tasks.get(taskId);
        }

        private String resolveUrl(String url) {
            if (url == null || url.isEmpty()) return url;
            // Already absolute HTTP URL — use as-is
            if (url.startsWith("http://") || url.startsWith("https://")) return url;
            // Extract just the filename (strip original author's Windows path)
            String fileName = url;
            int lastSlash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
            if (lastSlash >= 0) fileName = url.substring(lastSlash + 1);
            // Resolve against basePath (HTTP URL)
            if (basePath != null && !basePath.isEmpty()) {
                String base = basePath;
                if (!base.endsWith("/")) base += "/";
                return base + fileName;
            }
            return fileName;
        }

        static class PendingRequest {
            final int taskId;
            final String url;
            final String method;
            final String postData;
            final String[] fallbacks;

            PendingRequest(int taskId, String url, String method, String postData, String[] fallbacks) {
                this.taskId = taskId;
                this.url = url;
                this.method = method;
                this.postData = postData;
                this.fallbacks = fallbacks;
            }
        }

        static class NetTask {
            final int id;
            final String url;
            byte[] data;
            int errorCode;
            boolean done;

            NetTask(int id, String url) { this.id = id; this.url = url; }
        }
    }
}
