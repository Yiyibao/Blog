import { createSiteConfig, resolveUrl } from '../config/site';

export interface StructuredData {
  '@context': 'https://schema.org';
  '@type': string;
  [key: string]: unknown;
}

let applyId = 0;

export function useStructuredData() {
  const SCRIPT_SELECTOR = 'script[type="application/ld+json"][data-structured]';

  function removeAll() {
    document.querySelectorAll(SCRIPT_SELECTOR).forEach((el) => el.remove());
  }

  function clear() {
    removeAll();
    applyId++;
  }

  function apply(data: StructuredData | StructuredData[]) {
    const seq = ++applyId;
    removeAll();

    const arr = Array.isArray(data) ? data : [data];
    for (const item of arr) {
      if (seq !== applyId) break;
      const script = document.createElement('script');
      script.type = 'application/ld+json';
      script.setAttribute('data-structured', '');
      script.textContent = JSON.stringify(item);
      document.head.appendChild(script);
    }
    return seq;
  }

  return { apply, clear, removeAll };
}

export function webSite(): StructuredData {
  const cfg = createSiteConfig();
  return {
    '@context': 'https://schema.org',
    '@type': 'WebSite',
    name: cfg.siteName,
    description: cfg.siteDescription,
    url: cfg.siteUrl,
  };
}

export function blogPosting(params: {
  headline: string;
  description: string;
  url: string;
  datePublished: string;
  dateModified: string;
  authorName: string;
  image?: string;
}): StructuredData {
  const result: StructuredData = {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: params.headline,
    description: params.description,
    url: params.url,
    datePublished: params.datePublished,
    dateModified: params.dateModified,
    author: { '@type': 'Person', name: params.authorName },
  };
  if (params.image) {
    result.image = resolveUrl(params.image);
  }
  return result;
}

export function techArticle(params: {
  headline: string;
  description: string;
  url: string;
  datePublished: string;
  dateModified: string;
  authorName: string;
}): StructuredData {
  return {
    '@context': 'https://schema.org',
    '@type': 'TechArticle',
    headline: params.headline,
    description: params.description,
    url: params.url,
    datePublished: params.datePublished,
    dateModified: params.dateModified,
    author: { '@type': 'Person', name: params.authorName },
  };
}

export function recipe(params: {
  name: string;
  description: string;
  url: string;
  image: string;
  recipeIngredient: string[];
  recipeInstructions: string[];
  recipeCategory: string;
  authorName: string;
  datePublished: string;
  dateModified: string;
}): StructuredData {
  return {
    '@context': 'https://schema.org',
    '@type': 'Recipe',
    name: params.name,
    description: params.description,
    url: params.url,
    image: resolveUrl(params.image),
    recipeIngredient: params.recipeIngredient,
    recipeInstructions: params.recipeInstructions.map((text) => ({
      '@type': 'HowToStep',
      text,
    })),
    recipeCategory: params.recipeCategory,
    author: { '@type': 'Person', name: params.authorName },
    datePublished: params.datePublished,
    dateModified: params.dateModified,
  };
}

export function breadcrumbList(items: { name: string; path: string }[]): StructuredData {
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map((item, i) => ({
      '@type': 'ListItem',
      position: i + 1,
      name: item.name,
      item: resolveUrl(item.path),
    })),
  };
}
