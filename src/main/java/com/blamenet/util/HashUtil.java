package com.blamenet.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 计算工具，用于 manifest.json 中的真实文件校验值。 */
public final class HashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtil() {
    }

    /** 计算文件的 SHA-256，返回小写十六进制字符串。运行时真实计算，不使用任何预置值。 */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /** 计算字节数组的 SHA-256。 */
    public static String sha256(byte[] data) {
        MessageDigest digest = newDigest();
        digest.update(data);
        return toHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
