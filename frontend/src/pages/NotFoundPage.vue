<script setup lang="ts">
import { RouterLink } from 'vue-router';
</script>

<template>
  <section class="page-hero section-wrap compact-hero">
    <p class="eyebrow"><span /> NOT FOUND</p>
    <h1>这片页面不存在，<br /><em>也许已经被风带走了。</em></h1>
    <p>链接可能已失效，回到首页继续浏览其他内容。</p>
    <RouterLink class="button primary" to="/">返回首页 ↗</RouterLink>
  </section>
</template>
