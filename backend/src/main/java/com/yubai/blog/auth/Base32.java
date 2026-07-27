package com.yubai.blog.auth;

final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    static String encode(byte[] data) {
        var buf = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                buf.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            buf.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return buf.toString();
    }

    static byte[] decode(String encoded) {
        var cleaned = encoded.replace("=", "").toUpperCase(java.util.Locale.ROOT);
        var chars = cleaned.toCharArray();
        int buffer = 0;
        int bits = 0;
        int byteCount = chars.length * 5 / 8;
        var result = new byte[byteCount];
        int pos = 0;
        for (char c : chars) {
            int idx = ALPHABET.indexOf(c);
            if (idx < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                result[pos++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return result;
    }
}
