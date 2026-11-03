package android.util;

public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return "";
        if ((flags & URL_SAFE) != 0) {
            java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder();
            if ((flags & NO_PADDING) != 0) {
                enc = enc.withoutPadding();
            }
            return enc.encodeToString(input);
        } else {
            java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
            if ((flags & NO_PADDING) != 0) {
                enc = enc.withoutPadding();
            }
            return enc.encodeToString(input);
        }
    }

    public static byte[] decode(String str, int flags) {
        if (str == null || str.isEmpty()) return new byte[0];
        try {
            if ((flags & URL_SAFE) != 0) {
                return java.util.Base64.getUrlDecoder().decode(str);
            } else {
                return java.util.Base64.getDecoder().decode(str);
            }
        } catch (IllegalArgumentException e) {
            // Try standard decoder if url decoder failed or vice versa
            try {
                return java.util.Base64.getDecoder().decode(str);
            } catch (Exception ignored) {
                return java.util.Base64.getUrlDecoder().decode(str);
            }
        }
    }

    public static byte[] decode(byte[] input, int flags) {
        if (input == null) return new byte[0];
        return decode(new String(input, java.nio.charset.StandardCharsets.UTF_8), flags);
    }
}
