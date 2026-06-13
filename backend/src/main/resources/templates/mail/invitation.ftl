<#-- ================================================
 邀请邮件模板
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<h2 style="color:#1f2937;margin:0 0 12px;font-size:20px;font-weight:600;">📚 有人想和你一起读书</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.7;">
    <strong style="color:#374151;">${inviterName}</strong> 觉得你会喜欢 <strong style="color:#5B8C5A;">《${bookTitle}》</strong>，想邀请你一起在 KBook 阅读和交流。
</p>

<#-- ====== 邀请链接 ====== -->
<div style="background:linear-gradient(135deg,#ecfdf5 0%,#d1fae5 100%);border-radius:12px;padding:20px;margin-bottom:16px;border:1px solid #a7f3d0;">
    <p style="color:#065f46;margin:0 0 12px;font-size:13px;">
        ⏰ 链接在 <strong>${expireHours} 小时</strong> 内有效，别让它过期啦
    </p>
    <p style="color:#047857;margin:0;font-size:14px;word-break:break-all;">${inviteLink}</p>
</div>

<#-- ====== CTA 按钮 ====== -->
<a href="${inviteLink}" style="display:inline-block;background:linear-gradient(135deg,#5B8C5A 0%,#7DBA7C 100%);color:#ffffff;padding:14px 32px;border-radius:8px;text-decoration:none;font-size:15px;font-weight:600;box-shadow:0 2px 8px rgba(91,140,90,0.3);">${actionText}</a>

</@layout.emailLayout>
