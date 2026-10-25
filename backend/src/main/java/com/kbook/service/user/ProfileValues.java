package com.kbook.service.user;

import com.kbook.common.exception.BusinessException;
import com.kbook.service.admin.BookProfileDimension;

/**
 * 画像列的写入端枚举校验。
 *
 * 白名单只有一份，就是 {@link BookProfileDimension} 的维度表。抄第二份到 DTO 注解或常量里，
 * 迟早出现「注册页有某个选项、看板把它当脏值」这类静默不一致（实测 `gender=OTHER` 就是这样，
 * 见设计文档第 12 节与附录 F）。
 */
public final class ProfileValues {

    private ProfileValues() {
    }

    /**
     * @param rawValue 前端原样提交的值
     * @return 入库用的规范值；入参为 null 或空白时返回 null（该列留空/清除）
     * @throws BusinessException 值不在该维度的白名单里；报错文案用维度自身的 label，
     *                           与看板列名、AI 摘要用的是同一个字符串
     */
    public static String require(BookProfileDimension dim, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String canonical = dim.canonicalForWrite(rawValue);
        if (canonical == null) {
            throw new BusinessException(dim.getLabel() + "取值不合法");
        }
        return canonical;
    }
}
