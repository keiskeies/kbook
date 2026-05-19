/// <reference types="vite/client" />

declare module 'epubjs' {
  interface Rendition {
    display(target?: string): Promise<any>
    next(): Promise<any>
    prev(): Promise<any>
    destroy(): void
    on(event: string, callback: (...args: any[]) => void): void
    themes: {
      default(styles: Record<string, any>): void
      register(name: string, styles: Record<string, any>): void
      select(name: string): void
    }
  }

  interface Book {
    ready: Promise<void>
    navigation: { toc: any[] }
    spine: { spineItems: any[] }
    locations: {
      generate(chars?: number): Promise<string[]>
      percentageFromCfi(cfi: string): number
      cfiFromLocation(loc: number): string
    }
    renderTo(element: HTMLElement | string, options?: Record<string, any>): Rendition
    destroy(): void
  }

  function ePub(url: ArrayBuffer): Book
  export default ePub
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_APP_NAME: string
  readonly VITE_TOKEN_KEY: string
  readonly VITE_REFRESH_TOKEN_KEY: string
  readonly VITE_CODE_COUNTDOWN: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
