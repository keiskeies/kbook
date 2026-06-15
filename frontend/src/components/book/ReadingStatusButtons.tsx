interface ReadingStatusButtonsProps {
  currentStatus: string | null
  onStatusChange: (status: string) => void
}

export function ReadingStatusButtons({ currentStatus, onStatusChange }: ReadingStatusButtonsProps) {
  const statuses = [
    { key: 'WANT', label: '想读' },
    { key: 'READING', label: '在读' },
    { key: 'READ', label: '已读' },
  ]
  return (
    <div className="flex items-center gap-1.5">
      {statuses.map(s => (
        <button
          key={s.key}
          onClick={(e) => { e.stopPropagation(); onStatusChange(s.key) }}
          className={`rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors ${
            currentStatus === s.key
              ? 'bg-primary text-primary-foreground'
              : 'bg-muted text-muted-foreground hover:bg-muted/80'
          }`}
        >
          {s.label}
        </button>
      ))}
    </div>
  )
}