import request from '@/utils/request'

/** 关注用户 VO */
export interface FollowUserVO {
  userId: number
  nickname: string
  avatar: string | null
  bio: string | null
}

/** 关注用户 */
export function followUser(userId: number) {
  return request.post(`/user/follow/${userId}`)
}

/** 取消关注 */
export function unfollowUser(userId: number) {
  return request.delete(`/user/follow/${userId}`)
}

/** 是否已关注 */
export function isFollowing(userId: number) {
  return request.get<boolean>(`/user/follow/is-following/${userId}`)
}

/** 获取关注列表 */
export function getFollowings(userId: number) {
  return request.get<FollowUserVO[]>(`/user/${userId}/followings`)
}

/** 获取粉丝列表 */
export function getFollowers(userId: number) {
  return request.get<FollowUserVO[]>(`/user/${userId}/followers`)
}
