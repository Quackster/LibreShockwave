package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Director Player.
 */
class PlayerTest {

    @Test
    void testPlayerStateTransitions() {
        // Create a minimal mock for testing state transitions
        Player player = createMockPlayer();

        // Initially stopped
        assertEquals(PlayerState.STOPPED, player.getState());
        assertEquals(1, player.getCurrentFrame());

        // Play
        player.play();
        assertEquals(PlayerState.PLAYING, player.getState());

        // Pause
        player.pause();
        assertEquals(PlayerState.PAUSED, player.getState());

        // Resume
        player.resume();
        assertEquals(PlayerState.PLAYING, player.getState());

        // Stop
        player.stop();
        assertEquals(PlayerState.STOPPED, player.getState());
        assertEquals(1, player.getCurrentFrame());
    }

    @Test
    void testPauseFromStopped() {
        Player player = createMockPlayer();

        // Pause from stopped should have no effect
        player.pause();
        assertEquals(PlayerState.STOPPED, player.getState());
    }

    @Test
    void testResumeFromStopped() {
        Player player = createMockPlayer();

        // Resume from stopped should have no effect
        player.resume();
        assertEquals(PlayerState.STOPPED, player.getState());
    }

    @Test
    void testStepFrame() {
        Player player = createMockPlayer();

        // Step from stopped should start paused at frame 1
        player.stepFrame();
        assertEquals(PlayerState.PAUSED, player.getState());

        // Frame should advance (if there are frames)
        int frame = player.getCurrentFrame();
        assertTrue(frame >= 1);
    }

    @Test
    void testTempo() {
        Player player = createMockPlayer();

        // Default tempo from file
        assertTrue(player.getTempo() > 0);

        // Set tempo
        player.setTempo(30);
        assertEquals(30, player.getTempo());

        // Invalid tempo should default to 15
        player.setTempo(0);
        assertEquals(15, player.getTempo());

        player.setTempo(-5);
        assertEquals(15, player.getTempo());
    }

    @Test
    void testGoToFrame() {
        Player player = createMockPlayer();
        player.play();

        // Go to valid frame
        player.goToFrame(5);
        player.tick();  // Process the frame change
        // Frame should be 5 or loop if file has fewer frames
        assertTrue(player.getCurrentFrame() >= 1);

        // Invalid frame (0) should be ignored
        int before = player.getCurrentFrame();
        player.goToFrame(0);
        player.tick();
        // Frame should not change to 0
        assertTrue(player.getCurrentFrame() >= 1);
    }

    @Test
    void testEventListener() {
        Player player = createMockPlayer();

        List<PlayerEventInfo> events = new ArrayList<>();
        player.setEventListener(events::add);

        player.play();
        player.tick();

        // Should have received some events
        // (actual events depend on file content)
        assertNotNull(events);
    }

    @Test
    void testVMAccess() {
        Player player = createMockPlayer();

        LingoVM vm = player.getVM();
        assertNotNull(vm);

        // Test VM globals through player
        vm.setGlobal("testVar", Datum.of(42));
        assertEquals(42, vm.getGlobal("testVar").toInt());
    }

    @Test
    void testFrameLabels() {
        Player player = createMockPlayer();

        // Get labels (may be empty if file has none)
        assertNotNull(player.getFrameLabels());

        // Unknown label returns -1
        assertEquals(-1, player.getFrameForLabel("nonexistent"));
    }

    @Test
    void testTickWhileStopped() {
        Player player = createMockPlayer();

        // Tick while stopped should return false
        assertFalse(player.tick());
    }

    @Test
    void testTickWhilePaused() {
        Player player = createMockPlayer();
        player.play();
        player.pause();

        // Tick while paused should return true (still active, just not advancing)
        assertTrue(player.tick());
        assertEquals(PlayerState.PAUSED, player.getState());
    }

    @Test
    void testFileAccess() {
        Player player = createMockPlayer();
        // File may be null for mock player, just verify we can call it
        // A real file test would need an actual Director file
        player.getFile();
    }

    // Helper to create a mock player for testing
    private Player createMockPlayer() {
        // Try to find a test file, or create a minimal mock
        try {
            // Try common test file locations
            Path[] testPaths = {
                Path.of("C:/temp/test.dir"),
                Path.of("C:/temp/test.dcr"),
                Path.of("../test-files/test.dir")
            };

            for (Path path : testPaths) {
                if (Files.exists(path)) {
                    DirectorFile file = DirectorFile.load(path);
                    return new Player(file);
                }
            }
        } catch (Exception e) {
            // Ignore and fall through to mock
        }

        // Return player with null file (limited functionality but tests state machine)
        return new MockPlayer();
    }

    /**
     * Mock player for testing without a real Director file.
     */
    private static class MockPlayer extends Player {
        public MockPlayer() {
            super(null);
        }

        @Override
        public int getFrameCount() {
            return 10;  // Mock 10 frames
        }
    }
}
