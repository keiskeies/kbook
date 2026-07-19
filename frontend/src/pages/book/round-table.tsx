import { useState, useEffect, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Users, RefreshCw, History, Trash2, BookOpen, Sparkles, X,
} from 'lucide-react'
import {
  getRoundTableRoles, createRoundTableSession, getRoundTableSessions, deleteRoundTableSession,
} from '@/api/roundTable'
import { getBook } from '@/api/book'
import type { RoundTableRole, RoundTableSession } from '@/types/roundTable'
import type { Book } from '@/types/book'
import {
  ROLE_COLORS, ROLE_TITLES, ROLE_ICONS, ROLE_GROUP_NAMES, ROLE_GROUP_ORDER,
  hexToRgba,
} from '@/types/roundTable'
import { toast } from 'sonner'

/**
 * 角色卡片 — 紧凑布局
 * - AI 推荐的（role.selected）有彩色描边 + 角标
 * - 点击切换选中态
 * - perspective 两行截断，hover 展示全部（title 属性）
 */
function RoleCard({
  role,
  isSelected,
  isHost,
  isRecommended,
  onToggle,
}: {
  role: RoundTableRole
  isSelected: boolean
  isHost: boolean
  isRecommended: boolean
  onToggle: () => void
}) {
  const color = role.color || ROLE_COLORS[role.key] || '#6B655C'

  return (
    <button
      onClick={onToggle}
      disabled={isHost}
      title={role.perspective || undefined}
      className={`group relative flex flex-col gap-1.5 rounded-xl border p-2.5 text-left transition-all duration-200 ${
        isSelected
          ? 'border-[var(--role-color)]/50 bg-[var(--role-color)]/[0.05] shadow-sm'
          : 'border-border/40 bg-card hover:border-border/70 hover:shadow-sm'
      } ${isHost ? 'cursor-default' : 'cursor-pointer active:scale-[0.98]'}`}
      style={{ '--role-color': color } as React.CSSProperties}
    >
      {/* 选中角标 / 必选标记 / AI推荐标记 */}
      {isSelected && !isHost && (
        <span className="absolute -top-1.5 -right-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-[var(--role-color)] text-white shadow-sm z-10">
          <svg width="8" height="8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
        </span>
      )}
      {isHost && (
        <span className="absolute -top-1.5 -right-1.5 rounded-full bg-primary px-1.5 py-0.5 text-[10px] font-bold text-primary-foreground shadow-sm z-10">
          必选
        </span>
      )}
      {isRecommended && !isHost && !isSelected && (
        <span className="absolute -top-1.5 -left-1.5 flex items-center gap-0.5 rounded-full bg-brand-500 px-1.5 py-0.5 text-[9px] font-bold text-white shadow-sm z-10">
          <Sparkles className="h-2 w-2" />推荐
        </span>
      )}

      <div className="flex items-center gap-2">
        <div
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-base transition-transform duration-200 group-hover:scale-105"
          style={{
            backgroundColor: hexToRgba(color, 0.1),
            border: `1.5px solid ${hexToRgba(color, 0.25)}`,
          }}
        >
          {role.icon || ROLE_ICONS[role.key] || '👤'}
        </div>
        <div className="min-w-0 flex-1">
          <span className="block text-xs font-bold truncate" style={{ color }}>{role.name}</span>
          <span className="block text-[10px] text-muted-foreground/70 truncate">{ROLE_TITLES[role.key]}</span>
        </div>
      </div>

      {role.perspective && (
        <p className="line-clamp-2 text-[10px] leading-relaxed text-muted-foreground/70">
          {role.perspective}
        </p>
      )}

      {role.tags && role.tags.length > 0 && (
        <div className="flex flex-wrap gap-0.5">
          {role.tags.slice(0, 3).map(t => (
            <span
              key={t}
              className="rounded px-1 py-px text-[9px] font-medium"
              style={{
                backgroundColor: hexToRgba(color, 0.08),
                color: hexToRgba(color, 0.85),
              }}
            >
              {t}
            </span>
          ))}
        </div>
      )}
    </button>
  )
}

function SessionCard({
  session,
  onLoad,
  onDelete,
}: {
  session: RoundTableSession
  onLoad: () => void
  onDelete: () => void
}) {
  const roleKeysArr = session.roleKeys ? session.roleKeys.split(',') : []
  const dateStr = new Date(session.updatedAt || session.createdAt).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })

  return (
    <div className="flex items-center gap-3 rounded-xl border border-border/50 bg-card p-2.5 hover:border-border/70 transition-colors">
      <div className="flex-1 min-w-0">
        <p className="text-xs font-semibold truncate">{session.title || '圆桌派讨论'}</p>
        <div className="flex items-center gap-1.5 mt-1">
          <div className="flex -space-x-1">
            {roleKeysArr.slice(0, 5).map(key => (
              <span
                key={key}
                className="flex h-4 w-4 items-center justify-center rounded-full text-xs border border-background"
                style={{ backgroundColor: hexToRgba(ROLE_COLORS[key] || '#6B655C', 0.15) }}
              >
                {ROLE_ICONS[key] || '👤'}
              </span>
            ))}
            {roleKeysArr.length > 5 && (
              <span className="flex h-4 w-4 items-center justify-center rounded-full text-xs bg-muted text-muted-foreground border border-background">
                +{roleKeysArr.length - 5}
              </span>
            )}
          </div>
          <span className="text-[10px] text-muted-foreground">{dateStr}</span>
        </div>
      </div>
      <div className="flex items-center gap-1 shrink-0">
        <button
          onClick={onLoad}
          className="rounded-lg px-2 py-1 text-xs font-medium text-brand-500 hover:bg-brand-50 dark:hover:bg-brand-500/10 transition-colors"
        >
          观看
        </button>
        <button
          onClick={onDelete}
          className="rounded-lg p-1 text-muted-foreground/50 hover:text-red-500 hover:bg-red-50 transition-colors"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  )
}

export default function RoundTablePage() {
  const { bookId } = useParams<{ bookId: string }>()
  const navigate = useNavigate()
  const id = Number(bookId)

  const [phase, setPhase] = useState<'loading' | 'select'>('loading')
  const [availableRoles, setAvailableRoles] = useState<RoundTableRole[]>([])
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [recommendedKeys, setRecommendedKeys] = useState<Set<string>>(new Set())
  const [pastSessions, setPastSessions] = useState<RoundTableSession[]>([])
  const [bookInfo, setBookInfo] = useState<Book | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)

  useEffect(() => {
    if (!id) return
    loadRecommendedRoles()
    getRoundTableSessions(id).then((res: unknown) => {
      const data = (res as { data?: RoundTableSession[] })?.data ?? res as RoundTableSession[]
      if (Array.isArray(data)) setPastSessions(data)
    })
    getBook(id).then((res: unknown) => {
      const data = (res as { data?: Book })?.data ?? res as Book
      if (data) setBookInfo(data as Book)
    })
  }, [id])

  const loadRecommendedRoles = useCallback(async (refresh?: boolean) => {
    setPhase('loading')
    try {
      const res = await getRoundTableRoles(id, refresh)
      const data = (res as { data?: RoundTableRole[] })?.data ?? res as RoundTableRole[]
      if (Array.isArray(data) && data.length > 0) {
        setAvailableRoles(data)
        // 记录 AI 推荐的角色（后端用 selected 字段标记）
        const recommended = new Set<string>()
        data.forEach(r => {
          if (r.selected) recommended.add(r.key)
        })
        setRecommendedKeys(recommended)
        // 初始选中态 = AI 推荐 + HOST
        const initialSelected = new Set<string>(recommended)
        if (!initialSelected.has('HOST')) initialSelected.add('HOST')
        setSelectedKeys(initialSelected)
        setPhase('select')
      }
    } catch {
      setPhase('select')
      toast.error('获取角色列表失败')
    }
  }, [id])

  const toggleRole = useCallback((key: string) => {
    if (key === 'HOST') return
    setSelectedKeys(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  const startDiscussion = useCallback(async () => {
    if (selectedKeys.size < 4) {
      toast.warning('请至少选择4位嘉宾（含主持人）')
      return
    }

    const roleKeys = Array.from(selectedKeys)
    const roleConfigs = JSON.stringify(roleKeys.map(key => ({ key, selected: true })))
    try {
      const res = await createRoundTableSession(id, roleKeys, roleConfigs)
      const data = (res as { data?: RoundTableSession })?.data ?? res as RoundTableSession
      if (data.sessionId) {
        navigate(`/book/${bookId}/round-table/sessions/${data.sessionId}`)
      }
    } catch {
      toast.error('创建会话失败')
    }
  }, [id, bookId, selectedKeys, navigate])

  const loadHistorySession = useCallback((session: RoundTableSession) => {
    setHistoryOpen(false)
    navigate(`/book/${bookId}/round-table/sessions/${session.sessionId}`)
  }, [bookId, navigate])

  const handleDeleteSession = useCallback(async (sessionId: string) => {
    try {
      await deleteRoundTableSession(sessionId)
      setPastSessions(prev => prev.filter(s => s.sessionId !== sessionId))
      toast.success('删除成功')
    } catch {
      toast.error('删除失败')
    }
  }, [])

  // 按分组组织角色，按 ROLE_GROUP_ORDER 排序
  const groupedRoles = useMemo(() => {
    const groups: { key: string; label: string; roles: RoundTableRole[] }[] = []
    const bucket = new Map<string, RoundTableRole[]>()
    availableRoles.forEach(r => {
      const g = r.roleGroup || 'CORE'
      if (!bucket.has(g)) bucket.set(g, [])
      bucket.get(g)!.push(r)
    })
    ROLE_GROUP_ORDER.forEach(g => {
      const roles = bucket.get(g)
      if (roles && roles.length > 0) {
        groups.push({ key: g, label: ROLE_GROUP_NAMES[g] || g, roles })
      }
    })
    // 兜底：未知分组
    bucket.forEach((roles, g) => {
      if (!ROLE_GROUP_ORDER.includes(g)) {
        groups.push({ key: g, label: g, roles })
      }
    })
    return groups
  }, [availableRoles])

  const recommendedCount = recommendedKeys.size - (recommendedKeys.has('HOST') ? 1 : 0)

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
      {/* ============ Header ============ */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/30 bg-navbar/95 px-4 py-2.5 backdrop-blur-xl z-20 pt-safe-top">
        <button
          onClick={() => navigate(-1)}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold text-foreground">圆桌派</h1>
          <p className="text-xs text-muted-foreground truncate">选择嘉宾，开启讨论</p>
        </div>
        <button
          onClick={() => setHistoryOpen(true)}
          className="relative flex items-center gap-1.5 rounded-xl border border-border/40 bg-card px-2.5 py-1.5 text-xs font-medium hover:border-border/70 transition-colors"
          title="历史圆桌"
        >
          <History className="h-3.5 w-3.5" />
          历史
          {pastSessions.length > 0 && (
            <span className="flex h-4 min-w-4 items-center justify-center rounded-full bg-brand-500 px-1 text-[10px] font-bold text-white">
              {pastSessions.length}
            </span>
          )}
        </button>
      </header>

      {/* ============ 图书信息条带 ============ */}
      {bookInfo && (
        <div className="shrink-0 flex items-start gap-2.5 border-b border-border/30 bg-card/50 px-4 py-2">
          {bookInfo.coverUrl ? (
            <img src={bookInfo.coverUrl} alt={bookInfo.title} className="h-14 w-10 rounded shadow-sm object-cover shrink-0" />
          ) : (
            <div className="flex h-14 w-10 items-center justify-center rounded bg-muted shrink-0">
              <BookOpen className="h-4 w-4 text-muted-foreground" />
            </div>
          )}
          <div className="min-w-0 flex-1 pt-0.5">
            <p className="text-xs font-semibold truncate">{bookInfo.title}</p>
            <p className="text-[10px] text-muted-foreground truncate">{bookInfo.author}</p>
            {bookInfo.description && (
              <p className="line-clamp-2 text-[10px] leading-relaxed text-muted-foreground/70 mt-1">
                {bookInfo.description}
              </p>
            )}
          </div>
          {phase === 'select' && (
            <div className="flex items-center gap-1 text-[10px] text-brand-500 shrink-0 pt-0.5">
              <Sparkles className="h-3 w-3" />
              <span>AI 推荐 {recommendedCount} 位</span>
            </div>
          )}
        </div>
      )}

      {/* ============ 主体：角色选择 ============ */}
      <div className="flex-1 overflow-y-auto px-3 py-4">
        {phase === 'loading' ? (
          <div className="h-full flex flex-col items-center justify-center">
            <div className="relative w-28 h-28 mb-4">
              <div className="absolute inset-0 rounded-full border-2 border-primary/10 bg-primary/5" />
              <div className="absolute inset-[10%] rounded-full border border-primary/5 flex flex-col items-center justify-center gap-2">
                <Users className="h-6 w-6 animate-pulse text-primary" />
                <span className="text-xs text-muted-foreground">邀请嘉宾中...</span>
              </div>
            </div>
            <p className="text-sm text-muted-foreground">AI 正在根据书籍内容推荐讨论嘉宾</p>
          </div>
        ) : (
          <div className="max-w-3xl mx-auto space-y-5 pb-4">
            {/* 全选/清空快捷栏 */}
            <div className="flex items-center justify-between">
              <p className="text-xs text-muted-foreground">
                共 {availableRoles.length} 位嘉宾 · 已选 {selectedKeys.size} 人
              </p>
              <div className="flex items-center gap-2 text-xs">
                <button
                  onClick={() => {
                    setSelectedKeys(new Set(['HOST']))
                  }}
                  className="text-muted-foreground hover:text-foreground transition-colors"
                >
                  清空
                </button>
                <span className="text-border">·</span>
                <button
                  onClick={() => {
                    const next = new Set(['HOST'])
                    recommendedKeys.forEach(k => next.add(k))
                    setSelectedKeys(next)
                  }}
                  className="text-brand-500 hover:text-brand-600 transition-colors font-medium"
                >
                  恢复推荐
                </button>
              </div>
            </div>

            {/* 按分组渲染 */}
            {groupedRoles.map(group => (
              <div key={group.key}>
                <div className="flex items-center gap-2 mb-2 px-1">
                  <h3 className="text-xs font-bold text-foreground">{group.label}</h3>
                  <span className="text-[10px] text-muted-foreground/60">{group.roles.length}</span>
                  <div className="flex-1 h-px bg-border/30" />
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
                  {group.roles.map(role => (
                    <RoleCard
                      key={role.key}
                      role={role}
                      isSelected={selectedKeys.has(role.key)}
                      isHost={role.key === 'HOST'}
                      isRecommended={recommendedKeys.has(role.key)}
                      onToggle={() => toggleRole(role.key)}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ============ 底部常驻操作条 ============ */}
      <div className="shrink-0 border-t border-border/30 bg-navbar/95 backdrop-blur-xl px-4 py-2.5 pb-safe-bottom">
        <div className="max-w-3xl mx-auto flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-xs">
            <Users className="h-3.5 w-3.5 text-muted-foreground" />
            <span className="font-semibold text-foreground">{selectedKeys.size}</span>
            <span className="text-muted-foreground">/20</span>
          </div>
          <div className="flex-1" />
          <button
            onClick={() => loadRecommendedRoles(true)}
            disabled={phase === 'loading'}
            className="flex items-center gap-1.5 rounded-xl border border-border/40 bg-card px-3 py-2 text-xs font-medium text-muted-foreground hover:text-foreground hover:border-border/70 transition-colors active:scale-[0.98] disabled:opacity-50"
            title="重新邀请一组嘉宾"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${phase === 'loading' ? 'animate-spin' : ''}`} />
            重新邀请
          </button>
          <button
            onClick={startDiscussion}
            disabled={selectedKeys.size < 4 || phase === 'loading'}
            className="flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-5 py-2 text-xs font-semibold text-white shadow-md shadow-brand-400/20 transition-all active:scale-[0.98] disabled:opacity-50 disabled:shadow-none"
          >
            <Sparkles className="h-3.5 w-3.5" />
            开始讨论
          </button>
        </div>
      </div>

      {/* ============ 历史会话抽屉 ============ */}
      {historyOpen && (
        <div className="absolute inset-0 z-50 flex justify-end">
          <div
            className="absolute inset-0 bg-black/30 backdrop-blur-sm"
            onClick={() => setHistoryOpen(false)}
          />
          <div className="relative w-full max-w-sm h-full bg-background border-l border-border/40 shadow-2xl flex flex-col animate-in slide-in-from-right duration-200">
            <div className="shrink-0 flex items-center gap-3 border-b border-border/30 px-4 py-3">
              <History className="h-4 w-4 text-muted-foreground" />
              <div className="flex-1">
                <p className="text-sm font-bold">历史圆桌</p>
                <p className="text-[10px] text-muted-foreground">共 {pastSessions.length} 场讨论</p>
              </div>
              <button
                onClick={() => setHistoryOpen(false)}
                className="flex h-7 w-7 items-center justify-center rounded-lg hover:bg-muted transition-colors"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-3 space-y-2">
              {pastSessions.length > 0 ? (
                pastSessions.map(session => (
                  <SessionCard
                    key={session.sessionId}
                    session={session}
                    onLoad={() => loadHistorySession(session)}
                    onDelete={() => handleDeleteSession(session.sessionId)}
                  />
                ))
              ) : (
                <div className="h-full flex flex-col items-center justify-center text-center py-12">
                  <History className="h-8 w-8 text-muted-foreground/30 mb-2" />
                  <p className="text-xs text-muted-foreground/60">暂无圆桌讨论记录</p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
