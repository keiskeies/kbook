import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { UserCircle, Check } from 'lucide-react'
import { updateTraits } from '@/api/auth'
import type { UserInfo } from '@/store/auth'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'

const MBTI_OPTIONS = ['INTJ','INTP','ENTJ','ENTP','INFJ','INFP','ENFJ','ENFP','ISTJ','ISFJ','ESTJ','ESFJ','ISTP','ISFP','ESTP','ESFP']

const OCCUPATION_OPTIONS = [
  { value: 'STUDENT', label: '学生' }, { value: 'TECH', label: '技术/IT' },
  { value: 'FINANCE', label: '金融/商业' }, { value: 'EDUCATION', label: '教育/科研' },
  { value: 'MEDICAL', label: '医疗/健康' }, { value: 'ARTS', label: '文艺/传媒' },
  { value: 'MANAGEMENT', label: '管理/行政' }, { value: 'FREELANCE', label: '自由职业' },
  { value: 'RETIRED', label: '退休' }, { value: 'OTHER', label: '其他' },
]

const EDUCATION_OPTIONS = [
  { value: 'HIGH_SCHOOL', label: '高中及以下' }, { value: 'COLLEGE', label: '大专' },
  { value: 'BACHELOR', label: '本科' }, { value: 'MASTER', label: '硕士' },
  { value: 'DOCTORATE', label: '博士' }, { value: 'OTHER', label: '其他' },
]

const ENTREPRENEURSHIP_OPTIONS = [
  { value: 'ENTREPRENEUR_OR_WANT', label: '正在创业/想创业' },
  { value: 'NOT_INTERESTED', label: '暂不考虑' },
]

const ANNUAL_INCOME_OPTIONS = [
  { value: 'UNDER_50K', label: '5万以内' }, { value: '50K_150K', label: '5~15万' },
  { value: '150K_300K', label: '15~30万' }, { value: '300K_500K', label: '30~50万' },
  { value: '500K_1M', label: '50~100万' }, { value: 'OVER_1M', label: '100万+' },
  { value: 'PREFER_NOT_TO_SAY', label: '不方便说' },
]

const CHILDREN_AGE_RANGE_OPTIONS = [
  { value: 'children_0_2', label: '0-2岁' }, { value: 'children_3_6', label: '3-6岁' },
  { value: 'children_7_12', label: '7-12岁' }, { value: 'children_13_17', label: '13-17岁' },
  { value: 'children_18_plus', label: '18岁以上' },
]

function calcAge(birthday: string | null | undefined): number | null {
  if (!birthday) return null
  const birth = new Date(birthday)
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) age--
  return age >= 0 ? age : null
}

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  userInfo: UserInfo | null
  updateUserInfo: (data: Partial<UserInfo>) => void
  isMobile: boolean
  sheetSide: 'bottom' | 'right'
}

export default function ProfileTraitsSheet({ open, onOpenChange, userInfo, updateUserInfo, isMobile, sheetSide }: Props) {
  const [traitBirthday, setTraitBirthday] = useState(userInfo?.birthday ?? '')
  const [traitGender, setTraitGender] = useState(userInfo?.gender ?? '')
  const [traitMarried, setTraitMarried] = useState(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
  const [traitHasChildren, setTraitHasChildren] = useState(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
  const [traitChildrenAgeRanges, setTraitChildrenAgeRanges] = useState<string[]>(() => {
    const ranges = userInfo?.childrenAgeRanges
    if (!ranges) return []
    return ranges.split(',').filter(Boolean).map((v: string) => v.startsWith('children_') ? v : 'children_' + v)
  })
  const [traitMbti, setTraitMbti] = useState(userInfo?.mbti ?? '')
  const [traitOccupations, setTraitOccupations] = useState<string[]>(() => {
    const occ = userInfo?.occupation
    return occ ? occ.split(',').filter(Boolean) : []
  })
  const [traitEducation, setTraitEducation] = useState(userInfo?.aspirationEducation ?? '')
  const [traitEntrepreneurship, setTraitEntrepreneurship] = useState(userInfo?.entrepreneurship ?? '')
  const [traitAnnualIncome, setTraitAnnualIncome] = useState(userInfo?.aspirationIncome ?? '')
  const [savingTraits, setSavingTraits] = useState(false)

  useEffect(() => {
    if (open) {
      setTraitBirthday(userInfo?.birthday ?? '')
      setTraitGender(userInfo?.gender ?? '')
      setTraitMarried(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
      setTraitHasChildren(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
      const ranges = userInfo?.childrenAgeRanges
      setTraitChildrenAgeRanges(ranges ? ranges.split(',').filter(Boolean).map((v: string) => v.startsWith('children_') ? v : 'children_' + v) : [])
      setTraitMbti(userInfo?.mbti ?? '')
      const occ = userInfo?.occupation
      setTraitOccupations(occ ? occ.split(',').filter(Boolean) : [])
      setTraitEducation(userInfo?.aspirationEducation ?? '')
      setTraitEntrepreneurship(userInfo?.entrepreneurship ?? '')
      setTraitAnnualIncome(userInfo?.aspirationIncome ?? '')
    }
  }, [open, userInfo])

  const handleSave = async () => {
    setSavingTraits(true)
    try {
      const data: any = {
        birthday: traitBirthday || null,
        gender: traitGender || null,
        married: traitMarried ? traitMarried === 'yes' : null,
        hasChildren: traitHasChildren ? traitHasChildren === 'yes' : null,
        childrenAgeRanges: traitHasChildren === 'yes' && traitChildrenAgeRanges.length > 0
          ? traitChildrenAgeRanges.join(',')
          : (traitHasChildren === 'no' ? 'no_children' : null),
        mbti: traitMbti || null,
        occupation: traitOccupations.length > 0 ? traitOccupations.join(',') : null,
        aspirationEducation: traitEducation || null,
        entrepreneurship: traitEntrepreneurship || null,
        aspirationIncome: traitAnnualIncome || null,
      }
      await updateTraits(data)
      updateUserInfo({
        birthday: data.birthday, gender: data.gender, married: data.married,
        hasChildren: data.hasChildren, childrenAgeRanges: data.childrenAgeRanges,
        mbti: data.mbti, occupation: data.occupation,
        aspirationEducation: data.aspirationEducation, entrepreneurship: data.entrepreneurship,
        aspirationIncome: data.aspirationIncome,
      })
      onOpenChange(false)
      toast.success('画像已更新')
    } catch (err: any) {
      toast.error(err.message || '更新未完成，稍后再试试')
    } finally {
      setSavingTraits(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side={sheetSide} className={`flex flex-col gap-0 p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">编辑我的画像</SheetTitle>
        <SheetDescription className="sr-only">完善画像可获得更精准的图书推荐</SheetDescription>
        <MobileSheetHeader
          icon={<UserCircle className="h-5 w-5 text-primary" />}
          title="编辑我的画像"
          description="完善画像可获得更精准的图书推荐"
          onClose={() => onOpenChange(false)}
        />

        <div className="flex-1 overflow-y-auto overscroll-y-contain space-y-4 -mx-5 px-5">
          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1 block">出生日期</label>
            <div className="grid grid-cols-3 gap-2">
              <select
                value={traitBirthday ? traitBirthday.split('-')[0] : ''}
                onChange={(e) => {
                  const y = e.target.value; const m = traitBirthday ? traitBirthday.split('-')[1] : ''; const d = traitBirthday ? traitBirthday.split('-')[2] : ''
                  setTraitBirthday(y && m && d ? `${y}-${m}-${d}` : y ? `${y}-01-01` : '')
                }}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-2.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
              >
                <option value="">年</option>
                {Array.from({ length: 100 }, (_, i) => new Date().getFullYear() - i).map(y => (
                  <option key={y} value={String(y)}>{y}</option>
                ))}
              </select>
              <select
                value={traitBirthday ? traitBirthday.split('-')[1] : ''}
                onChange={(e) => {
                  const y = traitBirthday ? traitBirthday.split('-')[0] : ''; const m = e.target.value; const d = traitBirthday ? traitBirthday.split('-')[2] : ''
                  setTraitBirthday(y && m && d ? `${y}-${m}-${d}` : m ? `2000-${m}-01` : '')
                }}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-2.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
              >
                <option value="">月</option>
                {Array.from({ length: 12 }, (_, i) => i + 1).map(m => (
                  <option key={m} value={String(m).padStart(2, '0')}>{m}月</option>
                ))}
              </select>
              <select
                value={traitBirthday ? traitBirthday.split('-')[2] : ''}
                onChange={(e) => {
                  const y = traitBirthday ? traitBirthday.split('-')[0] : ''; const m = traitBirthday ? traitBirthday.split('-')[1] : ''; const d = e.target.value
                  setTraitBirthday(y && m && d ? `${y}-${m}-${d}` : d ? `2000-01-${d}` : '')
                }}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-2.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
              >
                <option value="">日</option>
                {Array.from({ length: 31 }, (_, i) => i + 1).map(d => (
                  <option key={d} value={String(d).padStart(2, '0')}>{d}日</option>
                ))}
              </select>
            </div>
            {traitBirthday && (
              <p className="mt-1 text-xs text-muted-foreground">当前年龄：{calcAge(traitBirthday)}岁</p>
            )}
          </div>

          <select value={traitGender} onChange={(e) => setTraitGender(e.target.value)} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">选择性别</option>
            <option value="MALE">男</option> <option value="FEMALE">女</option> <option value="OTHER">其他</option>
          </select>

          <select value={traitMarried} onChange={(e) => setTraitMarried(e.target.value)} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">婚姻状况</option>
            <option value="yes">已婚</option> <option value="no">未婚</option>
          </select>

          <select value={traitHasChildren} onChange={(e) => { setTraitHasChildren(e.target.value); if (e.target.value !== 'yes') setTraitChildrenAgeRanges([]) }} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">是否有孩子</option>
            <option value="yes">有孩子</option> <option value="no">无孩子</option>
          </select>

          {traitHasChildren === 'yes' && (
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">孩子年龄（可多选）</label>
              <div className="flex flex-wrap gap-2">
                {CHILDREN_AGE_RANGE_OPTIONS.map(r => {
                  const selected = traitChildrenAgeRanges.includes(r.value)
                  return (
                    <button key={r.value} type="button" onClick={() => setTraitChildrenAgeRanges(prev => selected ? prev.filter(v => v !== r.value) : [...prev, r.value])}
                      className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors border ${selected ? 'bg-primary text-primary-foreground border-primary shadow-sm' : 'bg-background text-muted-foreground border-border hover:border-primary/40'}`}
                    >
                      {selected && <Check className="h-3 w-3" />}{r.label}
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          <select value={traitMbti} onChange={(e) => setTraitMbti(e.target.value)} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">MBTI 人格</option>
            {MBTI_OPTIONS.map(m => <option key={m} value={m}>{m}</option>)}
          </select>

          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1.5 block">职业方向（可多选）</label>
            <div className="flex flex-wrap gap-2">
              {OCCUPATION_OPTIONS.map(o => {
                const selected = traitOccupations.includes(o.value)
                return (
                  <button key={o.value} type="button" onClick={() => setTraitOccupations(prev => selected ? prev.filter(v => v !== o.value) : [...prev, o.value])}
                    className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors border ${selected ? 'bg-primary text-primary-foreground border-primary shadow-sm' : 'bg-background text-muted-foreground border-border hover:border-primary/40'}`}
                  >
                    {selected && <Check className="h-3 w-3" />}{o.label}
                  </button>
                )
              })}
            </div>
          </div>

          <select value={traitEducation} onChange={(e) => setTraitEducation(e.target.value)} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">当前/目标学历</option>
            {EDUCATION_OPTIONS.map(e => <option key={e.value} value={e.value}>{e.label}</option>)}
          </select>

          <select value={traitEntrepreneurship} onChange={(e) => setTraitEntrepreneurship(e.target.value)} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">创业意向</option>
            {ENTREPRENEURSHIP_OPTIONS.map(e => <option key={e.value} value={e.value}>{e.label}</option>)}
          </select>

          <select value={traitAnnualIncome} onChange={(e) => setTraitAnnualIncome(e.target.value)} className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50">
            <option value="">当前/期望年收入</option>
            {ANNUAL_INCOME_OPTIONS.map(e => <option key={e.value} value={e.value}>{e.label}</option>)}
          </select>
        </div>

        <div className="shrink-0 pt-4">
          <button onClick={handleSave} disabled={savingTraits}
            className="w-full rounded-xl bg-primary py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
          >
            {savingTraits ? '保存中...' : '保存'}
          </button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
