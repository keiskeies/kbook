import { useEffect, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Lightbulb, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { getBookProfile, type DashboardQuery, type ReaderBasis } from '@/api/adminDashboard'
import { ROUTES } from '@/constants'
import { BookProfileSection } from './BookProfileSection'
import { AudienceFitBlock } from './AudienceFitBlock'
import { ProfileTrendBlock } from './ProfileTrendBlock'

const BASIS_OPTIONS: { value: ReaderBasis; label: string }[] = [
  { value: 'TOUCH', label: '触达' },
  { value: 'READ', label: '阅读' },
  { value: 'DEEP', label: '互动' },
]

interface Props {
  book: { id: number; title: string } | null
  /** 时间区间继承主看板 */
  query: DashboardQuery
  rangeLabel: string
  onClose: () => void
}

export function BookProfileDrawer({ book, query, rangeLabel, onClose }: Props) {
  const [basis, setBasis] = useState<ReaderBasis>('READ')

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin', 'book-profile', book?.id, basis, query],
    queryFn: () => getBookProfile({ ...query, bookId: book?.id as number, basis }),
    enabled: !!book,
    placeholderData: keepPreviousData,
  })

  useEffect(() => {
    if (!book) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [book, onClose])

  if (!book) return null

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="读者画像">
      <button className="absolute inset-0 bg-black/40" aria-label="关闭画像" onClick={onClose} />
      <aside className="relative flex h-full w-full flex-col border-l bg-background md:max-w-[480px]">
        <header className="flex shrink-0 items-start gap-3 border-b px-4 py-3">
          <div className="min-w-0 flex-1">
            <p className="text-caption text-muted-foreground">读者画像 · {rangeLabel}</p>
            <h2 className="truncate text-h4">{book.title}</h2>
          </div>
          <button onClick={onClose} className="rounded-full p-1.5 text-muted-foreground active:bg-muted" aria-label="关闭">
            <X className="h-5 w-5" />
          </button>
        </header>

        <div className="flex shrink-0 gap-1 border-b px-4 py-2">
          {BASIS_OPTIONS.map(option => (
            <button
              key={option.value}
              onClick={() => setBasis(option.value)}
              aria-pressed={basis === option.value}
              className={`rounded-full px-3 py-1 text-caption transition-colors
                ${basis === option.value ? 'bg-primary/15 text-primary font-medium' : 'text-muted-foreground active:bg-muted'}`}
            >
              {option.label}
            </button>
          ))}
        </div>

        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-3">
          {isLoading && <p className="py-10 text-center text-sm text-muted-foreground">正在统计读者画像…</p>}

          {isError && !isLoading && (
            <div className="space-y-2 rounded-lg bg-muted/50 p-4 text-center">
              <p className="text-sm text-muted-foreground">{error instanceof Error ? error.message : '加载失败'}</p>
              <button onClick={() => refetch()} className="rounded-full bg-primary/15 px-3 py-1 text-caption text-primary">
                重试
              </button>
            </div>
          )}

          {data && (
            <>
              <div className="rounded-lg bg-muted/50 p-3">
                <p className="text-sm font-medium">
                  {data.readers} 位读者
                  <span className="ml-2 text-caption font-normal text-muted-foreground">
                    {data.profiledUsers} 位填写了画像
                  </span>
                </p>
                <p className="mt-1 text-[10px] leading-relaxed text-muted-foreground">{data.basisNote}</p>
              </div>

              {data.sampleProtected ? (
                <div className="rounded-lg border border-dashed p-4 text-center">
                  <p className="text-sm">样本过小，出于隐私不提供画像分布</p>
                  <p className="mt-1 text-caption text-muted-foreground">
                    当前 {data.readers} 位读者，阈值为 {data.minCohortSize} 位
                  </p>
                  {/* 设计文档 4.1：单书分布被小样本吃掉时，人群选书是样本天然聚合的另一条路 */}
                  <Link to={ROUTES.ADMIN_PROFILE_BOOKS}
                    className="mt-3 inline-block rounded-full bg-primary/15 px-3 py-1 text-caption text-primary">
                    改用「人群选书」看这类读者读什么
                  </Link>
                </div>
              ) : (
                <>
                  {data.highlights.length > 0 && (
                    <div className="rounded-lg bg-primary/5 p-3">
                      <div className="mb-1.5 flex items-center gap-1.5 text-primary">
                        <Lightbulb className="h-3.5 w-3.5" />
                        <span className="text-caption font-medium">关键结论</span>
                      </div>
                      <ul className="space-y-1">
                        {data.highlights.map(text => (
                          <li key={text} className="text-caption leading-relaxed">{text}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <AudienceFitBlock bookId={book?.id as number} query={query} basis={basis} />

                  <ProfileTrendBlock bookId={book?.id as number} basis={basis} />

                  {data.dimensions.map(dimension => (
                    <BookProfileSection
                      key={`${book?.id}:${dimension.key}`}
                      dimension={dimension}
                      storageKey={`admin.profile.${book?.id}.${dimension.key}`}
                    />
                  ))}

                  <p className="pb-2 text-[10px] leading-relaxed text-muted-foreground">
                    占比分母为该维度填写了有效值的人数；多值维度（职业、孩子年龄段）一人可进多桶，占比之和可超过 100%。
                    读者池只统计审核通过的账号，画像字段取当前值。
                    <Link to={ROUTES.ADMIN_PROFILE_BOOKS} className="ml-1 text-primary underline-offset-2 active:underline">
                      按人群选书 →
                    </Link>
                  </p>
                </>
              )}
            </>
          )}
        </div>
      </aside>
    </div>
  )
}
