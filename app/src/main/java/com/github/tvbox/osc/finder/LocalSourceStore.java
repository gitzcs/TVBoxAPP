package com.github.tvbox.osc.finder;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 本地合并源的存储。
 *
 * <p>文件放在 App 私有目录 {@code files/finder/}，用 {@code local://文件名} 作为配置地址，
 * 由 {@code ApiConfig} 直接读文件。不用 {@code file://}：那条路径会被转成 clan 地址、
 * 经本地 HTTP 服务读外部存储，API 23+ 需要存储权限，API 24+ 还受 file:// 暴露限制。
 */
public final class LocalSourceStore {

    public static final String SCHEME = "local://";
    public static final String MERGED_FILE = "merged_sources.json";
    public static final String MERGED_URL = SCHEME + MERGED_FILE;
    public static final String MERGED_NAME = "本地合并源";

    private static final String DIR = "finder";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private LocalSourceStore() {
    }

    public static boolean isLocal(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    public static File mergedFile(Context context) {
        return new File(new File(context.getFilesDir(), DIR), MERGED_FILE);
    }

    /** 读 local:// 地址对应的文件；地址非法或文件不存在返回 null。 */
    public static String read(Context context, String url) {
        File file = fileFor(context, url);
        if (file == null || !file.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 先写临时文件再替换，避免写一半时被首页读到残缺配置。 */
    public static boolean writeMerged(Context context, String json) {
        File target = mergedFile(context);
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(dir, MERGED_FILE + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(json.getBytes(UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            closeQuietly(out);
            tmp.delete();
            return false;
        }
        closeQuietly(out);
        // File.renameTo 在部分老设备上不会覆盖已存在的目标
        if (target.exists() && !target.delete()) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(target);
    }

    /** 只允许 files/finder/ 下的单个文件名，防止 ../ 越界读取。 */
    static File fileFor(Context context, String url) {
        if (!isLocal(url)) {
            return null;
        }
        String name = url.substring(SCHEME.length()).trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        return new File(new File(context.getFilesDir(), DIR), name);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
