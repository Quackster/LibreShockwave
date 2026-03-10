package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.render.*;
import com.libreshockwave.vm.DebugConfig;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic: dump full hotel view sprite list and render PNG.
 */
public class HotelViewDiagnosticTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/hotel-view-diag";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();
        DebugConfig.setDebugPlaybackEnabled(true);

        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        Player player = new Player(file);
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=https://sandbox.h4bbo.net/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");
        player.preloadAllCasts();
        // Log ALL VM errors with full traces
        player.setErrorListener((msg, ex) -> {
            System.out.printf("[VM ERROR] %s | %s%n", msg, ex.getMessage());
        });
        player.play();

        CastLibManager clm = player.getCastLibManager();
        SpriteBaker baker = new SpriteBaker(player.getBitmapCache(), clm, player);
        StageRenderer renderer = player.getStageRenderer();

        int maxTicks = 500;
        int maxSprites = 0;
        int maxSpriteTick = 0;

        boolean errorDialogCaptured = false;
        int lastCh37Member = -1;
        int lastCh33Member = -1;
        for (int tick = 0; tick < maxTicks; tick++) {
            boolean alive = player.tick();
            if (!alive) break;

            int frame = player.getCurrentFrame();
            List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
            if (sprites.size() > maxSprites) {
                maxSprites = sprites.size();
                maxSpriteTick = tick;
            }
            // Track sprite member changes for animation debugging
            var ch37s = renderer.getSpriteRegistry().get(37);
            var ch33s = renderer.getSpriteRegistry().get(33);
            if (ch37s != null && ch37s.getEffectiveCastMember() != lastCh37Member) {
                if (tick < 40 || lastCh37Member == -1) // limit output
                    System.out.printf("[tick %d] ch37 member changed: %d -> %d%n", tick, lastCh37Member, ch37s.getEffectiveCastMember());
                lastCh37Member = ch37s.getEffectiveCastMember();
            }
            if (ch33s != null && ch33s.getEffectiveCastMember() != lastCh33Member) {
                System.out.printf("[tick %d] ch33 member changed: %d -> %d%n", tick, lastCh33Member, ch33s.getEffectiveCastMember());
                lastCh33Member = ch33s.getEffectiveCastMember();
            }
            // Check paletteRef on ch33's member for palette animation
            if (ch33s != null && ch33s.getEffectiveCastMember() > 0 && tick % 100 == 0) {
                var member = clm.getDynamicMember(ch33s.getEffectiveCastLib(), ch33s.getEffectiveCastMember());
                if (member != null) {
                    System.out.printf("[tick %d] ch33 member '%s' paletteVersion=%d hasPaletteOverride=%s%n",
                            tick, member.getName(), member.getPaletteVersion(), member.hasPaletteOverride());
                }
            }
            // Capture error dialog phase (window sprites appear around tick 10-30)
            if (!errorDialogCaptured && sprites.size() >= 8 && tick >= 10) {
                // Warmup bake to trigger async bitmap decode
                captureSnapshot(renderer, frame, "warmup", baker);
                Thread.sleep(2000); // Wait for async decoders
                // Now bake with decoded bitmaps
                FrameSnapshot errSnap = captureSnapshot(renderer, frame, "error-dialog", baker);
                Bitmap errImg = errSnap.renderFrame(RenderType.SOFTWARE);
                ImageIO.write(errImg.toBufferedImage(), "png",
                        new File(OUTPUT_DIR + "/error_dialog.png"));
                System.out.printf("Captured error dialog at tick %d with %d sprites%n", tick, sprites.size());
                errorDialogCaptured = true;
            }
            // Simulate ~20fps tempo so real-time timeouts (delay 500ms) can fire
            Thread.sleep(5);
        }

        System.out.printf("Max sprites: %d at tick %d%n", maxSprites, maxSpriteTick);
        System.out.printf("Current frame: %d, bg=0x%06X, stageImg=%s%n",
                player.getCurrentFrame(), renderer.getBackgroundColor(), renderer.hasStageImage());

        int frame = player.getCurrentFrame();
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);

        System.out.printf("%nTotal sprites: %d (sorted by locZ/channel)%n", sprites.size());
        System.out.println("---");
        for (RenderSprite s : sprites) {
            String memberInfo = s.getMemberName() != null ? s.getMemberName() : "null";
            if (s.getCastMember() != null) {
                memberInfo += " [" + s.getCastMember().memberType() + "]";
            }
            if (s.getDynamicMember() != null) {
                memberInfo += " [dyn:" + s.getDynamicMember().getMemberType() + "]";
            }
            System.out.printf("  ch=%d z=%d type=%s pos=(%d,%d) %dx%d ink=%d(%s) blend=%d visible=%s fg=0x%06X bg=0x%06X hasFg=%s hasBg=%s member=%s%n",
                    s.getChannel(), s.getLocZ(), s.getType(),
                    s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                    s.getInk(), s.getInkMode(), s.getBlend(), s.isVisible(),
                    s.getForeColor() & 0xFFFFFF, s.getBackColor() & 0xFFFFFF,
                    s.hasForeColor(), s.hasBackColor(),
                    memberInfo);
        }

        // Dump window layout text members
        System.out.println("\n--- Window Layout Text Members ---");
        for (String layoutName : new String[]{"habbo_simple.window", "login_a.window", "login_b.window"}) {
            String text = clm.getFieldValue(layoutName, 0);
            if (text != null && !text.isEmpty()) {
                System.out.printf("=== %s ===%n%s%n=== END %s ===%n", layoutName, text, layoutName);
            } else {
                System.out.printf("=== %s === NOT FOUND%n", layoutName);
            }
        }

        // Debug: check if receiveUpdate handler can be found
        System.out.println("\n--- Animation system debug ---");
        var vm = player.getVM();
        // Direct test: does CastLibManager find receiveUpdate?
        var clmHandler = clm.findHandler("receiveUpdate");
        System.out.printf("  clm.findHandler('receiveUpdate'): %s%n",
                clmHandler != null ? "FOUND" : "NOT FOUND");
        // Check timeout targets
        var tmgr = player.getTimeoutManager();
        System.out.printf("  Active timeouts: %d%n", tmgr.getTimeoutCount());
        // Test within-tick context: call vm.findHandler with providers set
        // Manually set up providers to test
        com.libreshockwave.vm.builtin.cast.CastLibProvider.setProvider(clm);
        try {
            var href = vm.findHandler("receiveUpdate");
            System.out.printf("  vm.findHandler('receiveUpdate') with provider: %s%n",
                    href != null ? "FOUND in " + href.script().scriptType() : "NOT FOUND");
            // Also test createObject
            href = vm.findHandler("createObject");
            System.out.printf("  vm.findHandler('createObject') with provider: %s%n",
                    href != null ? "FOUND" : "NOT FOUND");
        } finally {
            com.libreshockwave.vm.builtin.cast.CastLibProvider.clearProvider();
        }
        // Debug: check which cast libs are loaded and their script counts
        for (int i = 1; i <= 30; i++) {
            var cl = clm.getCastLib(i);
            if (cl != null) {
                var scripts = cl.getAllScripts();
                int movieScripts = 0;
                for (var sc : scripts) {
                    if (sc.scriptType() == com.libreshockwave.chunks.ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                        movieScripts++;
                    }
                }
                System.out.printf("  castLib %d '%s': loaded=%s scripts=%d movieScripts=%d%n",
                        i, cl.getName(), cl.isLoaded(), scripts.size(), movieScripts);
                // For fuse_client, dump ALL script types to see why movieScripts=0
                if (i == 2) {
                    var snames = cl.getScriptNames();
                    java.util.Map<com.libreshockwave.chunks.ScriptChunk.ScriptType, Integer> typeCounts = new java.util.EnumMap<>(com.libreshockwave.chunks.ScriptChunk.ScriptType.class);
                    for (var sc : scripts) {
                        typeCounts.merge(sc.scriptType(), 1, Integer::sum);
                    }
                    System.out.printf("    script types: %s scriptNames=%s%n", typeCounts,
                            cl.getScriptNames() != null ? "present" : "NULL");
                    // Find any script with receiveUpdate handler
                    for (var sc : scripts) {
                        var h = snames != null ? sc.findHandler("receiveUpdate", snames) : null;
                        if (h != null) {
                            System.out.printf("    FOUND receiveUpdate in script type=%s name='%s'%n",
                                    sc.scriptType(), sc.getScriptName());
                        }
                    }
                    // Show first 5 scripts
                    int count = 0;
                    for (var sc : scripts) {
                        if (count++ < 10) {
                            System.out.printf("    script[%d]: type=%s name='%s' handlers=%d%n",
                                    count, sc.scriptType(), sc.getScriptName(), sc.handlers().size());
                        }
                    }
                } else if (movieScripts > 0) {
                    var snames = cl.getScriptNames();
                    for (var sc : scripts) {
                        if (sc.scriptType() == com.libreshockwave.chunks.ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                            String name = sc.getScriptName();
                            var h = snames != null ? sc.findHandler("receiveUpdate", snames) : null;
                            System.out.printf("    movieScript name='%s' handlers=%d hasReceiveUpdate=%s%n",
                                    name, sc.handlers().size(), h != null);
                        }
                    }
                }
            }
        }

        // Dump bytecodes for Swap Animation Class advanceAnimFrame handler
        System.out.println("\n--- Swap Animation bytecode dump ---");
        com.libreshockwave.vm.builtin.cast.CastLibProvider.setProvider(clm);
        try {
            var loc = clm.findHandler("advanceAnimFrame");
            if (loc != null && loc.script() instanceof com.libreshockwave.chunks.ScriptChunk sc) {
                System.out.printf("  Found advanceAnimFrame in script '%s' (castLib %d)%n",
                        sc.getScriptName(), loc.castLibNumber());
                for (var h : sc.handlers()) {
                    String hn = sc.getHandlerName(h);
                    if ("advanceAnimFrame".equalsIgnoreCase(hn) || "define".equalsIgnoreCase(hn)) {
                        System.out.printf("  Handler '%s' (%d instructions):%n", hn, h.instructions().size());
                        for (var instr : h.instructions()) {
                            String argName = "";
                            try { argName = " [" + sc.resolveName(instr.argument()) + "]"; } catch (Exception e) {}
                            System.out.printf("    %04d: %-24s arg=%d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), argName);
                        }
                    }
                }
            } else {
                System.out.println("  advanceAnimFrame NOT FOUND in any cast lib!");
            }
        } finally {
            com.libreshockwave.vm.builtin.cast.CastLibProvider.clearProvider();
        }

        // Dump the entry.visual layout text to see swap animation definitions
        System.out.println("\n--- entry.visual layout ---");
        String entryVisual = clm.getFieldValue("entry.visual", 0);
        if (entryVisual != null) {
            // Just print lines containing swapAnim or brassivesiputousb
            for (String line : entryVisual.split("\n")) {
                if (line.toLowerCase().contains("swap") || line.toLowerCase().contains("brassiv") || line.toLowerCase().contains("fountain")) {
                    System.out.println("  " + line.trim());
                }
            }
        } else {
            System.out.println("  NOT FOUND");
        }

        // Check palette members in castLib 26 around member 56
        System.out.println("\n--- Palette member names (castLib 26) ---");
        for (int m = 44; m <= 60; m++) {
            var cm = clm.getDynamicMember(26, m);
            var chunk = clm.getCastMember(26, m);
            String name = cm != null ? cm.getName() : (chunk != null ? chunk.name() : "null");
            String type = chunk != null ? chunk.memberType().name() : "?";
            System.out.printf("  member(%d, 26): name='%s' type=%s%n", m, name, type);
        }

        // Check if memberExists finds palette members
        System.out.println("--- memberExists checks ---");
        for (String n : new String[]{"brassivesiputousb Palette1", "brassivesiputousb Palette2",
                "brassivesiputousb Palette10"}) {
            var ref = clm.getMemberByName(0, n);
            System.out.printf("  memberExists('%s'): %s%n", n, ref != null && !ref.isVoid() ? ref.toStr() : "NOT FOUND");
        }

        // Check missing channel 37 (fountain)
        var ch37state = renderer.getSpriteRegistry().get(37);
        if (ch37state != null) {
            System.out.printf("ch37: visible=%s castLib=%d castMember=%d hasDyn=%s ink=%d%n",
                    ch37state.isVisible(), ch37state.getEffectiveCastLib(),
                    ch37state.getEffectiveCastMember(), ch37state.hasDynamicMember(),
                    ch37state.getInk());
        } else {
            System.out.println("ch37: NOT IN REGISTRY");
        }

        // Check specific member lookups
        System.out.println("\n--- Member lookup checks ---");
        for (String name : new String[]{"brassivesiputousb", "Logo", "habbo ES fountain1", "corner_element"}) {
            com.libreshockwave.vm.datum.Datum ref = clm.getMemberByName(0, name);
            System.out.printf("  '%s': %s%n", name, ref != null && !ref.isVoid() ? ref.toStr() : "NOT FOUND");
        }
        // Check what castLib 16 member 52 resolves to
        var m52 = clm.getCastMember(16, 52);
        System.out.printf("  castLib=16 member=52: %s%n", m52 != null ? m52.name() : "null");
        // Check sprite state for ch=33
        var ch33state = renderer.getSpriteRegistry().get(33);
        if (ch33state != null) {
            System.out.printf("  ch33 sprite: castLib=%d castMember=%d hasDyn=%s%n",
                    ch33state.getEffectiveCastLib(), ch33state.getEffectiveCastMember(),
                    ch33state.hasDynamicMember());
        }

        // Trigger async bitmap decode by baking once, then wait for decoders
        System.out.println("\n--- Triggering bitmap decode ---");
        captureSnapshot(renderer, frame, "warmup", baker);
        Thread.sleep(2000);  // Wait for async bitmap decoders

        // Now bake again with decoded bitmaps
        System.out.println("--- Rendering ---");
        FrameSnapshot snapshot = captureSnapshot(renderer, frame, "hotel-view", baker);
        for (RenderSprite bs : snapshot.sprites()) {
            Bitmap bk = bs.getBakedBitmap();
            String bakeInfo = bk != null ? bk.getWidth() + "x" + bk.getHeight() : "NULL";
            System.out.printf("  baked ch=%d %s type=%s ink=%s member=%s%n",
                    bs.getChannel(), bakeInfo, bs.getType(), bs.getInkMode(), bs.getMemberName());
        }

        // Dump light1 bitmap details
        System.out.println("\n--- Light1 bitmap analysis ---");
        for (RenderSprite bs : snapshot.sprites()) {
            if ("light1".equals(bs.getMemberName())) {
                Bitmap bk = bs.getBakedBitmap();
                if (bk != null) {
                    int[] px = bk.getPixels();
                    int transparent = 0, white = 0, black = 0, other = 0;
                    int minR = 255, minG = 255, minB = 255, maxR = 0, maxG = 0, maxB = 0;
                    for (int p : px) {
                        int a = (p >>> 24);
                        if (a == 0) { transparent++; continue; }
                        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                        if (r == 255 && g == 255 && b == 255) white++;
                        else if (r == 0 && g == 0 && b == 0) black++;
                        else other++;
                        minR = Math.min(minR, r); minG = Math.min(minG, g); minB = Math.min(minB, b);
                        maxR = Math.max(maxR, r); maxG = Math.max(maxG, g); maxB = Math.max(maxB, b);
                    }
                    System.out.printf("  baked: %dx%d transparent=%d white=%d black=%d other=%d%n",
                            bk.getWidth(), bk.getHeight(), transparent, white, black, other);
                    System.out.printf("  color range: R[%d-%d] G[%d-%d] B[%d-%d]%n",
                            minR, maxR, minG, maxG, minB, maxB);
                    // Sample center and corners
                    int cx = bk.getWidth()/2, cy = bk.getHeight()/2;
                    for (int[] pt : new int[][]{{0,0},{cx,0},{bk.getWidth()-1,0},{0,cy},{cx,cy},{bk.getWidth()-1,cy},{0,bk.getHeight()-1},{cx,bk.getHeight()-1}}) {
                        int idx = pt[1] * bk.getWidth() + pt[0];
                        if (idx >= 0 && idx < px.length) {
                            int p = px[idx];
                            System.out.printf("  (%d,%d): ARGB=(%d,%d,%d,%d)%n", pt[0], pt[1],
                                    (p>>>24), (p>>16)&0xFF, (p>>8)&0xFF, p&0xFF);
                        }
                    }
                }
                // Also dump raw bitmap (before ink processing)
                if (bs.getCastMember() != null) {
                    var rawOpt = player.decodeBitmap(bs.getCastMember());
                    if (rawOpt.isPresent()) {
                        Bitmap raw = rawOpt.get();
                        int[] rpx = raw.getPixels();
                        int rt = 0, rw = 0, rb = 0, ro = 0;
                        for (int p : rpx) {
                            int a = (p >>> 24);
                            if (a == 0) { rt++; continue; }
                            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                            if (r == 255 && g == 255 && b == 255) rw++;
                            else if (r == 0 && g == 0 && b == 0) rb++;
                            else ro++;
                        }
                        System.out.printf("  raw: %dx%d bitDepth=%d transparent=%d white=%d black=%d other=%d%n",
                                raw.getWidth(), raw.getHeight(), raw.getBitDepth(), rt, rw, rb, ro);
                        // BitmapInfo
                        byte[] sd = bs.getCastMember().specificData();
                        if (sd != null && sd.length >= 10) {
                            var bi = com.libreshockwave.cast.BitmapInfo.parse(sd);
                            System.out.printf("  BitmapInfo: %dx%d bitDepth=%d useAlpha=%s paletteId=%d%n",
                                    bi.width(), bi.height(), bi.bitDepth(), bi.useAlpha(), bi.paletteId());
                        }
                        // Sample raw center
                        int cx = raw.getWidth()/2, cy = raw.getHeight()/2;
                        int idx = cy * raw.getWidth() + cx;
                        if (idx >= 0 && idx < rpx.length) {
                            int p = rpx[idx];
                            System.out.printf("  raw center (%d,%d): ARGB=(%d,%d,%d,%d)%n", cx, cy,
                                    (p>>>24), (p>>16)&0xFF, (p>>8)&0xFF, p&0xFF);
                        }
                        // Save raw as PNG
                        ImageIO.write(raw.toBufferedImage(), "png", new File(OUTPUT_DIR + "/light1_raw.png"));
                    }
                }
                break;
            }
        }

        // Dump brassivesiputousb bitmap details (logo shadow bug)
        System.out.println("\n--- brassivesiputousb bitmap analysis ---");
        for (RenderSprite bs : snapshot.sprites()) {
            if ("brassivesiputousb".equals(bs.getMemberName())) {
                System.out.printf("  ch=%d pos=(%d,%d) %dx%d ink=%d(%s) blend=%d fg=0x%06X bg=0x%06X hasFg=%s hasBg=%s%n",
                        bs.getChannel(), bs.getX(), bs.getY(), bs.getWidth(), bs.getHeight(),
                        bs.getInk(), bs.getInkMode(), bs.getBlend(),
                        bs.getForeColor() & 0xFFFFFF, bs.getBackColor() & 0xFFFFFF,
                        bs.hasForeColor(), bs.hasBackColor());
                Bitmap bk = bs.getBakedBitmap();
                if (bk != null) {
                    int[] px = bk.getPixels();
                    int transparent = 0, opaque = 0, semiTransparent = 0;
                    int black = 0, white = 0, other = 0;
                    java.util.Map<Integer, Integer> alphaHist = new java.util.TreeMap<>();
                    java.util.Map<Integer, Integer> colorHist = new java.util.TreeMap<>();
                    for (int p : px) {
                        int a = (p >>> 24);
                        if (a == 0) { transparent++; continue; }
                        else if (a == 255) opaque++;
                        else semiTransparent++;
                        alphaHist.merge(a, 1, Integer::sum);
                        int rgb = p & 0xFFFFFF;
                        colorHist.merge(rgb, 1, Integer::sum);
                        if (rgb == 0x000000) black++;
                        else if (rgb == 0xFFFFFF) white++;
                        else other++;
                    }
                    System.out.printf("  baked: %dx%d bitDepth=%d transparent=%d semiTransparent=%d opaque=%d%n",
                            bk.getWidth(), bk.getHeight(), bk.getBitDepth(), transparent, semiTransparent, opaque);
                    System.out.printf("  colors: black=%d white=%d other=%d%n", black, white, other);
                    System.out.printf("  alpha histogram (top 10): ");
                    alphaHist.entrySet().stream().sorted((a1, a2) -> a2.getValue() - a1.getValue()).limit(10)
                            .forEach(e -> System.out.printf("a=%d:%d ", e.getKey(), e.getValue()));
                    System.out.println();
                    System.out.printf("  color histogram (top 10): ");
                    colorHist.entrySet().stream().sorted((a1, a2) -> a2.getValue() - a1.getValue()).limit(10)
                            .forEach(e -> System.out.printf("0x%06X:%d ", e.getKey(), e.getValue()));
                    System.out.println();
                    ImageIO.write(bk.toBufferedImage(), "png",
                            new File(OUTPUT_DIR + "/sprite_brassivesiputousb_baked.png"));
                }
                // Dump raw bitmap
                if (bs.getCastMember() != null) {
                    var rawOpt = player.decodeBitmap(bs.getCastMember());
                    if (rawOpt.isPresent()) {
                        Bitmap raw = rawOpt.get();
                        int[] rpx = raw.getPixels();
                        int rt = 0, rw = 0, rb = 0, ro = 0;
                        java.util.Map<Integer, Integer> rawColorHist = new java.util.TreeMap<>();
                        for (int p : rpx) {
                            int a = (p >>> 24);
                            if (a == 0) { rt++; continue; }
                            int rgb = p & 0xFFFFFF;
                            rawColorHist.merge(rgb, 1, Integer::sum);
                            if (rgb == 0xFFFFFF) rw++;
                            else if (rgb == 0x000000) rb++;
                            else ro++;
                        }
                        System.out.printf("  raw: %dx%d bitDepth=%d transparent=%d white=%d black=%d other=%d%n",
                                raw.getWidth(), raw.getHeight(), raw.getBitDepth(), rt, rw, rb, ro);
                        System.out.printf("  raw color histogram (top 10): ");
                        rawColorHist.entrySet().stream().sorted((a1, a2) -> a2.getValue() - a1.getValue()).limit(10)
                                .forEach(e -> System.out.printf("0x%06X:%d ", e.getKey(), e.getValue()));
                        System.out.println();
                        // BitmapInfo
                        byte[] sd = bs.getCastMember().specificData();
                        if (sd != null && sd.length >= 10) {
                            var bi = com.libreshockwave.cast.BitmapInfo.parse(sd);
                            System.out.printf("  BitmapInfo: %dx%d bitDepth=%d useAlpha=%s paletteId=%d pitch=%d%n",
                                    bi.width(), bi.height(), bi.bitDepth(), bi.useAlpha(), bi.paletteId(), bi.pitch());
                        }
                        // Sample a 5x5 grid of pixels
                        System.out.println("  raw pixel grid (5x5):");
                        for (int gy = 0; gy < 5; gy++) {
                            int py = gy * raw.getHeight() / 5 + raw.getHeight() / 10;
                            System.out.printf("    y=%3d: ", py);
                            for (int gx = 0; gx < 5; gx++) {
                                int px2 = gx * raw.getWidth() / 5 + raw.getWidth() / 10;
                                int idx = py * raw.getWidth() + px2;
                                if (idx >= 0 && idx < rpx.length) {
                                    int p = rpx[idx];
                                    System.out.printf("(%3d,%3d)=0x%08X ", px2, py, p);
                                }
                            }
                            System.out.println();
                        }
                        ImageIO.write(raw.toBufferedImage(), "png",
                                new File(OUTPUT_DIR + "/sprite_brassivesiputousb_raw.png"));
                    } else {
                        System.out.println("  raw: DECODE FAILED");
                    }
                } else {
                    System.out.println("  castMember: null (dynamic member?)");
                    if (bs.getDynamicMember() != null) {
                        System.out.printf("  dynamicMember: type=%s%n", bs.getDynamicMember().getMemberType());
                    }
                }
                break;
            }
        }
        if (snapshot.sprites().stream().noneMatch(s -> "brassivesiputousb".equals(s.getMemberName()))) {
            System.out.println("  brassivesiputousb NOT FOUND in sprites!");
            // Try resolving by name
            var ref = clm.getMemberByName(0, "brassivesiputousb");
            System.out.printf("  lookup: %s%n", ref != null && !ref.isVoid() ? ref.toStr() : "NOT FOUND");
        }

        // Dump individual window sprite bitmaps for debugging
        System.out.println("\n--- Window sprite bitmap dumps ---");
        for (RenderSprite bs : snapshot.sprites()) {
            Bitmap bk = bs.getBakedBitmap();
            if (bk != null && bs.getMemberName() != null && (
                    bs.getMemberName().contains("login_b_login_b") ||
                    bs.getMemberName().contains("login_b_back") ||
                    bs.getMemberName().contains("login_a_login_a_title") ||
                    bs.getMemberName().contains("login_b_login_b_title") ||
                    bs.getMemberName().contains("login_b_login_ok") ||
                    bs.getMemberName().contains("login_a_login_a") ||
                    bs.getMemberName().startsWith("login_a_back") ||
                    bs.getMemberName().startsWith("login_name") ||
                    bs.getMemberName().startsWith("login_password"))) {
                String safeName = bs.getMemberName().replaceAll("[^a-zA-Z0-9_]", "_");
                ImageIO.write(bk.toBufferedImage(), "png",
                        new File(OUTPUT_DIR + "/sprite_" + safeName + ".png"));
                // Sample some pixels
                int[] px = bk.getPixels();
                int white = 0, black = 0, other = 0;
                for (int p : px) {
                    int rgb = p & 0xFFFFFF;
                    int a = (p >>> 24);
                    if (a == 0) continue;
                    if (rgb == 0xFFFFFF) white++;
                    else if (rgb == 0x000000) black++;
                    else other++;
                }
                System.out.printf("  %s: %dx%d white=%d black=%d other=%d%n",
                        bs.getMemberName(), bk.getWidth(), bk.getHeight(), white, black, other);
            }
        }

        Bitmap rendered = snapshot.renderFrame(RenderType.SOFTWARE);
        ImageIO.write(rendered.toBufferedImage(), "png",
                new File(OUTPUT_DIR + "/hotel_view.png"));

        // Check pixel content
        int[] px = rendered.getPixels();
        int nonBlack = 0, tealish = 0;
        for (int p : px) {
            if (p != 0xFF000000 && p != 0) nonBlack++;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (g > 150 && b > 180 && r < 150) tealish++;
        }
        System.out.printf("%nPixel stats: nonBlack=%d/%d tealish=%d%n", nonBlack, px.length, tealish);
        System.out.println("Output: " + OUTPUT_DIR + "/hotel_view.png");

        // --- INPUT TEST: simulate mouse click on the Name field ---
        System.out.println("\n--- Input Click Test ---");
        // Find a sprite that has scriptInstanceList (Event Broker)
        var registry = renderer.getSpriteRegistry();
        for (RenderSprite rs : sprites) {
            int ch = rs.getChannel();
            var st = registry.get(ch);
            if (st != null && !st.getScriptInstanceList().isEmpty()) {
                System.out.printf("  ch=%d has scriptInstanceList size=%d at (%d,%d) %dx%d%n",
                        ch, st.getScriptInstanceList().size(),
                        rs.getX(), rs.getY(), rs.getWidth(), rs.getHeight());
            }
        }
        // Click at center of the Name input field (approximately)
        // First find which sprite the Name field is
        int nameFieldX = 510, nameFieldY = 300;
        int hitCh = com.libreshockwave.player.input.HitTester.hitTest(renderer, frame, nameFieldX, nameFieldY);
        System.out.printf("  HitTest(%d,%d) = ch %d%n", nameFieldX, nameFieldY, hitCh);
        if (hitCh > 0) {
            var hitState = registry.get(hitCh);
            if (hitState != null) {
                System.out.printf("  hit sprite: scriptInstanceList=%d%n", hitState.getScriptInstanceList().size());
            }
        }
        // Simulate the click
        player.onMouseDown(nameFieldX, nameFieldY, false);
        player.tick();
        Thread.sleep(50);
        player.onMouseUp(nameFieldX, nameFieldY, false);
        player.tick();
        Thread.sleep(50);
        System.out.println("  Click simulated, checking keyboardFocusSprite...");
        // The keyboardFocusSprite should now be set if the click worked
        var inputState = player.getInputState();
        if (inputState != null) {
            System.out.printf("  keyboardFocusSprite = %d%n", inputState.getKeyboardFocusSprite());
        }

        player.shutdown();
    }

    private static FrameSnapshot captureSnapshot(StageRenderer renderer, int frame, String state, SpriteBaker baker) {
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
        List<RenderSprite> baked = baker.bakeSprites(sprites);
        renderer.setLastBakedSprites(baked);
        String debug = String.format("Frame %d | %s", frame, state);
        return new FrameSnapshot(frame, renderer.getStageWidth(), renderer.getStageHeight(),
            renderer.getBackgroundColor(), List.copyOf(baked), debug,
            renderer.hasStageImage() ? renderer.getStageImage() : null);
    }
}
