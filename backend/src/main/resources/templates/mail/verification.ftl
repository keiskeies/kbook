<#-- ================================================
 验证码邮件模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<h2 style="color:#1f2937;margin:0 0 12px;font-size:20px;font-weight:600;">${sceneIcon} 你好，这是你的验证码</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.7;">
    你正在 KBook 进行<strong style="color:#374151;">${sceneName}</strong>，输入下方 6 位验证码就能继续啦：
</p>

<#-- ====== 6 个独立数字方块（视觉展示） ====== -->
<div style="text-align:center;padding:28px 8px 8px;">
    <#list 0..code?length-1 as i>
    <span style="display:inline-block;width:46px;height:56px;line-height:56px;margin:0 4px;background:linear-gradient(135deg,#5B8C5A 0%,#7DBA7C 100%);color:#fff;font-size:28px;font-weight:700;border-radius:12px;text-align:center;font-family:'SF Mono','Consolas','Courier New',monospace;box-shadow:0 2px 8px rgba(91,140,90,0.25);">${code[i]}</span>
    </#list>
</div>

<#-- ====== 一键复制区 ====== -->
<div style="text-align:center;margin-bottom:6px;">
    <span style="color:#9ca3af;font-size:12px;">👆 长按或选中下方数字即可复制</span>
</div>
<div style="text-align:center;margin-bottom:20px;background:#f9fafb;border:1px dashed #d1d5db;border-radius:8px;padding:10px 16px;display:inline-block;width:100%;box-sizing:border-box;">
    <span style="font-size:20px;font-weight:700;letter-spacing:6px;color:#374151;font-family:'SF Mono','Consolas','Courier New',monospace;user-select:all;-webkit-user-select:all;">${code}</span>
</div>

<#-- ====== 有效期提示 ====== -->
<div style="text-align:center;margin-bottom:20px;">
    <span style="display:inline-block;background:#f0f7ef;color:#4A7249;padding:6px 16px;border-radius:20px;font-size:13px;">
        ⏱ 验证码 <strong>${expireMinutes} 分钟</strong> 内有效
    </span>
</div>

<#-- ====== 安全提示 ====== -->
<div style="background:#fef9f0;border-radius:10px;padding:14px 16px;border:1px solid #fde6c0;">
    <p style="color:#92400e;margin:0;font-size:12px;line-height:1.8;">
        <strong>🔐 安全小提示</strong><br>
        • 验证码就像家门钥匙，请不要告诉任何人<br>
        • 如果不是你本人操作，直接忽略就好<br>
        • KBook 绝不会主动向你索要验证码
    </p>
</div>

</@layout.emailLayout>
