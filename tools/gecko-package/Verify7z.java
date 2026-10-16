import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 用客户端同款解码器（commons-compress + xz）验证内核 7z 包可读、结构正确。
 * API 用法与 tokay-archive 的 SevenZExtractor 保持一致。
 */
public class Verify7z {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        File pkg = new File(args[0]);
        String arch = args[1];
        File outDir = Files.createTempDirectory("verify7z").toFile();

        int soCount = 0;
        boolean hasBaseApk = false;
        long totalBytes = 0;
        List<String> names = new ArrayList<>();
        int dirEntries = 0;

        try (SevenZFile sevenZ = new SevenZFile(pkg, SevenZFileOptions.DEFAULT)) {
            SevenZArchiveEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = sevenZ.getNextEntry()) != null) {
                names.add(entry.getName());
                totalBytes += entry.getSize();
                if (entry.isDirectory()) {
                    dirEntries++;
                }
                if (entry.getName().startsWith("jni/" + arch + "/") && entry.getName().endsWith(".so")) {
                    soCount++;
                }
                if (entry.getName().equals("res/base.apk")) {
                    hasBaseApk = true;
                    try (FileOutputStream fos = new FileOutputStream(new File(outDir, "base.apk"))) {
                        int n;
                        while ((n = sevenZ.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
            }
        }

        File baseApk = new File(outDir, "base.apk");
        List<String> apkEntries = new ArrayList<>();
        long omniSize = -1;
        try (ZipFile zip = new ZipFile(baseApk)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                apkEntries.add(entries.nextElement().getName());
            }
            ZipEntry omni = zip.getEntry("assets/omni.ja");
            if (omni != null) {
                omniSize = omni.getSize();
            }
        }
        long reFiles = apkEntries.stream().filter(n -> n.startsWith("assets/runtime_extensions/")).count();

        System.out.println("7z 文件:          " + pkg.getName() + "  (" + pkg.length() + " bytes)");
        System.out.println("7z 条目:          " + names.size() + " (目录 " + dirEntries + ")，解压后 " + totalBytes + " bytes");
        System.out.println("jni/*.so 数量:    " + soCount + "  (期望 13)");
        System.out.println("res/base.apk:     " + hasBaseApk);
        System.out.println("base.apk 条目:    " + apkEntries.size());
        System.out.println("  assets/omni.ja: " + omniSize + " bytes");
        System.out.println("  runtime_extensions 条目数: " + reFiles);
        System.out.println("  AndroidManifest.xml: " + apkEntries.contains("AndroidManifest.xml"));
        System.out.println("  META-INF/MANIFEST.MF: " + apkEntries.contains("META-INF/MANIFEST.MF"));

        boolean ok = soCount == 13 && hasBaseApk && omniSize > 0 && reFiles > 0
                && apkEntries.contains("AndroidManifest.xml") && apkEntries.contains("META-INF/MANIFEST.MF");
        System.out.println(ok ? "VERIFY_OK" : "VERIFY_FAILED");
        System.exit(ok ? 0 : 1);
    }
}
