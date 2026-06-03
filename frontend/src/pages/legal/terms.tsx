import { ArrowLeft } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

export default function TermsPage() {
  const navigate = useNavigate()

  return (
    <div className="h-full overflow-y-auto overscroll-contain bg-background">
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-base font-bold">用户协议</h1>
      </header>

      <div className="mx-auto max-w-2xl px-5 py-6 text-sm leading-relaxed text-muted-foreground space-y-6">
        <p className="text-xs text-muted-foreground/60">最后更新日期：2026年6月2日</p>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">一、总则</h2>
          <p>欢迎您使用 KBook（以下简称"本平台"）。本协议是您与 KBook 之间关于使用本平台服务所订立的协议。请您仔细阅读本协议，在确认充分理解并同意后再开始使用本平台服务。您使用本平台服务即视为您已阅读并同意本协议的约束。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">二、服务内容</h2>
          <p>2.1 本平台为用户提供图书阅读、AI 智能问答、个性化推荐、书架管理、阅读进度同步等服务。</p>
          <p>2.2 本平台保留随时变更、中断或终止部分或全部服务的权利。服务内容发生变更时，本平台将通过适当方式通知用户。</p>
          <p>2.3 本平台中的部分功能可能需要用户注册并登录后方可使用。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">三、账号注册与管理</h2>
          <p>3.1 用户应使用真实、准确的信息注册账号，并对账号信息的真实性、合法性负责。</p>
          <p>3.2 用户应妥善保管账号和密码，因用户个人原因导致账号泄露所引起的一切损失由用户自行承担。</p>
          <p>3.3 用户不得将账号转让、出售或出借给他人使用。如发现未经授权使用账号的情况，应立即通知本平台。</p>
          <p>3.4 新注册账号需经管理员审核通过后方可正常使用。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">四、用户行为规范</h2>
          <p>4.1 用户在使用本平台服务时，应遵守中华人民共和国相关法律法规，不得利用本平台从事任何违法违规活动。</p>
          <p>4.2 用户不得实施以下行为：</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>发布、传播违反法律法规的内容；</li>
            <li>侵犯他人知识产权或其他合法权益；</li>
            <li>恶意攻击、破坏平台系统或网络设施；</li>
            <li>利用技术手段批量获取平台数据；</li>
            <li>冒充他人或虚构身份；</li>
            <li>其他损害平台或他人合法权益的行为。</li>
          </ul>
          <p>4.3 用户违反上述规定的，本平台有权视情节轻重采取警告、限制功能、封禁账号等措施。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">五、知识产权</h2>
          <p>5.1 本平台的所有内容，包括但不限于文字、图片、软件、界面设计、版面编排等，其知识产权均归 KBook 或相关权利人所有。</p>
          <p>5.2 本平台提供的图书资源仅供个人学习、研究使用，未经版权方许可，不得用于商业目的或进行传播。</p>
          <p>5.3 用户在本平台发表的原创内容（如评论、笔记等），其知识产权归用户所有，但用户授予本平台在全球范围内免费的、非独占的使用许可。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">六、AI 服务说明</h2>
          <p>6.1 本平台提供的 AI 问答、推荐等服务基于人工智能技术生成，仅供参考，不构成任何专业建议。</p>
          <p>6.2 AI 生成的内容可能存在不准确或不完整之处，用户应自行判断并承担使用风险。</p>
          <p>6.3 本平台不对 AI 生成内容的准确性、完整性和可靠性做出明示或暗示的保证。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">七、免责声明</h2>
          <p>7.1 因不可抗力、计算机病毒、黑客攻击、系统不稳定等原因导致的服务中断或数据丢失，本平台不承担责任。</p>
          <p>7.2 本平台不对用户间的互动行为承担担保或赔偿责任。</p>
          <p>7.3 用户因使用本平台服务而产生的任何直接或间接损失，本平台在法律允许的范围内免责。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">八、协议变更</h2>
          <p>8.1 本平台有权根据需要修改本协议条款，修改后的协议将在平台上公布。</p>
          <p>8.2 用户在协议变更后继续使用本平台服务的，视为同意变更后的协议。</p>
          <p>8.3 如用户不同意变更后的协议，可停止使用本平台服务并注销账号。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">九、其他</h2>
          <p>9.1 本协议的订立、执行和解释均适用中华人民共和国法律。</p>
          <p>9.2 本协议未尽事宜，依照中华人民共和国相关法律法规执行。</p>
          <p>9.3 如本协议中的任何条款无论因何种原因完全或部分无效或不具有执行力，该条款的其余部分及本协议的其他条款仍应有效并具有约束力。</p>
          <p>9.4 如您对本协议有任何疑问，请发送邮件至 <a href="mailto:right_way@foxmail.com" className="text-primary hover:underline">right_way@foxmail.com</a>。</p>
        </section>

        <section>
          <h2 className="mb-2 text-base font-bold text-foreground">十、开源声明</h2>
          <p>本平台基于以下开源项目构建，感谢开源社区的贡献：</p>
          <ul className="list-disc pl-5 space-y-1 mt-2">
            <li><span className="text-foreground font-medium">React</span> — MIT License</li>
            <li><span className="text-foreground font-medium">React Router</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Zustand</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Vite</span> — MIT License</li>
            <li><span className="text-foreground font-medium">TypeScript</span> — Apache-2.0 License</li>
            <li><span className="text-foreground font-medium">Tailwind CSS</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Radix UI</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Lucide Icons</span> — ISC License</li>
            <li><span className="text-foreground font-medium">pdf.js</span> — Apache-2.0 License</li>
            <li><span className="text-foreground font-medium">epub.js</span> — BSD-3-Clause License</li>
            <li><span className="text-foreground font-medium">Axios</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Recharts</span> — MIT License</li>
            <li><span className="text-foreground font-medium">react-markdown</span> — MIT License</li>
            <li><span className="text-foreground font-medium">date-fns</span> — MIT License</li>
            <li><span className="text-foreground font-medium">DOMPurify</span> — Apache-2.0 / MPL-2.0</li>
            <li><span className="text-foreground font-medium">Zod</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Sonner</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Vaul</span> — MIT License</li>
            <li><span className="text-foreground font-medium">cmdk</span> — MIT License</li>
            <li><span className="text-foreground font-medium">react-easy-crop</span> — MIT License</li>
            <li><span className="text-foreground font-medium">Spring Boot</span> — Apache-2.0 License（后端）</li>
          </ul>
          <p className="mt-2">以上开源项目均按其各自的开源协议使用。如需了解具体协议内容，请访问各项目的官方仓库。</p>
        </section>
      </div>
    </div>
  )
}
