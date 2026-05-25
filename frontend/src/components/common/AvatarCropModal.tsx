import { useState, useCallback, useMemo, useEffect } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Check, Loader2, RotateCcw } from 'lucide-react'
import Cropper from 'react-easy-crop'
import type { Point, Area } from 'react-easy-crop'

interface AvatarCropModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  imageFile: File | null
  onCropComplete: (croppedBlob: Blob) => void
}

const CROP_SIZE = 300
const OUTPUT_QUALITY = 0.85

function getCroppedImg(imageSrc: string, pixelCrop: Area): Promise<Blob | null> {
  return new Promise((resolve) => {
    const image = new Image()
    image.onload = () => {
      const canvas = document.createElement('canvas')
      const ctx = canvas.getContext('2d')!
      canvas.width = CROP_SIZE
      canvas.height = CROP_SIZE

      ctx.imageSmoothingQuality = 'high'
      ctx.drawImage(
        image,
        pixelCrop.x,
        pixelCrop.y,
        pixelCrop.width,
        pixelCrop.height,
        0,
        0,
        CROP_SIZE,
        CROP_SIZE,
      )

      canvas.toBlob(resolve, 'image/jpeg', OUTPUT_QUALITY)
    }
    image.onerror = () => resolve(null)
    image.src = imageSrc
  })
}

export default function AvatarCropModal({ open, onOpenChange, imageFile, onCropComplete }: AvatarCropModalProps) {
  const [crop, setCrop] = useState<Point>({ x: 0, y: 0 })
  const [zoom, setZoom] = useState(1)
  const [croppedAreaPixels, setCroppedAreaPixels] = useState<Area | null>(null)
  const [cropping, setCropping] = useState(false)

  const imageUrl = useMemo(() => {
    return imageFile ? URL.createObjectURL(imageFile) : null
  }, [imageFile])

  useEffect(() => {
    return () => {
      if (imageUrl) URL.revokeObjectURL(imageUrl)
    }
  }, [imageUrl])

  useEffect(() => {
    if (!open) {
      setCrop({ x: 0, y: 0 })
      setZoom(1)
      setCroppedAreaPixels(null)
    }
  }, [open])

  const handleCropComplete = useCallback((_croppedArea: Area, croppedPixels: Area) => {
    setCroppedAreaPixels(croppedPixels)
  }, [])

  const handleConfirm = useCallback(async () => {
    if (!imageUrl || !croppedAreaPixels) return

    setCropping(true)
    try {
      const blob = await getCroppedImg(imageUrl, croppedAreaPixels)
      if (blob) {
        onCropComplete(blob)
      }
    } catch {
      // ignore
    } finally {
      setCropping(false)
    }
  }, [imageUrl, croppedAreaPixels, onCropComplete])

  const handleClose = () => {
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-lg sm:max-w-lg w-[95vw]">
        <DialogHeader>
          <DialogTitle>裁剪头像</DialogTitle>
        </DialogHeader>
        <div className="space-y-4 py-2">
          {imageUrl && (
            <div className="relative w-full aspect-square bg-black rounded-lg overflow-hidden">
              <Cropper
                image={imageUrl}
                crop={crop}
                zoom={zoom}
                aspect={1}
                cropShape="rect"
                showGrid={false}
                zoomSpeed={0.5}
                minZoom={1}
                maxZoom={5}
                onCropChange={setCrop}
                onCropComplete={handleCropComplete}
                onZoomChange={setZoom}
              />
            </div>
          )}

          <div className="flex items-center gap-3">
            <label className="text-sm text-muted-foreground shrink-0">缩放</label>
            <input
              type="range"
              min={1}
              max={5}
              step={0.01}
              value={zoom}
              onChange={(e) => setZoom(parseFloat(e.target.value))}
              className="flex-1 h-2 bg-muted rounded-lg appearance-none cursor-pointer accent-primary"
            />
            <button
              onClick={() => setZoom(1)}
              className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted"
              title="重置缩放"
            >
              <RotateCcw className="h-4 w-4 text-muted-foreground" />
            </button>
          </div>

          <div className="flex gap-2 pt-2">
            <button
              onClick={handleClose}
              className="flex-1 h-10 rounded-xl bg-muted text-muted-foreground text-sm font-medium hover:bg-muted/80 transition-colors"
            >
              取消
            </button>
            <button
              onClick={handleConfirm}
              disabled={cropping}
              className="flex-1 h-10 rounded-xl bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
            >
              {cropping ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Check className="h-4 w-4" />
              )}
              确认裁剪
            </button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
