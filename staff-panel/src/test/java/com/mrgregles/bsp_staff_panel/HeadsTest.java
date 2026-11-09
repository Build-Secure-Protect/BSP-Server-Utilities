package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.web.Heads;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Players' heads (SU-34): cut from the skin, Mojang's address only, fetched once. */
class HeadsTest {
    private static final String URL = "https://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef";

    private static byte[] skin(int face, int hat) throws Exception {
        BufferedImage s = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                s.setRGB(x, y, face);
            }
        }
        s.setRGB(40, 8, hat); // one hat pixel over the face's top left
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(s, "png", b);
        return b.toByteArray();
    }

    @Test
    void the_face_and_hat_are_cut_and_scaled_and_kept() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        byte[] png = skin(0xFF336699, 0xFFFF0000);
        Heads h = new Heads(u -> {
            fetches.incrementAndGet();
            return png;
        });
        BufferedImage head = ImageIO.read(new ByteArrayInputStream(h.head(URL).orElseThrow()));
        assertEquals(Heads.SIZE, head.getWidth());
        assertEquals(0xFFFF0000, head.getRGB(3, 3), "the hat layer over the face");
        assertEquals(0xFF336699, head.getRGB(40, 40), "the face");
        h.head(URL);
        assertEquals(1, fetches.get(), "fetched once, then kept");
    }

    @Test
    void only_mojang_s_address_and_real_skins() throws Exception {
        Heads h = new Heads(u -> {
            throw new AssertionError("never fetched: " + u);
        });
        assertFalse(h.head("https://evil.example/texture/0123456789abcdef0123456789abcdef").isPresent());
        assertFalse(h.head("http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef").isPresent(), "https only");
        assertFalse(h.head("https://textures.minecraft.net/texture/../../x").isPresent());
        assertFalse(h.head(null).isPresent());
        assertFalse(new Heads(u -> new byte[]{1, 2, 3}).head(URL).isPresent(), "not a picture");
        assertFalse(new Heads(u -> null).head(URL).isPresent(), "not fetched");
        assertTrue(Heads.allowed(URL));
    }

    @Test
    void the_feed_knows_each_player_s_skin() {
        Feed f = new Feed(System::currentTimeMillis);
        f.accept("Spawn", System.currentTimeMillis(), "{\"v\":1,\"at\":1,\"players\":[{\"id\":\"a\",\"name\":\"A\",\"skin\":\"" + URL + "\"},{\"id\":\"b\",\"name\":\"B\",\"skin\":null}]}");
        assertEquals(URL, f.skinOf("a"));
        assertNull(f.skinOf("b"));
        assertNull(f.skinOf("c"));
    }
}
