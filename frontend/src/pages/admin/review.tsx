import { useState, useEffect, useCallback, useRef } from 'react'
import { useAuthStore } from '@/store/auth'
import {
  getReviewStats,
  getUsersByStatus,
  searchUsers,
  approveUser,
  rejectUser,
  batchApprove,
  batchReject,
  banUser,
  unbanUser,
  sendInvitation,
  type AdminUser,
  type ReviewStats,
} from '@/api/adminUser'
import { ArrowLeft, Check, X, Unlock, RefreshCw, CheckCircle2, Mail, Copy, CheckCheck, Ban, Search } from 'lucide-react'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { toast } from 'sonner'

type StatusFilter = 'ALL' | 'PENDING' | 'APPROVED' | 'BANNED'

export default function AdminReviewPage() {
  const goBack = useGoBack()
  useAuthStore()

  const [activeTab, setActiveTab] = useState<StatusFilter>('PENDING')
  const [users, setUsers] = useState<AdminUser[]>([])
  const [stats, setStats] = useState<ReviewStats | null>(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())

  // 搜索
  const [searchKeyword, setSearchKeyword] = useState('')
  const [isSearching, setIsSearching] = useState(false)

  // 邀请功能
  const [showInviteModal, setShowInviteModal] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteLoading, setInviteLoading] = useState(false)
  const [inviteResult, setInviteResult] = useState<{ email: string; inviteCode: string } | null>(null)
  const [copied, setCopied] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const pageSize = 10

  // 格式化大数字（如 1000000 -> 100万）
  const formatCount = (n: number) => {
    if (n >= 100_000_000) return (n / 100_000_000).toFixed(1).replace(/\.0$/, '') + '亿'
    if (n >= 10_000) return (n / 10_000).toFixed(1).replace(/\.0$/, '') + '万'
    return String(n)
  }

  // 加载统计
  const loadStats = useCallback(async () => {
    try {
      const data = await getReviewStats() as any
      setStats(data)
    } catch (err: any) {
      toast.error(err.message || '加载统计失败')
    }
  }, [])

  // 加载用户列表
  const loadUsers = useCallback(async () => {
    setLoading(true)
    try {
      let data
      if (isSearching && searchKeyword.trim()) {
        const status = activeTab === 'ALL' ? undefined : activeTab
        data = await searchUsers(searchKeyword.trim(), status, page, pageSize) as any
      } else if (activeTab === 'ALL') {
        data = await getUsersByStatus([], page, pageSize) as any
      } else {
        data = await getUsersByStatus([activeTab], page, pageSize) as any
      }
      setUsers(data.list || [])
      setTotal(data.total)
    } catch (err: any) {
      toast.error(err.message || '加载列表失败')
    } finally {
      setLoading(false)
    }
  }, [activeTab, page, isSearching, searchKeyword])

  useEffect(() => {
    loadStats()
  }, [loadStats])

  // 搜索处理
  const handleSearch = () => {
    if (searchKeyword.trim()) {
      setIsSearching(true)
      setPage(1)
    } else {
      setIsSearching(false)
      setPage(1)
    }
  }

  const clearSearch = () => {
    setSearchKeyword('')
    setIsSearching(false)
    setPage(1)
  }

  useEffect(() => {
    setSelectedIds(new Set())
    loadUsers()
  }, [activeTab, page, loadUsers])

  // 审核操作
  const handleApprove = async (userId: number) => {
    try {
      await approveUser(userId)
      toast.success('已通过审核')
      loadUsers()
      loadStats()
    } catch (err: any) {
      toast.error(err.message || '操作失败')
    }
  }

  const handleReject = async (userId: number) => {
    try {
      await rejectUser(userId)
      toast.success('已拒绝审核')
      loadUsers()
      loadStats()
    } catch (err: any) {
      toast.error(err.message || '操作失败')
    }
  }

  const handleUnban = async (userId: number) => {
    try {
      await unbanUser(userId)
      toast.success('已解封')
      loadUsers()
      loadStats()
    } catch (err: any) {
      toast.error(err.message || '操作失败')
    }
  }

  const handleBan = async (userId: number) => {
    try {
      await banUser(userId)
      toast.success('已封禁')
      loadUsers()
      loadStats()
    } catch (err: any) {
      toast.error(err.message || '操作失败')
    }
  }

  const handleBatchApprove = async () => {
    if (selectedIds.size === 0) return
    try {
      const result = await batchApprove(Array.from(selectedIds)) as any
      toast.success(`已通过 ${result.count} 个用户`)
      setSelectedIds(new Set())
      loadUsers()
      loadStats()
    } catch (err: any) {
      toast.error(err.message || '批量操作失败')
    }
  }

  const handleBatchReject = async () => {
    if (selectedIds.size === 0) return
    try {
      const result = await batchReject(Array.from(selectedIds)) as any
      toast.success(`已拒绝 ${result.count} 个用户`)
      setSelectedIds(new Set())
      loadUsers()
      loadStats()
    } catch (err: any) {
      toast.error(err.message || '批量操作失败')
    }
  }

  // 发送邀请
  const handleSendInvite = async () => {
    if (!inviteEmail.trim()) {
      toast.error('请输入邮箱地址')
      return
    }
    setInviteLoading(true)
    try {
      const result = await sendInvitation(inviteEmail.trim()) as any
      setInviteResult(result)
      toast.success('邀请邮件已发送')
    } catch (err: any) {
      toast.error(err.message || '发送失败')
    } finally {
      setInviteLoading(false)
    }
  }

  // 复制邀请链接
  const handleCopyInviteLink = async () => {
    if (!inviteResult) return
    const inviteLink = `${window.location.origin}/invite/${inviteResult.inviteCode}`
    try {
      await navigator.clipboard.writeText(inviteLink)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast.error('复制失败')
    }
  }

  // 关闭邀请弹窗
  const closeInviteModal = () => {
    setShowInviteModal(false)
    setInviteEmail('')
    setInviteResult(null)
    setCopied(false)
  }

  const toggleSelect = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const statusBadge = (status: string) => {
    switch (status) {
      case 'PENDING':
        return <span className="rounded-full bg-warning/10 px-2 py-0.5 text-xs font-medium text-warning">待审核</span>
      case 'APPROVED':
        return <span className="rounded-full bg-success/10 px-2 py-0.5 text-xs font-medium text-success">已通过</span>
      case 'BANNED':
        return <span className="rounded-full bg-danger/10 px-2 py-0.5 text-xs font-medium text-danger">已封禁</span>
      default:
        return null
    }
  }

  const totalPages = Math.ceil(total / pageSize)

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background">
      {/* 顶部 */}
      <header className="shrink-0 z-10 border-b border-border/50 bg-navbar/95 backdrop-blur-xl">
        <div className="flex items-center gap-3 px-4 md:px-6 lg:px-8 py-3">
          <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-h3 font-semibold">用户审核</h1>
          <button
            onClick={() => setShowInviteModal(true)}
            className="ml-auto flex items-center gap-1.5 rounded-full bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground"
          >
            <Mail className="h-3.5 w-3.5" />
            邀请注册
          </button>
          <button onClick={() => { loadStats(); loadUsers() }} className="flex h-9 w-9 items-center justify-center rounded-full bg-muted">
            <RefreshCw className="h-4 w-4 text-muted-foreground" />
          </button>
        </div>
      </header>

      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-4 gap-2 px-4 py-3">
          {[
            { label: '待审核', value: stats.PENDING, color: 'text-warning', bg: 'bg-warning/10' },
            { label: '已通过', value: stats.APPROVED, color: 'text-success', bg: 'bg-success/10' },
            { label: '已封禁', value: stats.BANNED, color: 'text-danger', bg: 'bg-danger/10' },
            { label: '总计', value: stats.TOTAL, color: 'text-info', bg: 'bg-info/10' },
          ].map((stat) => (
            <div key={stat.label} className={`rounded-xl ${stat.bg} p-3 text-center`}>
              <div className={`text-xl font-bold ${stat.color}`}>{formatCount(stat.value)}</div>
              <div className="text-xs text-muted-foreground">{stat.label}</div>
            </div>
          ))}
        </div>
      )}

      {/* 状态筛选 Tab */}
      <div className="flex gap-1 overflow-x-auto px-4 pb-2">
        {(['PENDING', 'APPROVED', 'BANNED', 'ALL'] as StatusFilter[]).map((tab) => (
          <button
            key={tab}
            onClick={() => { setActiveTab(tab); setPage(1); if (isSearching) loadUsers() }}
            className={`shrink-0 whitespace-nowrap rounded-full px-4 py-1.5 text-sm font-medium transition-colors ${
              activeTab === tab
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground'
            }`}
          >
            {tab === 'ALL' ? '全部' : tab === 'PENDING' ? '待审核' : tab === 'APPROVED' ? '已通过' : '已封禁'}
          </button>
        ))}
      </div>

      {/* 搜索框 */}
      <div className="flex items-center gap-2 px-4 pb-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            placeholder="搜索用户名或邮箱"
            className="w-full rounded-lg border bg-background pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
          />
        </div>
        <button
          onClick={handleSearch}
          className="rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground"
        >
          搜索
        </button>
        {isSearching && (
          <button
            onClick={clearSearch}
            className="rounded-lg bg-muted px-3 py-2 text-sm font-medium text-muted-foreground"
          >
            清除
          </button>
        )}
      </div>

      {/* 搜索提示 */}
      {isSearching && (
        <div className="px-4 pb-1 text-xs text-muted-foreground">
          搜索: "{searchKeyword}" {activeTab !== 'ALL' ? `(筛选: ${activeTab === 'PENDING' ? '待审核' : activeTab === 'APPROVED' ? '已通过' : '已封禁'})` : ''}
        </div>
      )}

      {/* 批量操作 */}
      {selectedIds.size > 0 && (
        <div className="flex items-center gap-2 border-b px-4 py-2 bg-muted">
          <span className="text-sm text-muted-foreground">已选 {selectedIds.size} 项</span>
          <button
              onClick={handleBatchApprove}
              className="ml-auto flex items-center gap-1 rounded-lg bg-success px-3 py-1.5 text-xs font-medium text-white"
            >
            <CheckCircle2 className="h-3 w-3" />
            批量通过
          </button>
          <button
              onClick={handleBatchReject}
              className="flex items-center gap-1 rounded-lg bg-danger px-3 py-1.5 text-xs font-medium text-white"
            >
            <X className="h-3 w-3" />
            批量拒绝
          </button>
        </div>
      )}

      {/* 用户列表 */}
      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 py-2 space-y-2">
        {loading ? (
          <div className="py-12 text-center text-sm text-muted-foreground">加载中...</div>
        ) : users.length === 0 ? (
          <div className="py-12 text-center text-sm text-muted-foreground">暂无数据</div>
        ) : (
          users.map((user) => (
            <div key={user.id} className="flex items-center gap-3 rounded-xl bg-card p-3 shadow-xs">
              {/* 选择框（仅待审核显示） */}
              {activeTab === 'PENDING' && (
                <button
                  onClick={() => toggleSelect(user.id)}
                  className={`flex h-5 w-5 flex-shrink-0 items-center justify-center rounded border ${
                    selectedIds.has(user.id)
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-muted-foreground/30'
                  }`}
                >
                  {selectedIds.has(user.id) && <Check className="h-3 w-3" />}
                </button>
              )}

              {/* 头像 */}
              <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-primary/10">
                <span className="text-sm font-bold text-primary">
                  {user.nickname?.[0] || 'U'}
                </span>
              </div>

              {/* 信息 */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium truncate">{user.nickname}</span>
                  {statusBadge(user.status)}
                </div>
                <p className="text-xs text-muted-foreground truncate">{user.email}</p>
                <p className="text-xs text-muted-foreground">
                  {new Date(user.createdAt).toLocaleDateString()}
                </p>
              </div>

              {/* 操作按钮 */}
              <div className="flex flex-shrink-0 gap-1.5">
                {user.status === 'PENDING' && (
                  <>
                    <button
                      onClick={() => handleApprove(user.id)}
                      className="flex h-8 w-8 items-center justify-center rounded-full bg-success/10 text-success"
                      title="通过"
                    >
                      <Check className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleReject(user.id)}
                      className="flex h-8 w-8 items-center justify-center rounded-full bg-danger/10 text-danger"
                      title="拒绝"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </>
                )}
                {user.status === 'APPROVED' && (
                  <button
                    onClick={() => handleBan(user.id)}
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-danger/10 text-danger"
                    title="封禁"
                  >
                    <Ban className="h-4 w-4" />
                  </button>
                )}
                {user.status === 'BANNED' && (
                  <button
                    onClick={() => handleUnban(user.id)}
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-info/10 text-info"
                    title="解封"
                  >
                    <Unlock className="h-4 w-4" />
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 py-4">
          <button
            onClick={() => setPage((p) => Math.max(1, p - 1))}
            disabled={page <= 1}
            className="rounded-lg bg-muted px-3 py-1.5 text-sm disabled:opacity-50"
          >
            上一页
          </button>
          <span className="text-sm text-muted-foreground">
            {page} / {totalPages}
          </span>
          <button
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            disabled={page >= totalPages}
            className="rounded-lg bg-muted px-3 py-1.5 text-sm disabled:opacity-50"
          >
            下一页
          </button>
        </div>
      )}

      {/* 邀请注册弹窗 */}
      {showInviteModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-sm rounded-2xl bg-background p-5 shadow-lg">
            {!inviteResult ? (
              <>
                <h2 className="mb-4 text-h3 font-semibold">邀请注册</h2>
                <p className="mb-4 text-sm text-muted-foreground">
                  输入邮箱地址，向用户发送邀请链接。
                </p>
                <input
                  type="email"
                  value={inviteEmail}
                  onChange={(e) => setInviteEmail(e.target.value)}
                  placeholder="请输入邮箱地址"
                  className="mb-4 w-full rounded-lg border bg-background px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  onKeyDown={(e) => e.key === 'Enter' && handleSendInvite()}
                />
                <div className="flex gap-2">
                  <button
                    onClick={closeInviteModal}
                    className="flex-1 rounded-lg border bg-muted px-4 py-2.5 text-sm font-medium"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSendInvite}
                    disabled={inviteLoading}
                    className="flex-1 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground disabled:opacity-50"
                  >
                    {inviteLoading ? '发送中...' : '发送邀请'}
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="mb-4 flex items-center justify-between">
                  <h2 className="text-h3 font-semibold">邀请已发送</h2>
                  <button onClick={closeInviteModal} className="text-muted-foreground">
                    <X className="h-5 w-5" />
                  </button>
                </div>
                <div className="mb-4 rounded-xl bg-success/10 p-4 text-center">
                  <CheckCheck className="mx-auto mb-2 h-10 w-10 text-success" />
                  <p className="text-sm font-medium text-success">邀请邮件已发送到</p>
                  <p className="text-sm text-success">{inviteResult.email}</p>
                </div>
                <p className="mb-2 text-xs text-muted-foreground">邀请码</p>
                <div className="mb-4 flex items-center gap-2 rounded-lg bg-muted p-3">
                  <code className="flex-1 text-sm font-mono font-medium">{inviteResult.inviteCode}</code>
                  <button
                    onClick={handleCopyInviteLink}
                    className={`flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                      copied ? 'bg-success/10 text-success' : 'bg-primary/10 text-primary'
                    }`}
                  >
                    {copied ? (
                      <>
                        <CheckCheck className="h-3.5 w-3.5" />
                        已复制
                      </>
                    ) : (
                      <>
                        <Copy className="h-3.5 w-3.5" />
                        复制链接
                      </>
                    )}
                  </button>
                </div>
                <button
                  onClick={closeInviteModal}
                  className="w-full rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground"
                >
                  完成
                </button>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
