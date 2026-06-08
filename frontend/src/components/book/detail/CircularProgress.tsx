/** 圆形进度环 */
export function CircularProgress({ percentage, size = 56, strokeWidth = 4 }: {
  percentage: number
  size?: number
  strokeWidth?: number
}) {
  const radius = (size - strokeWidth) / 2
  const circumference = radius * 2 * Math.PI
  const offset = circumference - (percentage / 100) * circumference

  // 根据百分比选择颜色
  let color = '#94a3b8'
  if (percentage >= 80) color = '#f97316'
  else if (percentage >= 60) color = '#f59e0b'
  else if (percentage >= 40) color = '#10b981'

  return (
    <svg width={size} height={size} className="transform -rotate-90">
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        className="text-primary/10"
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        className="transition-all duration-700"
      />
      <text
        x="50%"
        y="50%"
        dy="0.3em"
        textAnchor="middle"
        className="text-xs font-bold"
        fill={color}
        transform={`rotate(90 ${size / 2} ${size / 2})`}
      >
        {percentage}%
      </text>
    </svg>
  )
}
