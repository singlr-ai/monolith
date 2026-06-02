import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// https://astro.build/config
export default defineConfig({
  // Update `site` (and set `base: '/monolith'` for a github.io project page) for the real host.
  site: 'https://singlr-ai.github.io',
  integrations: [
    starlight({
      title: 'Monolith',
      description: 'Live, typed queries on real Postgres. A Java-native data platform.',
      customCss: ['./src/styles/theme.css'],
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/singlr-ai/monolith' },
      ],
      sidebar: [
        { label: 'Getting started', items: [{ autogenerate: { directory: 'getting-started' } }] },
        { label: 'Guides', items: [{ autogenerate: { directory: 'guides' } }] },
        { label: 'Design notes', items: [{ autogenerate: { directory: 'design' } }] },
      ],
    }),
  ],
});
