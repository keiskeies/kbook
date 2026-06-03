import { ArrowLeft } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

export default function PrivacyPage() {
  const navigate = useNavigate()

  return (
    <div className="h-full overflow-y-auto overscroll-contain bg-background">
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-base font-bold">隐私政策</h1>
      </header>

      <div className="mx-auto max-w-2xl px-5 py-6 text-sm leading-relaxed text-muted-foreground space-y-6">
        <p className="text-xs text-muted-foreground/60">最后更新日期：2026年6月2日</p>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">一、概述</h2>
          <p>KBook（以下简称"我们"）非常重视用户隐私保护。本隐私政策旨在向您说明我们如何收集、使用、存储和保护您的个人信息。请您在使用我们的服务前，仔细阅读本隐私政策。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">二、信息收集</h2>
          <p>我们可能收集以下类型的信息：</p>
          <p className="font-medium text-foreground mt-2">2.1 您主动提供的信息</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>注册信息：邮箱地址、昵称、头像等；</li>
            <li>个人画像：性别、年龄、职业、MBTI、学历等（均为可选填写）；</li>
            <li>阅读偏好：您设置的偏好标签、排除标签等；</li>
            <li>互动内容：评论、书评、与 AI 的对话记录等。</li>
          </ul>
          <p className="font-medium text-foreground mt-2">2.2 自动收集的信息</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>阅读数据：阅读进度、阅读时长、阅读完成情况等；</li>
            <li>设备信息：设备型号、操作系统版本、浏览器类型等；</li>
            <li>日志信息：访问时间、页面浏览记录、操作日志等；</li>
            <li>位置信息：仅用于提供本地化服务，我们会进行模糊化处理。</li>
          </ul>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">三、信息使用</h2>
          <p>我们收集的信息将用于以下目的：</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>提供、维护和改进平台服务；</li>
            <li>个性化图书推荐和 AI 问答服务；</li>
            <li>同步您的阅读进度和书架数据；</li>
            <li>分析用户行为以优化产品体验；</li>
            <li>发送服务通知和系统公告；</li>
            <li>保障平台安全，防范欺诈和违规行为；</li>
            <li>遵守法律法规的要求。</li>
          </ul>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">四、信息存储与保护</h2>
          <p>4.1 您的个人信息存储在中华人民共和国境内的服务器上。如需跨境传输，我们将按照法律法规的要求进行。</p>
          <p>4.2 我们采用业界通行的安全技术措施保护您的个人信息，包括但不限于数据加密、访问控制、安全审计等。</p>
          <p>4.3 我们制定了个人信息安全事件应急预案，如发生安全事件，我们将及时通知您并采取补救措施。</p>
          <p>4.4 我们仅保留为实现服务目的所必需的最短期限内的个人信息。超出保留期限后，我们将删除或匿名化处理您的个人信息。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">五、信息共享</h2>
          <p>5.1 未经您的同意，我们不会向第三方共享您的个人信息，但以下情况除外：</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>事先获得您的明确授权同意；</li>
            <li>根据法律法规或政府主管部门的强制性要求；</li>
            <li>为维护平台及其他用户的合法权益；</li>
            <li>与关联公司共享：仅限于本声明所述目的；</li>
            <li>与授权合作伙伴共享：仅限于实现服务功能所必需（如 AI 服务提供商），且我们会与其签署数据保护协议。</li>
          </ul>
          <p>5.2 我们不会将您的个人信息出售给任何第三方。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">六、您的权利</h2>
          <p>您对您的个人信息享有以下权利：</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>访问权：您可以在个人中心查看您的个人信息；</li>
            <li>更正权：您可以在个人中心修改您的个人信息；</li>
            <li>删除权：您可以申请删除您的个人信息或注销账号；</li>
            <li>撤回同意权：您可以随时撤回此前给予的授权同意；</li>
            <li>数据可携带权：您可以申请导出您的个人数据。</li>
          </ul>
          <p>如您需要行使上述权利，请通过平台内功能或联系管理员进行操作。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">七、Cookie 和类似技术</h2>
          <p>7.1 我们使用 Cookie 和类似技术来存储您的登录状态、偏好设置等信息，以提供更好的服务体验。</p>
          <p>7.2 您可以通过浏览器设置管理或删除 Cookie。但请注意，禁用 Cookie 可能会影响您使用本平台的部分功能。</p>
          <p>7.3 我们使用本地存储（LocalStorage）保存您的阅读设置和离线进度数据。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">八、未成年人保护</h2>
          <p>8.1 我们非常重视对未成年人个人信息的保护。</p>
          <p>8.2 如您为未满 18 周岁的未成年人，请在监护人的陪同和指导下使用本平台服务，并在监护人明确同意后提供个人信息。</p>
          <p>8.3 如我们发现在未获得监护人同意的情况下收集了未成年人的个人信息，我们将尽快删除相关信息。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">九、隐私政策的变更</h2>
          <p>9.1 我们可能会适时修订本隐私政策。修订后的政策将在平台上公布。</p>
          <p>9.2 对于重大变更，我们将通过弹窗、公告等适当方式通知您。</p>
          <p>9.3 您在隐私政策变更后继续使用本平台服务，视为同意变更后的隐私政策。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">十、联系我们</h2>
          <p>如您对本隐私政策有任何疑问、意见或建议，请通过平台内反馈功能或发送邮件至 <a href="mailto:right_way@foxmail.com" className="text-primary hover:underline">right_way@foxmail.com</a> 与我们取得联系。我们将在合理期限内回复您的请求。</p>
        </section>
      </div>
    </div>
  )
}
