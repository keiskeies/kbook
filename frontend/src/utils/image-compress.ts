/**
 * 图片压缩工具
 * - 文件大小超过 200KB，长宽压缩到原图的 1/2
 * - 文件大小超过 500KB，JPEG 质量压缩到 0.65
 * - 否则 JPEG 质量压缩到 0.8
 */

interface CompressResult {
  file: File
  originalSize: number
  compressedSize: number
}

export async function compressImage(file: File): Promise<CompressResult> {
  const originalSize = file.size

  // 如果文件小于 200KB，不做尺寸压缩，只做格式转换
  const shouldResize = originalSize > 200 * 1024

  return new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = (e) => {
      const img = new Image()
      img.onload = () => {
        const canvas = document.createElement('canvas')
        let width = img.width
        let height = img.height

        if (shouldResize) {
          width = Math.floor(width / 2)
          height = Math.floor(height / 2)
        }

        canvas.width = width
        canvas.height = height

        const ctx = canvas.getContext('2d')!
        ctx.imageSmoothingQuality = 'high'
        ctx.drawImage(img, 0, 0, width, height)

        const quality = originalSize > 500 * 1024 ? 0.65 : 0.8

        canvas.toBlob(
          (blob) => {
            const compressedFile = new File([blob!], file.name.replace(/\.[^.]+$/, '.jpg'), {
              type: 'image/jpeg',
            })
            resolve({
              file: compressedFile,
              originalSize,
              compressedSize: compressedFile.size,
            })
          },
          'image/jpeg',
          quality,
        )
      }
      img.src = e.target?.result as string
    }
    reader.readAsDataURL(file)
  })
}
