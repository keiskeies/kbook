<#-- ================================================
 书评里程碑达标模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<h2 style="color:#1f2937;margin:0 0 12px;font-size:20px;font-weight:600;">🎉 你的分享影响了好多人</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.7;">
    真为你开心！你的书评 <strong style="color:#5B8C5A;">${thresholdType}已经达到 ${thresholdValue}</strong>，越来越多的读者因为你的文字而受益。
</p>

<div style="background:linear-gradient(135deg,#fef3c7 0%,#fde68a 100%);border-radius:12px;padding:24px;text-align:center;margin-bottom:20px;border:1px solid #fcd34d;">
    <p style="color:#92400e;margin:0;font-size:36px;font-weight:700;">🏆</p>
    <p style="color:#a16207;margin:8px 0 0;font-size:16px;font-weight:600;">每一个认真写下的字，都在照亮另一个读者 ✨</p>
</div>

<div style="background:#f9fafb;border-left:4px solid #f59e0b;border-radius:0 8px 8px 0;padding:14px;margin-bottom:20px;">
    <p style="color:#6b7280;margin:0 0 6px;font-size:12px;">《${bookTitle}》</p>
    <p style="color:#374151;margin:0;font-size:14px;line-height:1.6;">${content}</p>
</div>

<a href="${baseUrl}/comment" style="display:inline-block;background:linear-gradient(135deg,#f59e0b 0%,#d97706 100%);color:#ffffff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;">${actionText}</a>

</@layout.emailLayout>
