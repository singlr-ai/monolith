import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLlmsTxt from 'starlight-llms-txt';

// https://astro.build/config
// Root-hosted (base '/'): works on a custom domain or Firebase Hosting as-is.
// For a github.io project page instead, set `base: '/monolith'` and the matching `site`.
export default defineConfig({
  site: 'https://monolith.standardapplied.com',
  integrations: [
    starlight({
      title: 'Monolith',
      description: 'Live, typed queries on real Postgres. A Java-native data platform.',
      customCss: ['./src/styles/theme.css'],
      plugins: [
        // Generates /llms.txt (a curated index) and /llms-full.txt (all docs concatenated),
        // so coding agents can ingest the documentation as first-class context.
        starlightLlmsTxt({
          description:
            'Monolith is a set of small Java libraries for building applications on PostgreSQL where ' +
            'the schema, typed data access, the client reader, and live (reactive) queries all come ' +
            'from a single record declaration, generated at compile time over real relational tables.',
        }),
      ],
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/singlr-ai/monolith' },
      ],
      sidebar: [
        { label: 'Getting started', items: [{ autogenerate: { directory: 'getting-started' } }] },
        { label: 'Concepts', items: [{ autogenerate: { directory: 'concepts' } }] },
        { label: 'Guides', items: [{ autogenerate: { directory: 'guides' } }] },
        { label: 'Design notes', items: [{ autogenerate: { directory: 'design' } }] },
      ],
    }),
  ],
});
