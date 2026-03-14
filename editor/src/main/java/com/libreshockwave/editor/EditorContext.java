package com.libreshockwave.editor;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.audio.EditorAudioBackend;
import com.libreshockwave.editor.selection.SelectionManager;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
import com.libreshockwave.player.debug.DebugController;

import javax.swing.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.nio.file.Path;

/**
 * Central shared state for the editor.
 * Holds the current DirectorFile, Player, DebugController, selection state, and playback timer.
 * All panels observe this via PropertyChangeEvents.
 */
public class EditorContext {

    public static final String PROP_FILE = "file";
    public static final String PROP_FRAME = "currentFrame";
    public static final String PROP_PLAYING = "playing";

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final SelectionManager selectionManager = new SelectionManager();

    private DirectorFile file;
    private Player player;
    private DebugController debugController;
    private Timer playbackTimer;
    private int currentFrame = 1;
    private Path currentPath;

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener);
    }

    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    public DirectorFile getFile() {
        return file;
    }

    public Player getPlayer() {
        return player;
    }

    public DebugController getDebugController() {
        return debugController;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public Path getCurrentPath() {
        return currentPath;
    }

    public void openFile(Path path) {
        closeFile();
        try {
            DirectorFile newFile = DirectorFile.load(path);
            Player newPlayer = new Player(newFile);
            this.currentPath = path;

            // Wire audio backend
            newPlayer.setAudioBackend(new EditorAudioBackend());

            // Wire debug controller
            DebugController newDebugController = new DebugController();
            newPlayer.setDebugController(newDebugController);
            newPlayer.setDebugEnabled(true);

            DirectorFile oldFile = this.file;
            this.file = newFile;
            this.player = newPlayer;
            this.debugController = newDebugController;
            this.currentFrame = 1;

            pcs.firePropertyChange(PROP_FILE, oldFile, newFile);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                "Failed to load file: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void closeFile() {
        stopPlayback();
        if (player != null) {
            player.shutdown();
        }
        DirectorFile oldFile = this.file;
        this.file = null;
        this.player = null;
        this.debugController = null;
        this.currentPath = null;
        this.currentFrame = 1;
        selectionManager.clearSelection();
        if (oldFile != null) {
            pcs.firePropertyChange(PROP_FILE, oldFile, null);
        }
    }

    public void setCurrentFrame(int frame) {
        int oldFrame = this.currentFrame;
        this.currentFrame = frame;
        if (player != null) {
            player.goToFrame(frame);
            player.stepFrame();
        }
        pcs.firePropertyChange(PROP_FRAME, oldFrame, frame);
    }

    // Playback controls

    public void play() {
        if (player == null) return;
        player.play();
        startPlaybackTimer();
        pcs.firePropertyChange(PROP_PLAYING, false, true);
    }

    public void stop() {
        if (player == null) return;
        stopPlayback();
        player.stop();
        pcs.firePropertyChange(PROP_PLAYING, true, false);
    }

    public void rewind() {
        if (player == null) return;
        stopPlayback();
        player.stop();
        setCurrentFrame(1);
        pcs.firePropertyChange(PROP_PLAYING, true, false);
    }

    public void stepForward() {
        if (player == null) return;
        player.stepFrame();
        int oldFrame = currentFrame;
        currentFrame = player.getCurrentFrame();
        pcs.firePropertyChange(PROP_FRAME, oldFrame, currentFrame);
    }

    public void stepBackward() {
        if (player == null) return;
        int current = player.getCurrentFrame();
        if (current > 1) {
            setCurrentFrame(current - 1);
        }
    }

    public boolean isPlaying() {
        return playbackTimer != null && playbackTimer.isRunning();
    }

    private void startPlaybackTimer() {
        if (playbackTimer != null) {
            playbackTimer.stop();
        }
        int delay = 1000 / player.getTempo();
        playbackTimer = new Timer(delay, e -> {
            if (player == null || player.getState() != PlayerState.PLAYING) {
                stopPlayback();
                return;
            }
            if (player.tick()) {
                int oldFrame = currentFrame;
                currentFrame = player.getCurrentFrame();
                pcs.firePropertyChange(PROP_FRAME, oldFrame, currentFrame);
            }
        });
        playbackTimer.start();
    }

    private void stopPlayback() {
        if (playbackTimer != null) {
            playbackTimer.stop();
            playbackTimer = null;
        }
    }
}
