/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_ALLOW_BUNDLED_CONTENT: string;
  readonly VITE_AI_PLATFORM_ENABLED: string;

  readonly VITE_SITE_NAME: string;
  readonly VITE_SITE_SUBTITLE: string;
  readonly VITE_SITE_DESCRIPTION: string;
  readonly VITE_SITE_URL: string;
  readonly VITE_SOCIAL_IMAGE: string;
  readonly VITE_AUTHOR_NAME: string;
  readonly VITE_COPYRIGHT_OWNER: string;
  readonly VITE_COPYRIGHT_YEAR: string;
  readonly VITE_CONTACT_EMAIL: string;
  readonly VITE_ICP_RECORD: string;
  readonly VITE_ICP_LINK: string;
  readonly VITE_POLICE_RECORD: string;
  readonly VITE_POLICE_LINK: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
