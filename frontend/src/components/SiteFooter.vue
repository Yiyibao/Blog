<script setup lang="ts">
import { computed } from 'vue'
import { createSiteConfig } from '../config/site'

const cfg = createSiteConfig()

const currentYear = new Date().getFullYear()

const yearLabel = computed(() => {
  if (cfg.copyrightYear && cfg.copyrightYear < currentYear) {
    return `${cfg.copyrightYear}–${currentYear}`
  }
  return String(cfg.copyrightYear || currentYear)
})

const owner = computed(() => cfg.copyrightOwner || cfg.siteName)

const hasIcp = computed(() => !!cfg.icpRecord)
const hasPolice = computed(() => !!cfg.policeRecord)
const hasEmail = computed(() => !!cfg.contactEmail)
</script>

<template>
  <div class="footer-info">
    <p class="footer-copyright">© {{ yearLabel }} {{ owner }}</p>
    <p v-if="hasIcp" class="footer-icp">
      <a
        v-if="cfg.icpLink"
        :href="cfg.icpLink"
        target="_blank"
        rel="noopener noreferrer"
      >{{ cfg.icpRecord }}</a>
      <span v-else>{{ cfg.icpRecord }}</span>
    </p>
    <p v-if="hasPolice" class="footer-police">
      <a
        v-if="cfg.policeLink"
        :href="cfg.policeLink"
        target="_blank"
        rel="noopener noreferrer"
      >{{ cfg.policeRecord }}</a>
      <span v-else>{{ cfg.policeRecord }}</span>
    </p>
    <p v-if="hasEmail" class="footer-email">
      <a :href="`mailto:${cfg.contactEmail}`">{{ cfg.contactEmail }}</a>
    </p>
  </div>
</template>

<style scoped>
.footer-info {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1.2rem;
  margin-top: 0.5rem;
  font-size: 0.78rem;
  line-height: 1.6;
  color: var(--muted);
}
.footer-info p {
  margin: 0;
}
.footer-info a {
  color: var(--muted);
  text-decoration: none;
}
.footer-info a:hover {
  color: var(--ink);
}
</style>
