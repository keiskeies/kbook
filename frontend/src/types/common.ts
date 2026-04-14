/** 通用分页结果类型 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}
