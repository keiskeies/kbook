import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ArrowLeft, ChevronLeft, Info, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { ROUTES } from '@/constants'
import {
  getProfileBooks,
  getProfileDimensions,
  type DashboardRange,
  type ProfileSortBy,
  type ReaderBasis,
} from '@/api/adminDashboard'

const RANGE_OPTIONS: { value: DashboardRange; label: string }[] = [
  { value: 7, label: '7 天' },
  { value: 30, label: '30 天' },
  { value: 90, label: '90 天' },
  { value: 0, label: '全部' },
]

const BASIS_OPTIONS: { value: ReaderBasis; label: string }[] = [
  { value: 'READ', label: '翻开的' },
  { value: 'TOUCH', label: '触达的' },
  { value: 'DEEP', label: '深度互动的' },
]

const SORT_OPTIONS: { value: ProfileSortBy; label: string }[] = [
  { value: 'READERS', label: '按人数' },
  { value: 'LIFT', label: '按集中度' },
]

const LIMIT_OPTIONS = [10, 20, 50]

function Chips<T>({ options, value, onChange, label }: {
  options: { value: T; label: string }[]
  value: T
  onChange: (next: T) => void
  label: string
}) {
  return (
    <div>
      <p className="mb-1.5 text-[10px] text-muted-foreground">{label}</p>
      <div className="flex flex-wrap gap-1">
        {options.map(option => (
          <button
            key={String(option.value)}
            onClick={() => onChange(option.value)}
            aria-pressed={value === option.value}
            className={`rounded-full px-3 py-1 text-caption transition-colors
              ${value === option.value ? 'bg-primary/15 font-medium text-primary' : 'text-muted-foreground active:bg-muted'}`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}

function percent(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(0)}%`
}

export default function AdminProfileBooksPage() {
  const goBack = useGoBack()
  const [dimKey, setDimKey] = useState('')
  const [bucketValue, setBucketValue] = useState('')
  const [days, setDays] = useState<DashboardRange>(0)
  const [basis, setBasis] = useState<ReaderBasis>('READ')
  const [sortBy, setSortBy] = useState<ProfileSortBy>('READERS')
  const [limit, setLimit] = useState(10)

  const { data: dimensions, isLoading: dimsLoading, isError: dimsError, error: dimsQueryError } = useQuery({
    queryKey: ['admin', 'profile-dimensions'],
    queryFn: getProfileDimensions,
  })

  const queryable = (dimensions ?? []).filter(dimension => dimension.queryable)
  const skipped = (dimensions ?? []).filter(dimension => !dimension.queryable)
  // 默认落在「真有可反查取值」的维度上：只有「其他」的维度一进来就是空列表
  const firstSelectable = queryable.find(dimension => dimension.buckets.some(bucket => bucket.value !== null))
  const activeDim = queryable.find(dimension => dimension.key === dimKey) ?? firstSelectable ?? queryable[0]
  const bucketOptions = (activeDim?.buckets ?? []).filter(bucket => bucket.value !== null)
  const activeValue = bucketOptions.some(bucket => bucket.value === bucketValue)
    ? bucketValue
    : (bucketOptions[0]?.value ?? '')

  const {
    data: books,
    isFetching,
    isError,
    error,
  } = useQuery({
    queryKey: ['admin', 'profile-books', activeDim?.key, activeValue, days, basis, sortBy, limit],
    queryFn: () => getProfileBooks({
      dim: activeDim?.key as string,
      value: activeValue,
      days,
      basis,
      sortBy,
      limit,
    }),
    enabled: !!activeDim && !!activeValue,
    placeholderData: keepPreviousData,
  })

  const selectedBucket = bucketOptions.find(bucket => bucket.value === activeValue)

  return (
    <div className="mx-auto max-w-3xl px-4 py-4">
      <header className="mb-4 flex items-center gap-2">
        <button onClick={goBack} aria-label="返回" className="rounded-full p-1.5 text-muted-foreground active:bg-muted">
          <ChevronLeft className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-h4">人群选书</h1>
          <p className="text-caption text-muted-foreground">选定一类读者，看他们读得最多或最集中的书</p>
        </div>
        <Link to={ROUTES.ADMIN_DASHBOARD} className="shrink-0 text-caption text-primary">
          <ArrowLeft className="mr-1 inline h-3.5 w-3.5" />
          回看板
        </Link>
      </header>

      {dimsLoading && <p className="py-10 text-center text-sm text-muted-foreground">正在读取全站基线…</p>}

      {dimsError && (
        <p className="py-10 text-center text-sm text-danger">
          {dimsQueryError instanceof Error ? dimsQueryError.message : '基线加载失败'}
        </p>
      )}

      {!dimsLoading && !dimsError && queryable.length === 0 && (
        <p className="py-10 text-center text-sm text-muted-foreground">全站有效样本不足，暂无可反查的维度</p>
      )}

      {activeDim && (
        <div className="space-y-3 rounded-lg bg-muted/50 p-3">
          <Chips
            label="维度"
            value={activeDim.key}
            onChange={key => {
              setDimKey(key)
              setBucketValue('')
            }}
            options={queryable.map(dimension => ({ value: dimension.key, label: dimension.label }))}
          />
          <Chips
            label="分桶"
            value={activeValue}
            onChange={setBucketValue}
            options={bucketOptions.map(bucket => ({
              value: bucket.value as string,
              label: `${bucket.label} · ${bucket.count}`,
            }))}
          />
          <div className="flex flex-wrap gap-x-6 gap-y-3 border-t pt-3">
            <Chips label="时间" value={days} onChange={setDays} options={RANGE_OPTIONS} />
            <Chips label="读者口径" value={basis} onChange={setBasis} options={BASIS_OPTIONS} />
            <Chips label="排序" value={sortBy} onChange={setSortBy} options={SORT_OPTIONS} />
            <Chips label="榜单长度" value={limit} onChange={setLimit} options={LIMIT_OPTIONS.map(n => ({ value: n, label: String(n) }))} />
          </div>
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {activeDim.label}填写率 {percent(activeDim.coverage)}；
            {selectedBucket ? `分桶「${selectedBucket.label}」全站 ${selectedBucket.count} 人，占有效样本 ${percent(selectedBucket.ratio)}` : '请选择分桶'}。
            {sortBy === 'LIFT' ? '集中度榜' : '人数榜'}只收录该书在该桶读者数达到隐私阈值的条目，小样本不进榜。
          </p>
        </div>
      )}

      <div className="mt-4 space-y-2" role="list" aria-label="人群选书榜单">
        {activeDim && bucketOptions.length === 0 && (
          <div className="rounded-lg border border-dashed p-6 text-center">
            <Users className="mx-auto mb-2 h-6 w-6 text-muted-foreground" />
            <p className="text-sm">「{activeDim.label}」没有一个够数的可反查取值</p>
            <p className="mt-1 text-caption text-muted-foreground">
              全站属于某个取值的读者不足分桶下限时会被并进「其他」，而「其他」不参与反查；换一个维度试试，阈值本身在后台配置里调
            </p>
          </div>
        )}

        {books?.length === 0 && !isFetching && (
          <div className="rounded-lg border border-dashed p-6 text-center">
            <Users className="mx-auto mb-2 h-6 w-6 text-muted-foreground" />
            <p className="text-sm">这个人群在当前区间内没有达到出榜人数要求的书</p>
            <p className="mt-1 text-caption text-muted-foreground">
              该书在该桶里的读者数低于隐私阈值时不进榜（与排序方式无关）；换「全部」时间或「触达的」口径可放宽样本，阈值本身在后台配置里调
            </p>
          </div>
        )}

        {isError && (
          <p className="py-6 text-center text-sm text-danger">{error instanceof Error ? error.message : '加载失败'}</p>
        )}

        {books?.map((book, index) => (
          <div key={book.bookId} role="listitem" className="flex items-center gap-3 rounded-lg bg-muted/40 px-3 py-2">
            <span className="w-5 shrink-0 text-caption tabular-nums text-muted-foreground">{index + 1}</span>
            <div className="min-w-0 flex-1">
              <Link to={ROUTES.BOOK_DETAIL.replace(':bookId', String(book.bookId))}
                className="block truncate text-sm font-medium active:text-primary">
                {book.title}
              </Link>
              <p className="truncate text-[10px] text-muted-foreground">{book.author || '佚名'}</p>
            </div>
            <div className="shrink-0 text-right">
              <p className="text-sm tabular-nums">
                {book.count} <span className="text-[10px] text-muted-foreground">人</span>
              </p>
              <p className="text-[10px] tabular-nums text-muted-foreground">
                占该书读者 {percent(book.ratio)} · 共 {book.readers} 人
              </p>
            </div>
            <span className="w-14 shrink-0 text-right text-caption tabular-nums">
              {book.lift == null || !Number.isFinite(book.lift) ? '无基线' : `×${book.lift.toFixed(2)}`}
            </span>
          </div>
        ))}

        {isFetching && books && books.length > 0 && (
          <p className="py-2 text-center text-caption text-muted-foreground">更新中…</p>
        )}
      </div>

      {!dimsLoading && !dimsError && dimensions && dimensions.length > 0 && (
        <details className="mt-4 rounded-lg bg-muted/40 p-3" data-profile-health>
          <summary className="cursor-pointer text-caption text-muted-foreground">
            画像字段健康度 · {dimensions.length} 个维度
          </summary>
          <table className="mt-2 w-full text-caption">
            <caption className="sr-only">各画像维度的填写率与脏值人数，用于判断是否需要引导读者补全资料</caption>
            <thead>
              <tr className="text-left text-[10px] text-muted-foreground">
                <th className="py-1 font-normal">维度</th>
                <th className="py-1 text-right font-normal">填写率</th>
                <th className="py-1 text-right font-normal">未填写</th>
                <th className="py-1 text-right font-normal">脏值</th>
                <th className="py-1 text-right font-normal">可反查</th>
              </tr>
            </thead>
            <tbody>
              {dimensions.map(dimension => (
                <tr key={dimension.key} className="border-t">
                  <td className="py-1">
                    {dimension.label}
                    {dimension.multiValue && <span className="ml-1 text-[10px] text-muted-foreground">多值</span>}
                  </td>
                  <td className={`py-1 text-right tabular-nums ${dimension.coverage < 0.3 ? 'text-warning' : ''}`}>
                    {percent(dimension.coverage)}
                  </td>
                  <td className="py-1 text-right tabular-nums text-muted-foreground">{dimension.unknownCount}</td>
                  <td className="py-1 text-right tabular-nums text-muted-foreground">{dimension.dirtyCount}</td>
                  <td className="py-1 text-right">{dimension.queryable ? '✓' : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-2 text-[10px] leading-relaxed text-muted-foreground">
            填写率=有效样本人数÷全站读者数；脏值=库里存了白名单之外的取值（含历史枚举与注入串），已被挡在分布与榜单之外。
            引导读者补全哪一列，看这张表而不是看分布图。
          </p>
        </details>
      )}

      <div className="mt-4 flex gap-2 rounded-lg bg-primary/5 p-3">
        <Info className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" />
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          人数=该书读者中属于该分桶的数量；集中度=人数÷该书同口径总读者，再除以全站该分桶占比。
          读者池只统计审核通过的账号，画像字段取当前值。出于隐私，人群本身不足阈值时不出榜。
          {skipped.length > 0 && ` 不支持反查的维度：${skipped.map(dimension => dimension.label).join('、')}（多值维度或有效样本过薄）。`}
        </p>
      </div>
    </div>
  )
}
