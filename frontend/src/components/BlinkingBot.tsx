/** 会眨眼的 AI 机器人图标 */
export function BlinkingBot({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      {/* 头部轮廓 — 整体下移 */}
      <rect x="3" y="6" width="18" height="14" rx="4" />
      {/* 天线 */}
      <line x1="12" y1="6" x2="12" y2="3" />
      <circle cx="12" cy="3" r="1" fill="currentColor" />
      {/* 左眼 */}
      <g>
        <animateTransform
          attributeName="transform"
          type="translate"
          values="0,0; 1,0; -1,0; 0,0; 0,1; 0,-1; 0,0"
          dur="5s"
          repeatCount="indefinite"
        />
        <ellipse cx="9" cy="12" rx="1.5" ry="2">
          <animate
            attributeName="ry"
            values="2;0.01;2;0.01;2;2;2;2;2;2"
            dur="5s"
            repeatCount="indefinite"
          />
        </ellipse>
      </g>
      {/* 右眼 */}
      <g>
        <animateTransform
          attributeName="transform"
          type="translate"
          values="0,0; 1,0; -1,0; 0,0; 0,1; 0,-1; 0,0"
          dur="5s"
          repeatCount="indefinite"
        />
        <ellipse cx="15" cy="12" rx="1.5" ry="2">
          <animate
            attributeName="ry"
            values="2;0.01;2;0.01;2;2;2;2;2;2"
            dur="5s"
            repeatCount="indefinite"
          />
        </ellipse>
      </g>
    </svg>
  )
}
