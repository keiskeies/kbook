<#-- ================================================
 评论点赞通知模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<h2 style="color:#1f2937;margin:0 0 12px;font-size:20px;font-weight:600;">❤️ 你的分享获得了认可</h2>
<p style="color:#6b7280;margin:0 0 16px;font-size:14px;line-height:1.7;">
    <strong style="color:#374151;">${userName}</strong> 等读者觉得你的书评写出了他们的心声
</p>

<div style="background:#fef2f2;border-radius:10px;padding:16px;text-align:center;margin-bottom:16px;">
    <p style="color:#ef4444;margin:0;font-size:32px;font-weight:700;">${count}</p>
    <p style="color:#dc2626;margin:4px 0 0;font-size:13px;">位读者为你点赞</p>
</div>

<div style="background:#f9fafb;border-left:4px solid #ef4444;border-radius:0 8px 8px 0;padding:14px;margin-bottom:20px;">
    <p style="color:#6b7280;margin:0 0 6px;font-size:12px;">《${bookTitle}》</p>
    <p style="color:#374151;margin:0;font-size:14px;line-height:1.6;">${content}</p>
</div>

<a href="${baseUrl}/comment" style="display:inline-block;background:linear-gradient(135deg,#f43f5e 0%,#e11d48 100%);color:#ffffff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;">${actionText}</a>

</@layout.emailLayout>
