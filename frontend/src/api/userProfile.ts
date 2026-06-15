import request from '@/utils/request'

/** 更新简介 */
export function updateBio(bio: string) {
  return request.put('/user/profile/bio', { bio })
}