package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import com.kbook.config.properties.BookStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final BookStorageProperties storageProps;

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
     * 更新用户画像（出生日期/性别/婚否/孩子/MBTI/职业/学历/创业意向/年收入）
     */
    @Transactional
    public User updateTraits(Long userId, LocalDate birthday, String gender,
                              Boolean married, Boolean hasChildren, String mbti, String occupation,
                              String education, String entrepreneurship, String annualIncome) {
        User user = getUserById(userId);
        if (birthday != null) user.setBirthday(birthday);
        if (gender != null) user.setGender(gender);
        if (married != null) user.setMarried(married);
        if (hasChildren != null) user.setHasChildren(hasChildren);
        if (mbti != null) user.setMbti(mbti.toUpperCase());
        if (occupation != null) user.setOccupation(occupation);
        if (education != null) user.setEducation(education);
        if (entrepreneurship != null) user.setEntrepreneurship(entrepreneurship);
        if (annualIncome != null) user.setAnnualIncome(annualIncome);
        return userRepository.save(user);
    }

    /**
     * 更新当前心情状态
     */
    @Transactional
    public User updateMood(Long userId, String mood) {
        User user = getUserById(userId);
        user.setMood(mood != null && !mood.isBlank() ? mood : null);
        return userRepository.save(user);
    }

    /**
     * 上传头像
     * 保存文件到本地，将 URL 写入 User.avatar
     */
    @Transactional
    public User uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择头像文件");
        }

        // 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只支持上传图片文件");
        }

        // 校验文件大小（最大 2MB）
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new BusinessException("头像文件不能超过2MB");
        }

        // 生成文件名
        String originalFilename = file.getOriginalFilename();
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;

        // 保存文件
        try {
            Path dirPath = Paths.get(storageProps.getUpload().getAvatarDir());
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path filePath = dirPath.resolve(filename);
            file.transferTo(filePath.toFile());

            // 生成 URL
            String avatarUrl = storageProps.getUpload().getAvatarUrlPrefix() + "/" + filename;

            // 更新用户头像
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
