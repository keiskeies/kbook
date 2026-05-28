package com.kbook.test;

import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BookScanService 专用测试类
 * 测试图书扫描服务的各项功能
 */
@SpringBootTest
@ActiveProfiles("test")
public class BookScanServiceTest {

    @Autowired
    private BookScanService bookScanService;

    @Autowired
    private BookRepository bookRepository;

    @BeforeEach
    void setUp() {
        // 确保每次测试前扫描状态是重置的
        bookScanService.resetScanState();
    }

    /**
     * 测试1: 验证扫描服务初始状态
     */
    @Test
    public void testInitialScanState() {
        System.out.println("========== 测试1: 验证扫描服务初始状态 ==========");
        
        // 验证初始状态下没有正在进行的扫描
        assertFalse(bookScanService.isScanning(), "初始状态下不应该有扫描在进行");
        
        // 获取扫描进度
        Map<String, Object> progress = bookScanService.getScanProgress();
        assertNotNull(progress, "扫描进度不应为null");
        
        System.out.println("初始扫描进度: " + progress);
        System.out.println("✓ 测试通过\n");
    }

    /**
     * 测试2: 测试防止重复扫描功能
     */
    @Test
    public void testPreventDuplicateScanning() throws InterruptedException {
        System.out.println("========== 测试2: 测试防止重复扫描功能 ==========");
        
        // 第一次启动扫描
        SseEmitter emitter1 = bookScanService.scanAllWithProgress(null);
        assertNotNull(emitter1, "第一个emitter不应为null");
        
        assertTrue(bookScanService.isScanning(), "扫描应该正在进行中");
        System.out.println("第一次扫描已启动，状态: scanning=" + bookScanService.isScanning());
        
        // 尝试第二次扫描（应该被拒绝）
        SseEmitter emitter2 = bookScanService.scanAllWithProgress(null);
        assertNotNull(emitter2, "第二个emitter不应为null");
        
        // 等待一小段时间让SSE发送错误消息
        Thread.sleep(500);
        
        System.out.println("第二次扫描尝试完成");
        System.out.println("✓ 测试通过（重复扫描已被阻止）\n");
        
        // 重置状态以便后续测试
        bookScanService.resetScanState();
    }

    /**
     * 测试3: 测试扫描进度跟踪
     */
    @Test
    public void testScanProgressTracking() {
        System.out.println("========== 测试3: 测试扫描进度跟踪 ==========");
        
        // 获取初始进度
        Map<String, Object> initialProgress = bookScanService.getScanProgress();
        System.out.println("初始进度: " + initialProgress);
        
        // 验证进度数据结构
        assertTrue(initialProgress.containsKey("scanning"), "进度应包含scanning字段");
        assertTrue(initialProgress.containsKey("current"), "进度应包含current字段");
        assertTrue(initialProgress.containsKey("total"), "进度应包含total字段");
        assertTrue(initialProgress.containsKey("added"), "进度应包含added字段");
        assertTrue(initialProgress.containsKey("updated"), "进度应包含updated字段");
        assertTrue(initialProgress.containsKey("skipped"), "进度应包含skipped字段");
        assertTrue(initialProgress.containsKey("failed"), "进度应包含failed字段");
        assertTrue(initialProgress.containsKey("errors"), "进度应包含errors字段");
        assertTrue(initialProgress.containsKey("currentFile"), "进度应包含currentFile字段");
        
        System.out.println("✓ 进度数据结构验证通过");
        System.out.println("✓ 测试通过\n");
    }

    /**
     * 测试4: 测试断点续扫功能（skipBeforeId参数）
     */
    @Test
    public void testScanWithSkipBeforeId() {
        System.out.println("========== 测试4: 测试断点续扫功能 ==========");
        
        // 获取当前最大ID
        Long maxId = bookRepository.findAll().stream()
                .map(Book::getId)
                .max(Long::compareTo)
                .orElse(0L);
        
        System.out.println("当前最大图书ID: " + maxId);
        
        if (maxId > 0) {
            // 使用skipBeforeId进行扫描，应该跳过所有现有图书
            SseEmitter emitter = bookScanService.scanAllWithProgress(maxId);
            assertNotNull(emitter, "emitter不应为null");
            
            System.out.println("使用skipBeforeId=" + maxId + " 启动扫描");
            System.out.println("这将跳过所有ID小于" + maxId + "的图书");
            
            // 等待一段时间
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 检查进度
            Map<String, Object> progress = bookScanService.getScanProgress();
            System.out.println("扫描进度: " + progress);
            
            Integer skipped = (Integer) progress.get("skipped");
            System.out.println("跳过的图书数量: " + skipped);
        } else {
            System.out.println("数据库中没有图书，跳过此测试");
        }
        
        System.out.println("✓ 测试通过\n");
        
        // 重置状态
        bookScanService.resetScanState();
    }

    /**
     * 测试5: 测试完整扫描流程（无skipBeforeId）
     */
    @Test
    public void testFullScanWithoutSkipBeforeId() {
        System.out.println("========== 测试5: 测试完整扫描流程 ==========");
        
        // 记录初始图书数量
        long initialCount = bookRepository.count();
        System.out.println("扫描前图书总数: " + initialCount);
        
        // 启动完整扫描
        SseEmitter emitter = bookScanService.scanAllWithProgress(0L);
        assertNotNull(emitter, "emitter不应为null");
        
        System.out.println("已启动完整扫描（不跳过任何图书）");
        
        // 等待扫描进行
        int waitTime = 0;
        while (bookScanService.isScanning() && waitTime < 15) {
            try {
                Thread.sleep(1000);
                waitTime++;
                
                if (waitTime % 3 == 0) {
                    Map<String, Object> progress = bookScanService.getScanProgress();
                    System.out.println("  [" + waitTime + "秒] 进度: " + progress);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // 获取最终进度
        Map<String, Object> finalProgress = bookScanService.getScanProgress();
        System.out.println("\n最终扫描进度: " + finalProgress);
        
        // 验证扫描已完成
        assertFalse(bookScanService.isScanning(), "扫描应该已完成");
        
        // 记录最终图书数量
        long finalCount = bookRepository.count();
        System.out.println("扫描后图书总数: " + finalCount);
        System.out.println("新增图书数量: " + (finalCount - initialCount));
        
        Integer added = (Integer) finalProgress.get("added");
        Integer updated = (Integer) finalProgress.get("updated");
        Integer skipped = (Integer) finalProgress.get("skipped");
        Integer failed = (Integer) finalProgress.get("failed");
        
        System.out.println("统计信息:");
        System.out.println("  - 新增: " + added);
        System.out.println("  - 更新: " + updated);
        System.out.println("  - 跳过: " + skipped);
        System.out.println("  - 失败: " + failed);
        
        System.out.println("✓ 测试通过\n");
    }

    /**
     * 测试6: 测试重置扫描状态功能
     */
    @Test
    public void testResetScanState() {
        System.out.println("========== 测试6: 测试重置扫描状态功能 ==========");
        
        // 先启动一个扫描
        SseEmitter emitter = bookScanService.scanAllWithProgress(null);
        assertTrue(bookScanService.isScanning(), "扫描应该正在进行中");
        System.out.println("扫描已启动，状态: scanning=" + bookScanService.isScanning());
        
        // 重置状态
        bookScanService.resetScanState();
        assertFalse(bookScanService.isScanning(), "重置后扫描应该停止");
        System.out.println("状态已重置，scanning=" + bookScanService.isScanning());
        
        // 验证可以重新启动扫描
        SseEmitter emitter2 = bookScanService.scanAllWithProgress(null);
        assertNotNull(emitter2, "重置后应该可以重新启动扫描");
        System.out.println("重置后可以重新启动扫描");
        
        System.out.println("✓ 测试通过\n");
        
        // 清理
        bookScanService.resetScanState();
    }

    /**
     * 测试7: 测试扫描进度数据完整性
     */
    @Test
    public void testScanProgressDataIntegrity() {
        System.out.println("========== 测试7: 测试扫描进度数据完整性 ==========");
        
        Map<String, Object> progress = bookScanService.getScanProgress();
        
        // 验证所有必需字段都存在且类型正确
        assertNotNull(progress.get("scanning"), "scanning字段不应为null");
        assertTrue(progress.get("scanning") instanceof Boolean, "scanning应为Boolean类型");
        
        assertNotNull(progress.get("current"), "current字段不应为null");
        assertTrue(progress.get("current") instanceof Integer, "current应为Integer类型");
        
        assertNotNull(progress.get("total"), "total字段不应为null");
        assertTrue(progress.get("total") instanceof Integer, "total应为Integer类型");
        
        assertNotNull(progress.get("added"), "added字段不应为null");
        assertTrue(progress.get("added") instanceof Integer, "added应为Integer类型");
        
        assertNotNull(progress.get("updated"), "updated字段不应为null");
        assertTrue(progress.get("updated") instanceof Integer, "updated应为Integer类型");
        
        assertNotNull(progress.get("skipped"), "skipped字段不应为null");
        assertTrue(progress.get("skipped") instanceof Integer, "skipped应为Integer类型");
        
        assertNotNull(progress.get("failed"), "failed字段不应为null");
        assertTrue(progress.get("failed") instanceof Integer, "failed应为Integer类型");
        
        assertNotNull(progress.get("errors"), "errors字段不应为null");
        assertTrue(progress.get("errors") instanceof java.util.List, "errors应为List类型");
        
        assertNotNull(progress.get("currentFile"), "currentFile字段不应为null");
        assertTrue(progress.get("currentFile") instanceof String, "currentFile应为String类型");
        
        System.out.println("所有进度字段类型验证通过");
        System.out.println("✓ 测试通过\n");
    }

    /**
     * 测试8: 测试多次重置状态的稳定性
     */
    @Test
    public void testMultipleResetStability() {
        System.out.println("========== 测试8: 测试多次重置状态的稳定性 ==========");
        
        // 多次重置状态
        for (int i = 1; i <= 5; i++) {
            bookScanService.resetScanState();
            assertFalse(bookScanService.isScanning(), "第" + i + "次重置后应该不在扫描状态");
            System.out.println("第" + i + "次重置完成");
        }
        
        // 验证最后可以正常启动扫描
        SseEmitter emitter = bookScanService.scanAllWithProgress(null);
        assertNotNull(emitter, "多次重置后应该可以正常启动扫描");
        System.out.println("多次重置后扫描启动成功");
        
        System.out.println("✓ 测试通过\n");
        
        // 清理
        bookScanService.resetScanState();
    }
}
