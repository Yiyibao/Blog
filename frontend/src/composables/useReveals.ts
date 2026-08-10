/**
 * FD-5：滚动入场系统，从 App.vue 提取为可复用模块。
 * 异步页面（如美食页数据加载后）可在内容就绪后调用 refreshReveals() 补挂观察，
 * 解决"路由切换时元素尚不存在、错过入场编排"的时序问题。
 * 大面积渐变板块走 .reveal-lite（无 filter/will-change，避免嵌套重绘掉帧）。
 */
let revealObserver: IntersectionObserver | undefined;

const SELECTOR = [
  'main > section',
  '.ticker',
  '.section-heading',
  '.featured-card',
  '.post-card',
  '.archive-row',
  '.project-card',
  '.related-grid > a',
  '.values article',
  '.manifesto blockquote',
  '.manifesto > div span',
  '.pagination',
  '.food-catalog-head',
  '.food-ranking',
].join(', ');

/** 命中这些类的元素用轻量变体（无模糊滤镜）。 */
const LITE_CLASSES = ['food-catalog-head', 'food-ranking'];

export function refreshReveals() {
  revealObserver?.disconnect();
  // jsdom / 老浏览器兜底：没有观察能力时直接可见，绝不能让页面内容消失
  const canObserve = typeof IntersectionObserver !== 'undefined';
  const reduceMotion =
    typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : true;

  const nodes = [...document.querySelectorAll<HTMLElement>(SELECTOR)];
  const groupCount = new Map<string, number>();

  nodes.forEach((element) => {
    if (element.classList.contains('hero')) return;
    const lite = LITE_CLASSES.some((cls) => element.classList.contains(cls));
    element.classList.add(lite ? 'reveal-lite' : 'reveal-item');
    const parent = element.closest(
      'section, .post-grid, .projects, .related-grid, .values, .manifesto, .archive-list',
    );
    const key = parent ? `${parent.className}|${parent.tagName}` : 'root';
    const index = groupCount.get(key) ?? 0;
    groupCount.set(key, index + 1);
    element.style.setProperty('--reveal-delay', `${Math.min(index, 8) * 70}ms`);

    if (element.classList.contains('featured-card')) element.dataset.reveal = 'scale';
    else if (element.classList.contains('project-card') && index % 2 === 1) element.dataset.reveal = 'right';
    else if (element.classList.contains('project-card')) element.dataset.reveal = 'left';
    else if (element.matches('.related-grid > a') && index % 2 === 1) element.dataset.reveal = 'right';
    else if (element.matches('.related-grid > a')) element.dataset.reveal = 'left';

    if (reduceMotion || !canObserve) {
      element.classList.add('is-visible');
    }
  });

  if (reduceMotion || !canObserve) return;

  revealObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-visible');
        revealObserver?.unobserve(entry.target);
      });
    },
    { threshold: 0.12, rootMargin: '0px 0px -8% 0px' },
  );

  nodes.forEach((element) => {
    if (element.classList.contains('hero')) return;
    revealObserver?.observe(element);
  });
}

export function disconnectReveals() {
  revealObserver?.disconnect();
  revealObserver = undefined;
}
