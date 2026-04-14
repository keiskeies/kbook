import { useState, useEffect, useCallback } from 'react'
import { useAuthStore } from '@/store/auth'
import {
  getReviewStats,
  getPendingUsers,
  getUsersByStatus,
  approveUser,
  rejectUser,
  batchApprove,
  batchReject,
  banUser,
  unbanUser,
  sendInvitation,
  type AdminUser,
  type ReviewStats,
} from '@/api/admin'
import { ArrowLeft, Check, X, Unlock, RefreshCw, CheckCircle2, Mail, Copy, CheckCheck, Ban } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'

type StatusFilter = 'ALL' | 'PENDING' | 'APPROVED' | 'BANNED'

export default function AdminReviewPage() {
  const navigate = useNavigate()
  const { userInfo } = useAuthStore()

  const [activeTab, setActiveTab] = useState<StatusFilter>('PENDING')
  const [users, setUsers] = useState<AdminUser[]>([])
  const [stats, setStats] = useState<ReviewStats | null>(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())

  // 邀请功能
  const [showInviteModal, setShowInviteModal] = useState(false)
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteLoading, setInviteLoading] = useState(false)
  const [inviteResult, setInviteResult] = useState<{ email: string; inviteCode: string } | null>(null)
  const [copied, setCopied] = useState(false)

  const pageSize = 10

  // 加载统计
  const loadStats = useCallback(async () => {
    try {
      const data = await getReviewStats()
      setStats(data)
    } catch (err: any) {
      toast.error(err.message || '加载统计失败')
    }
  }, [])

  // 加载用户列表
  const loadUsers = useCallback(async () => {
    setLoading(true)
    try {
      const data = activeTab === 'ALL'
        ? await getUsersByStatus([], page, pageSize)
        : await getUsersByStatus([activeTab], page, pageSize)
      setUsers(data.list || [])
      setTotal(data.total)
    } catch (err: any) {
      toast.error(err.message || '加载列表失败')
    } finally {
      setLoading(false)
    }
  }, [activeTab, page])

  useEffect(() => {
    loadStats()
  }, [loadStats])

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
      const result = await batchApprove(Array.from(selectedIds))
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
      const result = await batchReject(Array.from(selectedIds))
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
      const result = await sendInvitation(inviteEmail.trim())
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
        return <span className="rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-700">待审核</span>
      case 'APPROVED':
        return <span className="rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">已通过</span>
      case 'BANNED':
        return <span className="rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700">已封禁</span>
      default:
        return null
    }
  }

  const totalPages = Math.ceil(total / pageSize)

  return (
    <div className="min-h-screen bg-background">
      {/* 顶部 */}
      <header className="sticky top-0 z-10 border-b bg-background/95 backdrop-blur-sm">
        <div className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-full bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-lg font-semibold">用户审核</h1>
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
            { label: '待审核', value: stats.PENDING, color: 'text-yellow-600', bg: 'bg-yellow-50' },
            { label: '已通过', value: stats.APPROVED, color: 'text-green-600', bg: 'bg-green-50' },
            { label: '已封禁', value: stats.BANNED, color: 'text-red-600', bg: 'bg-red-50' },
            { label: '总计', value: stats.TOTAL, color: 'text-blue-600', bg: 'bg-blue-50' },
          ].map((stat) => (
            <div key={stat.label} className={`rounded-xl ${stat.bg} p-3 text-center`}>
              <div className={`text-xl font-bold ${stat.color}`}>{stat.value}</div>
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
            onClick={() => { setActiveTab(tab); setPage(1) }}
            className={`flex-shrink-0 rounded-full px-4 py-1.5 text-sm font-medium transition-colors ${
              activeTab === tab
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground'
            }`}
          >
            {tab === 'ALL' ? '全部' : tab === 'PENDING' ? '待审核' : tab === 'APPROVED' ? '已通过' : '已封禁'}
          </button>
        ))}
      </div>

      {/* 批量操作 */}
      {selectedIds.size > 0 && (
        <div className="flex items-center gap-2 border-b px-4 py-2 bg-muted/50">
          <span className="text-sm text-muted-foreground">已选 {selectedIds.size} 项</span>
          <button
            onClick={handleBatchApprove}
            className="ml-auto flex items-center gap-1 rounded-lg bg-green-600 px-3 py-1.5 text-xs font-medium text-white"
          >
            <CheckCircle2 className="h-3 w-3" />
            批量通过
          </button>
          <button
            onClick={handleBatchReject}
            className="flex items-center gap-1 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-medium text-white"
          >
            <X className="h-3 w-3" />
            批量拒绝
          </button>
        </div>
      )}

      {/* 用户列表 */}
      <div className="px-4 py-2 space-y-2">
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
                      className="flex h-8 w-8 items-center justify-center rounded-full bg-green-100 text-green-600"
                      title="通过"
                    >
                      <Check className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleReject(user.id)}
                      className="flex h-8 w-8 items-center justify-center rounded-full bg-red-100 text-red-600"
                      title="拒绝"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </>
                )}
                {user.status === 'APPROVED' && (
                  <button
                    onClick={() => handleBan(user.id)}
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-red-100 text-red-600"
                    title="封禁"
                  >
                    <Ban className="h-4 w-4" />
                  </button>
                )}
                {user.status === 'BANNED' && (
                  <button
                    onClick={() => handleUnban(user.id)}
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-blue-100 text-blue-600"
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
                <h2 className="mb-4 text-lg font-semibold">邀请注册</h2>
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
                  <h2 className="text-lg font-semibold">邀请已发送</h2>
                  <button onClick={closeInviteModal} className="text-muted-foreground">
                    <X className="h-5 w-5" />
                  </button>
                </div>
                <div className="mb-4 rounded-xl bg-green-50 p-4 text-center">
                  <CheckCheck className="mx-auto mb-2 h-10 w-10 text-green-500" />
                  <p className="text-sm font-medium text-green-700">邀请邮件已发送到</p>
                  <p className="text-sm text-green-600">{inviteResult.email}</p>
                </div>
                <p className="mb-2 text-xs text-muted-foreground">邀请码</p>
                <div className="mb-4 flex items-center gap-2 rounded-lg bg-muted p-3">
                  <code className="flex-1 text-sm font-mono font-medium">{inviteResult.inviteCode}</code>
                  <button
                    onClick={handleCopyInviteLink}
                    className={`flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                      copied ? 'bg-green-100 text-green-700' : 'bg-primary/10 text-primary'
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
