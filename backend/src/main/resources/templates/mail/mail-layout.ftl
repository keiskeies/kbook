<#-- ================================================
 KBook 邮件公共布局模板
================================================ -->
<#macro emailLayout>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="margin:0;padding:0;font-family:'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;background-color:#f0f2f5;">
    <div style="max-width:520px;margin:40px auto;background:#ffffff;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,0.08);overflow:hidden;">

        <#-- ====== 品牌头部 ====== -->
        <div style="background:linear-gradient(135deg,#5B8C5A 0%,#7DBA7C 100%);padding:28px 24px;text-align:center;">
            <h1 style="color:#ffffff;margin:0;font-size:26px;font-weight:600;letter-spacing:3px;">KBook</h1>
            <p style="color:rgba(255,255,255,0.85);margin:6px 0 0;font-size:13px;">让每一本书都与你有关</p>
        </div>

        <#-- ====== 内容区域 ====== -->
        <div style="padding:28px 24px;">
            <#nested>
        </div>

        <#-- ====== 底部信息 ====== -->
        <div style="border-top:1px solid #e5e7eb;padding:20px 24px;background:#f9fafb;">
            <p style="color:#9ca3af;margin:0;font-size:12px;text-align:center;line-height:1.8;">
                这封邮件由 KBook 自动发出，无需回复<br>
                发送时间：${sendTime}<br>
                <a href="${baseUrl}" style="color:#5B8C5A;text-decoration:none;">前往 KBook 开始阅读 →</a>
            </p>
        </div>

    </div>
</body>
</html>
</#macro>
