package com.kbook.test;

import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootTest
@ActiveProfiles("dev")
public class EpubDirSyncTool {

    private static final String EPUB_TEMP_DIR = "G:\\图书\\epubTemp";

    @Autowired
    private BookStorageProperties storageProps;

    @Autowired
    private BookRepository bookRepository;

    @Test
    public void syncEpubDirectory() {
        Path epubDir = Paths.get(storageProps.getBookPaths().getEpub());
        Path epubTempDir = Paths.get(EPUB_TEMP_DIR);

        if (!Files.isDirectory(epubDir)) {
            System.out.println("EPUB目录不存在: " + epubDir);
            return;
        }

        // 1. Get all EPUB books from DB
        List<Book> dbEpubBooks = bookRepository.findByFormat("EPUB");
        Set<String> dbFileUrls = dbEpubBooks.stream()
                .map(Book::getFileUrl)
                .filter(Objects::nonNull)
                .map(url -> Paths.get(url).normalize().toAbsolutePath().toString())
                .collect(Collectors.toSet());

        // 2. Scan all .epub files on disk
        List<Path> diskEpubFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(epubDir, 1)) {
            paths.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.toString().toLowerCase().endsWith(".epub"))
                    .forEach(diskEpubFiles::add);
        } catch (IOException e) {
            System.out.println("扫描EPUB目录失败: " + e.getMessage());
            return;
        }

        System.out.println("========================================");
        System.out.println("EPUB 目录同步工具");
        System.out.println("========================================");
        System.out.println("EPUB目录:     " + epubDir);
        System.out.println("临时目录:     " + epubTempDir);
        System.out.println("数据库EPUB:   " + dbEpubBooks.size() + " 本");
        System.out.println("磁盘EPUB文件: " + diskEpubFiles.size() + " 个");

        // 3. Books in DB whose files are missing on disk
        System.out.println("\n【一】数据库中存在但文件缺失的图书");
        System.out.println("----------------------------------------");
        int missingCount = 0;
        for (Book book : dbEpubBooks) {
            if (book.getFileUrl() == null) {
                System.out.printf("  ✗ id=%d 《%s》 fileUrl为null%n", book.getId(), book.getTitle());
                missingCount++;
                continue;
            }
            Path filePath = Paths.get(book.getFileUrl()).normalize();
            if (!Files.exists(filePath)) {
                System.out.printf("  ✗ id=%d 《%s》 缺失: %s%n", book.getId(), book.getTitle(), filePath);
                missingCount++;
            }
        }
        if (missingCount == 0) System.out.println("  (无缺失)");
        System.out.println("总计: " + missingCount + " 本");

        // 4. Extra files on disk not in DB
        System.out.println("\n【二】磁盘上多余的文件（将移至 epubTemp）");
        System.out.println("----------------------------------------");
        List<Path> extraFiles = new ArrayList<>();
        for (Path file : diskEpubFiles) {
            String absPath = file.normalize().toAbsolutePath().toString();
            if (!dbFileUrls.contains(absPath)) {
                extraFiles.add(file);
                System.out.printf("  ! %s%n", file.getFileName());
            }
        }
        if (extraFiles.isEmpty()) System.out.println("  (无多余文件)");
        System.out.println("总计: " + extraFiles.size() + " 个");

        // 5. Move extra files to epubTemp
        if (!extraFiles.isEmpty()) {
            try {
                Files.createDirectories(epubTempDir);
            } catch (IOException e) {
                System.out.println("创建epubTemp目录失败: " + e.getMessage());
                return;
            }

            System.out.println("\n开始移动多余文件到 " + epubTempDir);
            int moved = 0, failed = 0;
            for (Path file : extraFiles) {
                try {
                    Path target = epubTempDir.resolve(file.getFileName());
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.printf("  ✓ %s%n", file.getFileName());
                    moved++;
                } catch (IOException e) {
                    System.err.printf("  ✗ %s — %s%n", file.getFileName(), e.getMessage());
                    failed++;
                }
            }
            System.out.printf("移动完成: 成功=%d, 失败=%d%n", moved, failed);
        }

        System.out.println("\n========================================");
        System.out.println("同步完成");
        System.out.println("========================================");
    }
}
