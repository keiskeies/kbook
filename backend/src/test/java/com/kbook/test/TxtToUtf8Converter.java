package com.kbook.test;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class TxtToUtf8Converter {

    // 候选编码列表（GBK + GB18030 + 繁中/日/韩 + UTF-16）
    private static final List<Charset> CANDIDATE_CHARSETS = Arrays.asList(
            Charset.forName("GBK"),            // 简体中文（您之前的原始编码）
            Charset.forName("GB18030"),        // 简体中文扩展（覆盖 GBK）
            Charset.forName("BIG5"),           // 繁体中文
            Charset.forName("Shift_JIS"),      // 日文
            Charset.forName("EUC-JP"),         // 日文
            Charset.forName("EUC-KR"),         // 韩文
            StandardCharsets.UTF_16LE,         // Unicode 小端序（无 BOM）
            StandardCharsets.UTF_16BE          // Unicode 大端序（无 BOM）
    );

    public static void main(String[] args) throws IOException {
        Path inputDir = Paths.get("G:\\图书\\txt");
        Path outputDir = Paths.get("G:\\图书\\txt1");

        if (!Files.isDirectory(inputDir)) {
            System.err.println("输入目录不存在或不是一个目录: " + inputDir);
            return;
        }

        Files.createDirectories(outputDir);

        System.out.println("输入目录: " + inputDir.toAbsolutePath());
        System.out.println("输出目录: " + outputDir.toAbsolutePath());
        System.out.println("候选编码顺序: " + CANDIDATE_CHARSETS);
        System.out.println("开始处理...");

        Files.walkFileTree(inputDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".txt")) {
                    processFile(inputDir, outputDir, file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.println("无法访问: " + file);
                return FileVisitResult.CONTINUE;
            }
        });

        System.out.println("处理完成！");
    }

    private static void processFile(Path inputDir, Path outputDir, Path file) throws IOException {
        Path relativePath = inputDir.relativize(file);
        Path targetFile = outputDir.resolve(relativePath);
        Files.createDirectories(targetFile.getParent());

        byte[] rawBytes = Files.readAllBytes(file);

        // 1. 如果已是 UTF-8（含 BOM），直接复制
        if (isUtf8Compatible(rawBytes)) {
            Files.write(targetFile, rawBytes);
            System.out.println("直接复制(UTF-8): " + relativePath);
            return;
        }

        // 2. 遍历候选编码，尝试解码并验证
        for (Charset charset : CANDIDATE_CHARSETS) {
            try {
                String content = new String(rawBytes, charset);
                if (Arrays.equals(rawBytes, content.getBytes(charset))) {
                    // 验证通过，转为 UTF-8 写入
                    Files.write(targetFile, content.getBytes(StandardCharsets.UTF_8));
                    System.out.println("已转换(" + charset.name() + "→UTF-8): " + relativePath);
                    return;
                }
            } catch (CharacterCodingException e) {
                // 继续尝试下一个
            }
        }

        // 3. 所有候选编码都失败，复制原文件并加上 .error 后缀
        Path errorTarget = outputDir.resolve(relativePath.toString() + ".error");
        Files.createDirectories(errorTarget.getParent());
        Files.write(errorTarget, rawBytes);
        System.err.println("未识别编码，复制为 .error 文件: " + relativePath);
    }

    private static boolean isUtf8Compatible(byte[] bytes) {
        try {
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            byte[] reEncoded = decoded.getBytes(StandardCharsets.UTF_8);
            // 处理带 BOM 的情况
            if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
                byte[] noBom = new byte[bytes.length - 3];
                System.arraycopy(bytes, 3, noBom, 0, noBom.length);
                return Arrays.equals(noBom, reEncoded);
            }
            return Arrays.equals(bytes, reEncoded);
        } catch (Exception e) {
            return false;
        }
    }
}

