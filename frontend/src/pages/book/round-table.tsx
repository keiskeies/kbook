import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Users, RefreshCw, History, Trash2, BookOpen, Sparkles,
} from 'lucide-react'
import {
  getRoundTableRoles, createRoundTableSession, getRoundTableSessions, deleteRoundTableSession,
} from '@/api/roundTable'
import { getBook } from '@/api/book'
import type { RoundTableRole, RoundTableSession } from '@/types/roundTable'
import type { Book } from '@/types/book'
import {
  ROLE_COLORS, ROLE_TITLES, ROLE_ICONS, describePersonality,
  hexToRgba,
} from '@/types/roundTable'
import { toast } from 'sonner'

function RoleCard({
  role,
  isSelected,
  isHost,
  onToggle,
}: {
  role: RoundTableRole
  isSelected: boolean
  isHost: boolean
  onToggle: () => void
}) {
  const color = ROLE_COLORS[role.key] || '#6B655C'
  const traits = describePersonality(role)

  return (
    <button
      onClick={onToggle}
      disabled={isHost}
      className={`group relative flex flex-col items-center gap-2 rounded-2xl border p-4 transition-all duration-300 ${
        isSelected
          ? 'border-[var(--role-color)]/40 bg-[var(--role-color)]/[0.04] shadow-sm'
          : 'border-border/40 bg-card hover:border-border/60 hover:shadow-sm'
      } ${isHost ? 'cursor-default' : 'cursor-pointer active:scale-[0.97]'}`}
      style={{ '--role-color': color } as React.CSSProperties}
    >
      {isSelected && !isHost && (
        <span className="absolute -top-1.5 -right-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-[var(--role-color)] text-xs text-white shadow-sm">
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
        </span>
      )}
      {isHost && (
        <span className="absolute -top-1.5 -right-1.5 rounded-full bg-primary px-2 py-0.5 text-xs font-bold text-primary-foreground shadow-sm">
          必选
        </span>
      )}

      <div
        className="flex h-12 w-12 items-center justify-center rounded-full text-xl transition-transform duration-300 group-hover:scale-105"
        style={{
          backgroundColor: hexToRgba(color, 0.1),
          border: `1.5px solid ${hexToRgba(color, 0.25)}`,
        }}
      >
        {ROLE_ICONS[role.key] || '👤'}
      </div>

      <div className="text-center">
        <span className="block text-xs font-bold" style={{ color }}>{role.name}</span>
        <span className="block text-xs text-muted-foreground mt-0.5">{ROLE_TITLES[role.key]}</span>
      </div>

      {traits.length > 0 && (
        <div className="flex flex-wrap gap-1 justify-center">
          {traits.slice(0, 3).map(t => (
            <span
              key={t}
              className="rounded-full px-1.5 py-0.5 text-xs font-medium"
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
    <div className="flex items-center gap-3 rounded-2xl border border-border/50 bg-card p-3 hover:border-border/60 transition-all duration-200">
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
          <span className="text-xs text-muted-foreground">{dateStr}</span>
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
  const [pastSessions, setPastSessions] = useState<RoundTableSession[]>([])
  const [bookInfo, setBookInfo] = useState<Book | null>(null)

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
        const initialSelected = new Set<string>()
        data.forEach(r => {
          if (r.selected) initialSelected.add(r.key)
        })
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

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
      <header className="shrink-0 flex items-center gap-3 border-b border-border/30 bg-navbar/95 px-4 py-2.5 backdrop-blur-xl z-20 pt-safe-top">
        <button
          onClick={() => navigate(-1)}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold text-foreground">圆桌派</h1>
          <p className="text-xs text-muted-foreground truncate">
            选择嘉宾，开启讨论
          </p>
        </div>
      </header>

      <div className="flex flex-1 flex-col items-center px-4 py-6 overflow-y-auto">
        {/* 已有圆桌 */}
        <div className="w-full max-w-2xl mb-6">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-muted-foreground">
            <History className="h-4 w-4" />
            已有圆桌 ({pastSessions.length})
          </h3>
          {pastSessions.length > 0 ? (
            <div className="space-y-2">
              {pastSessions.map(session => (
                <SessionCard
                  key={session.sessionId}
                  session={session}
                  onLoad={() => loadHistorySession(session)}
                  onDelete={() => handleDeleteSession(session.sessionId)}
                />
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground/50 text-center py-4">暂无圆桌讨论记录</p>
          )}
        </div>

        <div className="mb-5 text-center">
          <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-brand-100 to-brand-200 mb-3 shadow-sm">
            <Users className="h-7 w-7 text-brand-500" />
          </div>
          <h2 className="text-lg font-bold text-foreground">选择讨论嘉宾</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            AI 根据《{bookInfo?.title || '本书'}》推荐了 {availableRoles.length} 位嘉宾，已为你勾选 {selectedKeys.size - 1} 位
          </p>
        </div>

        {bookInfo && (
          <div className="w-full max-w-2xl mb-5 flex items-center gap-3 rounded-2xl border border-border/40 bg-card p-3 shadow-sm">
            {bookInfo.coverUrl ? (
              <img src={bookInfo.coverUrl} alt={bookInfo.title} className="h-14 w-10 rounded-md object-cover shadow-sm" />
            ) : (
              <div className="flex h-14 w-10 items-center justify-center rounded-md bg-muted">
                <BookOpen className="h-5 w-5 text-muted-foreground" />
              </div>
            )}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold truncate">{bookInfo.title}</p>
              <p className="text-xs text-muted-foreground">{bookInfo.author}</p>
            </div>
            <Sparkles className="h-4 w-4 text-brand-400 shrink-0" />
          </div>
        )}

        {/* 角色选择区 — 局部加载中 */}
        {phase === 'loading' ? (
          <div className="w-full max-w-2xl flex flex-col items-center justify-center py-12">
            <div className="relative w-32 h-32 mb-4">
              <div className="absolute inset-0 rounded-full border-2 border-primary/10 bg-primary/5" />
              <div className="absolute inset-[10%] rounded-full border border-primary/5 flex flex-col items-center justify-center gap-2">
                <Users className="h-6 w-6 animate-pulse text-primary" />
                <span className="text-xs text-muted-foreground">邀请嘉宾中...</span>
              </div>
            </div>
            <p className="text-sm text-muted-foreground">AI 正在根据书籍内容推荐讨论嘉宾</p>
          </div>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2.5 w-full max-w-2xl">
            {availableRoles.map(role => (
              <RoleCard
                key={role.key}
                role={role}
                isSelected={selectedKeys.has(role.key)}
                isHost={role.key === 'HOST'}
                onToggle={() => toggleRole(role.key)}
              />
            ))}
          </div>
        )}

        <div className="mt-6 flex items-center gap-3">
          <button
            onClick={() => loadRecommendedRoles(true)}
            disabled={phase === 'loading'}
            className="flex items-center gap-1.5 rounded-2xl border border-border/40 bg-card px-4 py-3 text-sm font-medium text-muted-foreground hover:text-foreground hover:border-border/60 transition-all duration-200 active:scale-[0.97] disabled:opacity-50"
            title="重新邀请一组嘉宾"
          >
            <RefreshCw className={`h-4 w-4 ${phase === 'loading' ? 'animate-spin' : ''}`} />
            重新邀请
          </button>
          <button
            onClick={startDiscussion}
            disabled={selectedKeys.size < 4 || phase === 'loading'}
            className="flex items-center gap-2 rounded-2xl bg-gradient-to-r from-brand-400 to-brand-500 px-8 py-3 text-sm font-semibold text-white shadow-lg shadow-brand-400/20 transition-all duration-200 hover:shadow-xl hover:shadow-brand-400/25 active:scale-[0.97] disabled:opacity-50 disabled:shadow-none"
          >
            <Sparkles className="h-4 w-4" />
            开始讨论 ({selectedKeys.size}人)
          </button>
        </div>

      </div>
    </div>
  )
}