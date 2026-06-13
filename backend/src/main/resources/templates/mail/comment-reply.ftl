<#-- ================================================
 评论回复通知模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<h2 style="color:#1f2937;margin:0 0 12px;font-size:20px;font-weight:600;">💬 有人回复了你的想法</h2>
<p style="color:#6b7280;margin:0 0 16px;font-size:14px;line-height:1.7;">
    <strong style="color:#374151;">${userName}</strong> 对你的书评写下了回复
</p>

<div style="background:#f9fafb;border-left:4px solid #5B8C5A;border-radius:0 8px 8px 0;padding:16px;margin-bottom:12px;">
    <p style="color:#6b7280;margin:0 0 6px;font-size:12px;">《${bookTitle}》</p>
    <p style="color:#374151;margin:0;font-size:14px;line-height:1.7;">${content}</p>
</div>

<a href="${baseUrl}/comment" style="display:inline-block;background:#f3f4f6;color:#374151;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:14px;font-weight:500;">去看看她说了什么 →</a>

</@layout.emailLayout>
