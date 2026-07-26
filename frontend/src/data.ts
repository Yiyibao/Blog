export type SearchType = 'POST' | 'DISH' | 'NOTE'

export interface SearchHit {
  type: SearchType
  id: number
  title: string
  excerpt: string
  category: string | null
  url: string
  color: string | null
  number: string | null
  slug?: string | null
}

export type PostStatus = 'DRAFT' | 'PUBLISHED'

// P1-2：列表接口只返回摘要（不含 content 正文），正文仅由详情接口返回。
export interface PostSummary {
  id?: number
  slug: string
  title: string
  excerpt: string
  date: string
  readTime: number
  category: string
  categorySlug?: string
  tags: string[]
  color: string
  number: string
  featured?: boolean
  status?: PostStatus
  likeCount?: number
  viewsCount?: number
}

export interface Post extends PostSummary {
  content: string
}

export interface CategorySummary {
  name: string
  slug: string
  publishedPostCount: number
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Dish {
  id: number
  slug: string
  name: string
  summary: string
  category: string
  imageUrl: string
  imageAlt: string
  imageCredit: string
  imageSourceUrl: string
  prepMinutes: number
  difficulty: '简单' | '家常' | '进阶'
  rating: number
  featured: boolean
  published: boolean
  displayOrder: number
  ingredients: string[]
  steps: string[]
  createdAt: string
  updatedAt: string
}

export const posts: Post[] = [
  {
    slug: 'clarity-by-design',
    title: '把复杂留给系统，把清晰留给读者',
    excerpt: '一个好界面并不展示所有能力，而是在每个决定发生的时刻，只给出恰到好处的信息。',
    date: '2026-07-18',
    readTime: 8,
    category: '设计札记',
    tags: ['产品设计', '信息架构'],
    color: '#1649d8',
    number: '01',
    featured: true,
    status: 'PUBLISHED',
    content: `
      <p class="lead">复杂从来不会真正消失。好的设计，只是把它安放到了更合适的位置。</p>
      <h2 id="start">清晰不是删减，而是排序</h2>
      <p>每一个成熟的产品背后，都存在大量状态、规则和边界条件。面向读者时，我们要做的不是假装这些复杂性不存在，而是建立一条清楚的阅读路径：先看见结论，再理解依据，最后保留深入探索的入口。</p>
      <blockquote>信息不是越多越好。真正稀缺的是读者的注意力，以及我们替他们完成排序的能力。</blockquote>
      <h2 id="rhythm">让界面拥有阅读节奏</h2>
      <p>字号、留白、颜色和动效共同决定了页面的语气。稳定的间距系统能让内容自然分组，有限的强调色能建立优先级，而克制的动效负责解释状态变化。</p>
      <pre><code>const clarity = complexity
  .sort(byRelevance)
  .map(toHumanLanguage)
  .filter(keepWhatMatters)</code></pre>
      <h2 id="system">设计系统应该服务内容</h2>
      <p>组件库不是一面展示墙。它的价值在于让团队能够持续地做出一致决定，同时给重要内容留下打破规则的空间。</p>
      <ul><li>先定义内容层级，再选择组件。</li><li>用有限的视觉变量表达明确的语义。</li><li>让每一种交互都有可预期的反馈。</li></ul>
      <p>当系统承担了复杂性，读者得到的便不是“简单”，而是一种被认真照顾过的清晰。</p>
    `,
  },
  {
    slug: 'vue-composable-notes',
    title: 'Vue Composable 的边界感',
    excerpt: '从“复用几行代码”到“描述一个稳定的领域能力”，组合式函数真正值得抽象的时机。',
    date: '2026-07-12',
    readTime: 6,
    category: '工程实践',
    tags: ['Vue', 'TypeScript'],
    color: '#ff6b35',
    number: '02',
    status: 'PUBLISHED',
    content: `<p class="lead">抽象的目标不是减少文件，而是让变化发生在正确的位置。</p><h2 id="signal">先寻找稳定信号</h2><p>当一段逻辑跨越多个组件，并且拥有清楚的输入、输出与生命周期，它才开始具备成为 composable 的条件。</p><h2 id="contract">用类型写下契约</h2><p>TypeScript 让边界变得可见：参数表达依赖，返回值表达能力，而命名表达意图。</p><pre><code>export function useReadingList(storage: Storage) {
  const items = ref&lt;string[]&gt;([])
  const toggle = (slug: string) =&gt; { /* ... */ }
  return { items: readonly(items), toggle }
}</code></pre><p>最好的组合式函数通常很无聊：行为稳定、依赖明确、无需阅读实现就能正确使用。</p>`,
  },
  {
    slug: 'slow-interface',
    title: '慢一点的界面，反而更有力量',
    excerpt: '当所有产品都在争抢注意力，克制、停顿与空白如何变成一种更长久的体验。',
    date: '2026-07-03',
    readTime: 5,
    category: '日常观察',
    tags: ['体验', '生活'],
    color: '#d7ef63',
    number: '03',
    status: 'PUBLISHED',
    content: `<p class="lead">速度是一种能力，但从来不是体验的唯一尺度。</p><h2 id="pause">允许停顿发生</h2><p>一个页面不必在首屏解释一切。空白可以建立期待，过渡可以帮助理解，适当的延迟甚至能让动作显得更有重量。</p><blockquote>克制不是缺少表达，而是知道什么值得被放大。</blockquote><h2 id="quiet">安静的产品</h2><p>关闭不必要的通知、减少竞争性的按钮、把阅读进度交还给用户。安静不是消极，它是一种对注意力的尊重。</p>`,
  },
  {
    slug: 'type-safe-content',
    title: '用 TypeScript 管好一座内容花园',
    excerpt: '静态内容也值得拥有可靠的结构：从文章元数据到路由，再到可维护的展示逻辑。',
    date: '2026-06-21',
    readTime: 7,
    category: '工程实践',
    tags: ['TypeScript', '内容系统'],
    color: '#8c7bff',
    number: '04',
    status: 'PUBLISHED',
    content: `<p class="lead">内容与代码的边界越清楚，网站就越容易继续生长。</p><h2 id="model">先建立内容模型</h2><p>标题、摘要、日期、标签与正文不仅是字段，更是页面能力的来源。可靠的模型能驱动筛选、推荐与 SEO。</p><h2 id="future">为未来留接口</h2><p>今天的数据来自本地文件，明天也可以替换成 Markdown 或 CMS，而展示层无需推倒重来。</p>`,
  },
  {
    slug: 'weekend-camera',
    title: '周末散步的 24 个画面',
    excerpt: '不追求目的地，只记录光线、转角和城市里那些短暂却具体的时刻。',
    date: '2026-06-08',
    readTime: 4,
    category: '日常观察',
    tags: ['摄影', '城市'],
    color: '#f2c94c',
    number: '05',
    status: 'PUBLISHED',
    content: `<p class="lead">散步是一种低速的搜索方式。</p><h2 id="light">下午四点的光</h2><p>阳光沿着旧楼的边缘落下，橱窗、树影和路人的衣角暂时拥有同一种颜色。</p><h2 id="collect">收集而不占有</h2><p>按下快门不是为了证明到过，而是提醒自己：平常的一天也有值得认真观看的部分。</p>`,
  },
]

export const categories = ['全部', ...new Set(posts.map((post) => post.category))]
