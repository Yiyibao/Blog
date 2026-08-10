import { createSiteConfig, resolveUrl } from '../config/site';

export interface PageMeta {
  title: string;
  description?: string;
  canonicalPath?: string;
  robots?: string;
  openGraph?: {
    title?: string;
    description?: string;
    type?: string;
    image?: string;
    url?: string;
  };
  twitter?: {
    card?: string;
    title?: string;
    description?: string;
    image?: string;
  };
}

function findOrCreate(selector: string, tagName: string, attr: string, attrValue: string): Element {
  const existing = document.head.querySelector(selector);
  if (existing) return existing;
  const el = document.createElement(tagName);
  el.setAttribute(attr, attrValue);
  document.head.appendChild(el);
  return el;
}

function removeAll(selector: string) {
  document.head.querySelectorAll(selector).forEach((el) => el.remove());
}

function setMeta(name: string, content: string) {
  if (!content) {
    removeAll(`meta[name="${name}"]`);
    return;
  }
  findOrCreate(`meta[name="${name}"]`, 'meta', 'name', name).setAttribute('content', content);
}

function setProperty(property: string, content: string) {
  if (!content) {
    removeAll(`meta[property="${property}"]`);
    return;
  }
  findOrCreate(`meta[property="${property}"]`, 'meta', 'property', property).setAttribute('content', content);
}

function setLink(rel: string, href: string) {
  if (!href) {
    removeAll(`link[rel="${rel}"]`);
    return;
  }
  findOrCreate(`link[rel="${rel}"]`, 'link', 'rel', rel).setAttribute('href', href);
}

/** Strip HTML tags, Markdown markers, and collapse whitespace. */
export function cleanText(raw: string, maxLen = 200): string {
  return raw
    .replace(/<[^>]*>/g, ' ')
    .replace(/[#*_~`>\[\]()-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, maxLen);
}

let applyId = 0;

export function usePageMeta() {
  function formatTitle(pageTitle: string): string {
    const cfg = createSiteConfig();
    if (!pageTitle) return cfg.siteName;
    if (pageTitle === cfg.siteName) return cfg.siteName;
    return `${pageTitle} | ${cfg.siteName}`;
  }

  function apply(meta: PageMeta) {
    const seq = ++applyId;
    const fullTitle = formatTitle(meta.title);
    document.title = fullTitle;

    setMeta('description', meta.description ?? '');

    if (meta.canonicalPath) {
      setLink('canonical', resolveUrl(meta.canonicalPath));
    } else {
      removeAll('link[rel="canonical"]');
    }

    setMeta('robots', meta.robots ?? '');

    const og = meta.openGraph;
    if (og?.title) {
      setProperty('og:title', og.title);
      setProperty('og:description', og.description ?? '');
      setProperty('og:url', og.url ?? '');
      setProperty('og:type', og.type ?? 'website');
      if (og.image) {
        setProperty('og:image', resolveUrl(og.image));
      } else {
        removeAll('meta[property="og:image"]');
      }
    } else {
      // Clear all OG tags when not provided
      removeAll('meta[property^="og:"]');
    }

    const tw = meta.twitter;
    if (tw?.title) {
      setMeta('twitter:card', tw.card ?? 'summary_large_image');
      setMeta('twitter:title', tw.title);
      setMeta('twitter:description', tw.description ?? '');
      if (tw.image) {
        setMeta('twitter:image', resolveUrl(tw.image));
      } else {
        removeAll('meta[name="twitter:image"]');
      }
    } else {
      removeAll('meta[name^="twitter:"]');
    }

    return seq;
  }

  return { apply };
}
