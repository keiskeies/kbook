package com.kbook.test;

import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookScanService;
import com.kbook.service.book.BookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@SpringBootTest
@ActiveProfiles("test")
public class EpubImportTool {

    private static final String SOURCE_DIR = "G:\\图书\\new_epub";

    @Autowired
    private BookStorageProperties storageProps;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookScanService bookScanService;

    @Autowired
    private BookService bookService;

    @Test
    public void importMissingEpubs() {
        Path sourceDir = Paths.get(SOURCE_DIR);
        Path targetDir = Paths.get(storageProps.getBookPaths().getEpub());

        if (!Files.isDirectory(sourceDir)) {
            System.out.println("源目录不存在: " + SOURCE_DIR);
            return;
        }

        List<Path> sourceEpubs = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            paths.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.toString().toLowerCase().endsWith(".epub"))
                    .forEach(sourceEpubs::add);
        } catch (IOException e) {
            System.out.println("扫描源目录失败: " + e.getMessage());
            return;
        }

        Set<String> existingFileNames = new java.util.HashSet<>();
        try (Stream<Path> paths = Files.walk(targetDir, 1)) {
            paths.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.toString().toLowerCase().endsWith(".epub"))
                    .forEach(p -> existingFileNames.add(p.getFileName().toString().toLowerCase()));
        } catch (IOException e) {
            System.out.println("扫描目标目录失败: " + e.getMessage());
            return;
        }

        List<Book> existingBooks = bookRepository.findByFormat("EPUB");
        Set<String> existingTitles = new java.util.HashSet<>();
        for (Book b : existingBooks) {
            if (b.getTitle() != null) existingTitles.add(b.getTitle().trim().toLowerCase());
        }

        List<Path> missingEpubs = new ArrayList<>();
        for (Path epubFile : sourceEpubs) {
            String fileName = epubFile.getFileName().toString();
            if (existingFileNames.contains(fileName.toLowerCase())) continue;

            int dotIndex = fileName.lastIndexOf('.');
            String title = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
            if (existingTitles.contains(title.trim().toLowerCase())) continue;

            missingEpubs.add(epubFile);
        }

        System.out.println("========================================");
        System.out.println("EPUB 导入工具");
        System.out.println("========================================");
        System.out.println("源目录:       " + sourceDir.toAbsolutePath());
        System.out.println("目标目录:     " + targetDir.toAbsolutePath());
        System.out.println("源目录 EPUB:  " + sourceEpubs.size() + " 本");
        System.out.println("目标目录 EPUB: " + existingFileNames.size() + " 本");
        System.out.println("数据库 EPUB:   " + existingBooks.size() + " 本");
        System.out.println("待导入:        " + missingEpubs.size() + " 本");
        System.out.println("----------------------------------------");

        if (missingEpubs.isEmpty()) {
            System.out.println("没有新的 EPUB 需要导入。");
            return;
        }

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            System.out.println("创建目标目录失败: " + e.getMessage());
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        long totalStartTime = System.currentTimeMillis();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(missingEpubs.size());

        Thread progressThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int done = completedCount.get();
                int ok = successCount.get();
                int fail = failCount.get();
                long elapsedMs = System.currentTimeMillis() - totalStartTime;
                double elapsedMin = elapsedMs / 60000.0;
                int processed = ok + fail;
                double avgMs = processed > 0 ? (double) elapsedMs / processed : 0;
                int remaining = missingEpubs.size() - done;
                double estMin = remaining * avgMs / 60000.0;
                java.time.LocalDateTime finishTime = java.time.LocalDateTime.now().plusSeconds((long)(estMin * 60));
                System.out.printf("[进度] %d/%d (%.1f%%) | 成功=%d 失败=%d | 已用=%.1f分钟 | 预计剩余=%.1f分钟 | 预计完成=%s%n",
                        done, missingEpubs.size(), done * 100.0 / missingEpubs.size(),
                        ok, fail, elapsedMin, estMin,
                        finishTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        for (int i = 0; i < missingEpubs.size(); i++) {
            final int index = i;
            final Path sourceFile = missingEpubs.get(i);
            final String fileName = sourceFile.getFileName().toString();
            final int dotIndex = fileName.lastIndexOf('.');
            final String title = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;

            executor.submit(() -> {
                try {
                    Path targetFile = targetDir.resolve(fileName);
                    Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

                    Book book = bookScanService.processBookFile(targetFile, "EPUB", title);
                    book.setContentEmbedded(false);
                    bookService.updateBook(book.getId(), book);

                    successCount.incrementAndGet();
                    System.out.printf("  ✓ [%d] %s → id=%d %s%n", index + 1, fileName, book.getId(), book.getTitle());
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.printf("  ✗ [%d] %s — %s%n", index + 1, fileName, e.getMessage());
                } finally {
                    completedCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        progressThread.interrupt();

        long totalElapsedMs = System.currentTimeMillis() - totalStartTime;
        double totalElapsedMin = totalElapsedMs / 60000.0;
        int processed = successCount.get() + failCount.get();
        double avgMs = processed > 0 ? (double) totalElapsedMs / processed : 0;

        System.out.println("----------------------------------------");
        System.out.printf("导入完成: 成功=%d, 失败=%d%n", successCount.get(), failCount.get());
        System.out.printf("总耗时: %.2f分钟%n", totalElapsedMin);
        System.out.printf("平均每本: %.2f秒%n", avgMs / 1000.0);
        System.out.println("数据库 EPUB 图书总数: " + bookRepository.findByFormat("EPUB").size());
        System.out.println("========================================");
    }
}
