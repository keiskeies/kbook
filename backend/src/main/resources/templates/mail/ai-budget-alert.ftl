<#-- ================================================
 AI 成本预算告警
 数据：adminName, monthCost, budget, usedPercent, overBudget, baseUrl, sendTime
================================================ -->
<#import "mail-layout.ftl" as layout>
<@layout.emailLayout>

<div style="text-align:center;padding:8px 0 16px;">
    <span style="display:inline-block;width:64px;height:64px;line-height:64px;background:linear-gradient(135deg,#C75B5B 0%,#E08A8A 100%);border-radius:50%;font-size:32px;">⚠️</span>
</div>

<h2 style="color:#1f2937;margin:0 0 8px;font-size:20px;font-weight:600;text-align:center;">AI 成本预算告警</h2>
<p style="color:#6b7280;margin:0 0 20px;font-size:14px;line-height:1.8;text-align:center;">
    ${adminName}，本月 AI 调用费用已达到预算阈值的 <strong style="color:#C75B5B;">${usedPercent}%</strong>。
</p>

<div style="background:#fef2f2;border:1px solid #fecaca;border-radius:12px;padding:20px;margin-bottom:20px;">
    <table style="width:100%;border-collapse:collapse;font-size:14px;color:#374151;">
        <tr>
            <td style="padding:6px 0;color:#6b7280;">本月已用</td>
            <td style="padding:6px 0;text-align:right;font-weight:600;color:#C75B5B;">¥${monthCost}</td>
        </tr>
        <tr>
            <td style="padding:6px 0;color:#6b7280;">月度预算</td>
            <td style="padding:6px 0;text-align:right;font-weight:600;">¥${budget}</td>
        </tr>
        <tr>
            <td style="padding:6px 0;color:#6b7280;">状态</td>
            <td style="padding:6px 0;text-align:right;font-weight:600;color:#C75B5B;">
                <#if overBudget>已超出预算<#else>接近预算上限</#if>
            </td>
        </tr>
    </table>
</div>

<p style="color:#6b7280;margin:0 0 20px;font-size:13px;line-height:1.8;text-align:center;">
    建议检查「数据看板 → 成本监控」中的场景/模型明细，必要时调整模型单价、预算或调用策略。
</p>

<a href="${baseUrl}/admin/dashboard" style="display:block;background:linear-gradient(135deg,#5B8C5A 0%,#7DBA7C 100%);color:#ffffff;padding:14px 0;border-radius:10px;text-align:center;text-decoration:none;font-size:15px;font-weight:600;box-shadow:0 2px 8px rgba(91,140,90,0.3);">查看成本看板 →</a>

</@layout.emailLayout>
