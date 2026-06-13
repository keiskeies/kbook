<#-- ================================================
 审核通过通知模板
 数据：userName, baseUrl, sendTime
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<#-- ====== 庆祝图标 ====== -->
<div style="text-align:center;padding:8px 0 16px;">
    <span style="display:inline-block;width:64px;height:64px;line-height:64px;background:linear-gradient(135deg,#5B8C5A 0%,#7DBA7C 100%);border-radius:50%;font-size:32px;">🎉</span>
</div>

<h2 style="color:#1f2937;margin:0 0 8px;font-size:20px;font-weight:600;text-align:center;">欢迎加入 KBook！</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.8;text-align:center;">
    <strong style="color:#374151;">${userName}</strong>，很高兴见到你！<br>
    你的账号已经通过审核，接下来让好书遇见你。
</p>

<#-- ====== 功能介绍 ====== -->
<div style="background:linear-gradient(135deg,#f0f7ef 0%,#d4e8d3 100%);border-radius:12px;padding:20px;text-align:center;margin-bottom:20px;border:1px solid #a8d1a7;">
    <p style="color:#4A7249;margin:0 0 4px;font-size:14px;font-weight:600;">📚 在 KBook 你可以</p>
    <p style="color:#5B8C5A;margin:0;font-size:12px;line-height:1.7;">用 AI 和书对话 · 找到真正适合你的书 · 和同好交流想法</p>
</div>

<#-- ====== CTA 按钮 ====== -->
<a href="${baseUrl}/login" style="display:block;background:linear-gradient(135deg,#5B8C5A 0%,#7DBA7C 100%);color:#ffffff;padding:14px 0;border-radius:10px;text-align:center;text-decoration:none;font-size:15px;font-weight:600;box-shadow:0 2px 8px rgba(91,140,90,0.3);">开始阅读之旅 →</a>

</@layout.emailLayout>
