package com.mrgregles.bsp_server_utilities.streamer;

/**
 * Whether Spout2 is still publishing Streamer Mode's frames (SU-160). Spout 2.007.018's {@code SendTexture} does not always say
 * when a frame did not reach OBS: it returns true when it skipped the copy (its texture-access wait timed out, or the GL/DX
 * lock failed), and once its GL/DX link has failed it returns false on every later frame while {@code IsInitialized} stays
 * true. OBS's Spout2 source copies only when the sender's frame count moves, so either way OBS keeps showing the last frame
 * that got through: the picture freezes. This watch is fed each send's answer and the sender's frame count
 * ({@code GetFrame}) and says what to do: carry on, restart the sender once, or give up (Streamer Mode then falls back to the
 * stream window and says why). A sender that cannot be made at all is not this watch's: {@code SpoutOutput} gives up on it at
 * once, as before.
 *
 * <p>Giving up after the restart applies to frames Spout accepted but did not publish as well as to refused ones: such a
 * sender freezes OBS without a word, which is the fault this watch is for. If OBS itself was stuck, the streamer chooses
 * Spout2 again in the menu once OBS is back (audit, SU-160).
 *
 * <p>A frame counts as published when Spout accepted it and, when Spout counts frames, the count moved. With frame counting
 * off in Spout's settings the count stays 0, and only refusals can be seen. Pure arithmetic, no client classes: GameTested.
 */
public final class SpoutWatch {
    /** Sends in a row without a published frame before acting (a second at 60 sends a second, two at 30). */
    public static final int STALL = 60;
    /** Frames published since a restart (not in a row: an odd skipped frame does not count against it) before another restart is allowed. */
    public static final int SETTLED = 600;

    public enum Verdict {
        /** Carry on sending. */
        OK,
        /** Release the sender and make it again (clears Spout's failed GL/DX link). */
        RESTART,
        /** Spout is not publishing even after a restart: stop using it. */
        GIVE_UP
    }

    private int bad, sinceRestart, restarts;
    private long last = -1; // the frame count last seen; -1 before the first send or after a restart
    private boolean counting; // Spout's frame count has moved at least once since the sender was made
    private boolean everCounted; // ... at least once since the sender was first opened, for the log
    private int sends, published;
    private boolean publishedEver;

    /** Forgets everything: a new sender. */
    public void reset() {
        bad = sinceRestart = restarts = 0;
        last = -1;
        counting = everCounted = false;
        sends = published = 0;
        publishedEver = false;
    }

    /** One send: whether {@code SendTexture} accepted the picture, and the sender's frame count right after. */
    public Verdict record(boolean accepted, long frame) {
        sends++;
        if (frame > 0) {
            counting = everCounted = true;
        }
        boolean moved = !counting || frame != last;
        last = frame;
        if (accepted && moved) {
            published++;
            publishedEver = true;
            bad = 0;
            if (restarts > 0 && ++sinceRestart >= SETTLED) {
                restarts = 0;
            }
            return Verdict.OK;
        }
        if (++bad < STALL) {
            return Verdict.OK;
        }
        bad = 0;
        last = -1;
        counting = false; // a remade sender counts from 0 again
        if (restarts == 0) {
            restarts = 1;
            sinceRestart = 0;
            return Verdict.RESTART;
        }
        return Verdict.GIVE_UP;
    }

    /** Whether Spout's frame count has ever moved since the sender was opened: false means its frame counting is off, and
     * skipped frames cannot be seen, only refused ones. */
    public boolean countsFrames() {
        return everCounted;
    }

    /** Whether a frame has been published since the sender was opened (a later failure is a stop, not a failed start). */
    public boolean everPublished() {
        return publishedEver;
    }

    /** Sends and published frames since the last call, for the log's minute line: {sends, published}. */
    public int[] takeCounts() {
        int[] c = {sends, published};
        sends = published = 0;
        return c;
    }
}
