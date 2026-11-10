package com.solar.launcher;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

/** 2026-11-10 — album artist from a FLAC's Vorbis comments (Y1, API 17). */
public class FlacVorbisCommentTest {

    private static byte[] block(int type, boolean last, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((last ? 0x80 : 0) | type);
        o.write((body.length >> 16) & 0xff);
        o.write((body.length >> 8) & 0xff);
        o.write(body.length & 0xff);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static byte[] vorbis(String... kvs) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] vendor = "test".getBytes("UTF-8");
        o.write(le(vendor.length)); o.write(vendor);
        o.write(le(kvs.length));
        for (String kv : kvs) {
            byte[] b = kv.getBytes("UTF-8");
            o.write(le(b.length)); o.write(b);
        }
        return o.toByteArray();
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static File flac(byte[]... blocks) throws Exception {
        File f = File.createTempFile("flactest", ".flac");
        f.deleteOnExit();
        FileOutputStream out = new FileOutputStream(f);
        out.write(new byte[] {'f', 'L', 'a', 'C'});
        for (byte[] b : blocks) out.write(b);
        out.write(new byte[64]);       // stand-in audio frames
        out.close();
        return f;
    }

    @Test
    public void readsAlbumArtistAfterOtherBlocks() throws Exception {
        File f = flac(block(0, false, new byte[34]),          // STREAMINFO
                block(6, false, new byte[5000]),               // PICTURE, skipped by length
                block(4, true, vorbis("TITLE=Skinny Love", "ARTIST=Bon Iver", "albumartist=Bon Iver")));
        assertEquals("Bon Iver", AudioTags.flacVorbisComment(f, "ALBUMARTIST", "ALBUM ARTIST"));
    }

    @Test
    public void acceptsSpacedKeyAndUtf8() throws Exception {
        File f = flac(block(0, false, new byte[34]),
                block(4, true, vorbis("ALBUM ARTIST=Beyoncé")));
        assertEquals("Beyoncé", AudioTags.flacVorbisComment(f, "ALBUMARTIST", "ALBUM ARTIST"));
    }

    @Test
    public void emptyWhenMissingOrNotFlac() throws Exception {
        File f = flac(block(0, false, new byte[34]), block(4, true, vorbis("ARTIST=Someone")));
        assertEquals("", AudioTags.flacVorbisComment(f, "ALBUMARTIST"));
        File g = File.createTempFile("flactest", ".flac");
        g.deleteOnExit();
        new FileOutputStream(g).close();
        assertEquals("", AudioTags.flacVorbisComment(g, "ALBUMARTIST"));
    }
}
