import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { SlidersHorizontal, XCircle } from 'lucide-react'
import { getExcludePreferences, getIncludePreferences, addExcludePreference, addIncludePreference, removeExcludePreference, removeIncludePreference } from '@/api/preference'
import type { UserBookPreferenceItem } from '@/api/preference'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  isMobile: boolean
  sheetSide: 'bottom' | 'right'
}

const catLabel = (c: string) => c === 'TAG' ? '标签' : c === 'AUTHOR' ? '作者' : '格式'

const getCategoryLabel = (cat: string) => {
  switch (cat) {
    case 'TAG': return '标签'
    case 'AUTHOR': return '作者'
    case 'FORMAT': return '格式'
    default: return cat
  }
}

const getCategoryColor = (cat: string) => {
  switch (cat) {
    case 'TAG': return 'bg-info/10 text-info dark:bg-info/10 dark:text-info'
    case 'AUTHOR': return 'bg-info/10 text-info dark:bg-info/10 dark:text-info'
    case 'FORMAT': return 'bg-success/10 text-success dark:bg-success/10 dark:text-success'
    default: return 'bg-muted text-muted-foreground'
  }
}

export default function ProfilePreferenceSheet({ open, onOpenChange, isMobile, sheetSide }: Props) {
  const [excludePrefs, setExcludePrefs] = useState<UserBookPreferenceItem[]>([])
  const [includePrefs, setIncludePrefs] = useState<UserBookPreferenceItem[]>([])
  const [prefTab, setPrefTab] = useState<'exclude' | 'include'>('include')
  const [prefCategory, setPrefCategory] = useState<'TAG' | 'AUTHOR' | 'FORMAT'>('TAG')
  const [prefValue, setPrefValue] = useState('')
  const [prefLoading, setPrefLoading] = useState(false)
  const [prefSaving, setPrefSaving] = useState(false)

  const loadPreferences = async () => {
    setPrefLoading(true)
    try {
      const [excludeData, includeData] = await Promise.all([getExcludePreferences(), getIncludePreferences()])
      setExcludePrefs((excludeData as any) || [])
      setIncludePrefs((includeData as any) || [])
    } catch { setExcludePrefs([]); setIncludePrefs([]) }
    finally { setPrefLoading(false) }
  }

  useEffect(() => { if (open) loadPreferences() }, [open])

  const handleAdd = async () => {
    if (!prefValue.trim()) { toast.error('先写点什么吧'); return }
    setPrefSaving(true)
    try {
      if (prefTab === 'exclude') {
        await addExcludePreference(prefCategory, prefValue.trim())
        toast.success(`已添加：不想看${catLabel(prefCategory)}为"${prefValue.trim()}"的书籍`)
      } else {
        await addIncludePreference(prefCategory, prefValue.trim())
        toast.success(`已添加：想看${catLabel(prefCategory)}为"${prefValue.trim()}"的书籍`)
      }
      setPrefValue('')
      loadPreferences()
    } catch (err: any) {
      toast.error(err.message || '添加失败')
    } finally {
      setPrefSaving(false)
    }
  }

  const handleRemove = async (category: string, value: string, type: 'exclude' | 'include') => {
    try {
      if (type === 'exclude') {
        await removeExcludePreference(category, value)
        toast.success('已恢复推荐')
      } else {
        await removeIncludePreference(category, value)
        toast.success('已取消偏好')
      }
      loadPreferences()
    } catch (err: any) {
      toast.error(err.message || '操作未完成')
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side={sheetSide} className={`flex flex-col gap-0 p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">阅读偏好</SheetTitle>
        <SheetDescription className="sr-only">设置你的阅读偏好，让推荐更懂你</SheetDescription>
        <MobileSheetHeader
          icon={<SlidersHorizontal className="h-5 w-5 text-primary" />}
          title="阅读偏好"
          description="设置你的阅读偏好，让推荐更懂你"
          onClose={() => onOpenChange(false)}
        />

        <div className="shrink-0 space-y-3">
          <div className="flex rounded-lg bg-muted p-1">
            <button
              onClick={() => { setPrefTab('include'); setPrefValue('') }}
              className={`flex-1 whitespace-nowrap rounded-md py-1.5 text-sm font-medium transition-colors ${prefTab === 'include' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground'}`}
            >
              ❤️ 想看
            </button>
            <button
              onClick={() => { setPrefTab('exclude'); setPrefValue('') }}
              className={`flex-1 whitespace-nowrap rounded-md py-1.5 text-sm font-medium transition-colors ${prefTab === 'exclude' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground'}`}
            >
              🚫 不想看
            </button>
          </div>

          <div className="flex gap-2">
            <select
              value={prefCategory}
              onChange={(e) => setPrefCategory(e.target.value as 'TAG' | 'AUTHOR' | 'FORMAT')}
              className="rounded-lg border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary/20"
            >
              <option value="TAG">标签</option>
              <option value="AUTHOR">作者</option>
              <option value="FORMAT">格式</option>
            </select>
            <input
              type="text"
              value={prefValue}
              onChange={(e) => setPrefValue(e.target.value)}
              placeholder={prefTab === 'exclude'
                ? `输入不想看的${catLabel(prefCategory)}${prefCategory === 'FORMAT' ? '(如PDF)' : ''}`
                : `输入想看的${catLabel(prefCategory)}${prefCategory === 'FORMAT' ? '(如EPUB)' : ''}`
              }
              className="flex-1 rounded-lg border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary/20"
              onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
            />
            <button
              onClick={handleAdd}
              disabled={prefSaving || !prefValue.trim()}
              className={`shrink-0 whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium text-white disabled:opacity-50 ${prefTab === 'include' ? 'bg-danger hover:bg-danger/90' : 'bg-primary hover:bg-primary/90'}`}
            >
              {prefSaving ? '...' : '添加'}
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto overscroll-y-contain -mx-5 px-5 pt-3">
          {prefTab === 'include' ? (
            <div>
              <h4 className="text-xs font-semibold text-muted-foreground mb-2">
                想看的内容（{includePrefs.length}）
              </h4>
              {prefLoading ? (
                <div className="py-4 text-center text-xs text-muted-foreground">加载中...</div>
              ) : includePrefs.length === 0 ? (
                <div className="py-4 text-center text-xs text-muted-foreground">暂无偏好，添加喜欢的类型获取更精准推荐</div>
              ) : (
                <div className="max-h-60 overflow-y-auto overscroll-y-contain space-y-1.5">
                  {includePrefs.map((pref) => (
                    <div key={pref.id} className="flex items-center gap-2 rounded-lg bg-danger/10 dark:bg-danger/10 px-3 py-2">
                      <span className="text-danger text-xs">❤️</span>
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${getCategoryColor(pref.category)}`}>
                        {getCategoryLabel(pref.category)}
                      </span>
                      <span className="flex-1 text-sm truncate">{pref.value}</span>
                      <button
                        onClick={() => handleRemove(pref.category, pref.value, 'include')}
                        className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full hover:bg-red-100 text-red-500 hover:text-red-600 transition-colors"
                        title="取消偏好"
                      >
                        <XCircle className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <div>
              <h4 className="text-xs font-semibold text-muted-foreground mb-2">
                已排除的内容（{excludePrefs.length}）
              </h4>
              {prefLoading ? (
                <div className="py-4 text-center text-xs text-muted-foreground">加载中...</div>
              ) : excludePrefs.length === 0 ? (
                <div className="py-4 text-center text-xs text-muted-foreground">暂无排除偏好</div>
              ) : (
                <div className="max-h-60 overflow-y-auto overscroll-y-contain space-y-1.5">
                  {excludePrefs.map((pref) => (
                    <div key={pref.id} className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${getCategoryColor(pref.category)}`}>
                        {getCategoryLabel(pref.category)}
                      </span>
                      <span className="flex-1 text-sm truncate">{pref.value}</span>
                      <button
                        onClick={() => handleRemove(pref.category, pref.value, 'exclude')}
                        className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full hover:bg-red-100 text-red-500 hover:text-red-600 transition-colors"
                        title="恢复推荐"
                      >
                        <XCircle className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <p className="text-xs text-muted-foreground text-center pb-2">
            {prefTab === 'include' ? '取消后，该类书籍不再获得优先推荐' : '恢复后，该类书籍将重新出现在推荐中'}
          </p>
        </div>
      </SheetContent>
    </Sheet>
  )
}
