package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户服务
 * <p>
 * 管理用户注册审核、状态变更（通过/拒绝/封禁/解封）、
 * 用户画像更新、头像上传和邮箱绑定。画像变更时异步触发推荐重算。
 */
@Slf4j
@Service
public class UserService {

    /** 用户数据仓库 */
    private final UserRepository userRepository;
    /** 文件存储配置 */
    private final BookStorageProperties storageProps;
    /** 推荐服务（@Lazy 避免循环依赖） */
    private final RecommendService recommendService;

    public UserService(UserRepository userRepository, BookStorageProperties storageProps,
                       @Lazy RecommendService recommendService) {
        this.userRepository = userRepository;
        this.storageProps = storageProps;
        this.recommendService = recommendService;
    }

    /**
     * 根据ID获取用户，不存在则抛出异常
     * @param id 用户ID
     * @return 用户实体
     */
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    /**
     * 分页查询待审核用户
     */
    public PageResult<User> getPendingUsers(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<User> pageData = userRepository.findByStatus("PENDING", pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 按状态筛选用户（支持多状态）
     * 按 id DESC 排序（自增主键，等价于按注册时间倒序但索引效率更高）
     */
    public PageResult<User> getUsersByStatus(List<String> statuses, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<User> pageData;
        if (statuses == null || statuses.isEmpty()) {
            pageData = userRepository.findAll(pageable);
        } else {
            pageData = userRepository.findByStatusIn(statuses, pageable);
        }
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 搜索用户（关键词 + 状态筛选）
     * 按 id DESC 排序（自增主键，索引效率更高）
     */
    public PageResult<User> searchUsers(String keyword, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<User> pageData = userRepository.searchUsers(keyword, status, pageable);
        return PageResult.of(pageData.getContent(), pageData.getTotalElements(), page, size);
    }

    /**
     * 审核统计
     */
    public Map<String, Long> getReviewStats() {
        Map<String, Long> stats = new HashMap<>();
        List<Object[]> groups = userRepository.countGroupByStatus();
        for (Object[] group : groups) {
            stats.put((String) group[0], (Long) group[1]);
        }
        // 确保每个状态都有值
        stats.putIfAbsent("PENDING", 0L);
        stats.putIfAbsent("APPROVED", 0L);
        stats.putIfAbsent("BANNED", 0L);
        stats.put("TOTAL", userRepository.count());
        return stats;
    }

    /**
     * 审核通过
     */
    @Transactional
    public void approveUser(Long userId) {
        User user = getUserById(userId);
        if (!"PENDING".equals(user.getStatus())) {
            throw new BusinessException("用户状态不是待审核");
        }
        user.setStatus("APPROVED");
        userRepository.save(user);
        log.info("用户审核通过: userId={}", userId);
    }

    /**
     * 批量审核通过
     */
    @Transactional
    public int batchApprove(List<Long> userIds) {
        List<User> users = userRepository.findAllById(userIds);
        int count = 0;
        for (User user : users) {
            if ("PENDING".equals(user.getStatus())) {
                user.setStatus("APPROVED");
                count++;
            }
        }
        userRepository.saveAll(users);
        log.info("批量审核通过: total={}, approved={}", users.size(), count);
        return count;
    }

    /**
     * 审核拒绝（封禁）
     */
    @Transactional
    public void rejectUser(Long userId) {
        User user = getUserById(userId);
        user.setStatus("BANNED");
        userRepository.save(user);
        log.info("用户审核拒绝: userId={}", userId);
    }

    /**
     * 批量拒绝
     */
    @Transactional
    public int batchReject(List<Long> userIds) {
        List<User> users = userRepository.findAllById(userIds);
        users.forEach(user -> user.setStatus("BANNED"));
        userRepository.saveAll(users);
        log.info("批量审核拒绝: count={}", users.size());
        return users.size();
    }

    /**
     * 解封用户
     */
    @Transactional
    public void unbanUser(Long userId) {
        User user = getUserById(userId);
        if (!"BANNED".equals(user.getStatus())) {
            throw new BusinessException("用户状态不是封禁");
        }
        user.setStatus("APPROVED");
        userRepository.save(user);
        log.info("用户解封: userId={}", userId);
    }

    /**
     * 封禁用户（封禁已通过的用户）
     */
    @Transactional
    public void banUser(Long userId) {
        User user = getUserById(userId);
        if (!"APPROVED".equals(user.getStatus())) {
            throw new BusinessException("只有已通过的用户才能被封禁");
        }
        user.setStatus("BANNED");
        userRepository.save(user);
        log.info("用户被封禁: userId={}", userId);
    }

    /**
     * 更新用户信息
     */
    @Transactional
    public User updateProfile(Long userId, String nickname, String avatar, String bio) {
        User user = getUserById(userId);
        if (nickname != null) user.setNickname(nickname);
        if (avatar != null) user.setAvatar(avatar);
        if (bio != null) user.setBio(bio);
        return userRepository.save(user);
    }

    /**
     * 更新用户画像（出生日期/性别/婚否/孩子年龄区间/MBTI/职业/期望学历/创业意向/期望年收入）
     */
    @Transactional
    public User updateTraits(Long userId, LocalDate birthday, String gender,
                             Boolean married, Boolean hasChildren, String childrenAgeRanges,
                             String mbti, String occupation,
                             String aspirationEducation, String entrepreneurship, String aspirationIncome) {
        User user = getUserById(userId);
        if (birthday != null) user.setBirthday(birthday);
        if (gender != null) user.setGender(gender);
        if (married != null) user.setMarried(married);
        if (hasChildren != null) user.setHasChildren(hasChildren);
        if (childrenAgeRanges != null) user.setChildrenAgeRanges(childrenAgeRanges);
        if (mbti != null) user.setMbti(mbti.toUpperCase());
        if (occupation != null) user.setOccupation(occupation);
        if (aspirationEducation != null) user.setAspirationEducation(aspirationEducation);
        if (entrepreneurship != null) user.setEntrepreneurship(entrepreneurship);
        if (aspirationIncome != null) user.setAspirationIncome(aspirationIncome);
        user = userRepository.save(user);
        recommendService.asyncRecompute(userId);
        return user;
    }

    /**
     * 更新当前心情状态
     */
    @Transactional
    public User updateMood(Long userId, String mood) {
        User user = getUserById(userId);
        user.setMood(mood != null && !mood.isBlank() ? mood : null);
        user = userRepository.save(user);
        recommendService.asyncRecompute(userId);
        return user;
    }

    /**
     * 上传头像
     * 裁剪后的图片在前端已转为 JPEG，后端统一缩放为 300x300 正方形
     */
    @Transactional
    public User uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择头像文件");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只支持上传图片文件");
        }

        String filename = UUID.randomUUID().toString().replace("-", "") + ".jpg";

        try {
            Path dirPath = Paths.get(storageProps.getUpload().getAvatarDir());
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path filePath = dirPath.resolve(filename);

            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new BusinessException("无法解析图片文件");
            }

            BufferedImage resized = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resized.createGraphics();
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, 300, 300);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.drawImage(image, 0, 0, 300, 300, null);
            g2d.dispose();

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.85f);
            try (FileImageOutputStream output = new FileImageOutputStream(filePath.toFile())) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(resized, null, null), params);
            } finally {
                writer.dispose();
            }

            String avatarUrl = storageProps.getUpload().getAvatarUrlPrefix() + "/" + filename;

            User user = getUserById(userId);
            user.setAvatar(avatarUrl);
            userRepository.save(user);

            log.info("头像上传成功: userId={}, avatarUrl={}", userId, avatarUrl);
            return user;
        } catch (IOException e) {
            log.error("头像上传失败: {}", e.getMessage());
            throw new BusinessException("头像上传失败，请稍后重试");
        }
    }

    /**
     * 管理员绑定邮箱（简化版，供 Controller 调用，验证码由 AuthService 校验）
     */
    @Transactional
    public User bindEmail(Long userId, String email) {
        User user = getUserById(userId);
        if (Boolean.TRUE.equals(user.getEmailBound())) {
            throw new BusinessException("邮箱已绑定");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱已被使用");
        }
        user.setEmail(email);
        user.setEmailBound(true);
        userRepository.save(user);
        log.info("管理员绑定邮箱: userId={}, email={}", userId, email);
        return user;
    }
}
