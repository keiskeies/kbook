import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { updateTraits } from '@/api/auth'
import { toast } from 'sonner'
import {
  ChevronRight, ChevronLeft, Sparkles, User, Briefcase,
  GraduationCap, Heart, Baby, Cake, BrainCircuit, Target,
  CheckCircle2, BookOpen,
} from 'lucide-react'

const MBTI_OPTIONS = ['INTJ','INTP','ENTJ','ENTP','INFJ','INFP','ENFJ','ENFP','ISTJ','ISFJ','ESTJ','ESFJ','ISTP','ISFP','ESTP','ESFP']

const OCCUPATION_OPTIONS = [
  { value: 'STUDENT', label: '学生' },
  { value: 'TECH', label: '技术/IT' },
  { value: 'FINANCE', label: '金融/商业' },
  { value: 'EDUCATION', label: '教育/科研' },
  { value: 'MEDICAL', label: '医疗/健康' },
  { value: 'ARTS', label: '文艺/传媒' },
  { value: 'MANAGEMENT', label: '管理/行政' },
  { value: 'FREELANCE', label: '自由职业' },
  { value: 'RETIRED', label: '退休' },
  { value: 'OTHER', label: '其他' },
]

const EDUCATION_OPTIONS = [
  { value: 'HIGH_SCHOOL', label: '高中及以下' },
  { value: 'COLLEGE', label: '大专' },
  { value: 'BACHELOR', label: '本科' },
  { value: 'MASTER', label: '硕士' },
  { value: 'DOCTORATE', label: '博士' },
  { value: 'OTHER', label: '其他' },
]

const ENTREPRENEURSHIP_OPTIONS = [
  { value: 'ENTREPRENEUR_OR_WANT', label: '正在创业/想创业' },
  { value: 'NOT_INTERESTED', label: '暂不考虑' },
]

const ANNUAL_INCOME_OPTIONS = [
  { value: 'UNDER_50K', label: '5万以内' },
  { value: '50K_150K', label: '5~15万' },
  { value: '150K_300K', label: '15~30万' },
  { value: '300K_500K', label: '30~50万' },
  { value: '500K_1M', label: '50~100万' },
  { value: 'OVER_1M', label: '100万+' },
  { value: 'PREFER_NOT_TO_SAY', label: '不方便说' },
]

const CHILDREN_AGE_RANGE_OPTIONS = [
  { value: '0_2', label: '0-2岁' },
  { value: '3_6', label: '3-6岁' },
  { value: '7_12', label: '7-12岁' },
  { value: '13_17', label: '13-17岁' },
  { value: '18_plus', label: '18岁以上' },
]

type Step = {
  id: string
  title: string
  subtitle: string
  icon: React.ReactNode
  required?: boolean
}

const STEPS: Step[] = [
  { id: 'welcome', title: '欢迎来到 KBook', subtitle: '让我们花 1 分钟了解你，为你推荐更适合的书', icon: <Sparkles className="h-5 w-5" /> },
  { id: 'basic', title: '基本信息', subtitle: '年龄、性别、婚姻状况', icon: <User className="h-5 w-5" />, required: true },
  { id: 'personality', title: '个性与职业', subtitle: 'MBTI 人格类型和职业方向', icon: <BrainCircuit className="h-5 w-5" />, required: true },
  { id: 'background', title: '背景与目标', subtitle: '学历、收入、创业意向', icon: <GraduationCap className="h-5 w-5" /> },
  { id: 'done', title: '准备就绪', subtitle: '开始探索属于你的书籍世界', icon: <BookOpen className="h-5 w-5" /> },
]

export default function OnboardingPage() {
  const navigate = useNavigate()
  const { updateUserInfo } = useAuthStore()
  const [currentStep, setCurrentStep] = useState(0)
  const [saving, setSaving] = useState(false)

  // 表单数据
  const [birthday, setBirthday] = useState('')
  const [gender, setGender] = useState('')
  const [married, setMarried] = useState('')
  const [hasChildren, setHasChildren] = useState('')
  const [childrenAgeRanges, setChildrenAgeRanges] = useState<string[]>([])
  const [mbti, setMbti] = useState('')
  const [occupations, setOccupations] = useState<string[]>([])
  const [education, setEducation] = useState('')
  const [entrepreneurship, setEntrepreneurship] = useState('')
  const [annualIncome, setAnnualIncome] = useState('')

  const step = STEPS[currentStep]
  const totalSteps = STEPS.length
  const progress = ((currentStep) / (totalSteps - 1)) * 100

  const canProceed = () => {
    if (step.id === 'basic') {
      return birthday !== '' && gender !== ''
    }
    if (step.id === 'personality') {
      return mbti !== '' && occupations.length > 0
    }
    return true
  }

  const handleNext = async () => {
    if (currentStep < totalSteps - 1) {
      setCurrentStep(currentStep + 1)
    }
  }

  const handleBack = () => {
    if (currentStep > 0) {
      setCurrentStep(currentStep - 1)
    }
  }

  const handleFinish = async () => {
    setSaving(true)
    try {
      const data = {
        birthday: birthday || undefined,
        gender: gender || undefined,
        married: married ? married === 'yes' : undefined,
        hasChildren: hasChildren ? hasChildren === 'yes' : undefined,
        childrenAgeRanges: hasChildren === 'yes' && childrenAgeRanges.length > 0
          ? childrenAgeRanges.join(',')
          : (hasChildren === 'no' ? 'no_children' : undefined),
        mbti: mbti || undefined,
        occupation: occupations.length > 0 ? occupations.join(',') : undefined,
        aspirationEducation: education || undefined,
        entrepreneurship: entrepreneurship || undefined,
        aspirationIncome: annualIncome || undefined,
      }
      await updateTraits(data)
      updateUserInfo({
        birthday: data.birthday ?? null,
        gender: (data.gender as 'MALE' | 'FEMALE' | 'OTHER') ?? null,
        married: data.married ?? null,
        hasChildren: data.hasChildren ?? null,
        childrenAgeRanges: data.childrenAgeRanges ?? null,
        mbti: data.mbti ?? null,
        occupation: data.occupation ?? null,
        aspirationEducation: data.aspirationEducation ?? null,
        entrepreneurship: data.entrepreneurship ?? null,
        aspirationIncome: data.aspirationIncome ?? null,
      })
      toast.success('画像已保存，开始为你推荐书籍！')
      navigate('/', { replace: true })
    } catch (err: any) {
      toast.error(err.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleSkip = () => {
    toast('你可以随时在「我的画像」中完善信息')
    navigate('/', { replace: true })
  }

  const renderStepContent = () => {
    switch (step.id) {
      case 'welcome':
        return (
          <div className="flex flex-col items-center text-center py-8">
            <div className="flex h-20 w-20 items-center justify-center rounded-3xl bg-gradient-to-br from-primary/20 to-primary/5 mb-6">
              <BookOpen className="h-10 w-10 text-primary" />
            </div>
            <h2 className="text-2xl font-bold mb-2">找到属于你的书</h2>
            <p className="text-sm text-muted-foreground max-w-[260px] leading-relaxed">
              KBook 根据你的画像精准推荐书籍，<br />不再被算法绑架，每一本都值得读
            </p>
            <div className="mt-8 space-y-3 w-full max-w-[280px]">
              <div className="flex items-center gap-3 rounded-xl bg-muted/50 px-4 py-3">
                <Target className="h-5 w-5 text-primary shrink-0" />
                <div className="text-left">
                  <p className="text-sm font-medium">精准匹配</p>
                  <p className="text-xs text-muted-foreground">基于 10+ 维度计算匹配度</p>
                </div>
              </div>
              <div className="flex items-center gap-3 rounded-xl bg-muted/50 px-4 py-3">
                <Sparkles className="h-5 w-5 text-warning shrink-0" />
                <div className="text-left">
                  <p className="text-sm font-medium">3分钟速读</p>
                  <p className="text-xs text-muted-foreground">AI 提炼核心观点，快速判断</p>
                </div>
              </div>
              <div className="flex items-center gap-3 rounded-xl bg-muted/50 px-4 py-3">
                <BrainCircuit className="h-5 w-5 text-success shrink-0" />
                <div className="text-left">
                  <p className="text-sm font-medium">深度问答</p>
                  <p className="text-xs text-muted-foreground">对书籍有任何疑问，AI 为你解答</p>
                </div>
              </div>
            </div>
          </div>
        )

      case 'basic':
        return (
          <div className="space-y-4 py-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">出生日期 <span className="text-danger">*</span></label>
              <input
                type="date"
                value={birthday}
                onChange={(e) => setBirthday(e.target.value)}
                max={new Date().toISOString().split('T')[0]}
                className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
            </div>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">性别 <span className="text-danger">*</span></label>
              <div className="flex gap-2">
                {[
                  { value: 'MALE', label: '男', icon: '♂' },
                  { value: 'FEMALE', label: '女', icon: '♀' },
                  { value: 'OTHER', label: '其他', icon: '○' },
                ].map((g) => (
                  <button
                    key={g.value}
                    onClick={() => setGender(g.value)}
                    className={`flex-1 flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm font-medium transition-all border ${
                      gender === g.value
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    <span>{g.icon}</span>
                    {g.label}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">婚姻状况</label>
              <div className="flex gap-2">
                {[
                  { value: 'yes', label: '已婚', icon: <Heart className="h-3.5 w-3.5" /> },
                  { value: 'no', label: '未婚', icon: <Heart className="h-3.5 w-3.5" /> },
                ].map((m) => (
                  <button
                    key={m.value}
                    onClick={() => setMarried(m.value)}
                    className={`flex-1 flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm font-medium transition-all border ${
                      married === m.value
                        ? 'bg-danger/10 text-danger border-danger/20 dark:bg-danger/10 dark:text-danger dark:border-danger/20'
                        : 'bg-background text-muted-foreground border-border hover:border-danger/10'
                    }`}
                  >
                    {m.icon}
                    {m.label}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">是否有孩子</label>
              <div className="flex gap-2">
                {[
                  { value: 'yes', label: '有孩子', icon: <Baby className="h-3.5 w-3.5" /> },
                  { value: 'no', label: '无孩子', icon: <Baby className="h-3.5 w-3.5" /> },
                ].map((c) => (
                  <button
                    key={c.value}
                    onClick={() => {
                      setHasChildren(c.value)
                      if (c.value !== 'yes') setChildrenAgeRanges([])
                    }}
                    className={`flex-1 flex items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm font-medium transition-all border ${
                      hasChildren === c.value
                        ? 'bg-info/10 text-info border-info/20 dark:bg-info/10 dark:text-info dark:border-info/20'
                        : 'bg-background text-muted-foreground border-border hover:border-info/10'
                    }`}
                  >
                    {c.icon}
                    {c.label}
                  </button>
                ))}
              </div>
            </div>

            {hasChildren === 'yes' && (
              <div>
                <label className="text-xs font-medium text-muted-foreground mb-1.5 block">孩子年龄（可多选）</label>
                <div className="flex flex-wrap gap-2">
                  {CHILDREN_AGE_RANGE_OPTIONS.map((r) => {
                    const selected = childrenAgeRanges.includes(r.value)
                    return (
                      <button
                        key={r.value}
                        onClick={() => {
                          setChildrenAgeRanges(prev =>
                            selected ? prev.filter(v => v !== r.value) : [...prev, r.value]
                          )
                        }}
                        className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-all border ${
                          selected
                            ? 'bg-info/10 text-info border-info/20 dark:bg-info/10 dark:text-info dark:border-info/20'
                            : 'bg-background text-muted-foreground border-border hover:border-info/10'
                        }`}
                      >
                        {selected && <CheckCircle2 className="h-3 w-3" />}
                        {r.label}
                      </button>
                    )
                  })}
                </div>
              </div>
            )}
          </div>
        )

      case 'personality':
        return (
          <div className="space-y-4 py-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">MBTI 人格类型 <span className="text-danger">*</span></label>
              <div className="grid grid-cols-4 gap-2">
                {MBTI_OPTIONS.map((m) => (
                  <button
                    key={m}
                    onClick={() => setMbti(m)}
                    className={`rounded-xl py-2 text-xs font-bold transition-all border ${
                      mbti === m
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    {m}
                  </button>
                ))}
              </div>
              {mbti && (
                <p className="mt-2 text-xs text-muted-foreground text-center">
                  已选择：<span className="font-medium text-primary">{mbti}</span>
                </p>
              )}
            </div>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">职业方向 <span className="text-danger">*</span>（可多选）</label>
              <div className="flex flex-wrap gap-2">
                {OCCUPATION_OPTIONS.map((o) => {
                  const selected = occupations.includes(o.value)
                  return (
                    <button
                      key={o.value}
                      onClick={() => {
                        setOccupations(prev =>
                          selected ? prev.filter(v => v !== o.value) : [...prev, o.value]
                        )
                      }}
                      className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-all border ${
                        selected
                          ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                          : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                      }`}
                    >
                      {selected && <CheckCircle2 className="h-3 w-3" />}
                      {o.label}
                    </button>
                  )
                })}
              </div>
            </div>
          </div>
        )

      case 'background':
        return (
          <div className="space-y-4 py-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">当前/目标学历</label>
              <div className="flex flex-wrap gap-2">
                {EDUCATION_OPTIONS.map((e) => (
                  <button
                    key={e.value}
                    onClick={() => setEducation(e.value)}
                    className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-all border ${
                      education === e.value
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    {e.label}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">创业意向</label>
              <div className="flex gap-2">
                {ENTREPRENEURSHIP_OPTIONS.map((e) => (
                  <button
                    key={e.value}
                    onClick={() => setEntrepreneurship(e.value)}
                    className={`flex-1 rounded-xl py-2.5 text-sm font-medium transition-all border ${
                      entrepreneurship === e.value
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    {e.label}
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">当前/期望年收入</label>
              <div className="flex flex-wrap gap-2">
                {ANNUAL_INCOME_OPTIONS.map((i) => (
                  <button
                    key={i.value}
                    onClick={() => setAnnualIncome(i.value)}
                    className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-all border ${
                      annualIncome === i.value
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    {i.label}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )

      case 'done':
        return (
          <div className="flex flex-col items-center text-center py-8">
            <div className="flex h-20 w-20 items-center justify-center rounded-full bg-gradient-to-br from-success/20 to-success/5 mb-6">
              <CheckCircle2 className="h-10 w-10 text-success" />
            </div>
            <h2 className="text-2xl font-bold mb-2">画像已完善</h2>
            <p className="text-sm text-muted-foreground max-w-[260px] leading-relaxed">
              我们会根据你的画像为你推荐最合适的书籍。<br />随时可以回来更新。
            </p>
            <div className="mt-6 w-full max-w-[280px] rounded-2xl bg-muted/50 p-4 text-left space-y-2">
              <div className="flex items-center gap-2 text-sm">
                <Cake className="h-4 w-4 text-muted-foreground" />
                <span>{birthday ? `${new Date().getFullYear() - new Date(birthday).getFullYear()}岁` : '未设置年龄'}</span>
              </div>
              <div className="flex items-center gap-2 text-sm">
                <User className="h-4 w-4 text-muted-foreground" />
                <span>{gender === 'MALE' ? '男' : gender === 'FEMALE' ? '女' : '其他'}</span>
              </div>
              <div className="flex items-center gap-2 text-sm">
                <BrainCircuit className="h-4 w-4 text-muted-foreground" />
                <span className="font-medium text-primary">{mbti}</span>
              </div>
              <div className="flex items-center gap-2 text-sm">
                <Briefcase className="h-4 w-4 text-muted-foreground" />
                <span>{occupations.map(v => OCCUPATION_OPTIONS.find(o => o.value === v)?.label).filter(Boolean).join('、')}</span>
              </div>
            </div>
          </div>
        )

      default:
        return null
    }
  }

  return (
    <div className="fixed inset-0 bg-background flex flex-col overscroll-contain">
      {/* 顶部进度条 */}
      {step.id !== 'welcome' && step.id !== 'done' && (
        <div className="px-4 pt-safe-top">
          <div className="flex items-center gap-3 py-3">
            {currentStep > 0 && (
              <button onClick={handleBack} className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted transition-colors">
                <ChevronLeft className="h-5 w-5" />
              </button>
            )}
            <div className="flex-1">
              <div className="h-1.5 w-full rounded-full bg-muted overflow-hidden">
                <div
                  className="h-full rounded-full bg-primary transition-all duration-500"
                  style={{ width: `${progress}%` }}
                />
              </div>
            </div>
            <span className="text-xs text-muted-foreground font-medium">
              {currentStep}/{totalSteps - 1}
            </span>
          </div>
        </div>
      )}

      {/* 内容区域 */}
      <div className="flex-1 px-5">
        {step.id !== 'welcome' && step.id !== 'done' && (
          <div className="mb-4">
            <div className="flex items-center gap-2 mb-1">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10">
                {step.icon}
              </div>
              <h2 className="text-lg font-bold">{step.title}</h2>
            </div>
            <p className="text-xs text-muted-foreground ml-10">{step.subtitle}</p>
          </div>
        )}

        {renderStepContent()}
      </div>

      {/* 底部按钮 */}
      <div className="px-5 pt-4" style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}>
        {step.id === 'welcome' && (
          <div className="space-y-2">
            <button
              onClick={handleNext}
              className="w-full rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
            >
              开始完善画像
            </button>
            <button
              onClick={handleSkip}
              className="w-full rounded-xl py-3 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              稍后再说
            </button>
          </div>
        )}

        {step.id === 'done' && (
          <button
            onClick={handleFinish}
            disabled={saving}
            className="w-full rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground shadow-md shadow-primary/20 active:scale-[0.98] transition-transform disabled:opacity-50"
          >
            {saving ? '保存中...' : '进入 KBook'}
          </button>
        )}

        {step.id !== 'welcome' && step.id !== 'done' && (
          <div className="flex gap-3">
            <button
              onClick={handleSkip}
              className="rounded-xl px-4 py-3 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              跳过
            </button>
            <button
              onClick={handleNext}
              disabled={!canProceed()}
              className="flex-1 flex items-center justify-center gap-1 rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground shadow-md shadow-primary/20 active:scale-[0.98] transition-transform disabled:opacity-40"
            >
              下一步
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
