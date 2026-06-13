<#-- ================================================
 账号解封通知模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<div style="text-align:center;padding:8px 0 16px;">
    <span style="display:inline-block;width:64px;height:64px;line-height:64px;background:linear-gradient(135deg,#6B8FA8 0%,#7BA8C4 100%);border-radius:50%;font-size:32px;">🔓</span>
</div>

<h2 style="color:#1f2937;margin:0 0 8px;font-size:20px;font-weight:600;text-align:center;">欢迎回来！</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.8;text-align:center;">
    <strong style="color:#374151;">${userName}</strong>，好久不见<br>
    你的 KBook 账号已经恢复正常，可以继续使用了。
</p>

<div style="background:linear-gradient(135deg,#f0f7f9 0%,#dceef5 100%);border-radius:12px;padding:20px;text-align:center;margin-bottom:20px;border:1px solid #b8d4e3;">
    <p style="color:#4A6A7A;margin:0 0 4px;font-size:14px;font-weight:600;">📖 一切都在，没有丢失</p>
    <p style="color:#6B8FA8;margin:0;font-size:12px;line-height:1.7;">你的书架、笔记和阅读进度都好好保存着</p>
</div>

<a href="${baseUrl}/login" style="display:block;background:linear-gradient(135deg,#6B8FA8 0%,#7BA8C4 100%);color:#ffffff;padding:14px 0;border-radius:10px;text-align:center;text-decoration:none;font-size:15px;font-weight:600;box-shadow:0 2px 8px rgba(107,143,168,0.3);">回去看看 →</a>

</@layout.emailLayout>
