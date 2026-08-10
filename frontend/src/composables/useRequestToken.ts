/**
 * FD-4：竞态守卫原语——异步取数的间隙里状态可能已被后续操作改写，
 * 每次发起前取新令牌，响应回来只在令牌仍是最新时落地，迟到响应直接丢弃。
 * 本仓库 review 实证过的缺陷模式（列表摘要 DTO + 详情异步补取）。
 */
export function useRequestToken() {
  let current = 0;

  /** 发起新请求前调用：作废所有在途请求并返回本次令牌。 */
  function next(): number {
    current += 1;
    return current;
  }

  /** 响应落地前调用：令牌已过期则丢弃。 */
  function isCurrent(token: number): boolean {
    return token === current;
  }

  return { next, isCurrent };
}
