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

      <div className="mx-auto max-w-2xl px-5 py-6 text-body leading-relaxed text-muted-foreground space-y-6">
        <p className="text-xs text-muted-foreground/60">最后更新日期：2026年6月16日</p>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">一、概述</h2>
          <p>KBook（以下简称"我们"）非常重视用户隐私保护。本隐私政策旨在向您说明我们如何收集、使用、存储和保护您的个人信息。请您在使用我们的服务前，仔细阅读本隐私政策。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">二、信息收集</h2>
          <p>我们可能收集以下类型的信息：</p>
          <p className="font-medium text-foreground mt-2">2.1 您主动提供的信息</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>注册信息：邮箱地址、密码、昵称、头像等；</li>
            <li>个人画像：出生日期、性别、婚姻状况、子女情况、MBTI 人格类型、职业方向、学历、年收入、创业意向、阅读意图与心情等（部分为注册时必填，部分可在个人中心修改）；</li>
            <li>阅读偏好：您设置的偏好标签、排除标签等；</li>
            <li>互动内容：评论、书评、与 AI 的对话记录、辩论讨论内容等；</li>
            <li>联系信息：您的邮箱地址将用于接收验证码和服务通知。</li>
          </ul>
          <p className="font-medium text-foreground mt-2">2.2 自动收集的信息</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>阅读数据：阅读进度、阅读时长、阅读完成情况、书架状态等；</li>
            <li>日志信息：访问时间、页面浏览记录、操作日志等。</li>
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
            <li>与第三方 AI 服务提供商共享：当您使用 AI 问答、图书分析等功能时，您的提问内容可能会被发送至由平台管理员配置的第三方 AI 服务提供商（如 OpenAI、DeepSeek、Ollama 等）进行处理。我们已与相关服务提供商签署数据保护协议，确保您的数据仅用于处理您的请求，不会被用于训练或改善第三方模型；</li>
            <li>与语音合成（TTS）服务提供商共享：当您使用语音朗读功能时，相关文本内容可能会发送至第三方 TTS 引擎（如小米、讯飞等）进行处理。</li>
          </ul>
          <p>5.2 我们不会将您的个人信息出售给任何第三方。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">六、您的权利</h2>
          <p>您对您的个人信息享有以下权利：</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>访问权：您可以在个人中心查看您的个人信息；</li>
            <li>更正权：您可以在个人中心修改您的个人信息；</li>
            <li>删除权：您可以联系管理员申请删除您的个人信息或注销账号。我们将在合理期限内处理您的请求；</li>
            <li>撤回同意权：您可以随时在个人中心关闭部分信息收集功能，或联系管理员撤销已提供的授权。</li>
          </ul>
          <p>如您需要行使上述权利，请通过平台内设置功能或发送邮件至 <a href="mailto:right_way@foxmail.com" className="text-primary hover:underline">right_way@foxmail.com</a> 进行操作。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">七、本地存储技术</h2>
          <p>7.1 我们使用浏览器本地存储（LocalStorage）来保存您的登录态（Token）、用户信息和阅读设置等数据，以提供持续的服务体验。</p>
          <p>7.2 我们使用本地存储保存您的阅读进度和离线数据，方便您在不同设备间同步。</p>
          <p>7.3 您可以通过浏览器设置清除本地存储数据。但请注意，清除后您需要重新登录，部分离线功能可能受到影响。</p>
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
