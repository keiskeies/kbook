import { useState, useEffect, useRef } from 'react'
import { ShieldCheck, RefreshCw } from 'lucide-react'
import { toast } from 'sonner'
import request from '@/utils/request'

interface ClickCaptchaProps {
  open: boolean
  onSuccess: (captchaId: string) => void
  onCancel: () => void
}

interface CaptchaItem {
  index: number
  shape: string
  color: string
  size: string
  colorHex: string
  isTarget: boolean
}

interface CaptchaData {
  captchaId: string
  hint: string
  items: CaptchaItem[]
}

const SIZE_MAP: Record<string, number> = {
  small: 20,
  medium: 30,
  large: 40,
}

/** SVG 图形渲染 */
function Shape({ shape, color, size }: { shape: string; color: string; size: number }) {
  const s = size
  const half = s / 2
  switch (shape) {
    case 'circle':
      return <circle cx={half} cy={half} r={half - 2} fill={color} />
    case 'triangle':
      return <polygon points={`${half},2 ${s - 2},${s - 2} 2,${s - 2}`} fill={color} />
    case 'square':
      return <rect x={3} y={3} width={s - 6} height={s - 6} fill={color} rx={2} />
    case 'diamond':
      return <polygon points={`${half},2 ${s - 2},${half} ${half},${s - 2} 2,${half}`} fill={color} />
    case 'star': {
      const cx = half, cy = half, or_ = half - 2, ir = or_ * 0.4
      const pts = Array.from({ length: 10 }, (_, i) => {
        const r = i % 2 === 0 ? or_ : ir
        const angle = (Math.PI / 5) * i - Math.PI / 2
        return `${cx + r * Math.cos(angle)},${cy + r * Math.sin(angle)}`
      }).join(' ')
      return <polygon points={pts} fill={color} />
    }
    case 'heart': {
      const cx = half, cy = half * 0.85, w = half * 0.52
      return (
        <path
          d={`M${cx},${cy + w * 1.2} C${cx - w * 2},${cy - w * 0.5} ${cx - w * 0.5},${cy - w * 2} ${cx},${cy - w * 0.5} C${cx + w * 0.5},${cy - w * 2} ${cx + w * 2},${cy - w * 0.5} ${cx},${cy + w * 1.2}Z`}
          fill={color}
        />
      )
    }
    default:
      return <circle cx={half} cy={half} r={half - 2} fill={color} />
  }
}

export default function ClickCaptcha({ open, onSuccess, onCancel }: ClickCaptchaProps) {
  const [data, setData] = useState<CaptchaData | null>(null)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [status, setStatus] = useState<'loading' | 'ready' | 'verifying' | 'success' | 'fail'>('loading')
  const onSuccessRef = useRef(onSuccess)
  useEffect(() => { onSuccessRef.current = onSuccess }, [onSuccess])

  // 加载验证码
  const loadCaptcha = () => {
    setSelected(new Set())
    setStatus('loading')
    // request 拦截器在 code=0 时直接返回 data
    request.get('/captcha/click/generate')
      .then((res: any) => {
        setData(res)
        setStatus('ready')
      })
      .catch((err: any) => {
        toast.error(err.message || '获取验证码失败')
        setStatus('fail')
      })
  }

  useEffect(() => {
    if (open) loadCaptcha()
  }, [open])

  // 点击格子
  const handleClick = (index: number) => {
    if (status !== 'ready') return
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(index)) {
        next.delete(index)
      } else {
        next.add(index)
      }
      return next
    })
  }

  // 提交验证
  const handleVerify = () => {
    if (!data || selected.size === 0) return
    setStatus('verifying')

    request.post('/captcha/click/verify', {
      captchaId: data.captchaId,
      positions: Array.from(selected),
    })
      .then(() => {
        setStatus('success')
        setTimeout(() => onSuccessRef.current(data.captchaId), 500)
      })
      .catch((err: any) => {
        toast.error(err.message || '验证失败')
        setStatus('fail')
        setTimeout(loadCaptcha, 800)
      })
  }

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-xs rounded-2xl bg-background p-5 shadow-lg">
        {/* 标题 */}
        <div className="mb-3 flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-primary" />
          <h3 className="text-sm font-semibold">安全验证</h3>
        </div>

        {/* 提示 */}
        {data && (
          <div className="mb-3 rounded-lg bg-primary/10 px-3 py-2 text-center text-sm font-medium text-primary">
            请点击所有的 <span className="font-bold">{data.hint}</span>
          </div>
        )}

        {/* 图形网格 */}
        {data && (
          <div className="mb-5 grid grid-cols-3 gap-2">
            {data.items.map((item) => {
              const isSelected = selected.has(item.index)
              const sz = SIZE_MAP[item.size] || 30
              return (
                <button
                  key={item.index}
                  onClick={() => handleClick(item.index)}
                  className="flex aspect-square items-center justify-center rounded-xl border-2 transition-all duration-150"
                  style={{
                    borderColor: isSelected ? 'hsl(var(--primary))' : 'hsl(var(--border))',
                    backgroundColor: isSelected ? 'hsl(var(--primary) / 0.08)' : 'hsl(var(--card))',
                  }}
                  disabled={status === 'verifying' || status === 'success'}
                >
                  <svg width={sz} height={sz} viewBox={`0 0 ${sz} ${sz}`}>
                    <Shape shape={item.shape} color={item.colorHex || '#999'} size={sz} />
                  </svg>
                </button>
              )
            })}
          </div>
        )}

        {/* 加载状态 */}
        {status === 'loading' && (
          <div className="mb-5 flex h-[220px] items-center justify-center">
            <RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        )}

        {/* 底部操作 */}
        <div className="flex items-center justify-between gap-3 pt-2">
          <button 
            onClick={onCancel} 
            className="px-4 py-2.5 text-sm font-medium text-muted-foreground hover:text-foreground rounded-lg hover:bg-muted transition-colors"
          >
            取消
          </button>
          <div className="flex gap-2">
            <button
              onClick={loadCaptcha}
              disabled={status === 'verifying' || status === 'loading'}
              className="flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium text-muted-foreground hover:text-foreground disabled:opacity-50 rounded-lg hover:bg-muted transition-colors"
            >
              <RefreshCw className="h-4 w-4" />
              刷新
            </button>
            <button
              onClick={handleVerify}
              disabled={selected.size === 0 || status === 'verifying' || status === 'success'}
              className="rounded-lg bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 hover:opacity-90 transition-opacity min-w-[90px]"
            >
              {status === 'verifying' ? '验证中...' : status === 'success' ? '验证成功' : `确认 (${selected.size})`}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
