package hungeralert.patches;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.Client;
import necesse.engine.state.MainGame;
import necesse.entity.mobs.PlayerMob;
import net.bytebuddy.asm.Advice;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.lang.reflect.Field;

/**
 * Patches MainGame.drawHud to draw a pulsing red vignette border around the
 * screen whenever the local player's hunger drops below 10%.
 */
@ModMethodPatch(target = MainGame.class, name = "drawHud", arguments = {TickManager.class})
public class MainGameDrawHudPatch {

    /** Trigger threshold — 10% of the 0–100 hunger scale. */
    private static final float HUNGER_WARNING_LEVEL = 10.0f;

    /**
     * Vignette reach as a fraction of screen height (top/bottom)
     * and screen width (left/right).  0.40 = 40 % from each edge.
     */
    private static final float VIG_H_FRAC = 0.40f;

    /** Throttle debug logging to once every 5 seconds. Must be public — advice is inlined into MainGame. */
    public static long lastDebugLog = 0;

    @Advice.OnMethodExit
    public static void onExit(@Advice.This MainGame mainGame) {

        try {
            // ── 1. Get client via reflection (proven OasisFinder pattern) ────
            Field clientField = MainGame.class.getDeclaredField("client");
            clientField.setAccessible(true);
            Client client = (Client) clientField.get(mainGame);

            if (client == null) {
                debugLog("[HungerAlert] client field is null — skipping draw");
                return;
            }

            // ── 2. Get the local player ──────────────────────────────────────
            PlayerMob player = client.getPlayer();
            if (player == null) {
                debugLog("[HungerAlert] getPlayer() returned null — skipping draw");
                return;
            }

            // ── 3. Read hunger via reflection (guards against private access) ─
            Field hungerField = PlayerMob.class.getDeclaredField("hungerLevel");
            hungerField.setAccessible(true);
            float hunger = hungerField.getFloat(player);

            debugLog("[HungerAlert] hunger = " + hunger);

            if (hunger >= HUNGER_WARNING_LEVEL) return;

            System.out.println("[HungerAlert] Low hunger (" + hunger + "%) — drawing alert border");

            // ── 4. Calculate pulsing alpha (~800 ms period) ──────────────────
            long  now   = System.currentTimeMillis();
            float pulse = (float) (Math.sin(now / 400.0) * 0.5 + 0.5); // 0.0–1.0
            float alpha = 0.40f + 0.40f * pulse;                        // 0.40–0.80

            // ── 5. Get current viewport dimensions ───────────────────────────
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            int w = vp[2];
            int h = vp[3];
            debugLog("[HungerAlert] viewport = " + w + "x" + h);
            if (w <= 0 || h <= 0) return;

            // ── 6. Save GL state ─────────────────────────────────────────────
            int[]     prevProgram  = new int[1];
            GL11.glGetIntegerv(0x8B8D /* GL_CURRENT_PROGRAM */, prevProgram);
            boolean   blendWasOn  = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean   texWasOn    = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);

            // ── 7. Set up unshaded, alpha-blended drawing ─────────────────────
            GL20.glUseProgram(0);                              // unbind any shader
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);

            // ── 8. Draw gradient vignette — red at edges, transparent inward ─
            //   vigH/vigW are how far the gradient reaches from each edge.
            //   Each quad uses per-vertex colour so GL interpolates smoothly.
            //   Left/right strips are clamped to [vigH, h-vigH] so the four
            //   corner regions are handled exclusively by the top/bottom strips.
            float vigH = h * VIG_H_FRAC;
            float r = 0.90f, g = 0.0f, b = 0.0f;

            GL11.glBegin(GL11.GL_QUADS);

            // Top strip  (full width, red→transparent downward)
            GL11.glColor4f(r, g, b, alpha); GL11.glVertex2f(0, 0);
            GL11.glColor4f(r, g, b, alpha); GL11.glVertex2f(w, 0);
            GL11.glColor4f(r, g, b, 0.0f);  GL11.glVertex2f(w, vigH);
            GL11.glColor4f(r, g, b, 0.0f);  GL11.glVertex2f(0, vigH);

            // Bottom strip  (full width, transparent→red downward)
            GL11.glColor4f(r, g, b, 0.0f);  GL11.glVertex2f(0, h - vigH);
            GL11.glColor4f(r, g, b, 0.0f);  GL11.glVertex2f(w, h - vigH);
            GL11.glColor4f(r, g, b, alpha); GL11.glVertex2f(w, h);
            GL11.glColor4f(r, g, b, alpha); GL11.glVertex2f(0, h);

            GL11.glEnd();

            // ── 9. Restore GL state ──────────────────────────────────────────
            GL20.glUseProgram(prevProgram[0]);
            if (!blendWasOn) GL11.glDisable(GL11.GL_BLEND);
            if (texWasOn)    GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);

        } catch (Throwable t) {
            // Broad catch so no Error subclass (e.g. IllegalAccessError) escapes silently.
            // Use the same throttle so a recurring error can't flood the log.
            debugLog("[HungerAlert] Unexpected error in drawHud patch: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Prints msg at most once every 5 seconds to avoid log spam. Must be public — advice is inlined into MainGame. */
    public static void debugLog(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastDebugLog > 5000) {
            System.out.println(msg);
            lastDebugLog = now;
        }
    }
}
