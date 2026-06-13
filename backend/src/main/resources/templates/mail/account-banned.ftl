<#-- ================================================
 账号封禁通知模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<div style="text-align:center;padding:8px 0 16px;">
    <span style="display:inline-block;width:64px;height:64px;line-height:64px;background:linear-gradient(135deg,#f59e0b 0%,#d97706 100%);border-radius:50%;font-size:32px;">⚠️</span>
</div>

<h2 style="color:#1f2937;margin:0 0 8px;font-size:20px;font-weight:600;text-align:center;">你的账号暂时无法访问</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.8;text-align:center;">
    <strong style="color:#374151;">${userName}</strong>，抱歉通知你<br>
    你的 KBook 账号因违反社区规范被暂时限制使用了。
</p>

<div style="background:linear-gradient(135deg,#fef9f0 0%,#fef3c7 100%);border-radius:12px;padding:20px;text-align:center;margin-bottom:20px;border:1px solid #fde6c0;">
    <p style="color:#92400e;margin:0 0 4px;font-size:14px;font-weight:600;">📋 如果你觉得是误会</p>
    <p style="color:#a16207;margin:0;font-size:12px;line-height:1.7;">可以通过平台内的反馈渠道联系我们说明情况<br>我们会认真复核每一个申诉</p>
</div>

<a href="${baseUrl}" style="display:block;background:#f3f4f6;color:#6b7280;padding:14px 0;border-radius:10px;text-align:center;text-decoration:none;font-size:15px;font-weight:600;">了解社区规范</a>

</@layout.emailLayout>
