package com.libreshockwave.player.simulator;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.NetBuiltins;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.List;

/**
 * HTTP integration test that loads a movie from localhost and renders frames.
 * Outputs a single frame.png that is overwritten every few seconds so you can
 * watch rendering progress in an image viewer without filling disk.
 */
public class HttpIntegrationTest {

    private static final String MOVIE_URL = "http://localhost/dcr/14.1_b8/habbo.dcr";
    private static final String BASE_PATH = "http://localhost/dcr/14.1_b8/";
    private static final int MAX_TICKS = 500;
    private static final String OUTPUT_FILE = "frame.png";

    // JS-side cache simulation: URL -> byte[]
    private static final Map<String, byte[]> jsUrlCache = new HashMap<>();

    // Pending dynamic cast requests captured from castDataRequestCallback
    private static final List<String> pendingCastDataRequests = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        String movieUrl = args.length > 0 ? args[0] : MOVIE_URL;
        String basePath = args.length > 1 ? args[1] : BASE_PATH;
        String outputFile = args.length > 2 ? args[2] : OUTPUT_FILE;

        System.out.println("=== HttpIntegrationTest ===");
        System.out.println("Movie URL: " + movieUrl);
        System.out.println("Base path: " + basePath);
        System.out.println("Output: " + outputFile + " (overwritten each capture)");

        // 1. Fetch movie via HTTP
        byte[] movieData = httpGet(movieUrl);
        if (movieData == null) {
            System.out.println("FAIL: Could not fetch movie from " + movieUrl);
            System.out.println("Make sure Apache/XAMPP is running on localhost.");
            System.exit(1);
        }
        System.out.println("Movie fetched: " + movieData.length + " bytes");

        DirectorFile file = DirectorFile.load(movieData);
        file.setBasePath(basePath);

        // 2. Create polling NetProvider
        PollingNetProvider netProvider = new PollingNetProvider(basePath);
        Player player = new Player(file, netProvider);

        // 3. Wire castDataRequestCallback
        pendingCastDataRequests.clear();
        final String baseDir = basePath.endsWith("/") ? basePath : basePath + "/";
        player.getCastLibManager().setCastDataRequestCallback((castLibNumber, fileName) -> {
            String baseName = FileUtil.getFileNameWithoutExtension(FileUtil.getFileName(fileName));
            String cctUrl = baseDir + baseName + ".cct";
            String cstUrl = baseDir + baseName + ".cst";

            byte[] data = jsUrlCache.get(cctUrl);
            String url = cctUrl;
            if (data == null) {
                data = jsUrlCache.get(cstUrl);
                url = cstUrl;
            }
            if (data == null) {
                data = httpGet(cctUrl);
                if (data != null) {
                    jsUrlCache.put(cctUrl, data);
                    url = cctUrl;
                } else {
                    data = httpGet(cstUrl);
                    if (data != null) {
                        jsUrlCache.put(cstUrl, data);
                        url = cstUrl;
                    }
                }
            }
            if (data != null) {
                player.getCastLibManager().setExternalCastData(castLibNumber, data);
                player.getBitmapCache().clear();
                System.out.println("  Dynamic cast loaded: " + url);
            } else {
                pendingCastDataRequests.add(fileName);
            }
        });

        // Set external params
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=http://localhost/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                       "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));

        // 4. Preload external casts
        int castCount = player.preloadAllCasts();
        System.out.println("Queued " + castCount + " external casts for fetch");

        // 5. Pump network
        int castsDelivered = pumpNetwork(netProvider, player);
        System.out.println("Delivered " + castsDelivered + " cast files");

        // 6. Start playback
        player.play();

        int stageW = player.getStageRenderer().getStageWidth();
        int stageH = player.getStageRenderer().getStageHeight();
        int tempo = player.getTempo();
        if (tempo <= 0) tempo = 15;
        long msPerTick = 1000L / tempo;
        int ticksPerCapture = tempo * 3; // capture every ~3 seconds

        System.out.println("Stage: " + stageW + "x" + stageH + " @ " + tempo + " fps");
        System.out.println("Capturing every " + ticksPerCapture + " ticks (~3s)");

        // 7. Tick loop with periodic frame capture
        int maxSprites = 0;
        int lastFrame = 0;
        int captures = 0;
        int totalCastsDelivered = castsDelivered;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            boolean alive;
            try {
                alive = player.tick();
            } catch (Throwable e) {
                System.out.println("Tick " + tick + " error: " + e.getMessage());
                alive = true;
            }
            if (!alive) {
                System.out.println("Player stopped at tick " + tick);
                break;
            }

            // Pump network each tick
            totalCastsDelivered += pumpNetwork(netProvider, player);
            totalCastsDelivered += deliverPendingCastRequests(player, basePath);

            // Track sprite count
            try {
                int spriteCount = player.getStageRenderer()
                        .getSpritesForFrame(player.getCurrentFrame()).size();
                if (spriteCount > maxSprites) {
                    maxSprites = spriteCount;
                    System.out.println("Tick " + tick + ": " + spriteCount +
                            " sprites (frame " + player.getCurrentFrame() + ")");
                }
            } catch (Throwable e) {
                // ignore
            }
            lastFrame = player.getCurrentFrame();

            // Capture frame periodically (overwrite same file)
            if (tick > 0 && tick % ticksPerCapture == 0) {
                try {
                    FrameSnapshot snap = player.getFrameSnapshot();
                    BufferedImage image = renderFrame(snap, stageW, stageH);
                    ImageIO.write(image, "png", new File(outputFile));
                    captures++;
                    System.out.println("Frame captured -> " + outputFile +
                            " (tick " + tick + ", frame " + snap.frameNumber() +
                            ", " + snap.sprites().size() + " sprites)");
                } catch (Exception e) {
                    System.out.println("Capture error at tick " + tick + ": " + e.getMessage());
                }
            }

            Thread.sleep(msPerTick);
        }

        // Final capture
        try {
            FrameSnapshot snap = player.getFrameSnapshot();
            BufferedImage image = renderFrame(snap, stageW, stageH);
            ImageIO.write(image, "png", new File(outputFile));
            captures++;
            System.out.println("Final frame captured -> " + outputFile +
                    " (frame " + snap.frameNumber() + ", " + snap.sprites().size() + " sprites)");
        } catch (Exception e) {
            System.out.println("Final capture error: " + e.getMessage());
        }

        player.shutdown();

        // Results
        System.out.println("\n--- Results ---");
        System.out.println("Total casts delivered: " + totalCastsDelivered);
        System.out.println("Max sprites: " + maxSprites);
        System.out.println("Last frame: " + lastFrame);
        System.out.println("Frames captured: " + captures);

        boolean pass = maxSprites >= 10 && lastFrame > 0;
        if (pass) {
            System.out.println("\nPASS (maxSprites=" + maxSprites + ", frame=" + lastFrame + ")");
        } else {
            System.out.println("\nFAIL: maxSprites=" + maxSprites + ", frame=" + lastFrame);
            System.exit(1);
        }
    }

    private static BufferedImage renderFrame(FrameSnapshot snap, int stageW, int stageH) {
        BufferedImage image = new BufferedImage(stageW, stageH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // Fill background
        g.setColor(new Color(snap.backgroundColor()));
        g.fillRect(0, 0, stageW, stageH);

        // Draw stage image
        if (snap.stageImage() != null) {
            g.drawImage(snap.stageImage().toBufferedImage(), 0, 0, null);
        }

        // Draw sprites
        for (RenderSprite sprite : snap.sprites()) {
            if (!sprite.isVisible()) continue;

            Bitmap bmp = sprite.getBakedBitmap();
            if (bmp == null) continue;

            int blend = sprite.getBlend();
            if (blend < 100) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
            } else {
                g.setComposite(AlphaComposite.SrcOver);
            }

            g.drawImage(bmp.toBufferedImage(), sprite.getX(), sprite.getY(), null);
        }

        g.dispose();
        return image;
    }

    // --- Network plumbing (same as before) ---

    private static int pumpNetwork(PollingNetProvider net, Player player) {
        int delivered = 0;
        while (!net.getPendingRequests().isEmpty()) {
            List<PollingNetProvider.PendingRequest> requests = new ArrayList<>(net.getPendingRequests());
            net.drainPendingRequests();

            for (var req : requests) {
                byte[] data = fetchWithFallbacks(req.url, req.fallbacks);
                if (data != null) {
                    jsUrlCache.put(req.url, data);

                    if (isCastFile(req.url)) {
                        net.onFetchStatusComplete(req.taskId, data.length);
                        boolean loaded = player.getCastLibManager()
                                .setExternalCastDataByUrl(req.url, data);
                        if (loaded) {
                            player.getBitmapCache().clear();
                            delivered++;
                            System.out.println("  Cast loaded: " + req.url);
                        }
                    } else {
                        net.onFetchComplete(req.taskId, data);
                    }
                } else {
                    net.onFetchError(req.taskId, 404);
                    System.out.println("  HTTP 404: " + req.url);
                }
            }
        }
        return delivered;
    }

    private static int deliverPendingCastRequests(Player player, String basePath) {
        if (pendingCastDataRequests.isEmpty()) return 0;

        List<String> requests = new ArrayList<>(pendingCastDataRequests);
        pendingCastDataRequests.clear();

        String baseDir = basePath;
        if (!baseDir.endsWith("/")) baseDir += "/";

        int delivered = 0;
        for (String fileName : requests) {
            String baseName = FileUtil.getFileNameWithoutExtension(FileUtil.getFileName(fileName));
            String cctUrl = baseDir + baseName + ".cct";
            String cstUrl = baseDir + baseName + ".cst";

            byte[] data = jsUrlCache.get(cctUrl);
            String url = cctUrl;
            if (data == null) {
                data = jsUrlCache.get(cstUrl);
                url = cstUrl;
            }
            if (data == null) {
                data = httpGet(cctUrl);
                if (data != null) {
                    jsUrlCache.put(cctUrl, data);
                    url = cctUrl;
                } else {
                    data = httpGet(cstUrl);
                    if (data != null) {
                        jsUrlCache.put(cstUrl, data);
                        url = cstUrl;
                    }
                }
            }

            if (data != null) {
                boolean loaded = player.getCastLibManager().setExternalCastDataByUrl(url, data);
                if (loaded) {
                    player.getBitmapCache().clear();
                    delivered++;
                    System.out.println("  Dynamic cast loaded: " + url);
                }
            }
        }
        return delivered;
    }

    private static boolean isCastFile(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        int qi = lower.indexOf('?');
        if (qi > 0) lower = lower.substring(0, qi);
        return lower.endsWith(".cct") || lower.endsWith(".cst");
    }

    private static byte[] fetchWithFallbacks(String url, String[] fallbacks) {
        String[] urls = fallbacks != null ? fallbacks : new String[]{url};
        for (String u : urls) {
            byte[] data = httpGet(u);
            if (data != null) return data;
        }
        return null;
    }

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
            int byteCount = task.done ? task.byteCount : 0;
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
            if (task != null) { task.data = data; task.byteCount = data != null ? data.length : 0; task.done = true; }
        }

        void onFetchStatusComplete(int taskId, int byteCount) {
            NetTask task = tasks.get(taskId);
            if (task != null) { task.data = null; task.byteCount = byteCount; task.done = true; }
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
            if (url.startsWith("http://") || url.startsWith("https://")) return url;
            String fileName = url;
            int lastSlash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
            if (lastSlash >= 0) fileName = url.substring(lastSlash + 1);
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
            int byteCount;
            int errorCode;
            boolean done;

            NetTask(int id, String url) { this.id = id; this.url = url; }
        }
    }
}
